package com.pethealth.boot.architecture;

import com.pethealth.boot.support.RepoRoot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * 模块边界的护栏（ADR-0006：「模块间只能走接口或领域事件，禁止 join 其它模块的表」）。
 *
 * <p>这条规矩原先**没有执行者**：全靠评审与自觉。{@code @MapperScan} 扫的是 {@code com.pethealth}
 * 全域，任何模块的 {@code *Mapper} 在任何地方都能注入——一个 import 就能把两个模块焊死。
 * 本测试把它变成断言：跨模块只允许落在对方的 {@code api}（接口面）或 {@code event}（领域事件）上。
 *
 * <p>判定与反空转保护都在 {@link ModuleBoundary}；本类负责「真树零违规」与「规则真的会响」
 * （后者用临时目录搭小树，见 {@link TempDir} 那几个用例）。
 *
 * <p><b>基线是零</b>：这条检查不做「已知违规清单」——仓库当前的跨模块引用已经全部收口到
 * {@code api} 包（谁需要常量，就由拥有方在 {@code api} 上给个名字），所以新出现的违规一定是
 * 这一次改动引入的，直接红掉最好查。
 */
@DisplayName("模块边界：跨模块引用只走 api / 领域事件")
class ModuleBoundaryTest {

    @Test
    @DisplayName("真实代码树零违规（且确实扫到了全部模块）")
    void realTreeHasNoCrossModuleInternalImports() {
        Path serverDir = RepoRoot.find().resolve("server");
        ModuleBoundary.Scan scan = ModuleBoundary.scan(serverDir);

        // 反空转一：扫到的规模要像回事。数字写死是刻意的——缩水说明扫描逻辑坏了（例如
        // 模块目录改名、Files.walk 提前返回），那时「零违规」是假绿。
        // 取值留了余量：13 个模块 / 441 个源文件 / 1275 条跨包 import（2026-09-30 实测）
        assertThat(scan.modules())
                .as("扫到的模块数（ph-*）")
                .isGreaterThanOrEqualTo(12);
        assertThat(scan.files())
                .as("扫到的源文件数")
                .isGreaterThanOrEqualTo(350);
        assertThat(scan.imports())
                .as("看到的 com.pethealth 跨包 import 数")
                .isGreaterThanOrEqualTo(1000);

        // 反空转二：零违规要建立在这三个计数都成立之上
        assertThat(scan.findings())
                .as("跨模块引用内部包（应改成走对方的 api 接口）")
                .isEmpty();
    }

    @Test
    @DisplayName("规则会响：引用别人的 domain / mapper 报出，api / 事件 / 同模块 / 共享模块放行")
    void rulesFireExactly(@TempDir Path tmp) throws IOException {
        Path server = tmp.resolve("server");
        // A → B.domain：违规
        writeSource(server, "ph-alpha", "com.pethealth.alpha.service", "Foo",
                "import com.pethealth.beta.domain.BetaThing;",
                "import com.pethealth.common.error.BusinessException;");
        writeSource(server, "ph-beta", "com.pethealth.beta.domain", "BetaThing");
        // C → D.mapper：违规
        writeSource(server, "ph-gamma", "com.pethealth.gamma.service", "Bar",
                "import com.pethealth.delta.mapper.DeltaMapper;");
        writeSource(server, "ph-delta", "com.pethealth.delta.mapper", "DeltaMapper");
        // E → F.api / F.event / api.app：放行（模块对外声明的两条通道 + 契约 DTO 模块）
        writeSource(server, "ph-epsilon", "com.pethealth.epsilon.service", "Baz",
                "import com.pethealth.zeta.api.ZetaApi;",
                "import com.pethealth.zeta.event.ZetaHappenedEvent;",
                "import com.pethealth.api.app.PetView;");
        writeSource(server, "ph-zeta", "com.pethealth.zeta.api", "ZetaApi");
        writeSource(server, "ph-zeta", "com.pethealth.zeta.event", "ZetaHappenedEvent");
        // H → 自己家的 domain：放行（同模块内部随便引）
        writeSource(server, "ph-eta", "com.pethealth.eta.service", "Qux",
                "import com.pethealth.eta.domain.EtaThing;");
        writeSource(server, "ph-eta", "com.pethealth.eta.domain", "EtaThing");
        // I → 静态导入别人的 domain：同样违规（换了个写法而已）
        writeSource(server, "ph-theta", "com.pethealth.theta.service", "Quux",
                "import static com.pethealth.iota.domain.IotaThing.NAME;");
        writeSource(server, "ph-iota", "com.pethealth.iota.domain", "IotaThing");

        ModuleBoundary.Scan scan = ModuleBoundary.scan(server);

        assertThat(scan.findings())
                .extracting(ModuleBoundary.Finding::owner, ModuleBoundary.Finding::target,
                        ModuleBoundary.Finding::layer)
                .containsExactlyInAnyOrder(
                        tuple("alpha", "beta", "domain"),
                        tuple("gamma", "delta", "mapper"),
                        tuple("theta", "iota", "domain"));
        assertThat(scan.findings()).allSatisfy(f -> assertThat(f.line()).isPositive());
    }

    @Test
    @DisplayName("空树不算通过：模块数与文件数会先不达标")
    void emptyTreeIsNotSilentlyGreen(@TempDir Path tmp) throws IOException {
        Path server = tmp.resolve("server");
        Files.createDirectories(server.resolve("ph-empty"));

        ModuleBoundary.Scan scan = ModuleBoundary.scan(server);

        assertThat(scan.findings()).isEmpty();
        // 「零违规」在这里没有意义——真实测试里正是靠那两个计数把这种情况挡住
        assertThat(scan.modules()).isZero();
        assertThat(scan.files()).isZero();
    }

    /** 在临时目录里落一个源文件；{@code imports} 追加在 package 行之后。 */
    private static void writeSource(Path server, String module, String pkg, String type, String... imports)
            throws IOException {
        Path dir = server.resolve(module).resolve("src/main/java").resolve(pkg.replace('.', '/'));
        Files.createDirectories(dir);
        StringBuilder body = new StringBuilder("package ").append(pkg).append(";\n\n");
        for (String line : imports) {
            body.append(line).append('\n');
        }
        body.append("\npublic class ").append(type).append(" {\n}\n");
        Files.writeString(dir.resolve(type + ".java"), body.toString());
    }
}
