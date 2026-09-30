package com.pethealth.provider.domain;

import com.pethealth.api.provider.AssessmentDtos;

import java.util.List;
import java.util.Map;

/**
 * 考核项目的编码与中文名（**与契约 {@code AssessmentItemView.item_code} 同一套**）。
 *
 * <p>编码常量只有一份，在 {@link AssessmentDtos.Item} 里（它是契约的形状，ph-api 里）；
 * 中文名是服务端给的展示值，留在这里。两者分开的代价是多一处引用，好处是编码不会两处各写一遍
 * ——两处写同一批字符串，早晚会出现「一处加了子项、另一处没加」。
 *
 * <p>三项主项（INVITE / COUPON / PROCESS）与过程子项的区别不只是层级：
 * **只有主项能被超级管理员覆盖**（子项是过程分的构成，覆盖它们等于绕过算法）。
 */
public final class AssessmentItems {

    /** 一个考核项目：编码 + 中文名 + 父项编码（主项为空）。 */
    public record Item(String code, String name, String parentCode) {

        public boolean isMain() {
            return parentCode == null;
        }
    }

    public static final Item INVITE = new Item(AssessmentDtos.Item.INVITE, "拉新", null);
    public static final Item COUPON = new Item(AssessmentDtos.Item.COUPON, "券", null);
    public static final Item PROCESS = new Item(AssessmentDtos.Item.PROCESS, "过程", null);

    public static final Item PROCESS_RESPONSE =
            new Item(AssessmentDtos.Item.PROCESS_RESPONSE, "接单响应", AssessmentDtos.Item.PROCESS);
    public static final Item PROCESS_REDEEM_RATE =
            new Item(AssessmentDtos.Item.PROCESS_REDEEM_RATE, "核销率", AssessmentDtos.Item.PROCESS);
    public static final Item PROCESS_REPORT_RATE =
            new Item(AssessmentDtos.Item.PROCESS_REPORT_RATE, "报工完整率", AssessmentDtos.Item.PROCESS);
    public static final Item PROCESS_REVIEW =
            new Item(AssessmentDtos.Item.PROCESS_REVIEW, "评价分", AssessmentDtos.Item.PROCESS);
    public static final Item PROCESS_CANCEL_RATE =
            new Item(AssessmentDtos.Item.PROCESS_CANCEL_RATE, "服务者取消率", AssessmentDtos.Item.PROCESS);

    /** 三项主项（按权重计算的层级）。 */
    public static final List<Item> MAIN = List.of(INVITE, COUPON, PROCESS);

    /** 过程分的子项。**子项之间等权**（ADR-0039 第二节：评价分等权进过程分）。 */
    public static final List<Item> PROCESS_CHILDREN =
            List.of(PROCESS_RESPONSE, PROCESS_REDEEM_RATE, PROCESS_REPORT_RATE, PROCESS_REVIEW,
                    PROCESS_CANCEL_RATE);

    private static final Map<String, Item> BY_CODE = Map.ofEntries(
            Map.entry(INVITE.code(), INVITE),
            Map.entry(COUPON.code(), COUPON),
            Map.entry(PROCESS.code(), PROCESS),
            Map.entry(PROCESS_RESPONSE.code(), PROCESS_RESPONSE),
            Map.entry(PROCESS_REDEEM_RATE.code(), PROCESS_REDEEM_RATE),
            Map.entry(PROCESS_REPORT_RATE.code(), PROCESS_REPORT_RATE),
            Map.entry(PROCESS_REVIEW.code(), PROCESS_REVIEW),
            Map.entry(PROCESS_CANCEL_RATE.code(), PROCESS_CANCEL_RATE));

    private AssessmentItems() {
    }

    /** 按编码取项目；编码不认识时返回 {@code null}（调用方决定是 40001 还是跳过）。 */
    public static Item byCode(String code) {
        return code == null ? null : BY_CODE.get(code);
    }

    /** 这一项能不能被超级管理员覆盖：只有三项主项可以。 */
    public static boolean overridable(String code) {
        Item item = byCode(code);
        return item != null && item.isMain();
    }
}
