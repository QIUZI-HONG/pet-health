/**
 * 积分与邀请配置页（GrowthConfigView）的组件测试。
 *
 * 这一页补的是「配置接口存在、但没有任何承载页」那一类缺口，所以测试盯四条规则：
 *   - **读**：五段各自的接口都要按分段取数，参照数据（券模板 / 权益码）一进页面就加载；
 *   - **改**：提交拼成契约的形状——`points_cost` 是整数、兑换档位与月度阶梯只给平台补贴券、
 *     邀请阶梯的「不发东西」必须把 `reward_type` 传成 null 且不带券模板；
 *   - **不编数值**：档位与奖励没有种子数据，空态说的就是「由你填」；
 *   - **失败有提示**：后端那句话与请求 ID 都露出来，成功后的提示要说清影响面。
 *
 * 另外验一条容易漏的：未登录时一个业务请求都不发（闸门管住插槽的挂载）。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises } from "@vue/test-utils";
import type {
  CouponTemplateRow,
  ExchangeOptionRow,
  InviteLadderRow,
  PointBehaviorRow,
  PointLadderRow,
  RightsCodeRow,
} from "../api/adminApi";
import GrowthConfigView from "../views/GrowthConfigView.vue";
import { apiFailure, buttonByText, mountPage } from "./support";

const getPointsSettings = vi.fn();
const updatePointsSettings = vi.fn();
const listPointBehaviors = vi.fn();
const updatePointBehavior = vi.fn();
const listPointExchangeOptions = vi.fn();
const createPointExchangeOption = vi.fn();
const updatePointExchangeOption = vi.fn();
const listPointLadderTiers = vi.fn();
const createPointLadderTier = vi.fn();
const updatePointLadderTier = vi.fn();
const listInviteLadderTiers = vi.fn();
const updateInviteLadderTier = vi.fn();
const listCouponTemplates = vi.fn();
const listRightsCodes = vi.fn();

vi.mock("../api/adminApi", () => ({
  adminApp: {
    getPointsSettings: (...args: unknown[]) => getPointsSettings(...args),
    updatePointsSettings: (...args: unknown[]) => updatePointsSettings(...args),
    listPointBehaviors: (...args: unknown[]) => listPointBehaviors(...args),
    updatePointBehavior: (...args: unknown[]) => updatePointBehavior(...args),
    listPointExchangeOptions: (...args: unknown[]) => listPointExchangeOptions(...args),
    createPointExchangeOption: (...args: unknown[]) => createPointExchangeOption(...args),
    updatePointExchangeOption: (...args: unknown[]) => updatePointExchangeOption(...args),
    listPointLadderTiers: (...args: unknown[]) => listPointLadderTiers(...args),
    createPointLadderTier: (...args: unknown[]) => createPointLadderTier(...args),
    updatePointLadderTier: (...args: unknown[]) => updatePointLadderTier(...args),
    listInviteLadderTiers: (...args: unknown[]) => listInviteLadderTiers(...args),
    updateInviteLadderTier: (...args: unknown[]) => updateInviteLadderTier(...args),
    listCouponTemplates: (...args: unknown[]) => listCouponTemplates(...args),
    listRightsCodes: (...args: unknown[]) => listRightsCodes(...args),
  },
}));

/** 一张平台补贴券（兑换档位与阶梯的合法奖励物）与一张服务者成本券（不是） */
const SUBSIDY: CouponTemplateRow = {
  id: 7,
  code: "CP-001",
  name: "平台补贴券",
  face_value: "20.00",
  min_amount: "100.00",
  valid_days: 30,
  cost_bearer: 2,
  scope_type: 0,
  status: 1,
  scope_desc: "不限",
  updated_at: "2026-09-20 10:00:00",
};
const PROVIDER_COST: CouponTemplateRow = {
  ...SUBSIDY,
  id: 8,
  code: "CP-002",
  name: "服务者成本券",
  cost_bearer: 1,
};

const BEHAVIOR: PointBehaviorRow = {
  code: "SIGN_IN",
  name: "每日签到",
  points: 1,
  counts_toward_daily_cap: 1,
  daily_count_limit: 1,
  monthly_count_limit: null,
  once_only: 0,
  status: 1,
  updated_at: "2026-09-20 10:00:00",
};

const RIGHT: RightsCodeRow = { code: "report.full", name: "完整报告", status: 1 };

const TIER_ROW: InviteLadderRow = {
  threshold: 3,
  reward_type: null,
  coupon_template_id: null,
  rights_code: null,
  reward_count: 1,
  status: 1,
  updated_at: "2026-09-20 10:00:00",
};

function page(list: unknown[]) {
  return { list, page: 1, page_size: 100, total: list.length, has_more: false };
}

beforeEach(() => {
  getPointsSettings.mockReset().mockResolvedValue({ daily_earn_limit: 20 });
  updatePointsSettings.mockReset().mockResolvedValue({ daily_earn_limit: 30 });
  listPointBehaviors.mockReset().mockResolvedValue([BEHAVIOR]);
  updatePointBehavior.mockReset().mockResolvedValue(BEHAVIOR);
  listPointExchangeOptions.mockReset().mockResolvedValue([]);
  createPointExchangeOption.mockReset().mockResolvedValue({} as ExchangeOptionRow);
  updatePointExchangeOption.mockReset().mockResolvedValue({} as ExchangeOptionRow);
  listPointLadderTiers.mockReset().mockResolvedValue([]);
  createPointLadderTier.mockReset().mockResolvedValue({} as PointLadderRow);
  updatePointLadderTier.mockReset().mockResolvedValue({} as PointLadderRow);
  listInviteLadderTiers.mockReset().mockResolvedValue([TIER_ROW]);
  updateInviteLadderTier.mockReset().mockResolvedValue(TIER_ROW);
  listCouponTemplates.mockReset().mockResolvedValue(page([SUBSIDY, PROVIDER_COST]));
  listRightsCodes.mockReset().mockResolvedValue([RIGHT]);
});

describe("积分与邀请配置：能读", () => {
  it("进页面先读积分规则，并同时加载券模板与权益码两份参照数据", async () => {
    const { wrapper } = await mountPage(GrowthConfigView, "/admin/points-invite");

    expect(getPointsSettings).toHaveBeenCalledTimes(1);
    expect(listCouponTemplates).toHaveBeenCalledTimes(1);
    expect(listRightsCodes).toHaveBeenCalledTimes(1);
    expect(wrapper.text()).toContain("每日获取上限");
    expect(wrapper.text()).toContain("20 分");
  });

  it("每个分段各自取数：行为分值 / 兑换档位 / 月度阶梯 / 邀请阶梯", async () => {
    const { wrapper } = await mountPage(GrowthConfigView, "/admin/points-invite");

    await buttonByText(wrapper, "行为分值").trigger("click");
    await flushPromises();
    expect(listPointBehaviors).toHaveBeenCalledTimes(1);
    expect(wrapper.text()).toContain("SIGN_IN");

    await buttonByText(wrapper, "兑换档位").trigger("click");
    await flushPromises();
    expect(listPointExchangeOptions).toHaveBeenCalledTimes(1);

    await buttonByText(wrapper, "月度阶梯").trigger("click");
    await flushPromises();
    expect(listPointLadderTiers).toHaveBeenCalledTimes(1);

    await buttonByText(wrapper, "邀请阶梯").trigger("click");
    await flushPromises();
    expect(listInviteLadderTiers).toHaveBeenCalledTimes(1);
    expect(wrapper.text()).toContain("未配奖励");
  });

  it("月度阶梯的空态说的是「本来就是空的」（种子里一个档位都没有，ADR-0046）", async () => {
    const { wrapper } = await mountPage(GrowthConfigView, "/admin/points-invite");

    await buttonByText(wrapper, "月度阶梯").trigger("click");
    await flushPromises();

    expect(wrapper.find(".ph-state--empty").exists()).toBe(true);
    expect(wrapper.text()).toContain("这是初始状态");
  });

  it("加载失败：显示后端那句话与请求 ID，重试真的重读", async () => {
    getPointsSettings
      .mockReset()
      .mockRejectedValueOnce(apiFailure(50000, "服务器内部错误", "req-500"))
      .mockResolvedValueOnce({ daily_earn_limit: 20 });

    const { wrapper } = await mountPage(GrowthConfigView, "/admin/points-invite");

    expect(wrapper.text()).toContain("服务器内部错误");
    expect(wrapper.text()).toContain("req-500");

    await wrapper.get(".ph-state--error button").trigger("click");
    await flushPromises();

    expect(getPointsSettings).toHaveBeenCalledTimes(2);
    expect(wrapper.text()).toContain("20 分");
  });

  it("未登录：闸门说话，一个业务请求都不发", async () => {
    const { wrapper } = await mountPage(GrowthConfigView, "/admin/points-invite", "anonymous");

    expect(wrapper.find(".ph-state--forbidden").exists()).toBe(true);
    expect(getPointsSettings).not.toHaveBeenCalled();
    expect(listCouponTemplates).not.toHaveBeenCalled();
  });
});

describe("积分与邀请配置：能改", () => {
  it("改每日上限：提交 { daily_earn_limit }，成功后提示即时生效", async () => {
    const { wrapper } = await mountPage(GrowthConfigView, "/admin/points-invite");

    await buttonByText(wrapper, "修改每日上限").trigger("click");
    await wrapper.get('input[placeholder="如 20"]').setValue("30");
    await wrapper.get("form.ph-growth__form").trigger("submit");
    await flushPromises();

    expect(updatePointsSettings).toHaveBeenCalledWith({ daily_earn_limit: 30 });
    expect(wrapper.text()).toContain("积分规则已更新");
  });

  it("上限越界本地就拦住（契约是 1–1000），不发请求", async () => {
    const { wrapper } = await mountPage(GrowthConfigView, "/admin/points-invite");

    await buttonByText(wrapper, "修改每日上限").trigger("click");
    await wrapper.get('input[placeholder="如 20"]').setValue("1001");
    await wrapper.get("form.ph-growth__form").trigger("submit");
    await flushPromises();

    expect(updatePointsSettings).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain("1–1000");
  });

  it("改行为分值：把当前值显式回传（分值 / 占上限 / 频次 / 一次性 / 状态一起给）", async () => {
    const { wrapper } = await mountPage(GrowthConfigView, "/admin/points-invite");
    await buttonByText(wrapper, "行为分值").trigger("click");
    await flushPromises();

    await buttonByText(wrapper, "编辑").trigger("click");
    await wrapper.get("form.ph-growth__form").findAll("input")[0]!.setValue("3");
    await wrapper.get("form.ph-growth__form").trigger("submit");
    await flushPromises();

    expect(updatePointBehavior).toHaveBeenCalledTimes(1);
    expect(updatePointBehavior.mock.calls[0]?.[0]).toBe("SIGN_IN");
    const body = updatePointBehavior.mock.calls[0]?.[1] as Record<string, unknown>;
    expect(body.points).toBe(3);
    expect(body.counts_toward_daily_cap).toBe(1);
    expect(body.daily_count_limit).toBe(1);
    // 「不限」要真的传空，不能传 0 或留着旧值——契约把 0 与空都当不限，但形状要一致
    expect(body.monthly_count_limit).toBe(null);
    expect(body.status).toBe(1);
  });

  it("兑换档位：只列平台补贴券（服务者成本券不进选择器）", async () => {
    const { wrapper } = await mountPage(GrowthConfigView, "/admin/points-invite");
    await buttonByText(wrapper, "兑换档位").trigger("click");
    await flushPromises();

    await buttonByText(wrapper, "新增兑换档位").trigger("click");
    await flushPromises();

    const options = wrapper.get("form.ph-growth__form").findAll("option").map((item) => item.text());
    expect(options.some((text) => text.includes("平台补贴券"))).toBe(true);
    expect(options.some((text) => text.includes("服务者成本券"))).toBe(false);
  });

  it("新增兑换档位：提交 name / points_cost / coupon_template_id / status", async () => {
    const { wrapper } = await mountPage(GrowthConfigView, "/admin/points-invite");
    await buttonByText(wrapper, "兑换档位").trigger("click");
    await flushPromises();

    await buttonByText(wrapper, "新增兑换档位").trigger("click");
    const form = wrapper.get("form.ph-growth__form");
    await form.get('input[placeholder="用户看到的兑换项名称"]').setValue("20 元券");
    await form.get('input[placeholder="正整数"]').setValue("500");
    await form.trigger("submit");
    await flushPromises();

    expect(createPointExchangeOption).toHaveBeenCalledTimes(1);
    const body = createPointExchangeOption.mock.calls[0]?.[0] as Record<string, unknown>;
    expect(body.name).toBe("20 元券");
    expect(body.points_cost).toBe(500);
    // 选择器默认选中第一张平台补贴券（沿用服务端给的参照数据，不是编一个 id）
    expect(body.coupon_template_id).toBe(7);
    expect(body.status).toBe(1);
  });

  it("月度阶梯：不预置门槛，空着提交会被本地拦住", async () => {
    const { wrapper } = await mountPage(GrowthConfigView, "/admin/points-invite");
    await buttonByText(wrapper, "月度阶梯").trigger("click");
    await flushPromises();

    await buttonByText(wrapper, "新增档位").trigger("click");
    await wrapper.get("form.ph-growth__form").trigger("submit");
    await flushPromises();

    expect(createPointLadderTier).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain("门槛要 1–1000000 的整数");
  });

  it("月度阶梯：门槛填了但张数留空也拦住（奖励数值要显式填，不预置）", async () => {
    const { wrapper } = await mountPage(GrowthConfigView, "/admin/points-invite");
    await buttonByText(wrapper, "月度阶梯").trigger("click");
    await flushPromises();

    await buttonByText(wrapper, "新增档位").trigger("click");
    const form = wrapper.get("form.ph-growth__form");
    await form.get('input[placeholder="由你定"]').setValue("500");
    await form.trigger("submit");
    await flushPromises();

    expect(createPointLadderTier).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain("发券张数要显式填");
  });

  it("邀请阶梯：改成发券时提交 reward_type=1 与张数", async () => {
    const { wrapper } = await mountPage(GrowthConfigView, "/admin/points-invite");
    await buttonByText(wrapper, "邀请阶梯").trigger("click");
    await flushPromises();

    await buttonByText(wrapper, "配置奖励").trigger("click");
    const form = wrapper.get("form.ph-growth__form");
    await form.findAll("select")[0]!.setValue("coupon");
    await flushPromises();
    // 券模板必须显式选：默认留空（不给一个「随便挑第一张」的隐式决定）
    await form.findAll("select")[1]!.setValue("7");
    await form.trigger("submit");
    await flushPromises();

    expect(updateInviteLadderTier).toHaveBeenCalledTimes(1);
    // 门槛是路径参数（门槛不可改），所以第一个实参就是它
    expect(updateInviteLadderTier.mock.calls[0]?.[0]).toBe(3);
    const body = updateInviteLadderTier.mock.calls[0]?.[1] as Record<string, unknown>;
    expect(body.reward_type).toBe(1);
    expect(body.coupon_template_id).toBe(7);
    expect(body.reward_count).toBe(1);
    expect(body.status).toBe(1);
  });

  it("邀请阶梯：选「不发东西」时 reward_type 传空且不带券模板（达成照记、不发东西）", async () => {
    const { wrapper } = await mountPage(GrowthConfigView, "/admin/points-invite");
    await buttonByText(wrapper, "邀请阶梯").trigger("click");
    await flushPromises();

    await buttonByText(wrapper, "配置奖励").trigger("click");
    const form = wrapper.get("form.ph-growth__form");
    await form.findAll("select")[0]!.setValue("none");
    await flushPromises();
    await form.trigger("submit");
    await flushPromises();

    const body = updateInviteLadderTier.mock.calls[0]?.[1] as Record<string, unknown>;
    expect(body.reward_type).toBe(null);
    expect(body.coupon_template_id).toBe(null);
    // 不发券就不该带张数：带了读的人会以为发了一张
    expect("reward_count" in body).toBe(false);
  });

  it("写失败：后端那句话与请求 ID 都露出来", async () => {
    updatePointsSettings.mockReset().mockRejectedValue(apiFailure(40001, "上限不在 1–1000 之间", "req-400"));

    const { wrapper } = await mountPage(GrowthConfigView, "/admin/points-invite");
    await buttonByText(wrapper, "修改每日上限").trigger("click");
    await wrapper.get('input[placeholder="如 20"]').setValue("30");
    await wrapper.get("form.ph-growth__form").trigger("submit");
    await flushPromises();

    expect(wrapper.text()).toContain("上限不在 1–1000 之间");
    expect(wrapper.text()).toContain("req-400");
  });
});
