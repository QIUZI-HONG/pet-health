package com.pethealth.api.admin;

import jakarta.validation.constraints.Size;

/**
 * 审核通过，对应 contract 的 {@code ReviewApproveRequest}。
 *
 * <p>入驻申请与服务上架审核共用同一个请求体：两者都是「通过 + 可选备注」。
 * {@code remark} 可空——通过时运营往往没什么要说的，硬要求填一句只会得到「OK」。
 */
public record ReviewApproveRequest(

        @Size(max = 255, message = "审核备注最长 255 个字符")
        String remark) {
}
