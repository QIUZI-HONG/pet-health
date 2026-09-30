package com.pethealth.boot.privilege;

import com.pethealth.api.app.InviteAttributionRequest;
import com.pethealth.api.app.InviteCodeRequest;
import com.pethealth.boot.support.ApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 邀请码上的**反作弊判据必须由服务端自己取**（2026-09-30 验收的头号发现）。
 *
 * <p>背景：三层判据里有两层要拿「码主人建码时的设备 / IP / 号段」当比较基准，而这三个值原先
 * 走的是入参——唯一的 C 端调用方传的是 {@code null, null, null}。于是那三列在生产库里恒为 NULL，
 * 两层反作弊**永远不会命中**；而测试全绿，因为测试直接调 {@code ensureInviteCode} 并自己传了值。
 * 这正是本仓反复出现的那一型：**测试证明了机制存在，没有一条用例在证明它在生产里会生效**。
 *
 * <p>这条用例盯的就是那件事：**走真实的 C 端 HTTP 入口**建一次码，然后去库里看那两列有没有值。
 * 号段必须是本账号手机号的前 7 位（不是硬编码、也不是入参）。
 *
 * <p>本用例**不覆盖** IP + 号段那一层的触发条件：测试里所有请求都来自回环地址，而那一层
 * 要求地址真的能区分来源（{@code InviteRiskGuard} 的类注释写了为什么）。触发条件是
 * {@code InviteTest#antiCheatRules} 用公网地址钉住的——两条用例合起来才是完整的：
 * 一条证明**值会被写上**，一条证明**规则会响**。
 */
class InviteCodeFactsTest extends PrivilegeTestSupport {

    @Test
    @DisplayName("建码：设备 / IP / 号段都写进 invite_code（三层反作弊的比较基准）")
    void createInviteCodeRecordsServerDerivedFacts() {
        String phone = "13800001234";
        String token = api.registerAndGetAccessToken(phone);

        ApiClient.ApiCall created = api.post("/api/v1/app/invites/code",
                new InviteCodeRequest(1, "dev-box-1"), token);
        assertThat(created.code()).isZero();
        String code = created.data().path("code").asText();
        assertThat(created.data().path("invite_path").asText()).contains(code);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT `owner_device_id`, `owner_ip`, `owner_phone_segment` FROM `invite_code` WHERE `code` = ?",
                code);
        assertThat(row.get("owner_device_id"))
                .as("设备号由客户端上报（桌面 Web 没有更硬的来源）——为空的后果是 SAME_DEVICE 那一层不命中")
                .isEqualTo("dev-box-1");
        assertThat(row.get("owner_ip"))
                .as("IP 取自连接（不是入参）——为空的后果是 SAME_IP_SEGMENT 那一层永远不命中")
                .isNotNull();
        assertThat(row.get("owner_phone_segment"))
                .as("号段是本账号手机号的前 7 位，由 ph-account 解密后给出")
                .isEqualTo(phone.substring(0, 7));

        // 一人一码：再取一次返回同一条，判据不被后来的调用改写（换渠道、换设备都不改写）
        ApiClient.ApiCall again = api.post("/api/v1/app/invites/code",
                new InviteCodeRequest(3, "dev-box-2"), token);
        assertThat(again.data().path("code").asText()).isEqualTo(code);
        Map<String, Object> after = jdbc.queryForMap(
                "SELECT `channel`, `owner_device_id`, `owner_ip`, `owner_phone_segment` FROM `invite_code` "
                        + "WHERE `code` = ?", code);
        assertThat(after.get("channel")).as("渠道记的是首次生成时的入口").isEqualTo(1);
        assertThat(after.get("owner_device_id")).as("设备号同样只记首次").isEqualTo("dev-box-1");
        assertThat(after.get("owner_ip")).isEqualTo(row.get("owner_ip"));
        assertThat(after.get("owner_phone_segment")).isEqualTo(row.get("owner_phone_segment"));
    }

    @Test
    @DisplayName("同设备那一层在生产路径上也会响：换账号、但用建码时那台设备去注册 → 拦下且不建关系")
    void sameDeviceIsRejectedThroughTheProductionPath() {
        // A 用自己的设备建码
        String inviterToken = api.registerAndGetAccessToken("13800002222");
        String code = api.post("/api/v1/app/invites/code", new InviteCodeRequest(1, "dev-same-box"), inviterToken)
                .data().path("code").asText();

        // B 换了个账号，但用的是同一台设备——这正是「自己刷自己」最省事的那种做法
        String inviteeToken = api.registerAndGetAccessToken("13900002222");
        ApiClient.ApiCall attributed = api.post("/api/v1/app/invites/attribution",
                new InviteAttributionRequest(code, 2, "dev-same-box"), inviteeToken);

        assertThat(attributed.code()).as("被拦下是业务结果，不是错误（注册照常成功）").isZero();
        assertThat(attributed.data().path("attributed").asBoolean()).isFalse();
        assertThat(attributed.data().path("reason").asText()).isEqualTo("SAME_DEVICE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `invite_relation`", Integer.class))
                .as("被拦下的邀请不计数、不建关系").isZero();
        assertThat(jdbc.queryForObject(
                "SELECT `rule` FROM `invite_risk_record` WHERE `device_id` = 'dev-same-box'", String.class))
                .isEqualTo("SAME_DEVICE");

        // 换一台设备（同一个账号）就能归因成功：这一层拦的是设备，不是人
        ApiClient.ApiCall otherDevice = api.post("/api/v1/app/invites/attribution",
                new InviteAttributionRequest(code, 2, "dev-another-box"), inviteeToken);
        assertThat(otherDevice.data().path("attributed").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `invite_relation`", Integer.class)).isEqualTo(1);
    }
}
