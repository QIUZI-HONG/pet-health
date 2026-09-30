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
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 调度与事务的边界护栏：**同一个类里不得同时出现 {@code @Scheduled} 与 {@code @Transactional}**。
 *
 * <p><b>为什么需要它</b>：Spring 的 {@code @Transactional} 靠代理生效，而**同一个类内部的方法调用
 * 不走代理**。于是「{@code @Scheduled} 方法调本类的 {@code @Transactional} 方法」这种写法，
 * 在生产上事务是**静默消失**的——批算退化成每条语句各自提交，而
 * **测试是注入 Bean 调那个方法的，走的是代理、有事务**：两边行为不同，而三套测试全绿。
 * 2026-09-30 这一轮实测有四处（月度阶梯 / 券过期 / 资质到期 / 预约提醒）。
 *
 * <p>这一型缺陷正是本仓最难自查的那一类（与「端口只有测试实现」同源：**测试证明机制可用，
 * 生产路径是断的**），所以规矩不能只写在注释里。做法沿用 {@code PortWiringTest}：
 * 把约定变成断言，并给触发与批算一个最简单的分工——
 * <b>触发单独一个 bean</b>（本仓既有先例：{@code ReminderScheduler} / {@code HealthReportScheduler}）。
 *
 * <p><b>为什么判据是「同类同时出现」而不是「自调用」</b>：静态文本认不出调用图
 * （一个方法可以被本类调、也可以被外部调），认「同处一个类」是**保守但绝不会漏**的近似：
 * 只要两类注解不在同一个类里，自调用就不可能出现。代价是它也拦住「{@code @Scheduled} 与一个
 * 只在测试里用的 {@code @Transactional} 方法同处一类」这种无害写法——真遇到时按例外处理，
 * 并在这里写下理由，别悄悄放宽判据。
 *
 * <p><b>只看 {@code src/main}</b>：测试类里的事务语义由测试框架决定（{@code @Transactional}
 * 在测试里是回滚开关），与这条规矩无关。
 */
@DisplayName("调度与事务：@Scheduled 与 @Transactional 不得同处一个类")
class ScheduledTransactionBoundaryTest {

    /** 扫描范围：各模块的生产源码。 */
    private static final String MAIN_GLOB = "src/main";

    /** 构建产物不算（它们不是「我们写的代码」）。 */
    private static final List<String> SKIP = List.of("/target/", "/node_modules/", "/.git/");

    @Test
    @DisplayName("真实代码树：零违规，且确实扫到了该扫的类与注解")
    void realTreeHasNoMixedClass() throws IOException {
        List<Path> offenders = scan(RepoRoot.find());

        assertThat(offenders)
                .as("这些类同时声明了 @Scheduled 与 @Transactional——"
                        + "定时触发要单独一个 bean（先例：ReminderScheduler / HealthReportScheduler），"
                        + "否则触发时那层事务会被自调用绕过，而测试走代理、看不出来")
                .isEmpty();

        // 反空转：一个「什么都没扫到」的绿比红更危险。数字写死是刻意的（留了余量），
        // 扫描范围缩水（模块被挪走、后缀写错）时它必须红。（2026-09-30 实测：488 个生产源文件 / 11 个 @Scheduled。）
        assertThat(filesScanned).as("扫过的生产源文件数").isGreaterThanOrEqualTo(450);
        assertThat(scheduledFound).as("认出的 @Scheduled 注解数").isGreaterThanOrEqualTo(10);
        assertThat(transactionalFound).as("认出的 @Transactional 注解数").isGreaterThanOrEqualTo(200);
    }

    @Test
    @DisplayName("规则会响：同类出现两类注解即报出，注释里提到注解不算数")
    void rulesFireExactly(@TempDir Path tmp) throws IOException {
        // 违规：同一个类既定时又开事务（本轮修掉的那一型）
        write(tmp, "ph-demo/src/main/java/com/pethealth/demo/Job.java", """
                package com.pethealth.demo;
                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.transaction.annotation.Transactional;
                public class Job {
                    @Scheduled(cron = "0 0 9 * * *")
                    public void scheduled() {
                        run();
                    }
                    @Transactional
                    public int run() {
                        return 0;
                    }
                }
                """);
        // 合规：触发与批算分居两个类
        write(tmp, "ph-demo/src/main/java/com/pethealth/demo/Trigger.java", """
                package com.pethealth.demo;
                import org.springframework.scheduling.annotation.Scheduled;
                public class Trigger {
                    @Scheduled(cron = "0 0 9 * * *")
                    public void scheduled() {
                        job.run();
                    }
                }
                """);
        write(tmp, "ph-demo/src/main/java/com/pethealth/demo/Sweep.java", """
                package com.pethealth.demo;
                import org.springframework.transaction.annotation.Transactional;
                public class Sweep {
                    @Transactional
                    public int run() {
                        return 0;
                    }
                }
                """);
        // 合规：只在注释里提到注解（本仓的类注释会大量这么写，不能当成真注解）
        write(tmp, "ph-demo/src/main/java/com/pethealth/demo/Doc.java", """
                package com.pethealth.demo;
                /**
                 * 触发在别处：{@code @Scheduled} 方法不能与 {@code @Transactional} 同处一个类。
                 */
                public class Doc {
                }
                """);

        assertThat(scan(tmp))
                .as("只有真正声明了两类注解的 Job 该被报出")
                .extracting(path -> path.getFileName().toString())
                .containsExactly("Job.java");
    }

    /** 上一次扫描的规模，供反空转断言读（比再扫一遍稳）。 */
    private int filesScanned;
    private int scheduledFound;
    private int transactionalFound;

    /** 扫出违规类。返回的是文件路径，因为在 Java 里「一个文件一个主类」是本仓的既有形态。 */
    private List<Path> scan(Path root) throws IOException {
        filesScanned = 0;
        scheduledFound = 0;
        transactionalFound = 0;
        List<Path> offenders = new ArrayList<>();
        Path server = root.resolve("server");
        if (!Files.isDirectory(server)) {
            throw new IllegalStateException("找不到 " + server + "——这个检查要能读到后端源码");
        }
        try (Stream<Path> modules = Files.list(server)) {
            for (Path module : modules.filter(Files::isDirectory).sorted().toList()) {
                Path main = module.resolve(MAIN_GLOB);
                if (!Files.isDirectory(main)) {
                    continue;
                }
                try (Stream<Path> walk = Files.walk(main)) {
                    for (Path file : walk.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().endsWith(".java"))
                            .filter(path -> !skipped(path))
                            .sorted()
                            .toList()) {
                        filesScanned++;
                        if (isMixed(file)) {
                            offenders.add(file);
                        }
                    }
                }
            }
        }
        return offenders;
    }

    /**
     * 这个文件里是否同时有真注解。
     *
     * <p>只认**行首就是注解**的那种写法（去掉缩进后以 {@code @Scheduled} / {@code @Transactional} 开头）：
     * 类注释里写 {@code {@code @Scheduled}} 是说明，不是声明——本仓的注释密度很高，
     * 用「包含 @Scheduled 字面量」会把它自己的注释判成违规。
     */
    private boolean isMixed(Path file) throws IOException {
        boolean scheduled = false;
        boolean transactional = false;
        for (String raw : Files.readAllLines(file)) {
            String line = raw.strip();
            if (line.startsWith("@Scheduled")) {
                scheduled = true;
                scheduledFound++;
            } else if (line.startsWith("@Transactional")) {
                transactional = true;
                transactionalFound++;
            }
            if (scheduled && transactional) {
                return true;
            }
        }
        return false;
    }

    private boolean skipped(Path path) {
        String normalized = "/" + path.toString().replace('\\', '/') + "/";
        return SKIP.stream().anyMatch(normalized::contains);
    }

    private void write(Path tmp, String relative, String content) throws IOException {
        Path file = tmp.resolve("server").resolve(relative);
        Files.createDirectories(file.getParent());
        try {
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
