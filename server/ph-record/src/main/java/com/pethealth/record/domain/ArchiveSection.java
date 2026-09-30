package com.pethealth.record.domain;

import com.pethealth.common.error.BusinessException;

import java.util.List;
import java.util.Optional;

/**
 * 档案的 8 个分项（交付文档 F004 / 4.16.4），以及它们与落库编码 {@code category} 的映射
 * （切片 #102，决策与字段清单见 ADR-0030）。
 *
 * <p><b>为什么要有这个枚举</b>：ADR-0023 把 8 个分项压在**一张表**上，于是「哪个分项对应哪些
 * {@code category}」这句映射必须有唯一一份实现——分项列表、记录读写、条数统计、时间轴、
 * 报告四处都要用它。散成四份的话，改一处忘三处的表现是「某个分项永远空着」，很难查。
 *
 * <p>三条固定口径（ADR-0030）：
 *
 * <ul>
 *   <li><b>分项与评分维度不同构</b>：分项是录进去的、维度是算出来的，这里没有分数；
 *   <li><b>可写的只有列表语义的分项</b>（{@link #writeCategory} 非空）：体重/排泄/防疫是
 *       「一天一条、幂等更新」的打卡语义，各有自己的接口；
 *   <li><b>照片视频不落这张表</b>：它的内容在文件模块（{@code biz_type=profile}），
 *       所以它的条数是 null——本模块不碰 {@code file_object}（ADR-0006）。
 * </ul>
 */
public enum ArchiveSection {

    /** 核心指标：体重与排泄走打卡，其余指标（体温/心率/饮水）走本接口（category=10）。 */
    METRICS("metrics", "核心指标", "体重、排泄，以及体温、心率、饮水这些能反映状态的数值",
            ContentSource.RECORDS, 10, 1, List.of(1, 3, 10)),

    /** 证件与合规：防疫记录（category=7，独立接口）+ 证件（category=11）。 */
    DOCUMENTS("documents", "证件与合规", "免疫证、犬证这类证件与它们的有效期",
            ContentSource.RECORDS, 11, 3, List.of(7, 11)),

    BEHAVIOR("behavior", "行为档案", "活动量、异常行为、训练进度这类观察",
            ContentSource.RECORDS, null, 1, List.of(4)),

    MOOD("mood", "情绪档案", "情绪状态与应激反应",
            ContentSource.RECORDS, null, 1, List.of(5)),

    HYGIENE("hygiene", "卫生档案", "清洁、护理与体表状态",
            ContentSource.RECORDS, null, 1, List.of(6)),

    DIET("diet", "饮食档案", "食欲与进食情况",
            ContentSource.RECORDS, null, 1, List.of(2)),

    /** 老年专项：专项照护开启后才可用（字段与判定见 ADR-0032）。 */
    ELDERLY("elderly", "老年专项", "复查、用药与慢病观察记录",
            ContentSource.RECORDS, 12, 1, List.of(12)),

    /** 照片视频：内容在文件模块，本分项只给入口（ADR-0023 第二条）。 */
    MEDIA("media", "照片视频", "体检单、疫苗本、患处照片；服务留痕照片不在这里",
            ContentSource.FILES, null, 1, List.of()),

    /**
     * 就医记录：**不是 8 个分项之一**（交付文档 F004 把它与 8 个模块并列写），
     * 但它在 ADR-0030 里是时间轴的一类事件，也可以录入（时间轴的详情入口）。
     */
    MEDICAL("medical", "就医记录", "一次就诊：机构、事由与结论（用户自述）",
            ContentSource.RECORDS, 8, 1, List.of(8));

    /** 分项的内容在哪：档案记录表里，还是文件模块里。 */
    public enum ContentSource {
        RECORDS, FILES
    }

    /** 交付文档 4.16.4 的 8 个模块入口，顺序就是界面的顺序。 */
    private static final List<ArchiveSection> ENTRIES = List.of(
            METRICS, DOCUMENTS, BEHAVIOR, MOOD, HYGIENE, DIET, ELDERLY, MEDIA);

    private final String code;
    private final String name;
    private final String description;

    /** 分项的内容来源：契约里是 records / files。 */
    private final ContentSource contentSource;

    /** 通过分项接口写记录时落到哪个 {@code category}；null = 不可写（各有自己的接口）。 */
    private final Integer writeCategory;

    /** 该分项的**权威来源**（1 用户 / 3 服务者报工）。AI（2）永远不是权威（ADR-0030 第三条）。 */
    private final int authoritySource;

    /** 读取该分项时要扫的 {@code category}（可能多个：核心指标 = 体重 + 排泄 + 其他指标）。 */
    private final List<Integer> categories;

    ArchiveSection(String code, String name, String description, ContentSource contentSource,
                   Integer writeCategory, int authoritySource, List<Integer> categories) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.contentSource = contentSource;
        this.writeCategory = writeCategory;
        this.authoritySource = authoritySource;
        this.categories = categories;
    }

    public String code() {
        return code;
    }

    public String displayName() {
        return name;
    }

    public String description() {
        return description;
    }

    public ContentSource contentSource() {
        return contentSource;
    }

    public Integer writeCategory() {
        return writeCategory;
    }

    public int authoritySource() {
        return authoritySource;
    }

    public List<Integer> categories() {
        return categories;
    }

    /** 是否可用分项写接口录入。 */
    public boolean recordable() {
        return writeCategory != null;
    }

    /** 8 个分项入口（含照片视频），顺序固定。 */
    public static List<ArchiveSection> entries() {
        return ENTRIES;
    }

    /** 按下发的编码找分项；未知编码一律 40001（不静默当成「全部分项」）。 */
    public static ArchiveSection of(String code) {
        for (ArchiveSection section : values()) {
            if (section.code.equals(code)) {
                return section;
            }
        }
        throw BusinessException.paramInvalid("不认识的分项：" + code);
    }

    /** 按落库编码反查分项（读的时候用；未知编码返回空，不抛错——历史脏数据不该让整个列表 500）。 */
    public static Optional<ArchiveSection> ofCategory(int category) {
        for (ArchiveSection section : ENTRIES) {
            if (section.categories.contains(category)) {
                return Optional.of(section);
            }
        }
        return MEDICAL.categories.contains(category) ? Optional.of(MEDICAL) : Optional.empty();
    }

    /** 写接口用的分项：只有列表语义的那四个，其余一律 40001（它们走打卡/防疫接口）。 */
    public static ArchiveSection requireWritable(String code) {
        ArchiveSection section = of(code);
        if (!section.recordable()) {
            throw BusinessException.paramInvalid(
                    "「" + section.name + "」不在分项录入接口的可写范围内（打卡六项与防疫各有自己的接口）");
        }
        return section;
    }

    /** 是否是分项写接口写出来的行（删除时的口径与写一致）。 */
    public static boolean isSectionWritten(int category) {
        for (ArchiveSection section : values()) {
            if (section.writeCategory != null && section.writeCategory == category) {
                return true;
            }
        }
        return false;
    }
}
