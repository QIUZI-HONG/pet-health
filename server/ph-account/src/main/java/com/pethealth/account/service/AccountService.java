package com.pethealth.account.service;

import com.pethealth.account.auth.JwtService;
import com.pethealth.account.auth.LoginThrottle;
import com.pethealth.account.auth.RefreshTokenStore;
import com.pethealth.account.domain.User;
import com.pethealth.account.mapper.UserMapper;
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
import com.pethealth.record.api.PetQueryApi;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 */
@Service
public class AccountService {

    private static final String DEFAULT_NICKNAME = "宠物主人";
    private static final String LOGIN_FAILED_MESSAGE = "手机号或密码不正确";

    private final UserMapper userMapper;
    private final FieldCipher fieldCipher;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenStore refreshTokens;
    private final LoginThrottle loginThrottle;
    private final PetQueryApi petQueryApi;

    public AccountService(UserMapper userMapper,
                          FieldCipher fieldCipher,
                          PasswordEncoder passwordEncoder,
                          JwtService jwtService,
                          RefreshTokenStore refreshTokens,
                          LoginThrottle loginThrottle,
                          PetQueryApi petQueryApi) {
        this.userMapper = userMapper;
        this.fieldCipher = fieldCipher;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokens = refreshTokens;
        this.loginThrottle = loginThrottle;
        this.petQueryApi = petQueryApi;
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
        return issueTokens(user);
    }

    @Transactional(readOnly = true)
    public TokenPair login(LoginRequest request) {
        String phoneHash = fieldCipher.lookupHash(request.phone().trim());
        loginThrottle.checkAllowed(phoneHash);

        User user = findByPhoneHash(phoneHash);
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            loginThrottle.recordFailure(phoneHash);
            throw new BusinessException(ErrorCode.UNAUTHORIZED, LOGIN_FAILED_MESSAGE);
        }
        if (!user.isActive()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已被禁用，请联系客服");
        }
        loginThrottle.clear(phoneHash);
        return issueTokens(user);
    }

    /**
     * 换发令牌：消费掉旧 Refresh（一次性），签发新的一对。
     *
     * <p>用户在这期间被禁用或注销时不给换——否则 Refresh 就成了绕过封禁的后门。
     */
    @Transactional(readOnly = true)
    public TokenPair refresh(RefreshRequest request) {
        RefreshTokenStore.Session session = refreshTokens.consume(request.refreshToken());
        User user = userMapper.selectById(session.userId());
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "账号不存在");
        }
        if (!user.isActive()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已被禁用，请联系客服");
        }
        // 换发的新令牌留在**同一个会话族**里：这样一旦检测到重放，整族一起吊销（ADR-0012）
        JwtService.Issued access = jwtService.issue(user.getId(), LoginDomain.APP);
        String refreshToken = refreshTokens.issueInFamily(session.familyId(), user.getId(), LoginDomain.APP);
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
                throw BusinessException.paramInvalid("nickname 昵称不能为空");
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

    private TokenPair issueTokens(User user) {
        JwtService.Issued access = jwtService.issue(user.getId(), LoginDomain.APP);
        String refreshToken = refreshTokens.issue(user.getId(), LoginDomain.APP);
        return new TokenPair(access.token(), refreshToken,
                Math.toIntExact(access.expiresInSeconds()), toProfile(user));
    }

    private UserProfile toProfile(User user) {
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
        return user;
    }

    private User findByPhoneHash(String phoneHash) {
        return userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getPhoneHash, phoneHash));
    }

}
