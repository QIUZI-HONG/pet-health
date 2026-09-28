package com.pethealth.account.service;

import com.pethealth.account.domain.User;
import com.pethealth.account.mapper.UserMapper;
import com.pethealth.api.app.AccountExportView;
import com.pethealth.common.crypto.FieldCipher;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.time.AppTime;
import com.pethealth.record.api.ProfileExportApi;
import com.pethealth.reminder.api.MessageQueryApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 账号生命周期的两个动作：**导出**与**注销**（切片 #74）。
 *
 * <p>两者是一件事的两面——隐私政策说明我们怎么用数据，这两个接口是用户行使权利的入口，
 * 所以放在同一个服务里（改一个通常要改另一个）。
 *
 * <p>跨模块只走接口：宠物与档案来自 {@link ProfileExportApi}，消息来自 {@link MessageQueryApi}，
 * 本模块不碰它们的表（ADR-0006）。
 */
@Service
public class AccountLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(AccountLifecycleService.class);

    private static final String NOTICE =
            "这是你的账号数据副本。服务者报工等由他人写入的记录只保留在其档案原始内容里，未逐字段展开。";

    private final UserMapper userMapper;
    private final AccountService accountService;
    private final ProfileExportApi profileExportApi;
    private final MessageQueryApi messageQueryApi;
    private final FieldCipher fieldCipher;

    public AccountLifecycleService(UserMapper userMapper, AccountService accountService,
                                   ProfileExportApi profileExportApi,
                                   MessageQueryApi messageQueryApi, FieldCipher fieldCipher) {
        this.userMapper = userMapper;
        this.accountService = accountService;
        this.profileExportApi = profileExportApi;
        this.messageQueryApi = messageQueryApi;
        this.fieldCipher = fieldCipher;
    }

    /** 导出：账号资料 + 宠物档案 + 消息。 */
    public AccountExportView export(long userId) {
        User user = require(userId);
        List<AccountExportView.Pet> pets = profileExportApi.exportOf(userId).stream()
                .map(pet -> new AccountExportView.Pet(
                        pet.petId(), pet.name(), pet.species(), pet.breed(), pet.gender(),
                        pet.birthday(), pet.weight(), pet.sterilized(), pet.chronicDesc(),
                        pet.records().stream()
                                .map(r -> new AccountExportView.Record(r.recordDate(), r.category(),
                                        r.content(), r.source(), r.dueOn(), r.numericValue()))
                                .toList(),
                        pet.scores().stream()
                                .map(s -> new AccountExportView.Score(s.calcDate(), s.totalScore(),
                                        s.physiology(), s.behavior(), s.hygiene(), s.epidemic(),
                                        s.elderly()))
                                .toList()))
                .toList();
        List<AccountExportView.PetMessage> messages = messageQueryApi.exportOf(userId).stream()
                .map(m -> new AccountExportView.PetMessage(m.kind(), m.type(), m.title(), m.content(),
                        m.riskLevel(), m.remindAt(), m.read(), m.createdAt()))
                .toList();
        return new AccountExportView(accountService.toProfile(user), pets, messages,
                AppTime.now(), NOTICE);
    }

    /**
     * 注销。四步，顺序即语义（见 V9 迁移的注释）：
     * 状态置禁用 → 手机号匿名化（**让出唯一键，同一手机号可重新注册**）→ 昵称匿名化 → 宠物软删。
     *
     * <p>不物理删除：留痕（#121）与将来的账目要留；物理删除属数据清理任务。
     *
     * <p>登录与刷新令牌在这之后立刻失效（AccountService 里已有的「禁用即拒绝」那道闸）；
     * 已经签发的 Access Token 最长还能用 2 小时——这是 JWT 的固有代价，写在这里免得被当成漏判。
     */
    @Transactional
    public void deactivate(long userId) {
        User user = require(userId);
        if (user.getStatus() != null && user.getStatus() == User.STATUS_DISABLED) {
            // 幂等：重复注销不报错，也不重复匿名化（第二次已经匿名了）
            return;
        }
        user.setStatus(User.STATUS_DISABLED);
        user.setDeactivatedAt(AppTime.now());
        user.setNickname("已注销用户");
        // 匿名化手机号：密文换成不可还原的标记，HMAC 换成随机值——让出 uk_phone_hash
        user.setPhoneEnc(fieldCipher.encrypt("deactivated:" + UUID.randomUUID()));
        user.setPhoneHash(fieldCipher.lookupHash("deactivated:" + userId + ":" + UUID.randomUUID()));
        userMapper.updateById(user);

        int pets = profileExportApi.softDeleteAll(userId);
        log.info("账号已注销 user_id={} 软删宠物 {} 只", userId, pets);
    }

    private User require(long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw BusinessException.notFound();
        }
        return user;
    }
}
