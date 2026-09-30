package com.pethealth.boot.support;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 仓库根的定位。
 *
 * <p>为什么需要：测试的工作目录取决于谁在跑（Maven 是 {@code server/ph-boot}，
 * IDE 常常是仓库根），所以不能写死相对层级。判据统一用「往上找到 {@code contract/app.yaml}」
 * ——它是仓库里最稳定的一处标记。
 *
 * <p>收在一处的原因与业务代码里的收口同理：「仓库根在哪」这件事原先在两个测试里各写了一遍，
 * 两处判据可以不一致（一处找 {@code contract/app.yaml}、另一处找 {@code pnpm-workspace.yaml}），
 * 而它们**必须**指向同一个目录——否则同一台机器上「契约测试过了、边界测试没过」会非常难查。
 */
public final class RepoRoot {

    /** 判据文件：仓库根下必有 {@code contract/app.yaml}。 */
    private static final Path MARKER = Path.of("contract", "app.yaml");

    private RepoRoot() {
    }

    /** 从当前工作目录往上找仓库根；找不到即抛（静默回退到某个目录会让检查悄悄扫错地方）。 */
    public static Path find() {
        Path start = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve(MARKER))) {
                return dir;
            }
        }
        throw new IllegalStateException(
                "从 " + start + " 往上找不到 " + MARKER + "——这个检查要能读到仓库根的文件");
    }
}
