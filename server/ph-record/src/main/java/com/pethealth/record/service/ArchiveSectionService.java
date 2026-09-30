package com.pethealth.record.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pethealth.api.app.ArchiveRecordRequest;
import com.pethealth.api.app.ArchiveRecordView;
import com.pethealth.api.app.ArchiveSectionView;
import com.pethealth.api.app.TimelineEventView;
import com.pethealth.api.record.ProviderReportArchiveApi;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.time.AppTime;
import com.pethealth.common.util.JsonFields;
import com.pethealth.record.domain.ArchiveRecord;
import com.pethealth.record.domain.ArchiveSection;
import com.pethealth.record.domain.CareMode;
import com.pethealth.record.domain.Pet;
import com.pethealth.record.mapper.ArchiveRecordMapper;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 档案分项、分项记录与时间轴（切片 #102，字段清单与多源规则见 ADR-0030）。
 *
 * <p>四件事各自的口径：
 *
 * <ol>
 *   <li><b>分项概览</b>：8 个入口固定返回，条数与最近日期按 {@link ArchiveSection} 的 category 映射合并；
 *       照片视频的内容在文件模块，条数是 null（本模块不碰 {@code file_object}，ADR-0006）；
 *   <li><b>记录读写</b>：只收**列表语义**的分项（其他指标 / 证件 / 老年专项 / 就医）；
 *       体重、排泄、防疫各有自己的接口——它们的「一天一条、幂等更新」语义不能从这条路径破坏；
 *   <li><b>多源标注</b>：每条记录给 {@code authoritative}（权威来源见分项定义），
 *       权威缺席时同一天里次一级来源顶上；**冲突两条都留**，不做静默覆盖；
 *   <li><b>时间轴</b>：只收四类事件（就医 / 疫苗驱虫 / 异常打卡 / 服务者报工），倒序分页。
 * </ol>
 *
 * <p>第五件事是**服务者报工写档案**（{@link ProviderReportArchiveApi}，F004 的第三源）：
 * 事实由 ph-order 在报工成功时给（它知道订单），落库的形状由本类定（本类拥有 {@code archive_record}）。
 * {@code @Primary} 是为了「有第二份实现时以生产这一份为准」：集成测试里可能放等价桩，
 * 两个候选且无主时 Spring 会抛 {@code NoUniqueBeanDefinitionException}，把「有实现」变成 500
 * （与 {@code ProviderAccessAdapter} 同一处取舍）。
 */
@Service
@Primary
public class ArchiveSectionService implements ProviderReportArchiveApi {

    /** 分项记录的可录窗口：与打卡同一口径（今天 + 过去 7 天），免得两条录入路径给出两套答案。 */
    public static final int WRITE_WINDOW_DAYS = 7;

    private static final Map<Integer, String> CATEGORY_NAMES = categoryNames();

    /** 分项名的字典。用 Map.ofEntries 而不是 Map.of：Map.of 最多接受 10 组键值对（这里是 11 组）。 */
    private static Map<Integer, String> categoryNames() {
        Map<Integer, String> names = new LinkedHashMap<>();
        names.put(ArchiveRecord.CATEGORY_WEIGHT, "体重");
        names.put(ArchiveRecord.CATEGORY_DIET, "饮食");
        names.put(ArchiveRecord.CATEGORY_EXCRETION, "排泄");
        names.put(ArchiveRecord.CATEGORY_BEHAVIOR, "行为");
        names.put(ArchiveRecord.CATEGORY_MOOD, "情绪");
        names.put(ArchiveRecord.CATEGORY_HYGIENE, "卫生");
        names.put(ArchiveRecord.CATEGORY_EPIDEMIC, "防疫");
        names.put(ArchiveRecord.CATEGORY_MEDICAL, "就医");
        names.put(ArchiveRecord.CATEGORY_METRIC_OTHER, "其他指标");
        names.put(ArchiveRecord.CATEGORY_DOCUMENT, "证件");
        names.put(ArchiveRecord.CATEGORY_ELDERLY, "老年专项");
        return java.util.Collections.unmodifiableMap(names);
    }

    private static final Map<String, Integer> TIMELINE_TYPES = Map.of(
            "medical", 1, "vaccine", 2, "deworm", 3, "abnormal", 4, "provider", 5);

    private final ArchiveRecordMapper recordMapper;
    private final PetService petService;
    private final CareModeService careModeService;

    public ArchiveSectionService(ArchiveRecordMapper recordMapper, PetService petService,
                                 CareModeService careModeService) {
        this.recordMapper = recordMapper;
        this.petService = petService;
        this.careModeService = careModeService;
    }

    // ---------------------------------------------------------------- 分项概览

    /** 8 个分项的入口与已录条数（顺序固定，前端直接渲染）。 */
    @Transactional(readOnly = true)
    public List<ArchiveSectionView> sections(long userId, long petId) {
        Pet pet = petService.requireOwned(userId, petId);
        CareMode care = careModeService.of(pet, AppTime.today());
        int threshold = careModeService.minAgeYears(pet.getSpecies());

        Map<Integer, ArchiveRecordMapper.CategoryAggregate> byCategory = new LinkedHashMap<>();
        for (ArchiveRecordMapper.CategoryAggregate row : recordMapper.selectCategoryAggregates(petId)) {
            byCategory.put(row.category(), row);
        }

        List<ArchiveSectionView> views = new ArrayList<>();
        for (ArchiveSection section : ArchiveSection.entries()) {
            boolean enabled = section != ArchiveSection.ELDERLY || care.active();
            Integer count = section.contentSource() == ArchiveSection.ContentSource.FILES ? null : 0;
            LocalDate latest = null;
            if (count != null) {
                int total = 0;
                for (Integer category : section.categories()) {
                    ArchiveRecordMapper.CategoryAggregate row = byCategory.get(category);
                    if (row != null) {
                        total += row.total();
                        if (row.latest() != null && (latest == null || row.latest().isAfter(latest))) {
                            latest = row.latest();
                        }
                    }
                }
                count = total;
            }
            views.add(new ArchiveSectionView(section.code(), section.displayName(), section.description(),
                    section.contentSource() == ArchiveSection.ContentSource.FILES ? "files" : "records",
                    section.recordable(), enabled,
                    enabled ? null : threshold + " 岁以上或有慢病时自动开启",
                    count, latest == null ? null : latest.toString()));
        }
        return views;
    }

    // ---------------------------------------------------------------- 记录读写

    /**
     * 分项记录列表（倒序分页）。
     *
     * @param sectionCode 分项编码；null 表示全部分项（含打卡与防疫的行）
     */
    @Transactional(readOnly = true)
    public PageResult<ArchiveRecordView> records(long userId, long petId, String sectionCode,
                                                 LocalDate from, LocalDate to, long page, long pageSize) {
        petService.requireOwned(userId, petId);
        validateRange(from, to);

        var query = Wrappers.<ArchiveRecord>lambdaQuery().eq(ArchiveRecord::getPetId, petId);
        if (sectionCode != null && !sectionCode.isBlank()) {
            ArchiveSection section = ArchiveSection.of(sectionCode);
            if (section.contentSource() == ArchiveSection.ContentSource.FILES) {
                // 照片视频的内容不在档案表里：返回空列表会让前端以为「没有照片」，
                // 所以这里明确报参数错误，让调用方去文件接口（契约里写了这条口径）
                throw BusinessException.paramInvalid(
                        "「照片视频」的内容在文件接口（/files?biz_type=profile），不在档案记录里");
            }
            query.in(ArchiveRecord::getCategory, section.categories());
        }
        if (from != null) {
            query.ge(ArchiveRecord::getRecordDate, from);
        }
        if (to != null) {
            query.le(ArchiveRecord::getRecordDate, to);
        }
        query.orderByDesc(ArchiveRecord::getRecordDate).orderByDesc(ArchiveRecord::getId);

        IPage<ArchiveRecord> result = recordMapper.selectPage(new Page<>(page, pageSize), query);
        List<ArchiveRecordView> views = withAuthority(result.getRecords());
        return PageResult.of(views, result.getCurrent(), result.getSize(), result.getTotal());
    }

    /** 录入一条分项记录。 */
    @Transactional
    public ArchiveRecordView create(long userId, long petId, ArchiveRecordRequest request) {
        petService.requireOwned(userId, petId);
        ArchiveSection section = ArchiveSection.requireWritable(request.section());
        LocalDate date = parseWithinWindow(request.date());
        LocalDate dueOn = parseDueOn(request.dueOn(), date);

        ArchiveRecord record = new ArchiveRecord();
        record.setPetId(petId);
        record.setUserId(userId);
        record.setRecordDate(date);
        record.setCategory(section.writeCategory());
        record.setDueOn(dueOn);
        record.setStructuredPayload(payloadOf(request));
        // 这条路径只代表**用户录入**：服务者报工与 AI 各有自己的写入路径（ADR-0030 第三条）
        record.setSource(ArchiveRecord.SOURCE_USER);
        record.setAbnormal(Boolean.TRUE.equals(request.abnormal()) ? 1 : 0);
        // 补录标记与打卡同口径：不是当场录入就标出来，不冒充
        record.setBackfilled(date.isBefore(AppTime.today()) ? 1 : 0);
        recordMapper.insert(record);
        return toView(record, section, true);
    }

    // ---------------------------------------------------------------- 服务者报工（第三源）

    /**
     * 服务者报工写一条档案记录（{@link ProviderReportArchiveApi}，F004 的「手动 + AI + 服务者报工」三源）。
     *
     * <p><b>为什么它是第三个写入口而不是复用 {@link #create}</b>：那条路径的语义是「用户按分项录入」
     * ——它有可录窗口（今天 + 过去 7 天）、有分项编码、有权威标注，三项都对不上报工这件事。
     * 报工的事实是「某家店在某个订单里服务了这只宠物」，订单侧不知道也不需要知道 {@code category}。
     *
     * <p><b>分项取「其他指标」（category=10）</b>，四个候选逐个排除后的落点：
     *
     * <ol>
     *   <li>打卡六项（1–6）不能用：它们在库里有 {@code uk_checkin_slot}（宠物 + 日期 + 分项的幂等槽，
     *       V4 的生成列），主人当天正好打卡过同一分项时，报工的插入会撞唯一键——那会把
     *       「今天洗了澡、主人顺手打了卫生卡」这种最常见的场景变成报工失败；
     *   <li>防疫（7）/ 证件（11）不能用：ADR-0030 把这两项定为「被机构核验过的事实」，权威来源是
     *       服务者报工——把一次洗护写进去，权威标注会把洗护当成一条防疫 / 证件事实；
     *   <li>就医（8）不能用：那是**用户自述**，且 ADR-0030 明写「平台不产出医疗记录」；
     *       老年专项（12）只在专项照护开启时才可见，写进去等于让记录消失。
     * </ol>
     *
     * <p>category=10 是「有形状但不参与任何评分维度」的桶（ADR-0030 决定二：10/11/12 不计入维度），
     * 服务记录落在这里**不会伪造任何一条医疗 / 防疫事实，也不会把分项条数变成评分**。
     * 代价是它会出现在「核心指标」分项的列表里——ADR-0030 的分项映射里本来就没有「服务记录」
     * 这一类，要让它归位得先给 ADR 补一个分项（属待澄清，不在本切片）。
     *
     * <p>业务日期取**报工当天**（不是订单的预约日）：ADR-0018 的口径是「当场录入=当天」，
     * 而报工正是服务结束的那一刻；跨日履约（预约昨天、今天才报工）时预约日会早于服务实际完成，
     * 按它落库会让时间轴上的事件时间比事实更早。
     */
    @Override
    @Transactional
    public void recordServiceReport(ProviderReportArchiveApi.ServiceReport report) {
        ArchiveRecord record = new ArchiveRecord();
        record.setPetId(report.petId());
        record.setUserId(report.userId());
        record.setRecordDate(AppTime.today());
        record.setCategory(ArchiveRecord.CATEGORY_METRIC_OTHER);
        record.setStructuredPayload(payloadOf(report));
        // 报工就是**服务者报工**这一源：唯一一处写 3 的地方（时间轴按它归到「服务者报工」那类）
        record.setSource(ArchiveRecord.SOURCE_PROVIDER);
        record.setAbnormal(0);
        // 服务发生与报工在同一天：不标补录（它不是「事后补的观察」，而是服务记录本身）
        record.setBackfilled(0);
        recordMapper.insert(record);
    }

    /** 服务报工的载荷：标题取订单里的服务项快照，备注就是门店留下的那句话。
     *  门店名不进载荷——它是**现取**的展示数据（订单模块刻意不快照门店名），
     *  抄进档案只会留下一份会过期的副本。 */
    private String payloadOf(ProviderReportArchiveApi.ServiceReport report) {
        Map<String, String> fields = new LinkedHashMap<>();
        String title = report.serviceName() == null || report.serviceName().isBlank()
                ? "到店服务" : report.serviceName().trim();
        fields.put("title", title);
        if (report.remark() != null && !report.remark().isBlank()) {
            fields.put("note", report.remark().trim());
        }
        return JsonFields.write(fields);
    }

    /** 修改一条分项记录（不改分项：要换分项就删了重录）。 */
    @Transactional
    public ArchiveRecordView update(long userId, long petId, long recordId, ArchiveRecordRequest request) {
        petService.requireOwned(userId, petId);
        ArchiveSection section = ArchiveSection.requireWritable(request.section());
        ArchiveRecord record = requireOwnRecord(petId, recordId);
        if (!record.getCategory().equals(section.writeCategory())) {
            throw BusinessException.paramInvalid("这条记录不属于「" + section.displayName() + "」，不能从这个分项修改");
        }
        LocalDate date = parseWithinWindow(request.date());
        record.setRecordDate(date);
        record.setDueOn(parseDueOn(request.dueOn(), date));
        record.setStructuredPayload(payloadOf(request));
        record.setAbnormal(Boolean.TRUE.equals(request.abnormal()) ? 1 : 0);
        recordMapper.updateById(record);
        return toView(record, section, true);
    }

    /**
     * 删除一条分项记录（软删除）。
     *
     * <p>与「宠物名下的子资源」同一口径（docs/conventions.md）：宠物归属先校验（不是自己的 40400），
     * **记录自身删除幂等**（没有也返回成功）。但**打卡与防疫的行不能从这里删**——它们各有自己的
     * 撤销/删除接口，从这条路径删会让「一天一条」的幂等语义出现第二个入口。
     */
    @Transactional
    public void delete(long userId, long petId, long recordId) {
        petService.requireOwned(userId, petId);
        ArchiveRecord record = recordMapper.selectById(recordId);
        if (record == null || !petId(record).equals(petId)) {
            return;
        }
        if (!ArchiveSection.isSectionWritten(record.getCategory())) {
            throw BusinessException.paramInvalid("打卡与防疫记录不在这个接口删除，请用各自的接口");
        }
        recordMapper.deleteById(recordId);
    }

    // ---------------------------------------------------------------- 时间轴

    /** 时间轴（倒序分页）。只收四类事件，正常打卡不在其中（ADR-0030 第四条）。 */
    @Transactional(readOnly = true)
    public PageResult<TimelineEventView> timeline(long userId, long petId, String type,
                                                  LocalDate from, LocalDate to,
                                                  long page, long pageSize) {
        petService.requireOwned(userId, petId);
        validateRange(from, to);
        int typeCode = 0;
        if (type != null && !type.isBlank()) {
            Integer code = TIMELINE_TYPES.get(type);
            if (code == null) {
                throw BusinessException.paramInvalid("不认识的事件类型：" + type);
            }
            typeCode = code;
        }

        IPage<ArchiveRecord> result = recordMapper.selectTimeline(
                new Page<>(page, pageSize), petId, typeCode, from, to);
        List<TimelineEventView> events = new ArrayList<>();
        for (ArchiveRecord record : result.getRecords()) {
            events.add(toEvent(record));
        }
        return PageResult.of(events, result.getCurrent(), result.getSize(), result.getTotal());
    }

    /** 一行档案记录 → 一条时间轴事件（类型与标题按分项与来源推导）。 */
    private TimelineEventView toEvent(ArchiveRecord record) {
        int category = record.getCategory();
        String sourceLabel = sourceLabel(record.getSource());
        String title = payloadValue(record, "title");
        String summary = payloadValue(record, "note");

        String type;
        String typeName;
        if (category == ArchiveRecord.CATEGORY_EPIDEMIC) {
            boolean deworm = ArchiveRecord.EPIDEMIC_DEWORM.equals(payloadValue(record, "kind"));
            type = deworm ? "deworm" : "vaccine";
            typeName = deworm ? "驱虫" : "疫苗";
            title = payloadValue(record, "name");
            summary = record.getDueOn() == null ? summary : "下次应接种 " + record.getDueOn();
        } else if (category == ArchiveRecord.CATEGORY_MEDICAL) {
            type = "medical";
            typeName = "就医记录";
        } else if (record.getSource() != null && record.getSource() == ArchiveRecord.SOURCE_PROVIDER) {
            // 服务者报工：任意 category 都进时间轴（ADR-0030 的四类之一）
            type = "provider";
            typeName = "服务者报工";
            if (title == null) {
                title = CATEGORY_NAMES.getOrDefault(category, "服务记录");
            }
            summary = "由服务者现场录入";
        } else {
            type = "abnormal";
            typeName = "异常记录";
            String value = payloadValue(record, "value");
            title = CATEGORY_NAMES.getOrDefault(category, "记录") + "标注为异常";
            summary = value == null ? summary : "记录值：" + value;
        }
        return new TimelineEventView(record.getId(), type, typeName, category, record.getRecordDate(),
                title == null ? CATEGORY_NAMES.getOrDefault(category, "记录") : title, summary,
                record.getSource(), sourceLabel, record.isAbnormalFlag(), record.isBackfilledFlag());
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 给一页记录标权威来源（ADR-0030 第三条）。
     *
     * <p>粒度是「宠物 + 分项 + 业务日期」：同一格里若有权威来源（证件/防疫是服务者报工，
     * 其余是用户），那条就是当前值；**权威缺席时次一级来源顶上**（否则「以报工为准」会变成
     * 「没报工就没内容」）。AI 永远不是权威。每组只标一条（该来源里最新的一条）。
     *
     * <p>按页计算是刻意的：跨页去算意味着每次翻页都要扫全表，而这个标注只影响展示层的强调——
     * 同一天的记录在按日期倒序的分页里必然相邻。
     */
    private List<ArchiveRecordView> withAuthority(List<ArchiveRecord> records) {
        Map<String, List<ArchiveRecord>> groups = new LinkedHashMap<>();
        for (ArchiveRecord record : records) {
            groups.computeIfAbsent(record.getCategory() + ":" + record.getRecordDate(),
                    key -> new ArrayList<>()).add(record);
        }

        Map<Long, Boolean> authoritative = new LinkedHashMap<>();
        for (List<ArchiveRecord> group : groups.values()) {
            ArchiveSection section = ArchiveSection.ofCategory(group.get(0).getCategory()).orElse(null);
            int preferred = section == null ? ArchiveRecord.SOURCE_USER : section.authoritySource();
            int effective = group.stream().anyMatch(r -> sameSource(r, preferred))
                    ? preferred
                    // 权威来源缺席：取最新一条非 AI 记录的来源顶上（AI 只产生建议，永远不是权威）
                    : group.stream().filter(r -> !sameSource(r, ArchiveRecord.SOURCE_AI))
                            .map(ArchiveRecord::getSource)
                            .filter(java.util.Objects::nonNull)
                            .findFirst().orElse(preferred);
            // 列表已按 record_date + id 倒序，所以组内第一条属于该来源的记录就是最新那条
            ArchiveRecord current = group.stream().filter(r -> sameSource(r, effective))
                    .findFirst().orElse(null);
            for (ArchiveRecord record : group) {
                authoritative.put(record.getId(), current != null && current.getId().equals(record.getId()));
            }
        }

        List<ArchiveRecordView> views = new ArrayList<>();
        for (ArchiveRecord record : records) {
            ArchiveSection section = ArchiveSection.ofCategory(record.getCategory()).orElse(null);
            views.add(toView(record, section, Boolean.TRUE.equals(authoritative.get(record.getId()))));
        }
        return views;
    }

    /** 来源相等判断。{@code source} 形参是 int 而列可空（历史行没有来源），不先判 null 就是一次拆箱 NPE。 */
    private boolean sameSource(ArchiveRecord record, int source) {
        return record.getSource() != null && record.getSource() == source;
    }

    private Long petId(ArchiveRecord record) {
        return record.getPetId();
    }

    /** 按**宠物**取记录（不是按 user_id）：同一个人可能有多只宠物，A 宠物的记录不能从 B 宠物的路径改。
     *  别人的与不存在的同码 40400（docs/conventions.md）。 */
    private ArchiveRecord requireOwnRecord(long petId, long recordId) {
        ArchiveRecord record = recordMapper.selectById(recordId);
        if (record == null || !petId(record).equals(petId)) {
            throw BusinessException.notFound("这条记录不存在");
        }
        return record;
    }

    /** 行 → 视图（读路径的归一）。两列 payload 已由 {@link #payloadValue} 并成一个视图（ADR-0030 决定一）；
     *  标题缺失时回落分项名——列表里不出现没有标题的行。 */
    private ArchiveRecordView toView(ArchiveRecord record, ArchiveSection section, boolean authoritative) {
        String payloadTitle = payloadValue(record, "title");
        return new ArchiveRecordView(
                record.getId(),
                section == null ? null : section.code(),
                record.getCategory(),
                record.getRecordDate(),
                payloadTitle == null && section != null ? section.displayName() : payloadTitle,
                payloadValue(record, "value"),
                JsonFields.read(record.getStructuredPayload(), "unit"),
                record.getDueOn(),
                payloadValue(record, "note"),
                record.isAbnormalFlag(),
                record.getSource(),
                sourceLabel(record.getSource()),
                authoritative,
                record.isBackfilledFlag(),
                record.getCreatedAt());
    }

    /**
     * 取值优先读 {@code structured_payload}，回落到打卡/防疫的 {@code content}。
     *
     * <p>这正是读接口的「归一」：两列在库里是分开的（ADR-0030 决定一的取舍），
     * 对下发出去的形状没有影响——前端只看到一个 payload 视图。
     */
    private String payloadValue(ArchiveRecord record, String field) {
        String value = JsonFields.read(record.getStructuredPayload(), field);
        return value != null ? value : JsonFields.read(record.getContent(), field);
    }

    /** 用户填的四个字段 → {@code structured_payload} 的 JSON。空串不入库（「没填」与「填了个空」
     *  下发时不该有区别）；键名与 {@link #payloadValue} 的取值名一一对应——改键名等于改契约。 */
    private String payloadOf(ArchiveRecordRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", request.title().trim());
        if (request.value() != null && !request.value().isBlank()) {
            fields.put("value", request.value().trim());
        }
        if (request.unit() != null && !request.unit().isBlank()) {
            fields.put("unit", request.unit().trim());
        }
        if (request.note() != null && !request.note().isBlank()) {
            fields.put("note", request.note().trim());
        }
        return JsonFields.write(fields);
    }

    /** 可录窗口：今天 + 过去 {@link #WRITE_WINDOW_DAYS} 天，与打卡同一口径（两条录入路径给出同一个答案）。
     *  未来的日期一律拒：它会进趋势与评分，那是「预测」不是「记录」。「今天」走 {@link AppTime} 的
     *  Asia/Shanghai，不是 JVM 默认时区（本地、容器、CI 会给出三个不同的今天）。 */
    private LocalDate parseWithinWindow(String raw) {
        LocalDate date = AppTime.parseDate(raw);
        LocalDate today = AppTime.today();
        if (date.isAfter(today)) {
            throw BusinessException.paramInvalid("不能给将来的日期录入记录");
        }
        if (date.isBefore(today.minusDays(WRITE_WINDOW_DAYS))) {
            throw BusinessException.paramInvalid("只能录入今天或最近 " + WRITE_WINDOW_DAYS + " 天的记录");
        }
        return date;
    }

    private LocalDate parseDueOn(String raw, LocalDate date) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        LocalDate dueOn = AppTime.parseDate(raw);
        if (dueOn.isBefore(date)) {
            throw BusinessException.paramInvalid("到期日期不能早于记录日期");
        }
        return dueOn;
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw BusinessException.paramInvalid("起始日期不能晚于结束日期");
        }
    }

    private String sourceLabel(Integer source) {
        if (source == null) {
            return "未知来源";
        }
        return switch (source) {
            case ArchiveRecord.SOURCE_AI -> "AI 建议";
            case ArchiveRecord.SOURCE_PROVIDER -> "服务者报工";
            default -> "用户录入";
        };
    }
}
