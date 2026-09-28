package com.pethealth.file.api;

import java.util.List;

/**
 * 文件模块对外暴露的「取读地址」接口（ADR-0006：别的模块不碰 file_object 表）。
 *
 * <p>目前的调用方是 ph-ai：AI 服务要用图片参与判断，而图片是私有的（ADR-0020），
 * 所以由 Java 侧签发短时读地址交给它——AI 服务不需要知道签名怎么算，也不必持有存储凭据。
 *
 * <p>**只返回属于该用户的、已落定的文件**：不属于他的、还在待传状态的、已删的都不在返回里，
 * 调用方拿到的列表长度可能小于传入的 id 数（据此可以判断有文件不可用）。
 */
public interface FileUrlApi {

    /** 按 id 批量取签名读地址；顺序与传入一致，不可用的 id 会被跳过。 */
    List<String> readUrls(long userId, List<Long> fileIds);
}
