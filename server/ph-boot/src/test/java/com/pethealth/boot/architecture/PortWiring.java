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
 * 跨模块端口的**接线检查**：模块对外声明了的 {@code *Api} 接口，必须有生产实现。
 *
 * <p><b>为什么需要它</b>：本仓的形态是「模块间只走对方的 {@code api} 接口」（ADR-0006），
 * 接口声明在 {@code com.pethealth.api} 或各模块自己的 {@code xxx.api} 包里，**实现由表的拥有者写**。
 * 这种形态有一个安静的失败方式：接口声明了、消费方用 {@code ObjectProvider} 就地取实现，
 * 取不到时各自决定降级——于是**测试注入等价桩、CI 全绿，而生产环境那个功能不存在**。
 * 它已经被真实地踩过一次（2026-09-30，四个端口：下单/号源/核销在线上报 50300、
 * 订单详情的预约人与券卡片为空、考核的拉新与券两项按「未参与」处理）。
 *
 * <p>桩在 {@code src/test} 里，本检查**只扫 {@code src/main}**：测试里的实现一律不算数
 * ——这正是那次能瞒过 394 个用例的原因。
 *
 * <p><b>与 {@link ModuleBoundary} 的分工</b>：那一条管「跨模块引用的方向对不对」（只能引 api/event），
 * 这一条管「引用的那个接口到底有没有人实现」。两条都不成立时，模块边界只是一张声明。
 *
 * <p><b>判定规则</b>：扫描 {@code server/ph-*}{@code /src/main/java} 下所有声明了
 * {@code public interface XxxApi} 的文件作为「端口」，再扫同一批树里所有 {@code implements} 子句
 * （含 {@code implements A, B} 这种多实现、以及换行的写法）作为「实现」。
 * 判定用的是**简单名**——本仓没有同名不同包的 {@code *Api}，同名会导致这个检查失去意义，
 * 所以真出现同名时正确的做法是改名，而不是把检查改成全限定名。
 */
final class PortWiring {

    /** 端口：一个对外声明的接口面。 */
    record Port(String name, String module, String file) {

        @Override
        public String toString() {
            return name + "（" + module + "，" + file + "）";
        }
    }

    /**
     * 扫描结果。
     *
     * <p>带上 {@code files} 与 {@code declarations} 两个计数是**反空转**用的：
     * 「零缺失」必须建立在这两个数都像回事之上，否则扫描逻辑坏掉时（目录改名、
     * {@code Files.walk} 提前返回）会静默变成一条假绿。
     *
     * @param ports        扫到的端口
     * @param files        扫过的源文件数
     * @param declarations 看到的 {@code implements} 子句里的接口名总数
     */
    record Scan(List<Port> ports, int files, Set<String> declarations) {

        /** 没有生产实现的端口，按模块名 + 接口名排好序（报错信息要稳定）。 */
        List<Port> missing() {
            List<Port> missing = new ArrayList<>();
            for (Port port : ports) {
                if (!declarations.contains(port.name())) {
                    missing.add(port);
                }
            }
            missing.sort(Comparator.comparing(Port::module).thenComparing(Port::name));
            return missing;
        }
    }

    /** 声明了对外接口：{@code public interface XxxApi}。 */
    private static final Pattern INTERFACE =
            Pattern.compile("public\\s+interface\\s+([A-Za-z0-9_]+Api)\\b");

    /** {@code implements} 子句：从关键字到类体的左花括号。换行写法一并吃掉。 */
    private static final Pattern IMPLEMENTS =
            Pattern.compile("implements\\s+([^{;]*?)\\{", Pattern.DOTALL);

    private PortWiring() {
    }

    /** 扫描 {@code server} 目录下的全部模块。 */
    static Scan scan(Path serverDir) {
        List<Port> ports = new ArrayList<>();
        Set<String> declarations = new LinkedHashSet<>();
        int files = 0;

        for (Path moduleDir : moduleDirs(serverDir)) {
            Path sourceRoot = moduleDir.resolve("src/main/java");
            if (!Files.isDirectory(sourceRoot)) {
                continue;
            }
            String module = moduleDir.getFileName().toString();
            for (Path file : javaFiles(sourceRoot)) {
                files++;
                String text = read(file);
                Matcher port = INTERFACE.matcher(text);
                while (port.find()) {
                    ports.add(new Port(port.group(1), module, relative(serverDir, file)));
                }
                Matcher impl = IMPLEMENTS.matcher(text);
                while (impl.find()) {
                    for (String name : impl.group(1).split(",")) {
                        // 去掉泛型参数（`Coupons<X>` 这种本仓没有，写上是为了不因将来的写法误判）
                        String simple = name.replaceAll("<.*>", "").trim();
                        int dot = simple.lastIndexOf('.');
                        declarations.add(dot < 0 ? simple : simple.substring(dot + 1));
                    }
                }
            }
        }
        return new Scan(ports, files, declarations);
    }

    private static List<Path> moduleDirs(Path serverDir) {
        try (Stream<Path> entries = Files.list(serverDir)) {
            return entries.filter(Files::isDirectory)
                    .filter(dir -> dir.getFileName().toString().startsWith("ph-"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Path> javaFiles(Path sourceRoot) {
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String relative(Path serverDir, Path file) {
        return serverDir.relativize(file).toString().replace('\\', '/');
    }
}
