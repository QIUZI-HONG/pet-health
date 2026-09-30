/**
 * 我的权益（切片 #112）的组件测试。
 *
 * 权益是**服务端实时判定**的结论（ADR-0045）；界面只做三件事：把 `effective=false` 的码
 * 也列出来（让用户看到「有这项权益、但当前不生效」）、说清来源与到期、以及说明来源优先级。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises } from "@vue/test-utils";
import RightsView from "../views/RightsView.vue";
import { apiFailure, mountPage } from "./support";

const getRights = vi.fn();
vi.mock("../api/commerce", () => ({
  commerce: {
    getRights: (...args: unknown[]) => getRights(...args),
  },
}));

beforeEach(() => {
  getRights.mockReset();
});

describe("我的权益：四态", () => {
  it("加载中给骨架", async () => {
    getRights.mockReturnValue(new Promise(() => undefined));

    const { wrapper } = await mountPage(RightsView, "/rights");

    expect(wrapper.find(".ph-state--loading").exists()).toBe(true);
  });

  it("空态：说清权益从哪来，并给两条去处", async () => {
    getRights.mockResolvedValue({ user_id: 1, rights: [] });

    const { wrapper } = await mountPage(RightsView, "/rights");

    expect(wrapper.text()).toContain("还没有权益");
    expect(wrapper.text()).toContain("去邀请好友");
  });

  it("错误态：带请求 ID 与重试", async () => {
    getRights.mockRejectedValueOnce(apiFailure(50000, "服务器内部错误", "req-500"));
    getRights.mockResolvedValueOnce({ user_id: 1, rights: [{ code: "ai.unlimited", name: "无限 AI 问答", effective: true }] });

    const { wrapper } = await mountPage(RightsView, "/rights");
    expect(wrapper.text()).toContain("req-500");

    await wrapper.get(".ph-state--error button").trigger("click");
    await flushPromises();
    expect(wrapper.text()).toContain("无限 AI 问答");
  });

  it("无权限态：未登录时不发请求", async () => {
    const { wrapper } = await mountPage(RightsView, "/rights", "anonymous");

    expect(wrapper.find(".ph-state--forbidden").exists()).toBe(true);
    expect(getRights).not.toHaveBeenCalled();
  });
});

describe("我的权益：生效口径与来源", () => {
  it("生效的码显示来源与到期；不生效的码也列出来（不许给一个空页）", async () => {
    getRights.mockResolvedValue({
      user_id: 1,
      rights: [
        { code: "ai.unlimited", name: "无限 AI 问答", effective: true, source: 2, source_name: "邀请", expire_at: null },
        { code: "report.full", name: "完整健康报告", effective: true, source: 3, source_name: "打卡", expire_at: "2026-10-31 23:59:59" },
        { code: "community.post", name: "社区发帖", effective: false, source: null, source_name: null, expire_at: null },
      ],
    });

    const { wrapper } = await mountPage(RightsView, "/rights");

    // 永久权益说「永久」，不当成「没有到期时间」的空白
    expect(wrapper.text()).toContain("永久");
    expect(wrapper.text()).toContain("邀请");
    expect(wrapper.text()).toContain("打卡");
    expect(wrapper.text()).toContain("2026年10月31日 23:59");
    // 不生效的那条也在，并明确写「未生效」
    expect(wrapper.text()).toContain("社区发帖");
    expect(wrapper.text()).toContain("未生效");
    expect(wrapper.text()).toContain("生效中");
  });

  it("写明来源优先级（不按到期时间比较，这条是 ADR-0038 第三节定的）", async () => {
    getRights.mockResolvedValue({ user_id: 1, rights: [{ code: "ai.unlimited", name: "无限 AI 问答", effective: true, source: 2, source_name: "邀请" }] });

    const { wrapper } = await mountPage(RightsView, "/rights");

    expect(wrapper.text()).toContain("订阅 > 邀请（永久）> 打卡（当月）");
    expect(wrapper.text()).toContain("服务端实时结果");
  });
});
