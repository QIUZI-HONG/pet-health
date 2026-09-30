package com.pethealth.account.service;

import com.pethealth.account.auth.AccountStatus;
import com.pethealth.account.auth.JwtService;
import com.pethealth.account.auth.LoginThrottle;
import com.pethealth.account.auth.RefreshTokenStore;
import com.pethealth.account.domain.AuditLog;
import com.pethealth.account.domain.User;
import com.pethealth.account.mapper.UserMapper;
import com.pethealth.api.account.UserQueryApi;
import com.pethealth.api.app.InviteAttributionRequest;
import com.pethealth.api.app.InviteAttributionView;
import com.pethealth.api.app.LoginRequest;
import com.pethealth.api.app.LogoutRequest;
import com.pethealth.api.app.RefreshRequest;
import com.pethealth.api.app.RegisterRequest;
import com.pethealth.api.app.TokenPair;
import com.pethealth.api.app.UpdateProfileRequest;
import com.pethealth.api.app.UserProfile;
import com.pethealth.common.crypto.FieldCipher;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.error.ErrorCode;
import com.pethealth.common.security.LoginDomain;
import com.pethealth.common.util.Masking;
import com.pethealth.common.web.ClientIp;
import com.pethealth.privilege.api.InviteAttributionApi;
import com.pethealth.record.api.PetQueryApi;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 账号与会话（切片 #94）。
 *
 * <p>几个刻意的选择：
 *
 * <ul>
 *   <li><b>登录失败不说具体原因</b>：手机号不存在与密码不对都返回同一句「手机号或密码不正确」，
 *       否则接口就成了「这个号注册过没有」的探测工具。
 *   <li><b>限流按手机号</b>：见 {@link LoginThrottle} 的说明。
 *   <li><b>手机号明文只在内存里</b>：入库加密、出接口脱敏（ADR-0013）。
 * </ul>
 *
 * <p>{@code @Primary} 是为了 {@link UserQueryApi}：集成测试里有一个等价的桩
 * （{@code OrderTestSupport.StubCrossModuleApis}），取用方用 {@code ObjectProvider} 就地取实现
 * ——两个候选且无主时会抛 {@code NoUniqueBeanDefinitionException}（与
 * {@code ProviderAccessAdapter} 同一处取舍）。
 */
@Service
@Primary
public class AccountService implements UserQueryApi {

    private static final String DEFAULT_NICKNAME = "宠物主人";
    private static final String LOGIN_FAILED_MESSAGE = "手机号或密码不正确";

    // ---- 注册归因给用户看的那四句话（契约 InviteAttributionView.notice；见 noticeOf）
    private static final String ATTRIBUTED_NOTICE = "邀请关系已记录，待生效";
    private static final String ALREADY_ATTRIBUTED_NOTICE = "该账号已绑定邀请关系";
    private static final String CODE_NOT_FOUND_NOTICE = "邀请码不存在或已失效";
    /** 被反作弊拦下时的那句：**不暴露命中了哪条判据**（ADR-0046 第三节）。 */
    private static final String REJECTED_NOTICE = "邀请码不可用";

    private final UserMapper userMapper;
    private final FieldCipher fieldCipher;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenStore refreshTokens;
    private final LoginThrottle loginThrottle;
    private final PetQueryApi petQueryApi;
    private final AccountStatus accountStatus;
    private final AuditRecorder audit;
    private final InviteAttributionApi inviteApi;
    private final BaselineRights baselineRights;

    public AccountService(UserMapper userMapper,
                          FieldCipher fieldCipher,
                          PasswordEncoder passwordEncoder,
                          JwtService jwtService,
                          RefreshTokenStore refreshTokens,
                          LoginThrottle loginThrottle,
                          PetQueryApi petQueryApi,
                          AccountStatus accountStatus,
                          AuditRecorder audit,
                          InviteAttributionApi inviteApi,
                          BaselineRights baselineRights) {
        this.userMapper = userMapper;
        this.fieldCipher = fieldCipher;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokens = refreshTokens;
        this.loginThrottle = loginThrottle;
        this.petQueryApi = petQueryApi;
        this.accountStatus = accountStatus;
        this.audit = audit;
        this.inviteApi = inviteApi;
        this.baselineRights = baselineRights;
    }

    @Transactional
    public TokenPair register(RegisterRequest request) {
        String phone = request.phone().trim();
        String phoneHash = fieldCipher.lookupHash(phone);

        if (findByPhoneHash(phoneHash) != null) {
            throw BusinessException.conflict("该手机号已注册");
        }

        User user = new User();
        user.setPhoneEnc(fieldCipher.encrypt(phone));
        user.setPhoneHash(phoneHash);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setNickname(request.nickname() == null || request.nickname().isBlank()
                ? DEFAULT_NICKNAME
                : request.nickname().trim());
        user.setGender(0);
        user.setStatus(User.STATUS_ACTIVE);
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 同一个手机号并发注册时唯一索引会拦下第二个，转成同一个业务码
            throw BusinessException.conflict("该手机号已注册");
        }
        // 审计放在插入之后、返回之前：拿得到自增 id 才能把它当 target_id（ADR-0028）
        audit.recordOutcome(AuditLog.ACTION_REGISTER, user.getId(), user.getId(), phoneHash, null);
        // 注册即得的基线权益（`community.post`，见 BaselineRights）：交付文档 2.2 的权限矩阵把
        // 「发布内容 / 评论」给的是**注册用户**，而社区那道门是权益码。这里与账号插入同一个事务——
        // 授予失败就整笔回滚，不留「注册成功但发不了内容」的账号
        baselineRights.grantTo(user.getId());
        return issueTokens(user, LoginDomain.APP);
    }

    /**
     * 注册那一刻的邀请归因——接线点（ADR-0039 第一节 / ADR-0046 第一节的「需要协调」）。
     *
     * <p><b>为什么必须在这里接</b>：归因的时点被钉死在「注册那一刻」，而注册发生在本模块。
     * 契约（{@code contract/app.yaml}）把这一步定成注册成功后的**一次独立调用**
     * （{@link com.pethealth.api.app.RegisterRequest} 不带邀请码，它的形状已经实现并被测试钉住，
     * 不为了归因改它），所以本模块是唯一能把 {@link InviteAttributionApi#attribute} 发出去的地方：
     * 再往后就没有入口了。邀请关系的表在 ph-privilege，本模块只经它的 api 接口调它（ADR-0006）。
     *
     * <p>三条不许改的口径：
     *
     * <ul>
     *   <li><b>归因只有这一次机会</b>：接口层没有补填出口（补填是刷券的入口，ADR-0046 第一节）；
     *   <li><b>被反作弊拦下时注册仍然成功</b>：这里照常返回结果、不抛异常——拦下的是邀请关系，
     *       不是账号（{@code attributed=false} 是明确的业务结果）；
     *   <li><b>重复调用是幂等的 no-op</b>：{@code invitee_user_id} 唯一，第二次返回
     *       {@code ALREADY_ATTRIBUTED}，不改写第一次建立的关系。
     * </ul>
     *
     * <p>方法本身**不开事务**：归因那一段的边界在 ph-privilege 一侧
     * （{@code InviteService.attribute} 是 {@code @Transactional}），本方法只是把服务端才拿得到
     * 的三个事实（来源 IP、号段、注册时刻）补齐后转交。
     */
    public InviteAttributionView attributeInvite(long userId, InviteAttributionRequest request) {
        User user = requireUser(userId);
        InviteAttributionApi.Attribution result = inviteApi.attribute(new InviteAttributionApi.AttributionCommand(
                request.inviteCode(),
                userId,
                request.channel(),
                request.deviceId(),
                // 反作弊的两个判据由服务端自己取：来源 IP 读连接（**不读 X-Forwarded-For**，
                // 客户端声明的地址不是证据，ADR-0028）、号段读本账号自己的手机号（只需前 7 位）
                ClientIp.current(),
                phoneSegmentOf(user),
                // 观察窗（24 小时）从**注册时刻**起算，而不是这次请求的时刻：两者之间还有一次往返，
                // user.created_at 才是「注册那一刻」，不能由客户端说了算（ADR-0046 第二节）
                user.getCreatedAt()));
        return new InviteAttributionView(
                result.attributed(),
                // 给用户看的那句话走 data 里的 notice（信封的 message 在成功响应里到不了前端，
                // 见 contract/common.yaml）：文案只有这一处实现，前端直接展示它
                noticeOf(result.attributed(), result.reason()),
                result.reason(),
                // 只回显本次提交的码，不代表关系已建立（契约就是这么写的）
                request.inviteCode(),
                // 刚归因完一定是「待生效」：有效与否要等观察窗结束由结算批算给结论
                result.attributed() ? InviteAttributionApi.STATUS_ATTRIBUTED_PENDING : null);
    }

    /**
     * 归因结果 → 给用户看的那句话（契约里的 {@code notice}）。
     *
     * <p><b>反作弊的三个判据共用一个笼统的说法</b>（{@code SELF_INVITE} / {@code SAME_DEVICE} /
     * {@code SAME_IP_SEGMENT}）：把判据说清楚等于教人怎么绕过（ADR-0046 第三节）。
     * 只有「已经归因过」和「码不存在或已停用」这两个**与反作弊无关**的原因说实话——
     * 它们是用户能自己解释的事情（换了个码、或者上次已经绑过了）。
     *
     * <p>这是**文案的唯一定义处**：控制器把它同时放进信封的 {@code message}（日志与排查要用），
     * 前端读的是 {@code data.notice}。两处同一个来源，改文案不会漏改一边。
     */
    static String noticeOf(boolean attributed, String reason) {
        if (attributed) {
            // 观察窗（24 小时）内有没有行为由结算批算判（ADR-0046 第二节），所以此刻是「待生效」
            return ATTRIBUTED_NOTICE;
        }
        if (reason == null) {
            return REJECTED_NOTICE;
        }
        return switch (reason) {
            case "ALREADY_ATTRIBUTED" -> ALREADY_ATTRIBUTED_NOTICE;
            case "CODE_NOT_FOUND" -> CODE_NOT_FOUND_NOTICE;
            default -> REJECTED_NOTICE;
        };
    }

    /**
     * 手机号**前 7 位**（{@code SAME_IP_SEGMENT} 判据只需要这一段）。
     *
     * <p>完整手机号属身份信息（ADR-0013），不出这个方法——归因命令里也不带它。
     */
    private String phoneSegmentOf(User user) {
        String phone = fieldCipher.decrypt(user.getPhoneEnc());
        return phone != null && phone.length() >= 7 ? phone.substring(0, 7) : null;
    }

    @Transactional(readOnly = true)
    public TokenPair login(LoginRequest request) {
        return issueTokens(authenticate(request), LoginDomain.APP);
    }

    /**
     * 手机号 + 口令校验（含限流与审计），**不带签发**。
     *
     * <p>抽出来的理由：服务者后台与运营后台用的是**同一批账号**（ADR-0035 决定 4：
     * 「绑定现有账号 id」，不另建一套后台账号体系），所以那两个端的登录与 C 端共用这一份
     * 口令校验——重复实现一份等于把「登录失败不说具体原因」「限流按手机号」
     * 「失败也要留痕」这三条口径各写一遍。签发交给调用方，因为三个域的 `domain` 不同。
     *
     * @return 校验通过的账号（此时它的状态一定是启用中的）
     */
    User authenticate(LoginRequest request) {
        String phoneHash = fieldCipher.lookupHash(request.phone().trim());
        loginThrottle.checkAllowed(phoneHash);

        User user = findByPhoneHash(phoneHash);
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            loginThrottle.recordFailure(phoneHash);
            // 失败也要留痕，而且必须留得下来：这一行由 AuditRecorder 的独立事务保证，
            // 不跟着下面这个注定回滚的业务事务一起消失（ADR-0028）
            audit.recordAttempt(AuditLog.ACTION_LOGIN_FAILED, null, null, phoneHash, "手机号不存在或口令不正确");
            throw new BusinessException(ErrorCode.UNAUTHORIZED, LOGIN_FAILED_MESSAGE);
        }
        if (!user.isActive()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已被禁用，请联系客服");
        }
        loginThrottle.clear(phoneHash);
        audit.recordOutcome(AuditLog.ACTION_LOGIN_SUCCESS, user.getId(), user.getId(), phoneHash, null);
        return user;
    }

    /**
     * 换发令牌：消费掉旧 Refresh（一次性），签发新的一对。
     *
     * <p>用户在这期间被禁用或注销时不给换——否则 Refresh 就成了绕过封禁的后门。
     */
    @Transactional(readOnly = true)
    public TokenPair refresh(RefreshRequest request) {
        return refresh(LoginDomain.APP, request);
    }

    /**
     * 按域换发令牌（C 端 / 服务者后台 / 运营后台共用一份实现）。
     *
     * <p>域由**调用方**给，不从这里推断：换发路径（{@code /api/v1/{provider,admin}/auth/refresh}）
     * 已经决定了是哪个域，从路径推等于把「请求从哪来」当成「令牌该是什么域」——
     * 一个域的 Refresh 不该能换出另一个域的 Access（ADR-0012 的跨端不通用）。
     *
     * <p>还有一处防呆：**旧 Refresh 的域必须与新域一致**。否则拿 C 端的 Refresh 去打
     * {@code /provider/auth/refresh} 就能换到一枚 provider 令牌——那正是「三个登录域互不通用」
     * 要堵的洞。
     */
    @Transactional(readOnly = true)
    TokenPair refresh(LoginDomain domain, RefreshRequest request) {
        RefreshTokenStore.Session session = refreshTokens.consume(request.refreshToken());
        if (session.domain() != domain) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "当前登录身份不能访问该端的接口");
        }
        User user = userMapper.selectById(session.userId());
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "账号不存在");
        }
        if (!user.isActive()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已被禁用，请联系客服");
        }
        // 换发的新令牌留在**同一个会话族**里：这样一旦检测到重放，整族一起吊销（ADR-0012）
        JwtService.Issued access = jwtService.issue(user.getId(), domain);
        String refreshToken = refreshTokens.issueInFamily(session.familyId(), user.getId(), domain);
        return new TokenPair(access.token(), refreshToken,
                Math.toIntExact(access.expiresInSeconds()), toProfile(user));
    }

    public void logout(LogoutRequest request) {
        refreshTokens.revoke(request.refreshToken());
    }

    @Transactional(readOnly = true)
    public UserProfile profile(long userId) {
        return toProfile(requireUser(userId));
    }

    @Transactional
    public UserProfile updateProfile(long userId, UpdateProfileRequest request) {
        User user = requireUser(userId);

        if (request.nickname() != null) {
            if (request.nickname().isBlank()) {
                throw BusinessException.paramInvalid("昵称不能为空");
            }
            user.setNickname(request.nickname().trim());
        }
        if (request.avatar() != null) {
            // 传空串表示清掉头像
            String avatar = request.avatar().trim();
            user.setAvatar(avatar.isEmpty() ? null : avatar);
        }
        if (request.gender() != null) {
            user.setGender(request.gender());
        }

        userMapper.updateById(user);
        return toProfile(user);
    }

    @Transactional
    public UserProfile activatePet(long userId, long petId) {
        User user = requireUser(userId);
        // 越权与不存在都按 40400 处理：不告诉调用方「这只宠物存在但不是你的」
        if (!petQueryApi.existsOwnedBy(userId, petId)) {
            throw BusinessException.notFound("宠物不存在");
        }
        user.setActivePetId(petId);
        userMapper.updateById(user);
        return toProfile(user);
    }

    /** 按域签发一对令牌。C 端走 {@code APP}，两个后台走各自的域（ADR-0012：跨端不通用）。 */
    TokenPair issueTokens(User user, LoginDomain domain) {
        JwtService.Issued access = jwtService.issue(user.getId(), domain);
        String refreshToken = refreshTokens.issue(user.getId(), domain);
        return new TokenPair(access.token(), refreshToken,
                Math.toIntExact(access.expiresInSeconds()), toProfile(user));
    }

    UserProfile toProfile(User user) {
        String phone = fieldCipher.decrypt(user.getPhoneEnc());
        return new UserProfile(
                user.getId(),
                Masking.phone(phone),
                user.getNickname(),
                user.getAvatar(),
                user.getGender(),
                resolveActivePetId(user),
                user.getCreatedAt());
    }

    /**
     * 批量取用户资料（{@link UserQueryApi}）：服务者侧的订单列表要显示「预约人是谁」。
     *
     * <p>出口与 {@link #toProfile} **是同一份映射**——手机号解密后立即脱敏
     * （{@code 138****8888}，ADR-0013）、当前宠物按同一条规则校验失效。映射只此一处，
     * 免得「C 端看到的自己」与「服务者看到的客户」在某个字段上悄悄分叉。
     *
     * <p>批量版的代价要认：{@code resolveActivePetId} 会按人查一次宠物归属，所以一页 20 条订单
     * 最多多 20 次跨模块调用。这是**刻意保留**的——把 {@code UserProfile.activePetId} 在批量路径上
     * 改成原样透出（或恒 null），会让同一个形状在两个入口有两种语义，那种不一致比几次查询糟得多。
     * 真到了需要优化的量级，正确的做法是给 `PetQueryApi` 加一个批量归属查询，而不是在这里绕开校验。
     */
    @Override
    @Transactional(readOnly = true)
    public Map<Long, UserProfile> profiles(Collection<Long> userIds) {
        Map<Long, UserProfile> profiles = new HashMap<>();
        if (userIds == null || userIds.isEmpty()) {
            // 空集合不查库：IN () 会被 MySQL 拒绝，也让调用方的空列表变成一次无意义的往返
            return profiles;
        }
        for (User user : userMapper.selectBatchIds(userIds)) {
            profiles.put(user.getId(), toProfile(user));
        }
        return profiles;
    }

    /**
     * 按**完整手机号**等值查用户 id（{@link UserQueryApi}）：核销的三个定位入口之一（ADR-0038 第二节）。
     *
     * <p>手机号在这里**是入参、不是返回值**：能定位到订单，但看不到号码本身。加密与 HMAC 的密钥
     * 只归本模块（ADR-0013），所以比对只能在这里做——查的是 {@code phone_hash} 的等值索引，
     * 不是密文（密文每次加密都不同，比不了）。
     *
     * <p>注销过的账号已经匿名化（{@code phone_hash} 换成了随机值，见
     * {@link AccountLifecycleService}），所以查不到——这正是期望的行为。
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<Long> findUserIdByPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return Optional.empty();
        }
        User user = findByPhoneHash(fieldCipher.lookupHash(phone.trim()));
        return user == null ? Optional.empty() : Optional.of(user.getId());
    }

    /**
     * 号段（手机号前 7 位）给邀请反作弊当判据（{@link UserQueryApi#phoneSegmentOf}）。
     *
     * <p>与 {@link #attributeInvite} 里那一处共用同一个私有实现（{@code phoneSegmentOf(User)}）：
     * 同一件事在「被邀请人注册时」与「邀请人建码时」两侧必须得到同一个值，各写一份迟早分叉。
     * 号码解不开（字段加密的密钥换过、历史脏数据）时返回空——调用方按「没有信号」处理，
     * **不要**兜底成某个看起来正常的号段：那会把一条判据变成偶然成立的东西。
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<String> phoneSegmentOf(long userId) {
        User user = userMapper.selectById(userId);
        return user == null ? Optional.empty() : Optional.ofNullable(phoneSegmentOf(user));
    }

    /**
     * 当前宠物：库里存的是「最近一次显式切换到的」，可能已经被删掉。
     * 读的时候校验一次，失效就报 null 让前端回退到列表第一只——**不回写**，免得读接口产生写操作。
     */
    private Long resolveActivePetId(User user) {
        Long activePetId = user.getActivePetId();
        if (activePetId == null) {
            return null;
        }
        return petQueryApi.existsOwnedBy(user.getId(), activePetId) ? activePetId : null;
    }

    private User requireUser(long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "账号不存在");
        }
        if (!accountStatus.isActive(userId)) {
            // 账号状态的**权威判定在鉴权层**（JwtAuthenticationFilter + AccountStatus），
            // 这一句是本模块内的兜底（例如将来有定时任务直接调 service，不经过过滤器）。
            // 复用同一个 AccountStatus，而不是在这里再写一遍「status == DISABLED」——
            // 否则注销判定会有两份实现，改一处漏一处
            throw BusinessException.unauthorized("账号已注销或禁用");
        }
        return user;
    }

    private User findByPhoneHash(String phoneHash) {
        return userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getPhoneHash, phoneHash));
    }

}
