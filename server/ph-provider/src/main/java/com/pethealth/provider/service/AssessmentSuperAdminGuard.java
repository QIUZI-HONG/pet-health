package com.pethealth.provider.service;

import com.pethealth.common.error.BusinessException;
import com.pethealth.common.error.ErrorCode;
import com.pethealth.common.security.CurrentUser;
import com.pethealth.common.security.LoginDomain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 考核的**超级管理员门禁**：规则配置与单项分覆盖只归超管（ADR-0037 第一节的权限矩阵）。
 *
 * <p><b>为什么需要它</b>：本仓的运营后台**还没有角色体系**——admin 域的 Token 里只有「这是运营后台
 * 身份」，运营与超管不可区分（ADR-0035 的「需要协调」）。而 ADR-0037 把「考核规则配置」与
 * 「清退服务者」「删除目录项」一起明确划给了超级管理员，其中「改规则」与「改分」是**能直接改变
 * 服务者收益与曝光**的动作。对这一条最省事的做法是像其它 admin 接口一样「一视同仁」，
 * 但那等于把一条 ADR 明文规定的边界丢掉——所以这里多一道门禁：
 *
 * <pre>
 *   登录域必须是 admin（ADR-0012）  +  账号在「超管名单」里
 * </pre>
 *
 * <p><b>名单来自环境变量</b>（{@code ASSESSMENT_SUPER_ADMIN_USER_IDS}，逗号分隔的账号 id）：
 * 它是**技术参数**而不是业务可调项（ADR-0010 的分层）——让「谁能改分」变成运营后台里可自助修改的
 * 配置，等于把门禁的钥匙挂在门上。
 *
 * <p><b>名单未配置时 fail-closed：谁都不能改</b>（40300，消息里说明要配哪个环境变量）。
 * 不选 fail-open（默认人人可用）的理由：一个「忘了配」就会让这两件事对所有运营敞开，
 * 而它没有任何可见的征兆；fail-closed 至少有明确报错，运维当场就知道要做什么。
 * 代价是**部署后必须先配名单**，这条写在 ADR-0052 与交付报告的「需要协调」里。
 *
 * <p>角色体系落地后，这个类的判定换成真实 RBAC（届时名单这个临时机制删掉）——
 * 调用方（{@code requireSuperAdmin()}）不用改，这也是把它单独收成一个类的原因。
 */
@Component
public class AssessmentSuperAdminGuard {

    private static final Logger log = LoggerFactory.getLogger(AssessmentSuperAdminGuard.class);

    /** 环境变量名，写进报错消息里，让运维一眼知道要配什么。 */
    static final String ENV_NAME = "app.assessment.super-admin-user-ids（环境变量 ASSESSMENT_SUPER_ADMIN_USER_IDS）";

    private final Set<Long> superAdminIds;

    public AssessmentSuperAdminGuard(
            @Value("${app.assessment.super-admin-user-ids:}") String superAdminUserIds) {
        this.superAdminIds = parse(superAdminUserIds);
        if (superAdminIds.isEmpty()) {
            log.warn("考核的超管名单未配置（{}）：**任何 admin 域身份都不能**修改考核规则或覆盖单项分"
                    + "（fail-closed）。要启用这两件事，请把超管的账号 id 配进去。", ENV_NAME);
        }
    }

    /**
     * 当前身份必须是运营后台的超级管理员。
     *
     * @return 当前账号 id（落进覆盖留痕的 operator_id）
     * @throws BusinessException 40100 不是 admin 域；40300 不在超管名单里（或名单未配置）
     */
    public long requireSuperAdmin() {
        long userId = CurrentUser.requireDomain(LoginDomain.ADMIN);
        if (superAdminIds.isEmpty()) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "考核规则配置与单项分覆盖只对超级管理员开放，而当前部署还没有配置超管名单（"
                            + ENV_NAME + "）");
        }
        if (!superAdminIds.contains(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "考核规则配置与单项分覆盖只对超级管理员开放（当前账号不在超管名单里）");
        }
        return userId;
    }

    /** 解析名单：空串 / null → 空集合；忽略空白项与非法项（非法项只忽略它自己，不让整串失效）。 */
    private static Set<Long> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> {
                    try {
                        return Long.valueOf(s);
                    } catch (NumberFormatException e) {
                        log.warn("超管名单里的这一项不是账号 id，已忽略：{}", s);
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
