package com.pethealth.api.app;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 申请上传凭证，对应 contract/app.yaml 的 {@code FilePresignRequest}。
 *
 * <p>一次申请可以带多个文件（交付文档说的「拍照拍到电脑再批量上传」就是这条路径）：逐个返回上传地址，
 * 浏览器并发送字节。
 *
 * @param bizType 业务场景，决定文件归到哪个入口（打卡 / 防疫 / 证件 / 咨询 / 护理）
 * @param petId   关联宠物；证件类可以不挂
 */
public record FilePresignRequest(
        @NotBlank(message = "请说明文件用途")
        @Size(max = 32, message = "用途编码过长")
        String bizType,

        Long petId,

        @NotEmpty(message = "至少选择一个文件")
        @Size(max = 9, message = "一次最多 9 张")
        List<Item> items) {

    /**
     * @param mime     客户端声明的类型，**只用于提前拦明显不对的请求**；落库以魔数判定结果为准（ADR-0020）
     * @param sizeBytes 客户端声明的体积，同样只用于提前拦截
     * @param role     原图 original / 局部特写 closeup（#61 的视觉模型要看清局部）
     */
    public record Item(
            @NotBlank(message = "缺少文件类型")
            String mime,

            Long sizeBytes,

            String role) {
    }
}
