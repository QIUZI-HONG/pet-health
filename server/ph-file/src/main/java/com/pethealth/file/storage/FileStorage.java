package com.pethealth.file.storage;

/**
 * 存储本体的抽象（ADR-0020）。业务层只认它，不认识磁盘也不认识云。
 *
 * <p>故意保持**四个方法**：多一个都在为「将来可能要用」买单（ADR-0020 的 Considered Options 里
 * 否掉了自建 MinIO，就是为了不让猜测性的抽象长进来）。真正随驱动变的只有 {@link #uploadUrl} 的形态
 * ——本地是我们自己的签名地址，生产是对象存储的直传地址。
 */
public interface FileStorage {

    /** 驱动名，写进 {@code file_object.storage_driver}；换驱动后历史行仍指着各自的对象键。 */
    String driver();

    /**
     * 浏览器把字节送到的地址。
     *
     * <p>凭证由调用方签发（见 {@code UploadTokens}），驱动只负责拼出自己那一套 URL 形态。
     */
    String uploadUrl(long fileId, String token);

    /** 写入对象。{@code storageKey} 由业务层按 {@code f/{userId}/{yyyyMM}/{fileId}.{ext}} 约定生成。 */
    void put(String storageKey, byte[] content);

    /** 读出对象；不存在时抛 {@code IllegalStateException}（属于「不该发生」，不是业务分支）。 */
    byte[] read(String storageKey);

    /** 删除对象；不存在时静默返回（幂等）。 */
    void remove(String storageKey);
}
