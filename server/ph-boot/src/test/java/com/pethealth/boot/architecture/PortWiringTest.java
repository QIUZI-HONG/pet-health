package com.pethealth.boot.architecture;

import com.pethealth.boot.support.RepoRoot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨模块端口的接线护栏（ADR-0006 的另一半，见 {@link PortWiring} 的类注释）。
 *
 * <p>它盯的失败方式是「**测试绿、生产不可用**」：端口声明了，消费方用
 * {@code ObjectProvider} 就地取实现，取不到时各自决定降级（抛 50300 / 静默返回空 /
 * 按「未参与」处理），而集成测试注入了等价桩——于是 CI 永远不会红。
 *
 * <p><b>基线是「一条有据可查的例外」</b>，不是零：{@link #PENDING_WIRING} 里那一条是
 * ADR 明确记为「接线点」的（口径本身还没定），所以它不是漏做；除此之外任何新出现的
 * 未接线端口都要在 CI 红掉。加例外**必须**同时在下面的常量里写明 ADR 依据——
 * 让「故意留白」与「忘了接线」在代码里长得不一样。
 */
@DisplayName("跨模块端口：声明了接口面的都要有生产实现（桩不算）")
class PortWiringTest {

    /**
     * 已知的未接线端口：**只有这一条**，且是有 ADR 依据的接线点，不是漏做。
     *
     * <p>{@code ProviderGrowthFactsApi}（考核的拉新与券两项）：ADR-0052 的「需要协调」第 1 条写明，
     * 拉新那一半要先把「服务者的拉新入口」落下来（交付文档 F022 的店内二维码把用户绑到门店），
     * 而现在的邀请关系只有用户对用户——**这条口径本身也还没定**（ADR-0052 待澄清第 1 条）。
     * 所以它不能靠「实现出四个方法」清偿：{@code effectiveInvites} 的返回值要么是编的，
     * 要么得先有一个不存在的业务定义。未接线期间考核按「不参与、重算权重、明细里注明」处理
     * （ADR-0050 第四节），这条降级是**被声明的行为**，不是静默失败。
     */
    private static final String PENDING_WIRING = "ProviderGrowthFactsApi";

    @Test
    @DisplayName("真实代码树：除声明的例外外，每个 *Api 都有 src/main 里的实现")
    void everyPortHasProductionImplementation() {
        Path serverDir = RepoRoot.find().resolve("server");
        PortWiring.Scan scan = PortWiring.scan(serverDir);

        // 反空转：扫到的规模要像回事。数字写死是刻意的——缩水说明扫描逻辑坏了
        // （模块目录改名、Files.walk 提前返回、正则不再匹配），那时「零缺失」是假绿。
        // 取值留了余量：13 个模块 / 473 个 src/main 源文件 / 24 个 *Api（2026-09-30 实测）
        assertThat(scan.files())
                .as("扫过的 src/main 源文件数")
                .isGreaterThanOrEqualTo(400);
        assertThat(scan.ports().size())
                .as("扫到的 *Api 端口数")
                .isGreaterThanOrEqualTo(20);

        // 例外之外必须零缺失。报错时把「谁、在哪个文件」列出来，而不是只给一个数字
        assertThat(scan.missing())
                .as("声明了但没有任何生产实现的端口（测试桩不算）")
                .allSatisfy(port -> assertThat(port.name())
                        .as("未接线端口 %s 不在 PENDING_WIRING 里：要么补实现，要么在常量上写明 ADR 依据",
                                port)
                        .isEqualTo(PENDING_WIRING));
    }

    @Test
    @DisplayName("规则会响：只有测试实现的新端口报出来，有生产实现的放行")
    void rulesFireExactly(@TempDir Path tmp) throws IOException {
        Path server = tmp.resolve("server");
        writeSource(server, "ph-owner", "main/java/com/pethealth/owner/api/WiredApi.java", """
                package com.pethealth.owner.api;
                public interface WiredApi {
                }
                """);
        writeSource(server, "ph-owner", "main/java/com/pethealth/owner/service/WiredService.java", """
                package com.pethealth.owner.service;
                import com.pethealth.owner.api.WiredApi;
                public class WiredService implements WiredApi {
                }
                """);
        // 声明了、只有 test 里有实现：这一条必须被报出来（就是那次事故的形状）
        writeSource(server, "ph-owner", "main/java/com/pethealth/owner/api/StubbedApi.java", """
                package com.pethealth.owner.api;
                public interface StubbedApi {
                }
                """);
        writeSource(server, "ph-owner", "test/java/com/pethealth/owner/Stub.java", """
                package com.pethealth.owner;
                import com.pethealth.owner.api.StubbedApi;
                class Stub implements StubbedApi {
                }
                """);

        PortWiring.Scan scan = PortWiring.scan(server);

        assertThat(scan.ports()).extracting(PortWiring.Port::name)
                .containsExactlyInAnyOrder("WiredApi", "StubbedApi");
        assertThat(scan.missing()).extracting(PortWiring.Port::name)
                .as("只被测试实现的端口才算缺失")
                .containsExactly("StubbedApi");
    }

    @Test
    @DisplayName("规则会响：多实现与换行的 implements 都算数")
    void multiAndWrappedImplementsCount(@TempDir Path tmp) throws IOException {
        Path server = tmp.resolve("server");
        writeSource(server, "ph-owner", "main/java/com/pethealth/owner/api/FirstApi.java", """
                package com.pethealth.owner.api;
                public interface FirstApi {
                }
                """);
        writeSource(server, "ph-owner", "main/java/com/pethealth/owner/api/SecondApi.java", """
                package com.pethealth.owner.api;
                public interface SecondApi {
                }
                """);
        writeSource(server, "ph-owner", "main/java/com/pethealth/owner/service/BothService.java", """
                package com.pethealth.owner.service;
                import com.pethealth.owner.api.FirstApi;
                import com.pethealth.owner.api.SecondApi;
                public class BothService implements FirstApi,
                        SecondApi {
                }
                """);

        PortWiring.Scan scan = PortWiring.scan(server);

        assertThat(scan.missing())
                .as("逗号分隔与换行的 implements 都要认出来")
                .isEmpty();
    }

    private static void writeSource(Path server, String module, String relative, String content)
            throws IOException {
        Path file = server.resolve(module).resolve("src").resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
