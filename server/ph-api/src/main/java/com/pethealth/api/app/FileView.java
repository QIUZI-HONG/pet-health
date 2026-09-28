package com.pethealth.api.app;

/**
 * 一个已落定的文件，对应 contract/app.yaml 的 {@code FileView}。
 *
 * <p>前端拿到的是 **{@code url} 而不是外链**：文件默认私有（ADR-0020），读地址由后端签发、带有效期；
 * 换成对象存储后前端不用改。
 *
 * @param url      原图的读地址（签名 URL）
 * @param thumbUrl 缩略图读地址；缩略图在上传落定时就生成好了
 */
public record FileView(
        long id,
        Long petId,
        String bizType,
        String role,
        String mime,
        long sizeBytes,
        Integer width,
        Integer height,
        String url,
        String thumbUrl) {
}
