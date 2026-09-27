package com.pethealth.boot;

import com.pethealth.boot.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 骨架冒烟测试：Spring 上下文能起来、迁移脚本能在真实 MySQL 上跑完、Redis 连得上。
 *
 * <p>这条测试的价值在于它是「模块接线是否正确」的最廉价检查——加了新模块、改了依赖、
 * 写错了配置，它会第一时间失败。
 *
 * <p>它同时是**迁移脚本的守门人**：Flyway 在上下文启动时执行 {@code db/migration} 下的全部脚本，
 * 脚本里任何语法与字符集问题都会让这一条挂掉。
 */
class PetHealthApplicationTests extends IntegrationTestBase {

    @Test
    void contextLoadsWithMigrationsApplied() {
        Integer appliedMigrations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class);
        assertThat(appliedMigrations).isNotNull().isGreaterThanOrEqualTo(2);

        // 两张表都在，且是 InnoDB（交付文档 7.2 的统一约定）
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() "
                        + "AND table_name IN ('user', 'pet') AND engine = 'InnoDB'", Integer.class))
                .isEqualTo(2);

        // Redis 真的在用（Refresh Token 存这里）：写一个键再读回来
        redis.opsForValue().set("ph:test:ping", "pong");
        assertThat(redis.opsForValue().get("ph:test:ping")).isEqualTo("pong");
    }
}
