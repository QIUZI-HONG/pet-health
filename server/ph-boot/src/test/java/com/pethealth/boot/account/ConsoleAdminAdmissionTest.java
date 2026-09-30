package com.pethealth.boot.account;

import com.pethealth.account.config.ConsoleProperties;
import com.pethealth.account.service.AccountService;
import com.pethealth.account.service.ConsoleAuthService;
import com.pethealth.api.app.LoginRequest;
import com.pethealth.api.app.TokenPair;
import com.pethealth.boot.support.ApiClient;
import com.pethealth.boot.support.IntegrationTestBase;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.security.LoginDomain;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 运营后台准入名单的**正向**用例 + 「名单为空时谁都进不去」的 fail-closed 用例。
 *
 * <p>为什么单独一个类、而且走服务层而不是 HTTP：名单值必须在 Spring 上下文启动前定下来
 * （{@code @ConfigurationProperties}），而**账号 id 是运行时才知道的**（自增，且取决于
 * 这个共享数据库里已经注册过多少账号——第一版测试写死「第一个账号是 1001」当场被打脸，
 * 实际是 2）。所以这里注册完账号**再**用它的 id 构造一份属性，直接调
 * {@link ConsoleAuthService}——准入判据本身一行不少地跑一遍，而 HTTP 那一层（路径 → 域）
 * 由 {@link ConsoleLoginTest} 的拒绝路径与本类的第一条用例共同覆盖。
 */
class ConsoleAdminAdmissionTest extends IntegrationTestBase {

    private ApiClient api;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountService accounts;

    @BeforeEach
    void setUpClient() {
        api = new ApiClient(rest, objectMapper);
    }

    @Test
    @DisplayName("名单里有的账号能登进运营后台，拿到的是 admin 域令牌")
    void listedAccountCanEnterAdminConsole() {
        long userId = api.register("13800139300").data().path("user").path("id").asLong();

        ConsoleAuthService consoleAuth = new ConsoleAuthService(accounts,
                new ConsoleProperties(String.valueOf(userId)));
        TokenPair pair = consoleAuth.login(LoginDomain.ADMIN,
                new LoginRequest("13800139300", ApiClient.DEFAULT_PASSWORD));

        assertThat(pair.accessToken()).isNotBlank();
        // 令牌的域：admin 域接口认它、C 端接口不认
        assertThat(api.get("/api/v1/admin/providers?page=1&page_size=10", pair.accessToken()).code())
                .as("名单里的账号能用这枚令牌读运营接口").isZero();
        assertThat(api.get("/api/v1/app/users/me", pair.accessToken()).code()).isEqualTo(40100);
    }

    @Test
    @DisplayName("名单为空时谁都进不去（fail-closed，不是「谁都能进」）")
    void emptyAllowlistAdmitsNobody() {
        api.register("13800139301");

        ConsoleAuthService consoleAuth = new ConsoleAuthService(accounts, new ConsoleProperties(""));
        assertThatThrownBy(() -> consoleAuth.login(LoginDomain.ADMIN,
                new LoginRequest("13800139301", ApiClient.DEFAULT_PASSWORD)))
                .isInstanceOf(BusinessException.class)
                // 断言钉的是**文案要能指导下一步**：说的是「不在名单里」（+ 配在哪个变量里），
                // 而不是「不是后台账号」——后者听起来像「你注册错端了」，会把运营引去重新注册
                .hasMessageContaining("不在运营后台名单里")
                .hasMessageContaining("CONSOLE_ADMIN_USER_IDS");
    }

    @Test
    @DisplayName("名单里的空白项与非法数字被忽略，不会让准入放宽")
    void malformedAllowlistEntriesAreIgnored() {
        long userId = api.register("13800139302").data().path("user").path("id").asLong();

        ConsoleAuthService consoleAuth = new ConsoleAuthService(accounts,
                new ConsoleProperties(" , abc, " + userId + " ,, "));
        assertThat(consoleAuth.login(LoginDomain.ADMIN,
                new LoginRequest("13800139302", ApiClient.DEFAULT_PASSWORD)).accessToken()).isNotBlank();
    }
}
