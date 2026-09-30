package com.pethealth.boot.privilege;

import com.pethealth.api.provider.ProviderAccessApi;
import com.pethealth.boot.provider.ProviderApiTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 券池 / 权益 / 邀请 / 积分集成测试的公共基座（切片 #110–#113）。
 *
 * <p>清库与造数据的工具直接继承 {@code ProviderApiTestSupport}：本切片的服务者侧接口同样
 * 需要「已入驻的服务者 + 后台令牌」，而「注册账号 → 签后台令牌 → 提交入驻 → 运营审核通过」
 * 这条链路已经在那里实现好了，重写一遍只会产生第二份会过期的测试脚手架。
 *
 * <p><b>唯一需要额外搭的是 {@link ProviderAccessApi} 的实现</b>：它是 ph-api 里的跨模块接口，
 * 按契约应由 ph-provider 提供一个适配器（本次交付未接线，见 ADR-0044 的「需要协调」）。
 * 这里的 stub 就是那个适配器的**等价物**——用同一条 SQL 读 {@code provider_user} 与
 * {@code provider}，所以被它通过的用例在真适配器落地后仍然成立。生产环境没有它，
 * 服务者侧接口会明确报「未接线」，而不是静默按「没有门店」处理。
 *
 * <p>清理：{@code provider} 那批表由父类清；这里清本切片的九组表。
 * **种子数据不清**（{@code rights_code} 四个码、{@code point_behavior} 七行、
 * {@code point_task} 六行、{@code invite_ladder_tier} 五档、{@code point_config} 单行），
 * 只把测试改过的字段复位——清了它们，本切片的其余用例就没内容可测了。
 */
@Import(PrivilegeTestSupport.StubProviderAccess.class)
public abstract class PrivilegeTestSupport extends ProviderApiTestSupport {

    @BeforeEach
    void cleanPrivilegeData() {
        // 顺序：先子后主（本切片全是逻辑引用，没有物理外键，但这个顺序读起来最清楚）
        jdbc.execute("DELETE FROM `coupon`");
        jdbc.execute("DELETE FROM `coupon_contribution_log`");
        jdbc.execute("DELETE FROM `coupon_contribution`");
        jdbc.execute("DELETE FROM `coupon_template`");
        jdbc.execute("DELETE FROM `rights_grant`");
        jdbc.execute("DELETE FROM `rights_code` WHERE `code` NOT IN "
                + "('ai.unlimited','report.full','community.post','quota.ai.bonus')");
        jdbc.execute("UPDATE `rights_code` SET `status` = 1, `sort_order` = `id`");
        jdbc.execute("DELETE FROM `invite_risk_record`");
        jdbc.execute("DELETE FROM `invite_ladder_achievement`");
        jdbc.execute("DELETE FROM `invite_relation`");
        jdbc.execute("DELETE FROM `invite_code`");
        // 五档阶梯是种子：奖励物复位成「未配置」（测试里配过的要还回去）
        jdbc.execute("UPDATE `invite_ladder_tier` SET `reward_type` = NULL, `coupon_template_id` = NULL, "
                + "`rights_code` = NULL, `reward_count` = 1, `status` = 1");
        jdbc.execute("DELETE FROM `point_ladder_grant`");
        jdbc.execute("DELETE FROM `point_ladder_tier`");
        jdbc.execute("DELETE FROM `point_exchange_option`");
        jdbc.execute("DELETE FROM `point_record`");
        jdbc.execute("DELETE FROM `user_point`");
        jdbc.execute("DELETE FROM `point_task` WHERE `code` NOT IN "
                + "('DAILY_SIGN_IN','DAILY_CHECK_IN','DAILY_AI_ADVICE','DAILY_SHARE',"
                + "'WEEKLY_CHECK_IN','WEEKLY_INVITE')");
        // 行为分值表是种子：分值 / 频次 / 启停复位（测试会改它们）
        jdbc.execute("UPDATE `point_behavior` SET "
                + "`points` = CASE `code` WHEN 'SIGN_IN' THEN 1 WHEN 'CHECK_IN' THEN 3 WHEN 'INVITE' THEN 20 "
                + "WHEN 'REVIEW' THEN 5 WHEN 'PROFILE_COMPLETE' THEN 10 ELSE 0 END, "
                + "`counts_toward_daily_cap` = CASE `code` WHEN 'SIGN_IN' THEN 1 WHEN 'CHECK_IN' THEN 1 "
                + "WHEN 'REVIEW' THEN 1 ELSE 0 END, "
                + "`daily_count_limit` = CASE `code` WHEN 'SIGN_IN' THEN 1 WHEN 'CHECK_IN' THEN 1 "
                + "WHEN 'PROFILE_COMPLETE' THEN 1 WHEN 'AI_ADVICE' THEN 1 WHEN 'SHARE' THEN 1 ELSE NULL END, "
                + "`monthly_count_limit` = CASE `code` WHEN 'REVIEW' THEN 5 ELSE NULL END, "
                + "`once_only` = CASE `code` WHEN 'PROFILE_COMPLETE' THEN 1 ELSE 0 END, "
                + "`status` = 1");
        jdbc.execute("UPDATE `point_config` SET `daily_earn_limit` = 20 WHERE `id` = 1");
    }

    /**
     * {@link ProviderAccessApi} 的测试实现——**与 ph-provider 需要补的那个适配器等价**：
     * 读的还是 {@code provider_user} / {@code provider} 两张表，只是搬到了测试里。
     */
    @TestConfiguration
    static class StubProviderAccess {

        @Bean
        ProviderAccessApi providerAccessApi(JdbcTemplate jdbc) {
            return new ProviderAccessApi() {

                @Override
                public Optional<Long> findProviderId(long userId) {
                    List<Long> ids = jdbc.queryForList("SELECT provider_id FROM provider_user "
                            + "WHERE user_id = ? AND status = 1 AND is_deleted = 0 ORDER BY id LIMIT 1",
                            Long.class, userId);
                    return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
                }

                @Override
                public boolean isAdmin(long userId) {
                    List<Integer> roles = jdbc.queryForList("SELECT role FROM provider_user "
                            + "WHERE user_id = ? AND status = 1 AND is_deleted = 0 ORDER BY id LIMIT 1",
                            Integer.class, userId);
                    return !roles.isEmpty() && roles.get(0) == 1;
                }

                @Override
                public Map<Long, String> providerNames(Collection<Long> providerIds) {
                    Map<Long, String> names = new HashMap<>();
                    if (providerIds == null || providerIds.isEmpty()) {
                        return names;
                    }
                    String placeholders = String.join(",", providerIds.stream().map(id -> "?").toList());
                    jdbc.query("SELECT id, name FROM provider WHERE is_deleted = 0 AND id IN ("
                                    + placeholders + ")",
                            rs -> {
                                names.put(rs.getLong("id"), rs.getString("name"));
                            },
                            providerIds.toArray());
                    return names;
                }
            };
        }
    }
}
