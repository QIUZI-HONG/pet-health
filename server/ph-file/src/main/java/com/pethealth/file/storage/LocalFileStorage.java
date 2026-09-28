package com.pethealth.file.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 本地盘驱动（ADR-0020）：字节落在 {@code app.file.root} 下，按 storageKey 分层建目录。
 *
 * <p>写入用「先写临时文件再原子移动」：直传可能中途断，半截文件留在正式路径上会让后续读到一个
 * **看起来存在、其实残缺**的对象——那比读不到更难查。临时文件放在同目录下，保证 move 是原子的。
 *
 * <p>**故意不加 {@code @Component}**：驱动由 {@code FileConfig} 按 {@code app.file.driver} 装配，
 * 两个装配点会造成「同类型两个 bean」的冲突，而且会让「配错了驱动」变成静默退回本地盘。
 */
public class LocalFileStorage implements FileStorage {

    public static final String DRIVER = "local";

    /** 直传地址的前缀。放在 open 域下：签名即授权，浏览器送字节时不带登录态（与对象存储一致）。 */
    private static final String UPLOAD_PATH = "/api/v1/open/files/";
    private static final String UPLOAD_SUFFIX = "/content";

    private final Path root;

    public LocalFileStorage(FileStorageProperties properties) {
        this.root = Path.of(properties.root()).toAbsolutePath().normalize();
    }

    @Override
    public String driver() {
        return DRIVER;
    }

    @Override
    public String uploadUrl(long fileId, String token) {
        // 相对地址：前端与后端同源（开发期走 vite 代理），换域名不用改配置
        return UPLOAD_PATH + fileId + UPLOAD_SUFFIX + "?token=" + token;
    }

    @Override
    public void put(String storageKey, byte[] content) {
        Path target = resolve(storageKey);
        try {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), ".upload-", ".part");
            Files.write(temp, content);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("写入文件失败：" + storageKey, e);
        }
    }

    @Override
    public byte[] read(String storageKey) {
        Path target = resolve(storageKey);
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new IllegalStateException("文件对象缺失：" + storageKey, e);
        }
    }

    @Override
    public void remove(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException e) {
            throw new UncheckedIOException("删除文件失败：" + storageKey, e);
        }
    }

    /** 根目录的绝对路径（测试与运维脚本要用）。 */
    public Path root() {
        return root;
    }

    /**
     * 落到根目录下的绝对路径。
     *
     * <p>{@code normalize} 之后必须仍在根目录内——storageKey 虽然由服务端生成，但这条检查是
     * **目录穿越的最后一道闸**（交付文档 6.3 的「上传安全」里点名了目录穿越）。少了它，
     * 将来任何一处把用户输入拼进 key 都会变成任意文件读写。
     */
    private Path resolve(String storageKey) {
        Path target = root.resolve(storageKey).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法存储键：" + storageKey);
        }
        return target;
    }
}
