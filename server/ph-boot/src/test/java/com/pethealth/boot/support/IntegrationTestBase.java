package com.pethealth.boot.support;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 集成测试基类：真实 MySQL 8.4 + Redis 8 + 真实 HTTP（ADR-0014）。
 *
 * <p>为什么不用 H2：本项目用了 MySQL 的字符集、排序规则、{@code ngram} 与 JSON 列，
 * 内存库跑通不代表 MySQL 跑通。这里的镜像与 {@code deploy/docker-compose.dev.yml} 一致，
 * 本地与 CI 行为相同。
 *
 * <p>容器是 **static 单例**：一个 JVM 只起一次，被所有测试类共享；否则每个测试类起一遍容器，
 * 整个测试套会慢到没人愿意跑。表数据在每个测试方法前清空，不靠事务回滚——真实提交路径要一起测。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestBase {

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("pet_health")
            .withUsername("pet_health")
            .withPassword("pet_health")
            // 与 dev compose 对齐：utf8mb4 + 东八区，否则 CURRENT_TIMESTAMP 默认值是 UTC
            .withCommand("--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+08:00");

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8-alpine"))
            .withExposedPorts(6379);

    static {
        MYSQL.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));

        // 密钥在这里给测试值：应用配置本身不再带任何默认密钥（免得仓库里躺着假密钥），
        // 这几个常量只活在这套跑在临时容器上的测试里，不会出现在任何真实环境。
        registry.add("app.auth.jwt-secret", () -> "integration-test-jwt-secret-not-for-real-use-0123456789");
        registry.add("app.crypto.enc-key", () -> TEST_ENC_KEY);
        registry.add("app.crypto.hmac-key", () -> TEST_HMAC_KEY);
        // 文件落在一个随 JVM 进程生灭的临时目录：测试之间不互相污染，也不往工作目录里堆东西
        registry.add("app.file.root", IntegrationTestBase::temporaryStorageRoot);
    }

    /** 测试用的文件根目录；JVM 退出时由临时目录清理策略回收。 */
    private static String temporaryStorageRoot() {
        try {
            if (STORAGE_ROOT == null) {
                STORAGE_ROOT = Files.createTempDirectory("pet-health-files-test");
            }
            return STORAGE_ROOT.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path STORAGE_ROOT;

    /** base64 的 32 字节，仅供测试。 */
    static final String TEST_ENC_KEY = "aW50ZWdyYXRpb24tdGVzdC1lbmMta2V5LTMyYnl0ZXM=";
    static final String TEST_HMAC_KEY = "aW50ZWdyYXRpb24tdGVzdC1obWFjLWtleS0zMmJ5dGU=";

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected StringRedisTemplate redis;

    /**
     * 清掉上一个用例留下的数据。顺序不能反：先删子表再删主表（没有物理外键，
     * 但保持这个习惯，将来加约束时不至于漏改）。
     */
    @BeforeEach
    protected void cleanDatabase() {
        // 顺序：先清业务数据再清主表（没有物理外键，但这个顺序读起来最清楚）
        jdbc.execute("DELETE FROM `ai_consult`");
        // 合规文档是**种子数据**（迁移里插的），不能清——清了整个切片就没内容了
        jdbc.execute("DELETE FROM `file_object`");
        jdbc.execute("DELETE FROM `message`");
        jdbc.execute("DELETE FROM `reminder_setting`");
        jdbc.execute("DELETE FROM `archive_record`");
        jdbc.execute("DELETE FROM `health_score`");
        jdbc.execute("DELETE FROM `pet`");
        jdbc.execute("DELETE FROM `user`");
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }
}
