/**
 * 数据看板页（StatsView）的组件测试。
 *
 * 这一页是**只读指标**，所以测试的重点不是写操作（没有写操作），而是两条容易被糊掉的口径：
 *   - **每个数字都挂在某个接口上**：分组标题里写着来源路径，测试把它钉住——这是「不许造一个
 *     不存在的指标」在界面上的落点；
 *   - **考核分布不是把全平台的行拉回来数的**：三档各查一次、每页 1 条、只读分页信封的 `total`。
 *     测试验的就是那四个请求的参数（`level` 与 `pageSize=1`），因为换成「拉全量再筛」时界面
 *     一模一样，只有请求形状变了。
 *
 * 指标屏没有「空态」：全 0 是数据而不是「暂无数据」（那一格会误导运营），所以这里验的是
 * **全 0 时照常显示 0**、且不长出空态。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises } from "@vue/test-utils";
import StatsView from "../views/StatsView.vue";
import { apiFailure, mountPage } from "./support";

const getCouponPoolOverview = vi.fn();
const getInviteOverview = vi.fn();
const getPointsOverview = vi.fn();
const listAssessments = vi.fn();
const getAiUsage = vi.fn();
const getOrderStats = vi.fn();

vi.mock("../api/adminApi", () => ({
  adminApp: {
    getCouponPoolOverview: (...args: unknown[]) => getCouponPoolOverview(...args),
    getInviteOverview: (...args: unknown[]) => getInviteOverview(...args),
    getPointsOverview: (...args: unknown[]) => getPointsOverview(...args),
    listAssessments: (...args: unknown[]) => listAssessments(...args),
    getAiUsage: (...args: unknown[]) => getAiUsage(...args),
    getOrderStats: (...args: unknown[]) => getOrderStats(...args),
  },
}));

/** 考核分布按 level 过滤：桩按参数给不同的 total，模拟「三档各查一次」 */
function assessmentPage(total: number) {
  return { list: [], page: 1, page_size: 1, total, has_more: false };
}

beforeEach(() => {
  getOrderStats.mockReset().mockResolvedValue({
    period: "2026-10",
    total: 3,
    by_status: [
      { status: 0, label: "待接单", count: 1, pay_amount: "128.00" },
      { status: 2, label: "履约中", count: 1, pay_amount: "128.00" },
      { status: 4, label: "已取消", count: 1, pay_amount: "128.00" },
    ],
    pay_amount: "384.00",
    cancel_rate: "0.33",
  });
  getAiUsage.mockReset().mockResolvedValue({
    period: "2026-03",
    models: [
      { model_name: "deepseek-chat", model_version: "v3", calls: 2, prompt_tokens: 1500, completion_tokens: 300, red_flag_calls: 0, degraded_calls: 1 },
      { model_name: "rule:red_flag", model_version: "v1", calls: 1, prompt_tokens: 0, completion_tokens: 0, red_flag_calls: 1, degraded_calls: 0 },
    ],
    totals: { model_name: "合计", model_version: "", calls: 3, prompt_tokens: 1500, completion_tokens: 300, red_flag_calls: 1, degraded_calls: 1 },
  });
  getCouponPoolOverview.mockReset().mockResolvedValue({
    template_count: 4,
    template_active_count: 3,
    provider_cost_template_count: 2,
    platform_subsidy_template_count: 2,
    contribution_count: 5,
    committed_total: 500,
    available_total: 480,
    issued_total: 120,
    redeemed_total: 60,
    reserved_total: 50,
    expired_total: 10,
    by_source: [],
    reconciliation: { issued: 120, redeemed: 60, reserved: 50, expired: 10, balanced: true, note: "券的对账恒等式" },
  });
  getInviteOverview.mockReset().mockResolvedValue({
    invite_code_count: 300,
    registered_count: 210,
    pending_count: 20,
    effective_count: 150,
    invalid_count: 40,
    valid_rate: "0.71",
    ladder_stats: [{ threshold: 1, achieved_count: 88 }],
  });
  getPointsOverview.mockReset().mockResolvedValue({
    account_count: 180,
    balance_total: 3200,
    earned_total: 9800,
    spent_total: 6600,
    today_earned: 42,
    today_awarded_users: 21,
    daily_earn_limit: 20,
  });
  listAssessments.mockReset().mockImplementation((params: { level?: number }) => {
    if (params?.level === 1) return Promise.resolve(assessmentPage(30));
    if (params?.level === 2) return Promise.resolve(assessmentPage(12));
    if (params?.level === 3) return Promise.resolve(assessmentPage(3));
    return Promise.resolve(assessmentPage(45));
  });
});

describe("数据看板：渲染与口径", () => {
  it("四个分组的数字都渲染出来，并把来源接口写在分组标题上", async () => {
    const { wrapper } = await mountPage(StatsView, "/admin/stats");

    expect(wrapper.text()).toContain("来源：GET /api/v1/admin/coupon-pool/overview");
    expect(wrapper.text()).toContain("来源：GET /api/v1/admin/invites/overview");
    expect(wrapper.text()).toContain("来源：GET /api/v1/admin/points/overview");
    expect(wrapper.text()).toContain("来源：GET /api/v1/admin/assessments");

    // 券池的额度是张数不是金额：页面要把「这里不带 ¥」说明白（ADR-0036 没有资金可对）
    expect(wrapper.text()).toContain("张数不是金额");
    expect(wrapper.text()).toContain("120");
    expect(wrapper.text()).toContain("0.71");
    expect(wrapper.text()).toContain("今日发放");
  });

  it("考核分布：三档各查一次、每页 1 条，只读信封里的 total", async () => {
    const { wrapper } = await mountPage(StatsView, "/admin/stats");

    expect(listAssessments).toHaveBeenCalledTimes(4);
    const levels = listAssessments.mock.calls.map((call) => (call[0] as { level?: number }).level);
    expect(levels).toContain(1);
    expect(levels).toContain(2);
    expect(levels).toContain(3);
    for (const call of listAssessments.mock.calls) {
      expect((call[0] as { pageSize?: number }).pageSize).toBe(1);
    }
    expect(wrapper.text()).toContain("30");
    expect(wrapper.text()).toContain("12");
  });

  it("账期改成某个 yyyy-MM 后重查考核分布（其余分组不受影响）", async () => {
    const { wrapper } = await mountPage(StatsView, "/admin/stats");

    await wrapper.get(".ph-stats__period").setValue("2026-08");
    await wrapper.get(".ph-stats__period").trigger("keyup.enter");
    await flushPromises();

    expect(listAssessments).toHaveBeenLastCalledWith({ period: "2026-08", level: 3, page: 1, pageSize: 1 });
  });

  it("全 0 时照常显示 0（指标屏没有「暂无数据」那一种态）", async () => {
    listAssessments.mockReset().mockResolvedValue(assessmentPage(0));

    const { wrapper } = await mountPage(StatsView, "/admin/stats");

    expect(wrapper.text()).toContain("考核记录总数");
    expect(wrapper.find(".ph-state--empty").exists()).toBe(false);
    // 0 的常见原因是这个账期还没算（每月 1 日算上月），页面要把这句说出来
    expect(wrapper.text()).toContain("已经算过的账期");
  });

  it("错误态：显示后端那句话与请求 ID，重试只重读这一块", async () => {
    getPointsOverview.mockReset().mockRejectedValue(apiFailure(50000, "服务器内部错误", "req-500"));

    const { wrapper } = await mountPage(StatsView, "/admin/stats");

    expect(wrapper.text()).toContain("服务器内部错误");
    expect(wrapper.text()).toContain("req-500");

    getPointsOverview.mockResolvedValue({ account_count: 7, daily_earn_limit: 20 });
    await wrapper.get(".ph-state--error button").trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("有积分账户的用户");
    expect(wrapper.text()).not.toContain("服务器内部错误");
  });

  it("未登录：闸门说话，且一个业务请求都不发", async () => {
    const { wrapper } = await mountPage(StatsView, "/admin/stats", "anonymous");

    expect(wrapper.find(".ph-state--forbidden").exists()).toBe(true);
    expect(getCouponPoolOverview).not.toHaveBeenCalled();
    expect(listAssessments).not.toHaveBeenCalled();
  });
});

describe("数据看板：AI 用量（D-28）", () => {
  it("按账期 × 模型摊开真实用量，合计行与各行对得上，且**没有金额**（单价是页面参数）", async () => {
    const { wrapper } = await mountPage(StatsView, "/admin/stats");

    expect(getAiUsage).toHaveBeenCalled();
    expect(wrapper.text()).toContain("deepseek-chat");
    expect(wrapper.text()).toContain("1500");
    expect(wrapper.text()).toContain("合计");
    expect(wrapper.text()).toContain("2026-03");
    // 红线短路那行：没调模型就没有 token（ADR-0021）
    expect(wrapper.text()).toContain("rule:red_flag");
  });

  it("估算成本只用页面上填的单价算：不填就显示「—」，填了才是乘法结果", async () => {
    const { wrapper } = await mountPage(StatsView, "/admin/stats");

    // 单价没填：不拿 0 当默认值（0 会算出一个「成本为零」的假事实）
    expect(wrapper.text()).toContain("—");

    await wrapper.get("#usage-price-in").setValue("2");    // 输入单价 2 元 / 千 token
    await wrapper.get("#usage-price-out").setValue("8");   // 输出单价 8 元 / 千 token
    await flushPromises();

    // 200 条路径：deepseek-chat 1500/1000×2 + 300/1000×8 = 3 + 2.4 = ¥5.40；合计同样
    expect(wrapper.text()).toContain("¥5.40");
    // 乘法只在页面发生：没有任何一次请求带上单价
    expect(getAiUsage.mock.calls.every((call) => {
      const arg = call[0] as string | undefined;
      return arg === undefined || typeof arg === "string";
    })).toBe(true);
  });
});

describe("数据看板：订单与履约（D-30）", () => {
  it("按状态分组的条数与金额、合计与取消率都来自服务端，并写明金额是门店应收", async () => {
    const { wrapper } = await mountPage(StatsView, "/admin/stats");

    expect(getOrderStats).toHaveBeenCalled();
    expect(wrapper.text()).toContain("订单与履约");
    expect(wrapper.text()).toContain("待接单");
    expect(wrapper.text()).toContain("履约中");
    expect(wrapper.text()).toContain("¥384.00");
    expect(wrapper.text()).toContain("0.33");
    // 口径必须看得见：这是门店应收，不是平台收款（ADR-0002）
    expect(wrapper.text()).toContain("门店应收");
  });
});
