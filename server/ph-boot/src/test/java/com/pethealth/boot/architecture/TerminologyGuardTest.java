package com.pethealth.boot.architecture;

import com.pethealth.boot.support.RepoRoot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 领域术语的护栏：代码目录里**禁用词零命中**，引用交付文档原文的场合必须带 {@code 文档用词} 标记。
 *
 * <p><b>为什么需要它</b>：这条规矩写在三处（[CONTEXT.md] 的 _Avoid_ 词表、
 * {@code docs/conventions.md} 的「禁用词」一行、ADR-0001 ~ 0004 的改名记录），
 * 而且 {@code docs/conventions.md} 甚至给了一条**手工命令**来自查
 * （{@code grep -rn 商家 --include=*.ts --include=*.java | grep -v 文档用词}）。
 * 一条靠人记着手工跑的命令与没有规矩差不多：2026-09-30 这一轮就是按那条命令跑的，
 * 结果查出一处**漏标**（{@code AppKnowledgeController} 引用了交付文档的权限矩阵却没带标记），
 * 而上一轮的报告写的是「术语纪律零违规」。检查搬进 CI 之后，「零违规」才是一个可以复算的结论。
 *
 * <p><b>为什么必须有例外</b>：交付文档里的表名（{@code merchant} / {@code merchant_service}）
 * 与权限矩阵的原文（「审核商家/服务」）是**必须引用**的历史事实——改名本身要解释改成什么、
 * 为什么改，不写原文就讲不清。所以规矩不是「禁止出现」，而是「出现时必须自证是引用」：
 * 同一句（含折行）里带上 {@code 文档用词} 标记。这样审计时一条 grep 就能把
 * 「必要引用」与「真的写错了」分开。
 *
 * <p><b>判定范围</b>是 {@code docs/conventions.md} 写明的四个代码目录：{@code server} / {@code ai} /
 * {@code apps} / {@code packages}。{@code docs/} 与 {@code contract/} 不在范围里
 * ——ADR 是当时的决策记录（改它等于伪造记录），契约描述也大量引用交付文档原文。
 * 构建产物、依赖与虚拟环境一律跳过。
 */
@DisplayName("领域术语：代码目录禁用词零命中（引用交付文档原文须带「文档用词」标记）")
class TerminologyGuardTest {

    /** 禁用词：CONTEXT.md 的 _Avoid_ 词表 + 交付文档的英文字段名。 */
    private static final List<String> BANNED = List.of("商家", "商户", "店铺", "merchant");

    /** 扫描的四个代码目录（docs/ 与 contract/ 刻意不在其中，见类注释）。 */
    private static final List<String> ROOTS = List.of("server", "ai", "apps", "packages");

    /** 只看会被人读的源文件；构建产物、依赖与虚拟环境另有前缀过滤。 */
    private static final Set<String> SUFFIXES = Set.of(".java", ".ts", ".vue", ".sql", ".py",
            ".yaml", ".yml", ".json", ".css", ".html", ".md");

    /** 生成物、依赖与本机环境：它们不是「我们写的代码」。 */
    private static final List<String> SKIP = List.of("node_modules", "/target/", "/.venv/",
            "/__pycache__/", "/.pytest_cache/", "/.ruff_cache/", "/dist/", "pnpm-lock.yaml",
            "/.mvn/", "/.git/");

    /**
     * 本文件自己：它**必须**能写出那些词（词表、说明、以及 {@code @TempDir} 里那几段构造用例的
     * 源码文本）。这是与「文档用词」同类的自证例外——检查器不能把自己也算成违规，
     * 否则唯一的结果是它写不出来。**只排除这一个文件名**，不要扩大到整个测试目录：
     * 测试代码里的文案同样是会被人读到的文案。
     */
    private static final String SELF = "TerminologyGuardTest.java";

    /** 引用交付文档原文的自证标记。 */
    private static final String MARKER = "文档用词";

    /** 命中处允许标记出现在同一句的下方两行内：注释按 100 列折行，标记常被挤到下一行。 */
    private static final int MARKER_LOOKAHEAD_LINES = 2;

    @Test
    @DisplayName("真实代码树：零未标记命中，且确实扫到了全部四个代码目录")
    void realTreeHasNoUnmarkedBannedWords() throws IOException {
        List<Hit> hits = scan(RepoRoot.find(), ROOTS);
        List<Hit> unmarked = hits.stream().filter(hit -> !hit.marked()).toList();

        assertThat(unmarked)
                .as("禁用词命中且同一句里没有「%s」标记（引用交付文档原文时补上标记，否则换词）",
                        MARKER)
                .isEmpty();

        // 反空转：扫描规模要像回事。数字写死是刻意的——缩水说明跳过规则或后缀集合写宽了，
        // 那时「零命中」是假绿。（2026-09-30 实测：四个目录 / 900+ 个源文件。）
        assertThat(filesScanned).as("扫过的文件数").isGreaterThanOrEqualTo(500);
        assertThat(scannedRoots).as("实际扫到的代码目录").containsExactlyElementsOf(ROOTS);
    }

    @Test
    @DisplayName("规则会响：未标记命中报出，带标记的引用放行（含折行）")
    void rulesFireExactly(@TempDir Path tmp) throws IOException {
        writeRoot(tmp, "server", "ph-demo/src/main/java/com/pethealth/demo/Demo.java", """
                package com.pethealth.demo;
                /** 某个门店（交付文档写作「商家」，文档用词）。 */
                public class Demo {
                }
                """);
        writeRoot(tmp, "apps", "c-web/src/views/Bad.vue", """
                <template>
                  <div>选择商家</div>
                </template>
                """);
        writeRoot(tmp, "server", "ph-demo/src/main/resources/db/migration/V1__x.sql", """
                -- 交付文档 7.2 的 `merchant`
                --     表名，文档用词。
                CREATE TABLE provider (id BIGINT);
                """);
        writeRoot(tmp, "ai", "app/main.py", """
                \"\"\"商家入口。\"\"\"
                """);
        writeRoot(tmp, "packages", "shared/src/api/x.ts", """
                export const MERCHANT_LABEL = "商户";
                """);

        List<Hit> unmarked = scan(tmp, List.of("server", "apps", "ai", "packages")).stream()
                .filter(hit -> !hit.marked())
                .toList();

        assertThat(unmarked).extracting(Hit::file, Hit::word, Hit::line)
                .as("标记只豁免同一句：同行的放行，折到下一行的也放行，其余全部报出"
                        + "（大小写不敏感，所以 MERCHANT_LABEL 也算命中）")
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("apps/c-web/src/views/Bad.vue", "商家", 2),
                        org.assertj.core.groups.Tuple.tuple("ai/app/main.py", "商家", 1),
                        org.assertj.core.groups.Tuple.tuple("packages/shared/src/api/x.ts", "商户", 1),
                        org.assertj.core.groups.Tuple.tuple("packages/shared/src/api/x.ts", "merchant", 1));
    }

    /** 一处命中。 */
    record Hit(String file, String word, int line, boolean marked) {
    }

    /** 一次测试要跨方法读到扫描规模，用字段记（比再扫一遍稳，也能被反空转断言检查）。 */
    private int filesScanned;
    private final List<String> scannedRoots = new ArrayList<>();

    private List<Hit> scan(Path root, List<String> roots) throws IOException {
        filesScanned = 0;
        scannedRoots.clear();
        List<Hit> hits = new ArrayList<>();
        for (String name : roots) {
            Path dir = root.resolve(name);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            scannedRoots.add(name);
            try (Stream<Path> walk = Files.walk(dir)) {
                List<Path> files = walk.filter(Files::isRegularFile)
                        .filter(path -> !skipped(path))
                        .filter(path -> hasWantedSuffix(path))
                        .sorted()
                        .toList();
                for (Path file : files) {
                    filesScanned++;
                    hits.addAll(hitsIn(root.relativize(file).toString().replace('\\', '/'), file));
                }
            }
        }
        hits.sort(Comparator.comparing(Hit::file).thenComparing(Hit::line).thenComparing(Hit::word));
        return hits;
    }

    private List<Hit> hitsIn(String relative, Path file) throws IOException {
        List<String> lines = Files.readAllLines(file);
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            for (String word : BANNED) {
                Matcher matcher = Pattern.compile(word, Pattern.CASE_INSENSITIVE).matcher(line);
                while (matcher.find()) {
                    hits.add(new Hit(relative, word, i + 1, marked(lines, i)));
                }
            }
        }
        return hits;
    }

    /** 标记是否出现在这一句里：本行，或折行后的接下来两行。 */
    private boolean marked(List<String> lines, int index) {
        int last = Math.min(lines.size() - 1, index + MARKER_LOOKAHEAD_LINES);
        for (int i = index; i <= last; i++) {
            if (lines.get(i).contains(MARKER)) {
                return true;
            }
        }
        return false;
    }

    private boolean skipped(Path path) {
        if (path.getFileName().toString().equals(SELF)) {
            return true;
        }
        String normalized = "/" + path.toString().replace('\\', '/') + "/";
        return SKIP.stream().anyMatch(normalized::contains);
    }

    private boolean hasWantedSuffix(Path path) {
        String name = path.getFileName().toString();
        return SUFFIXES.stream().anyMatch(name::endsWith);
    }

    private void writeRoot(Path root, String name, String relative, String content) throws IOException {
        Path file = root.resolve(name).resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
