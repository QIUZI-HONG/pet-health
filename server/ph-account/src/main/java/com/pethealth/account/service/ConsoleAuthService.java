package com.pethealth.account.service;

import com.pethealth.account.config.ConsoleProperties;
import com.pethealth.account.domain.User;
import com.pethealth.api.app.LoginRequest;
import com.pethealth.api.app.LogoutRequest;
import com.pethealth.api.app.RefreshRequest;
import com.pethealth.api.app.TokenPair;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.error.ErrorCode;
import com.pethealth.common.security.LoginDomain;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 两个后台（控制台）的登录与换发——**补的是 ADR-0035「需要协调」第 3 条那半截**。
 *
 * <p>在此之前，服务者后台的 11 个页面与运营后台的 9 个页面**都实现了、每页都真的调接口**，
 * 但两端没有登录入口、契约里也没有 auth 路径，生产代码只签发 APP 域令牌
 * （PROVIDER / ADMIN 域仅测试代码签发）。于是这 20 个页面在真实使用中都停在「尚未登录」闸门
 * ——2026-09-30 验收把这条列为两个后台的头号缺口。本类补的就是那道门。
 *
 * <p><b>账号是同一批，准入按域判</b>：口令校验复用 {@link AccountService#authenticate}
 * （与 C 端逐字同一份实现，含限流与失败留痕），差别只在**签发哪个域**以及**这个人准不准进**：
 *
 * <ul>
 *   <li>{@code provider} 域：**任何启用中的账号都能登**。这是流程要求的，不是放宽——
 *       BPM-4 的第一句是「商家申请入驻」（交付文档原文，文档用词），而申请要先有能提交的
 *       表单，也就是先能进控制台。
 *       若把「已有服务者绑定」当作登录前置，就永远没人能提交第一份申请（鸡生蛋）。
 *       登录进来以后**看得到什么**由各接口已有的绑定校验兜着：未绑定 / 审核中 / 已驳回
 *       读门店类接口 40400、写 40300（ADR-0035 的 Consequences 已经写明这条）。
 *   <li>{@code admin} 域：必须在名单里，名单为空则谁都进不去（fail-closed）。
 *       与 provider 域不同，运营后台没有任何「先登录才能申请」的流程需要照顾，
 *       而它能看到全平台数据——所以这里从严。
 * </ul>
 *
 * <p><b>准入失败回 40300 而不是 40100</b>：口令是对的，只是这个账号不属于该端。回 40100 会让
 * 用户以为密码错了、反复重试（而登录限流会因此把他锁住）。这一点与「登录失败不说具体原因」
 * （那是防手机号探测）不冲突——40300 只发生在**口令正确之后**，不泄露任何未认证信息。
 */
@Service
public class ConsoleAuthService {

    private static final Logger log = LoggerFactory.getLogger(ConsoleAuthService.class);

    private final AccountService accounts;
    private final ConsoleProperties properties;

    public ConsoleAuthService(AccountService accounts, ConsoleProperties properties) {
        this.accounts = accounts;
        this.properties = properties;
    }

    /**
     * 启动时把「运营后台现在谁都进不去」这件事说清楚。
     *
     * <p>为什么需要它：admin 名单是 **fail-closed**（空 = 都不允许），而「空」与「配错」在运行时
     * 长得一样——表现都是运营登录时收到一句 40300。启动日志里留一行，运维才知道要去配名单，
     * 而不是去查「为什么密码对了还进不去」。名单非空时不打日志（正常状态不该有噪音）。
     */
    @PostConstruct
    void warnWhenNobodyCanEnterAdminConsole() {
        if (properties.adminIds().isEmpty()) {
            log.warn("运营后台没有任何账号可登录：CONSOLE_ADMIN_USER_IDS 为空（fail-closed）。"
                    + "把运维账号的 id 填进去并重启，否则 /api/v1/admin/** 的页面全部进不去");
        }
    }

    @Transactional(readOnly = true)
    public TokenPair login(LoginDomain domain, LoginRequest request) {
        User user = accounts.authenticate(request);
        requireAdmitted(domain, user.getId());
        return accounts.issueTokens(user, domain);
    }

    /** 换发：域由路径决定，且**旧 Refresh 的域必须一致**（在 {@link AccountService#refresh} 里判）。 */
    @Transactional(readOnly = true)
    public TokenPair refresh(LoginDomain domain, RefreshRequest request) {
        return accounts.refresh(domain, request);
    }

    public void logout(LogoutRequest request) {
        accounts.logout(request);
    }

    private void requireAdmitted(LoginDomain domain, long userId) {
        if (domain == LoginDomain.PROVIDER) {
            // 服务者后台不设准入：申请入驻的第一步就是登进这个控制台（BPM-4）。
            // 进来以后能看什么，由各接口的绑定校验决定（见类注释）。
            return;
        }
        if (domain == LoginDomain.ADMIN) {
            if (!properties.adminIds().contains(userId)) {
                // 文案要能指导下一步：这条 40300 是**运营自己**会撞上的（名单没配、或他的号没写进去），
                // 而「该账号不是运营后台账号」听起来像是「你注册错端了」，会把人引到去重新注册。
                throw new BusinessException(ErrorCode.FORBIDDEN,
                        "该账号不在运营后台名单里（名单由 CONSOLE_ADMIN_USER_IDS 配置），请联系平台管理员开通");
            }
            return;
        }
        // APP 域不走这条路径（它有 /app/auth/login）。真走到这里说明调用方写错了域
        throw new IllegalArgumentException("控制台登录不支持这个登录域：" + domain);
    }
}
