/**
 * 营销中心的组件测试。
 *
 * 这一页在 2026-09-30 之后有了实打实的一块：**门店推广码**（V44 的 `provider_invite_code` +
 * 服务者侧 `GET|POST /invite-code`），它同时是考核拉新项的取数来源。测试要钉的：
 *
 *   - **没码时不假装有**：显示「生成推广码」按钮，点它发的是 POST；
 *   - **有码时把三个计数摆出来**（有效 / 观察中 / 无效）——「扫了多少人」与「算几个人」是两件事，
 *     服务者最容易误解的就是这个；
 *   - 拉新项在考核明细里的口径照旧（原始值、权重、达标线、数据来源），未参与时说明原因而不记 0 分；
 *   - 还没做的部分（二维码图片、物料模板、活动管理）如实写出来，不摆点了会 404 的按钮。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises } from "@vue/test-utils";
import type { AssessmentRow, AssessmentView, ProviderInviteCodeView } from "../api/providerApi";
import MarketingView from "../views/MarketingView.vue";
import { apiFailure, mountPage } from "./support";

const listAssessments = vi.fn();
const getAssessment = vi.fn();
const getInviteCode = vi.fn();
const ensureInviteCode = vi.fn();

vi.mock("../api/providerApi", () => ({
  providerApp: {
    listAssessments: (...args: unknown[]) => listAssessments(...args),
    getAssessment: (...args: unknown[]) => getAssessment(...args),
    getInviteCode: (...args: unknown[]) => getInviteCode(...args),
    ensureInviteCode: (...args: unknown[]) => ensureInviteCode(...args),
  },
}));

const ASSESSMENT: AssessmentRow = {
  id: 1,
  period: "2026-08",
  total_score: "86.50",
  level: 2,
  level_name: "优选",
  recommend_priority: 2,
  participated_weight: 100,
};

const DETAIL: AssessmentView = {
  ...ASSESSMENT,
  items: [
    {
      item_code: "INVITE",
      item_name: "拉新",
      weight: 40,
      participated: true,
      score: "80.00",
      raw_value: "有效邀请 4 人",
      target_value: "5 人",
      data_source: "ph-privilege 邀请关系",
      note: "只算完成建档且 24 小时内有行为的被邀请人",
    },
  ],
};

const CODE: ProviderInviteCodeView = {
  code: "PVABCDEFGH",
  status: 1,
  created_at: "2026-09-25 10:00:00",
  effective_invites: 4,
  pending_invites: 2,
  invalid_invites: 1,
};

const NO_CODE: ProviderInviteCodeView = {
  code: null,
  status: null,
  created_at: null,
  effective_invites: 0,
  pending_invites: 0,
  invalid_invites: 0,
};

function page<T>(list: T[]) {
  return { list, page: 1, page_size: 20, total: list.length, has_more: false };
}

beforeEach(() => {
  listAssessments.mockReset().mockResolvedValue(page([ASSESSMENT]));
  getAssessment.mockReset().mockResolvedValue(DETAIL);
  getInviteCode.mockReset().mockResolvedValue(CODE);
  ensureInviteCode.mockReset().mockResolvedValue(CODE);
});

describe("营销中心：推广码", () => {
  it("有码：摆出码本身与三个计数（有效 / 观察中 / 无效）", async () => {
    const { wrapper } = await mountPage(MarketingView, "/b/marketing");

    expect(getInviteCode).toHaveBeenCalled();
    expect(wrapper.text()).toContain("PVABCDEFGH");
    expect(wrapper.text()).toContain("4 人（考核只算这个数）");
    expect(wrapper.text()).toContain("2 人");
    expect(wrapper.text()).toContain("1 人");
  });

  it("没码：显示生成按钮（不是空态占位），点了发 POST", async () => {
    getInviteCode.mockResolvedValue(NO_CODE);

    const { wrapper } = await mountPage(MarketingView, "/b/marketing");
    expect(wrapper.text()).toContain("还没有推广码");

    // 按文案找按钮：找不到就抛错，而不是把 undefined 打一下静默通过
    const generate = wrapper.findAll("button").find((button) => button.text().includes("生成推广码"));
    expect(generate, "应当有一个「生成推广码」按钮").toBeTruthy();
    await generate!.trigger("click");
    await flushPromises();

    expect(ensureInviteCode).toHaveBeenCalledTimes(1);
    expect(wrapper.text()).toContain("推广码已生成");
    expect(wrapper.text()).toContain("PVABCDEFGH");
  });

  it("读码失败：错误态带重试，且**不挡住**下面的拉新成绩", async () => {
    getInviteCode.mockRejectedValue(apiFailure(50000, "服务器内部错误", "req-code"));

    const { wrapper } = await mountPage(MarketingView, "/b/marketing");

    expect(wrapper.text()).toContain("服务器内部错误");
    expect(wrapper.text()).toContain("有效邀请 4 人");
  });
});

describe("营销中心：拉新成绩与还没做的部分", () => {
  it("本店拉新成绩来自考核明细（原始值、权重、达标线、数据来源都在）", async () => {
    const { wrapper } = await mountPage(MarketingView, "/b/marketing");

    expect(listAssessments).toHaveBeenCalledWith({ page: 1, pageSize: 1 }, expect.anything());
    expect(getAssessment).toHaveBeenCalledWith("2026-08", expect.anything());
    expect(wrapper.text()).toContain("本店拉新（考核口径）");
    expect(wrapper.text()).toContain("拉新（权重 40%）");
    expect(wrapper.text()).toContain("有效邀请 4 人");
    expect(wrapper.text()).toContain("5 人");
    expect(wrapper.text()).toContain("ph-privilege 邀请关系");
  });

  it("拉新项未参与时说明「为什么不参与」而不是记成 0 分", async () => {
    getAssessment.mockResolvedValue({
      ...DETAIL,
      items: [
        {
          item_code: "INVITE",
          item_name: "拉新",
          weight: 40,
          participated: false,
          score: null,
          data_source: "ph-privilege 有效邀请数（ProviderGrowthFactsApi）",
          note: "未参与：平台侧还没有给这个服务者拉新入口（无可归因的邀请来源），无该维度要求",
        },
      ],
    } as AssessmentView);

    const { wrapper } = await mountPage(MarketingView, "/b/marketing");

    expect(wrapper.text()).toContain("未参与计分");
    expect(wrapper.text()).toContain("平台侧还没有给这个服务者拉新入口");
    expect(wrapper.text()).toContain("不记 0 分");
  });

  it("如实写明还没做的部分，且**不摆**下载二维码 / 物料模板这类点了会 404 的按钮", async () => {
    const { wrapper } = await mountPage(MarketingView, "/b/marketing");

    expect(wrapper.text()).toContain("物料与活动的边界");
    expect(wrapper.text()).toContain("没有二维码图片下载");
    expect(wrapper.text()).toContain("没有物料模板与活动管理");
    const buttons = wrapper.findAll("button").map((button) => button.text());
    expect(buttons.some((text) => text.includes("二维码"))).toBe(false);
    expect(buttons.some((text) => text.includes("下载"))).toBe(false);
  });

  it("没有考核记录时的空态，以及接口失败时的错误态（带请求 ID 与重试）", async () => {
    listAssessments.mockResolvedValueOnce(page([]));
    const { wrapper: empty } = await mountPage(MarketingView, "/b/marketing");
    expect(empty.text()).toContain("还没有考核记录");

    listAssessments.mockReset().mockRejectedValueOnce(apiFailure(50000, "服务器内部错误", "req-500"));
    const { wrapper } = await mountPage(MarketingView, "/b/marketing");
    expect(wrapper.text()).toContain("服务器内部错误");
    expect(wrapper.text()).toContain("req-500");
  });

  it("未登录：闸门先拦，考核与推广码请求都不发", async () => {
    const { wrapper } = await mountPage(MarketingView, "/b/marketing", "anonymous");

    expect(wrapper.text()).toContain("尚未登录服务者账号");
    expect(listAssessments).not.toHaveBeenCalled();
    expect(getInviteCode).not.toHaveBeenCalled();
  });
});
