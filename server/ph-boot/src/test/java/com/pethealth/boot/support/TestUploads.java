package com.pethealth.boot.support;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * 需要在用例里**真的传一张图**时用它——走与线上完全一样的两步：
 * 申请凭证（`POST /api/v1/provider/files/presign`）→ 把字节直传到凭证给的地址（ADR-0020，字节不经业务接口）。
 *
 * <p>为什么要有这个共享件：资质材料**必带图**（ADR-0053），于是「造一份材料」这个动作
 * 在服务者侧、账号侧的好几组用例里都要做。各写一份的话，哪天上传协议变了要改好多个地方，
 * 而漏改的用例会以「莫名其妙 40001」的形式红掉。
 *
 * <p>失败一律抛 {@link AssertionError} 而不是返回值：这是**前置条件**，
 * 前置条件没成立时用例不该继续往下跑（否则后面会在一个错误的起点上断言别的东西）。
 */
public final class TestUploads {

    private TestUploads() {
    }

    /** 上传一张 64×64 的 PNG 作为资质材料图，返回 `file_id`。 */
    public static long qualificationImage(ApiClient api, String providerToken) {
        return qualificationImage(api, providerToken, TestImages.png(64, 64));
    }

    /**
     * 同上，但由调用方给字节。
     *
     * <p>要这个重载是为了「读回来与传上去**逐字节一致**」这条断言：自己拿着原始字节，
     * 才不用依赖图片编码是否可复现。
     */
    public static long qualificationImage(ApiClient api, String providerToken, byte[] content) {
        ApiClient.ApiCall presigned = api.post("/api/v1/provider/files/presign",
                Map.of("biz_type", "qualification", "items", List.of(Map.of("mime", "image/png"))),
                providerToken);
        if (presigned.code() != 0) {
            throw new AssertionError("申请资质图上传凭证失败：" + presigned.body());
        }
        JsonNode target = presigned.data().get(0);
        ApiClient.ApiCall uploaded = api.putBinary(target.path("upload_url").asText(), content);
        if (uploaded.status() != 204) {
            throw new AssertionError("直传应返回 204，实际 " + uploaded.status() + "：" + uploaded.body());
        }
        return target.path("file_id").asLong();
    }
}
