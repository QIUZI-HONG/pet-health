package com.pethealth.privilege.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pethealth.privilege.domain.InviteLadderTier;
import com.pethealth.privilege.mapper.InviteLadderTierMapper;

import java.util.List;

/**
 * 邀请阶梯的**读法**：把「哪些档位算数」收成一处。
 *
 * <p>为什么需要这个类：阶梯档位原先有两处读——发奖侧（{@code InviteService.grantLadderRewards}）
 * 与展示侧（C 端「我的邀请」进度）。发奖侧只取**启用中**的档位，展示侧取全部档位；
 * 于是运营把某一档停用之后，C 端仍然会把它当作「再邀 3 人得券」的下一个目标显示出来，
 * 而那一档永远不会发。这不是显示问题，是**平台对用户作出的承诺与兑现不一致**。
 *
 * <p>停用档位的口径定为**对用户完全不可见**（而不是「显示但标注已下线」）：停用是一个
 * 运营动作，它要表达的正是「这一档现在不作数」；显示一个已经不作数的承诺只会让用户来问
 * 「为什么我邀够 3 个人没有券」。两处都从这里取，就不会再分叉。
 *
 * <p>为什么不把这段查询写进 {@link InviteLadderTier}：领域对象不该认识 MyBatis-Plus 的
 * 查询包装器（那是持久层的形状）。口径写在注释里、查询收在这里，是这一层能做的最干净的分工。
 */
final class InviteLadder {

    private InviteLadder() {
    }

    /**
     * 启用中的档位，按门槛升序。
     *
     * <p>两处调用方对「升序」的需要不同（发奖侧只遍历已达成的，展示侧要顺着找下一个目标），
     * 但顺序本身是同一个事实（阶梯从小到大），所以放在这一处，不留给调用方各排一次。
     */
    static List<InviteLadderTier> enabledTiers(InviteLadderTierMapper tierMapper) {
        return tierMapper.selectList(Wrappers.<InviteLadderTier>lambdaQuery()
                .eq(InviteLadderTier::getStatus, InviteLadderTier.STATUS_ENABLED)
                .orderByAsc(InviteLadderTier::getThreshold));
    }
}
