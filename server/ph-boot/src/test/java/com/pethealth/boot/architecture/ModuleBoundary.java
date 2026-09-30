package com.pethealth.boot.architecture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 模块边界的**静态检查**：跨模块的 import 只能落在对方对外的那几个包上。
 *
 * <p><b>为什么需要它</b>：仓库的规矩是「模块间只能走接口或领域事件，禁止 join 其它模块的表」
 * （AGENTS.md / ADR-0006），但这条规矩在代码里**没有结构性的执行者**——{@code @MapperScan}
 * 扫的是 {@code com.pethealth} 全域，任何模块的 {@code *Mapper} 在任何地方都能注入，
 * 所以「不许碰别人的内部」全靠自觉。检查器把自觉变成一条会在 CI 红的断言。
 *
 * <p><b>判定规则</b>：设文件属于模块 {@code A}，其 {@code import com.pethealth.B.<layer>.…}：
 *
 * <ul>
 *   <li>{@code B} 是 {@code api} / {@code common} / {@code boot}——**放行**。
 *       前两个是共享内核与契约 DTO 模块，{@code boot} 是启动装配；
 *   <li>{@code layer} 是 {@code api} 或 {@code event}——**放行**。它们是模块**对外声明的**
 *       接口面与领域事件，正是规矩要求的两条通道；
 *   <li>其余一律**报违规**：{@code domain}（实体/状态机）、{@code mapper}（数据访问）、
 *       {@code service}（实现）、{@code config}、{@code storage}、{@code client}…
 *       ——这些是模块的内部，从外面引用它们等于把两个模块焊死，日后拆服务时无从下手。
 * </ul>
 *
 * <p><b>只扫 {@code src/main/java}</b>：测试代码跨模块取数据是合理的（校验要看到真值），
 * 这不是生产依赖。
 *
 * <p><b>已知的「合法例外」怎么处理</b>：确实需要跨模块用的**常量与值对象**，
 * 应当由拥有方在自己的 {@code api} 包里给出名字（如 {@code OrderStatsApi.STATUS_IN_SERVICE}
 * 绑定到 {@code OrderStatus.IN_SERVICE}），而不是让消费方去 import 对方的 {@code domain}。
 * 这条要求不是洁癖：{@code api} 包有 Javadoc 说明语义、有契约测试盯着，{@code domain} 没有。
 */
final class ModuleBoundary {

    /** 共享内核与契约模块：任何模块都可以引。 */
    private static final Set<String> SHARED_MODULES = Set.of("api", "common", "boot");

    /** 模块对外声明的两条通道：接口面与领域事件。 */
    private static final Set<String> OPEN_LAYERS = Set.of("api", "event");

    private static final Pattern PACKAGE = Pattern.compile("^package com\\.pethealth\\.(\\w+)", Pattern.MULTILINE);
    private static final Pattern IMPORT =
            Pattern.compile("^import\\s+(?:static\\s+)?com\\.pethealth\\.(\\w+)\\.(\\w+)\\.", Pattern.MULTILINE);

    private ModuleBoundary() {
    }

    /**
     * 一处违规。
     *
     * @param file      源文件（相对仓库根）
     * @param line      行号（从 1 起）
     * @param owner     这个文件所属模块
     * @param target    被引用的模块
     * @param layer     被引用的包（{@code domain} / {@code mapper} / …）
     * @param importLine 原始 import 语句
     */
    record Finding(String file, int line, String owner, String target, String layer, String importLine) {

        @Override
        public String toString() {
            return file + ":" + line + "  " + owner + " → " + target + "." + layer + "   " + importLine;
        }
    }

    /**
     * 扫描结果。
     *
     * <p>带上 {@code modules} / {@code files} / {@code imports} 三个计数是**反空转**用的：
     * 一个「什么都没扫到所以零违规」的检查会一直是绿的，那比没有检查更糟。
     */
    record Scan(int modules, int files, int imports, List<Finding> findings) {
    }

    /** 扫 {@code server/} 下的全部 {@code ph-*&#47;src/main/java}。 */
    static Scan scan(Path serverDir) {
        List<Path> sources = new ArrayList<>();
        Set<String> modules = new LinkedHashSet<>();
        try (Stream<Path> dirs = Files.list(serverDir)) {
            List<Path> moduleDirs = dirs.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith("ph-"))
                    .sorted()
                    .toList();
            for (Path moduleDir : moduleDirs) {
                Path sourceRoot = moduleDir.resolve("src/main/java");
                if (!Files.isDirectory(sourceRoot)) {
                    continue;
                }
                modules.add(moduleDir.getFileName().toString());
                try (Stream<Path> walk = Files.walk(sourceRoot)) {
                    sources.addAll(walk.filter(p -> p.toString().endsWith(".java")).toList());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("列不出模块源码：" + serverDir, e);
        }
        return scan(serverDir, modules, sources);
    }

    /**
     * 扫指定的文件集合（测试用：可以指向一个临时目录里手工搭出来的小树）。
     *
     * @param serverDir 用于算相对路径（只影响报告里的文件名）
     * @param modules   参与扫描的模块名
     * @param sources   待检源文件
     */
    static Scan scan(Path serverDir, Set<String> modules, List<Path> sources) {
        List<Finding> findings = new ArrayList<>();
        int imports = 0;
        for (Path file : sources.stream().sorted(Comparator.comparing(Path::toString)).toList()) {
            String text;
            try {
                text = Files.readString(file);
            } catch (IOException e) {
                throw new UncheckedIOException("读不到源文件：" + file, e);
            }
            Matcher pkg = PACKAGE.matcher(text);
            if (!pkg.find()) {
                continue;
            }
            String owner = pkg.group(1);
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].strip();
                if (!line.startsWith("import ")) {
                    continue;
                }
                Matcher im = IMPORT.matcher(line);
                if (!im.find()) {
                    continue;
                }
                imports++;
                String target = im.group(1);
                String layer = im.group(2);
                if (target.equals(owner) || SHARED_MODULES.contains(target) || OPEN_LAYERS.contains(layer)) {
                    continue;
                }
                String relative = serverDir.relativize(file).toString();
                findings.add(new Finding(relative, i + 1, owner, target, layer, line));
            }
        }
        return new Scan(modules.size(), sources.size(), imports, List.copyOf(findings));
    }
}
