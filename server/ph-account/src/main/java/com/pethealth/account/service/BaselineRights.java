package com.pethealth.account.service;

import com.pethealth.privilege.api.RightsApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * **注册即得的基线权益**：交付文档 2.2 的权限矩阵写的是「发布内容 / 评论：注册用户 ✓」，
 * 而社区发文的门禁是权益码 {@code community.post}（ADR-0041 第一节）。两者之间原本没有任何路径相连
 * ——邀请阶梯没配 {@code rights_code}、打卡不发权益、注册也不发，于是那道门**关着且没人能开**：
 * 新用户注册完就撞 40300，而他能做的动作里没有任何一步会让自己有权益。
 *
 * <p>本类就是那条路径：注册成功时授予一条**平台默认**的 {@code community.post}。
 *
 * <p><b>为什么落在注册流程（ph-account）而不是数据层的种子 / 迁移</b>：
 *
 * <ol>
 *   <li>「每人一条」的授予记录**只能在他存在之后写**——迁移里没有「将来注册的人」这一行，
 *       所以种子最多只能回填存量，新用户必须有代码路径。既然新用户必须有，就把这条路径写在这里，
 *       存量用户由 V33 回填（两半都要，只有一半就会留下「有人能发、有人不能」的错位）；
 *   <li>注册发生在本模块，且本模块**已经在做注册那一刻的旁路调用**（邀请归因走
 *       {@code InviteAttributionApi}，见 {@link AccountService#attributeInvite}）——
 *       同一类接线点放在同一处，下一个人不用猜「注册时到底会碰哪些模块」；
 *   <li>跨模块只走接口（ADR-0006）：这里调 ph-privilege 的 {@link RightsApi#grant}，
 *       **不碰它的表**（那两张表的归属与判定口径是 ADR-0045 的事）。
 * </ol>
 *
 * <p><b>与注册事务同一个事务（刻意不 catch）</b>：账号存在 ⇔ 有基线权益，是一条不变量。
 * 授予失败（例如 {@code rights_code} 码表里那一行被人删了）时注册整笔回滚、注册者拿到
 * 40400——听起来刺耳，但那正是**部署坏了**这件事该有的样子：把异常吞掉就会造出
 * 「注册成功但门依旧关着」的账号，而那正是本切片要修掉的状态（打卡 → 发分那条旁路不同：
 * 积分是另一套账，丢了能补，所以它在 AFTER_COMMIT 里降级，见 {@code CheckInPointsListener}）。
 *
 * <p><b>来源用哪个 {@code source}（ADR-0045 第二节的口径）</b>：ADR-0038 / ADR-0045 的枚举只有
 * 订阅(1) / 邀请(2) / 打卡(3) / 运营补偿(4)，**没有「注册 / 平台默认」这一项**，
 * 而本切片不自己加枚举值（加一个值要同时改码表注释、admin 契约的取值说明与校验上限，
 * 那是契约写入者的动作）。在现有四项里选 4（运营补偿），理由是**优先级**
 * ：判定取「生效记录里 source 最小的一条」，基线必须是**最弱**的那一条，
 * 这样任何真正挣来的来源（订阅 / 邀请 / 打卡）都会盖在它上面并成为对用户解释「你从哪来的」的答案；
 * 若用 2（邀请）会给出一条**永久**的邀请来源记录，用户后来真通过邀请拿到东西时，
 * 判定仍显示这条基线，真正的来源信息就丢了。
 *
 * <p>代价说清楚：C 端「我的权益」页会把这条显示成「运营补偿」。语义上不精确，但不骗人；
 * 要语义干净就得加第五个来源（建议值 {@code 5 = 平台默认}，改动面见上）——留给项目所有者裁决。
 */
@Component
public class BaselineRights {

    private static final Logger log = LoggerFactory.getLogger(BaselineRights.class);

    /**
     * 社区发帖权益码（ADR-0038 第三节的初始四码之一）。
     *
     * <p>与 ph-content 的 {@code PostingAccess.CODE_COMMUNITY_POST} 是**同一个字符串的两份常量**：
     * 码是这两个模块之间的契约面，谁都不该依赖对方的类（ADR-0006），
     * 所以它像其他代码常量一样各自写在自己的模块里（ADR-0010 的第三层）。
     */
    public static final String CODE_COMMUNITY_POST = "community.post";

    /**
     * 幂等键的一半：{@code (user_id, code, source, source_ref)} 唯一（ADR-0045 第三节）。
     * 用固定串而不是用户 id 拼出来的值：它表达的是「这条授予来自哪一次业务事件」——
     * 一个用户一辈子只注册一次，所以固定串就够，且重放（事件重投、批算重跑）不会多写一行。
     */
    private static final String SOURCE_REF = "register:baseline";

    /** 授予记录上的理由：ADR-0045 要求手动作业留理由，这条是系统授予，理由写清依据。 */
    private static final String REMARK = "注册即得（交付文档 2.2：发布内容 = 注册用户）";

    /** 永久：基线不该到期——到期意味着「老用户哪天突然不能发内容」，而那正是本切片要消掉的状态。 */
    private static final LocalDateTime NO_EXPIRY = null;

    private final RightsApi rights;

    public BaselineRights(RightsApi rights) {
        this.rights = rights;
    }

    /**
     * 给一个新注册的账号授予基线权益。
     *
     * <p>在注册事务里调用（见类注释：授予失败就整笔回滚，不留「注册成功但门关着」的账号）。
     * {@code grant} 本身幂等，所以重复调用、并发注册都不会多出一行。
     */
    public void grantTo(long userId) {
        rights.grant(new RightsApi.GrantCommand(
                userId,
                CODE_COMMUNITY_POST,
                RightsApi.SOURCE_COMPENSATION,
                SOURCE_REF,
                NO_EXPIRY,
                REMARK));
        log.debug("已授予注册基线权益：userId={} code={} source={}", userId, CODE_COMMUNITY_POST,
                RightsApi.SOURCE_COMPENSATION);
    }
}
