package com.pethealth.content.service;

import com.pethealth.common.error.BusinessException;
import com.pethealth.common.error.ErrorCode;
import com.pethealth.privilege.api.RightsApi;
import org.springframework.stereotype.Component;

/**
 * 社区的发文准入（ADR-0041 第一节：**发文权限走权益码 `community.post`**）。
 *
 * <p>三条口径：
 *
 * <ul>
 *   <li><b>判定只有一处</b>：走 ph-privilege 的 {@link RightsApi}（ADR-0038 第三节的
 *       {@code rights(userId)}），社区**不 join 它的表、也不自己拼规则**（ADR-0006 / ADR-0045）。
 *       自己拼一套的结果是「用户明明有权益却被拦住」，而这类错误用户无法自救；
 *   <li><b>返回 40300，不是 40100</b>：这是「登录了，但没这项能力」——前端该引导去做任务 / 邀请；
 *       40100 是「没登录」，前端该引导去登录。两个码混用会让引导文案指向错误的方向；
 *   <li><b>覆盖三个动作</b>：发卡片、提问、回答（都是「发文」）。**点赞不在此列**——
 *       它是互动不是发文，拿发文权限去卡点赞等于把最轻的互动也变成门槛。
 * </ul>
 *
 * <p>权益码是**代码常量**（ADR-0010 的第三层）：它在代码里被引用、语义由代码决定，
 * 库里那张码表只负责「有没有、从哪来、到什么时候」。
 *
 * <p><b>现状提醒</b>：{@code community.post} 现在**没有任何自动授予路径**——
 * 邀请阶梯档位默认没配 {@code rights_code}、打卡阶梯不发权益，所以这个闸门目前是**关着**的。
 * 需要运营在 {@code POST /admin/rights/grants} 手动授予，或把邀请阶梯的某一档配上这个码
 * （ADR-0051 的待澄清第 1 条：内容冷启动）。
 */
@Component
public class PostingAccess {

    /** 社区发帖权益码（ADR-0038 第三节的初始四码之一）。 */
    public static final String CODE_COMMUNITY_POST = "community.post";

    private final RightsApi rights;

    public PostingAccess(RightsApi rights) {
        this.rights = rights;
    }

    /**
     * 要求当前用户有发帖权益，没有就抛 40300。
     *
     * @throws BusinessException 40300 无权益；权益引擎本身不抛业务异常（判定是只读查询）
     */
    public void requirePostingRight(long userId) {
        if (!rights.isEffective(userId, CODE_COMMUNITY_POST)) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "还没有社区发帖权益，暂时不能发布内容");
        }
    }
}
