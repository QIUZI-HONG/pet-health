package com.pethealth.boot.contract;

import com.pethealth.boot.contract.ContractDtoDrift.Finding;
import com.pethealth.boot.contract.ContractDtoDrift.Kind;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.pethealth.boot.support.RepoRoot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * 自动校验：**契约声明的字段与 {@code ph-api} 的 DTO 是否漂移**（ADR-0047 的配套护栏）。
 *
 * <p>为什么值得单独测一层：前端类型由契约生成、后端 DTO 手写（ADR-0005），并行开发下
 * 没人盯得住四十个接口的字段名。{@code ContractJsonTest} 盯的是**运行时表现**
 * （信封、snake_case、时区、以及两张手写的字段清单），它得连库连 Redis 才跑；
 * 这一层盯的是**静态形状**——只读契约文件与编译好的 DTO 类，不碰数据库，
 * 也不需要一个被启动的应用，所以它可以是最先红的那盏灯。
 *
 * <p><b>为什么用反射而不是再写一份字段清单</b>：手写清单只覆盖写下它那一刻的那几个 schema，
 * 而且契约改了、DTO 改了，清单自己不会变——它守不住「未来的漂移」，而未来的漂移正是
 * 并行开发里唯一会发生的那种。反射把清单变成契约 × 编译产物的实时投影，谁改了字段名，
 * 下一次 {@code mvn test} 就是红灯。
 *
 * <p><b>已知漂移</b>放在同目录的 {@code known-drift-baseline.txt}：每条差异一行，
 * 谁负责修写在行末注释里。基线是**棘轮**——不变量：修好的必须删行（{@link #baselineHasNoStaleEntries()}），
 * 新出现的必须修（其余各测）。理由：把整张测试 {@code @Disabled} 掉等于把「已经对齐的
 * 九成字段」一起放手，新漂移会藏在旧漂移后面进来；而基线文件让测试**继续红着**，
 * 只是红得可归因（哪几张 schema、谁负责），改一处少一行。
 */
class ContractDtoDriftTest {

    /** 基线旁边这份名单的路径说明见 {@link #baselinePath()}。 */
    private static final String BASELINE_FILE_NAME = "known-drift-baseline.txt";

    /** 全量报告开关：{@code -DcontractDtoDrift.reportAll=true} 时把基线内的差异也打出来。 */
    private static final String REPORT_ALL_PROPERTY = "contractDtoDrift.reportAll";

    private static List<Finding> all;
    private static ContractDtoDrift.Report report;
    private static Set<String> baseline;
    private static Path baselineFile;

    @BeforeAll
    static void scanOnce() {
        report = ContractDtoDrift.scan(ContractDocuments.load(RepoRoot.find().resolve("contract")), DtoIndex.scan());
        all = report.findings();
        baselineFile = baselinePath();
        baseline = readBaseline(baselineFile);
        System.out.printf("契约 ↔ DTO 漂移：共 %d 处（基线内 %d 处）。比过 %d 张 schema / %d 个字段 / %d 个内联对象，%s%n",
                all.size(),
                all.stream().filter(f -> baseline.contains(f.key())).count(),
                report.schemasSeen(),
                report.fieldsCompared(),
                report.inlineObjectsCompared(),
                baselineFile);
        if (Boolean.getBoolean(REPORT_ALL_PROPERTY)) {
            System.out.println(renderGrouped(all));
        }
    }

    /**
     * 计数护栏：一个静态校验最坏的失败不是「红」，而是**因为什么都没比而绿**。
     *
     * <p>下界按「今天这个仓库」的实测值取（留了余量）：契约变大不会红，扫描范围缩水（类路径变了、
     * 包名改了、源文件被挪走）会红——那种绿比红危险得多。
     */
    @Test
    @DisplayName("自检：这一轮确实比到了该比的 schema、字段与内联对象")
    void scanCoverageIsNonTrivial() {
        assertThat(report.schemasSeen()).as("三份契约 components/schemas 的条目数").isGreaterThanOrEqualTo(114);
        assertThat(report.dtosMatched()).as("配上 DTO 的 schema 数").isGreaterThanOrEqualTo(110);
        assertThat(report.fieldsCompared()).as("逐个比过的字段数").isGreaterThanOrEqualTo(900);
        assertThat(report.inlineObjectsCompared()).as("比过的内联对象数（匿名形状）").isGreaterThanOrEqualTo(5);
    }

    @Test
    @DisplayName("每个 schema 都有同名 DTO，且 ph-api 里没有契约之外的接口 DTO")
    void schemaCoverageMatchesDto() {
        assertNoUnbaselined(Kind.SCHEMA_WITHOUT_DTO, Kind.SCHEMA_CLASS_NAME_MISMATCH, Kind.DTO_WITHOUT_SCHEMA);
    }

    @Test
    @DisplayName("字段名与数量与契约一致（漏实现与未声明字段都会红）")
    void fieldNamesAndCountsMatchContract() {
        assertNoUnbaselined(Kind.MISSING_IN_DTO, Kind.UNDECLARED_IN_DTO);
    }

    @Test
    @DisplayName("字段形状一致：契约的对象 / 数组 / 标量与 DTO 类型对得上（含内联对象递归）")
    void fieldShapesMatchContract() {
        assertNoUnbaselined(Kind.SHAPE_MISMATCH);
    }

    @Test
    @DisplayName("请求体的必填性与契约 required 一致（两个方向）")
    void requestRequirednessMatchesContract() {
        assertNoUnbaselined(Kind.REQUIRED_NOT_ENFORCED, Kind.REQUIRED_NOT_DECLARED);
    }

    /**
     * 棘轮：基线里的每一行都必须仍然对应一处真实差异。
     *
     * <p>少了这一条，基线会变成一张只增不减的欠条——修好之后没人删行，过几个月没人知道
     * 那些条目还在不在，于是「基线」退化成「免责声明」。红了就删行，成本是一条删除线。
     */
    @Test
    @DisplayName("基线没有过期条目（差异修好了就必须从基线里删掉）")
    void baselineHasNoStaleEntries() {
        Set<String> actual = all.stream().map(Finding::key).collect(Collectors.toCollection(LinkedHashSet::new));
        List<String> stale = baseline.stream().filter(key -> !actual.contains(key)).sorted().toList();

        assertThat(stale)
                .as("""
                        known-drift-baseline.txt 这些行已经没有对应的差异了（差异被修好，或契约/DTO 改名后被重新分类）：
                        %s
                        请删掉这些行——基线只应该包含**仍然存在**的差异。""", String.join("\n", stale))
                .isEmpty();
    }

    /**
     * 自检：这个校验算 JSON 名用的是 Jackson 自己的 SNAKE_CASE 策略。
     *
     * <p>如果哪天有人换了实现（自己写下划线转换、或换成别的策略），这条会先红——
     * 校验工具用错规则比不校验更坏：它会对着一个不存在的运行时点头。
     * （运行时那一侧由 {@code ContractJsonTest} 的 {@code active_pet_id} 等断言守。）
     */
    @Test
    @DisplayName("自检：JSON 名换算用的就是 Jackson 的 SNAKE_CASE")
    void snakeCaseRuleIsJacksons() {
        assertThat(DtoIndex.snakeCaseOf("isSterilized")).isEqualTo("is_sterilized");
        assertThat(DtoIndex.snakeCaseOf("activePetId")).isEqualTo("active_pet_id");
        assertThat(DtoIndex.snakeCaseOf("pageSize")).isEqualTo("page_size");
    }

    // ------------------------------------------------------------------ 断言与报告

    private static void assertNoUnbaselined(Kind... kinds) {
        Set<Kind> wanted = Set.of(kinds);
        List<Finding> relevant = all.stream().filter(f -> wanted.contains(f.kind())).toList();
        List<Finding> unexpected = relevant.stream().filter(f -> !baseline.contains(f.key())).toList();
        if (unexpected.isEmpty()) {
            return;
        }
        fail("""
                契约与 DTO 漂移：%d 处不在基线里（这一类共 %d 处，基线内 %d 处）。

                %s
                修法二选一：改 DTO 对齐契约；或（契约是只读输入）找契约写入者改契约，改完重跑 pnpm gen:api。
                已知且暂时不修的，加进 %s（每行 `文件|schema|字段路径|差异类型`，行末注明负责人）；
                修好的要删行，否则基线会过期。
                要看基线内的全量差异：-D%s=true""".formatted(
                unexpected.size(),
                relevant.size(),
                relevant.size() - unexpected.size(),
                renderGrouped(unexpected),
                BASELINE_FILE_NAME,
                REPORT_ALL_PROPERTY));
    }

    /** 按差异类型分组打印：报告要能直接当工单读，不是一句「不一致」。 */
    private static String renderGrouped(List<Finding> findings) {
        if (findings.isEmpty()) {
            return "（无）";
        }
        StringBuilder out = new StringBuilder();
        for (Kind kind : Kind.values()) {
            List<Finding> ofKind = findings.stream().filter(f -> f.kind() == kind).toList();
            if (ofKind.isEmpty()) {
                continue;
            }
            out.append("  ── ").append(kind.label()).append("（").append(ofKind.size()).append(" 处）\n");
            for (Finding finding : ofKind) {
                out.append("    ").append(finding.render().replace("\n", "\n    ")).append('\n');
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ 路径与基线文件

    /**
     * 基线文件就放在本类旁边（同一个包目录）。
     *
     * <p>不放进 {@code src/test/resources}：那个目录不在这次改动的允许范围里；而且基线与测试类
     * 放在一起，改哪张 schema、谁负责，评审时在同一个 diff 里看得见。
     */
    private static Path baselinePath() {
        Path path = RepoRoot.find().resolve("server/ph-boot/src/test/java/com/pethealth/boot/contract")
                .resolve(BASELINE_FILE_NAME);
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("找不到基线文件 " + path
                    + "——若确实没有已知漂移，请建一个只含注释的空文件（别删这个检查）");
        }
        return path;
    }

    private static Set<String> readBaseline(Path path) {
        List<String> lines;
        try (Stream<String> stream = Files.lines(path, StandardCharsets.UTF_8)) {
            lines = stream.toList();
        } catch (IOException e) {
            throw new UncheckedIOException("读不到基线文件 " + path, e);
        }
        Set<String> keys = new LinkedHashSet<>();
        List<String> malformed = new ArrayList<>();
        for (String raw : lines) {
            String line = raw;
            int comment = line.indexOf('#');
            if (comment >= 0) {
                line = line.substring(0, comment);
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            // 键的形状是 file|Schema|path|KIND，四段；多一段少一段都是手滑，直接报出来
            if (line.split("\\|", -1).length != 4) {
                malformed.add(raw);
                continue;
            }
            keys.add(line);
        }
        assertThat(malformed)
                .as("""
                        %s 里这些行不符合 `文件|schema|字段路径|差异类型` 的格式（字段路径为空时写成两个竖线相连）：
                        %s""", path, String.join("\n", malformed))
                .isEmpty();
        return keys;
    }
}
