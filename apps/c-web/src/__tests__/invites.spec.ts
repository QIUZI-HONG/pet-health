/**
 * 邀请有礼（切片 #111）的组件测试 + 两处前端行为的单测。
 *
 * 组件侧：**没有码时先引导生成**、进度按有效邀请数展示、阶梯奖励没配也照记达成。
 * 单测侧（`utils/invite.ts`）：链接记住 7 天、设备标识、以及**归因失败不阻塞**——
 * 后者是 ADR-0046 第一节点名的口径：不能因为邀请码可疑就不让用户注册。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises } from "@vue/test-utils";
import type { InviteCenterView } from "../api/commerce";
import InviteView from "../views/InviteView.vue";
import {
  attributeAfterRegister,
  attributionNotice,
  deviceId,
  readRememberedInvite,
  rememberInviteFromUrl,
} from "../utils/invite";
import { apiFailure, mountPage } from "./support";

const getInviteCenter = vi.fn();
const createInviteCode = vi.fn();
const attributeInvite = vi.fn();
vi.mock("../api/commerce", () => ({
  commerce: {
    getInviteCenter: (...args: unknown[]) => getInviteCenter(...args),
    createInviteCode: (...args: unknown[]) => createInviteCode(...args),
    attributeInvite: (...args: unknown[]) => attributeInvite(...args),
  },
}));

const CENTER: InviteCenterView = {
  codes: [
    { id: 2, code: "ABC123", channel: 1, invite_path: "/register?invite=ABC123", status: 1, created_at: "2026-09-20 10:00:00" },
    { id: 1, code: "OLD999", channel: 1, invite_path: "/register?invite=OLD999", status: 1, created_at: "2026-09-01 10:00:00" },
  ],
  registered_count: 5,
  pending_count: 1,
  effective_count: 3,
  invalid_count: 1,
  ladder: [
    { threshold: 1, achieved: true, achieved_at: "2026-09-10 10:00:00", reward_desc: "10 元券" },
    { threshold: 3, achieved: true, achieved_at: "2026-09-25 10:00:00", reward_desc: null },
    { threshold: 5, achieved: false, achieved_at: null, reward_desc: null },
  ],
  next_threshold: 5,
  next_remaining: 2,
};

beforeEach(() => {
  getInviteCenter.mockReset();
  createInviteCode.mockReset();
  attributeInvite.mockReset();
  window.localStorage.clear();
});

describe("邀请页：四态与生成", () => {
  it("没有邀请码时先引导生成，并说明「不删旧码」", async () => {
    getInviteCenter.mockResolvedValue({ ...CENTER, codes: [] });

    const { wrapper } = await mountPage(InviteView, "/invites");

    expect(wrapper.text()).toContain("还没有邀请码");
    expect(wrapper.text()).toContain("不删旧码");
  });

  it("生成邀请码：带渠道标签、本机设备号与幂等键，成功后拉一次最新进度", async () => {
    getInviteCenter.mockResolvedValueOnce({ ...CENTER, codes: [] }).mockResolvedValue(CENTER);
    createInviteCode.mockResolvedValue({ id: 2, code: "ABC123", invite_path: "/register?invite=ABC123", status: 1 });

    const { wrapper } = await mountPage(InviteView, "/invites");
    await wrapper.findAll("button").find((button) => button.text() === "生成我的邀请码")?.trigger("click");
    await flushPromises();

    const [body, key] = createInviteCode.mock.calls[0] as [Record<string, unknown>, string];
    // 设备号是 SAME_DEVICE 那一层反作弊的比较基准：不带上它，那层判据在真实使用中就不成立
    // （值来自本机生成一次、之后一直复用的 deviceId()，与注册归因用的是同一个）
    expect(body).toEqual({ channel: 1, device_id: deviceId() });
    expect(typeof key).toBe("string");
    expect(wrapper.text()).toContain("ABC123");
  });

  it("错误态带请求 ID 与重试；未登录时给去登录的入口", async () => {
    getInviteCenter.mockRejectedValueOnce(apiFailure(50000, "服务器内部错误", "req-500"));
    getInviteCenter.mockResolvedValueOnce(CENTER);

    const { wrapper } = await mountPage(InviteView, "/invites");
    expect(wrapper.text()).toContain("req-500");
    await wrapper.get(".ph-state--error button").trigger("click");
    await flushPromises();
    expect(wrapper.text()).toContain("ABC123");

    const anonymous = await mountPage(InviteView, "/invites", "anonymous");
    expect(anonymous.wrapper.find(".ph-state--forbidden").exists()).toBe(true);
  });
});

describe("邀请页：码、链接与进度", () => {
  it("展示最新的码与拼好域名的分享链接，并写明「注册时填、注册后不能补填」", async () => {
    getInviteCenter.mockResolvedValue(CENTER);

    const { wrapper } = await mountPage(InviteView, "/invites");

    expect(wrapper.text()).toContain("ABC123");
    expect(wrapper.text()).toContain("/register?invite=ABC123");
    expect(wrapper.text()).toContain("邀请码要在注册时填写");
    expect(wrapper.text()).toContain("无法补填");
  });

  it("进度按有效邀请数展示（有效 / 已注册 / 待生效 / 无效）", async () => {
    getInviteCenter.mockResolvedValue(CENTER);

    const { wrapper } = await mountPage(InviteView, "/invites");

    expect(wrapper.text()).toContain("有效邀请");
    expect(wrapper.text()).toContain("3");
    expect(wrapper.text()).toContain("还差");
    expect(wrapper.text()).toContain("2 个有效邀请到 5 人档");
  });

  it("阶梯：奖励物没配的档位照记达成，界面照实说「该档未配奖励」", async () => {
    getInviteCenter.mockResolvedValue(CENTER);

    const { wrapper } = await mountPage(InviteView, "/invites");

    expect(wrapper.text()).toContain("10 元券");
    expect(wrapper.text()).toContain("该档未配奖励（达成照记）");
    expect(wrapper.text()).toContain("已达成 2026年09月25日");
  });
});

describe("邀请码的落地页记忆（前端行为，ADR-0039 第一节）", () => {
  it("地址栏带 ?invite=CODE 时记住它，并在 7 天内可读", () => {
    rememberInviteFromUrl("?invite=XYZ789");

    expect(readRememberedInvite()).toEqual({ code: "XYZ789", channel: 1 });
  });

  it("超过 7 天就失效（并顺手清掉）", () => {
    rememberInviteFromUrl("?invite=XYZ789");
    const eightDaysLater = Date.now() + 8 * 24 * 60 * 60 * 1000;

    expect(readRememberedInvite(eightDaysLater)).toBeNull();
    expect(readRememberedInvite()).toBeNull();
  });

  it("地址栏没有邀请码时不动已记住的那条", () => {
    rememberInviteFromUrl("?invite=XYZ789");
    rememberInviteFromUrl("?other=1");

    expect(readRememberedInvite()?.code).toBe("XYZ789");
  });

  it("设备标识持久化：同一浏览器两次调用拿到同一个值（反作弊要用）", () => {
    const first = deviceId();
    expect(first.length).toBeGreaterThan(0);
    expect(deviceId()).toBe(first);
  });
});

describe("注册归因的调用口径（ADR-0046 第一节）", () => {
  it("成功：带码、渠道与设备标识", async () => {
    attributeInvite.mockResolvedValue({ attributed: true, reason: null, invite_code: "ABC123", relation_status: 1 });

    const notice = await attributeAfterRegister("ABC123", 1);

    expect(notice).toContain("待生效");
    const [body] = attributeInvite.mock.calls[0] as [Record<string, unknown>];
    expect(body.invite_code).toBe("ABC123");
    expect(body.channel).toBe(1);
    expect(typeof body.device_id).toBe("string");
  });

  it("被拦下（attributed=false）不是异常：返回一句给用户看的话，不抛错", async () => {
    attributeInvite.mockResolvedValue({ attributed: false, reason: "SAME_DEVICE", invite_code: "ABC123", relation_status: null });

    const notice = await attributeAfterRegister("ABC123", 1);

    // 反作弊的判据**不往外说**（ADR-0046 第三节）：只说这句笼统的
    expect(notice).toBe("邀请码不可用");
    expect(notice).not.toContain("SAME_DEVICE");
  });

  it("调用失败也不抛错：注册已经成功了，归因不能把它翻回去", async () => {
    attributeInvite.mockRejectedValue(apiFailure(50000, "服务器内部错误"));

    await expect(attributeAfterRegister("ABC123", 1)).resolves.toBe("");
  });

  it("没填码就不发请求（少一次必然失败的往返）", async () => {
    await expect(attributeAfterRegister("   ", 2)).resolves.toBe("");
    expect(attributeInvite).not.toHaveBeenCalled();
  });

  it("原因映射：只有「已归因」「码不存在」说实话，反作弊一律笼统", () => {
    expect(attributionNotice("ALREADY_ATTRIBUTED")).toBe("该账号已绑定邀请关系");
    expect(attributionNotice("CODE_NOT_FOUND")).toBe("邀请码不存在或已失效");
    expect(attributionNotice("SELF_INVITE")).toBe("邀请码不可用");
    expect(attributionNotice("SAME_IP_SEGMENT")).toBe("邀请码不可用");
  });
});
