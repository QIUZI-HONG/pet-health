/**
 * 券管理的组件测试。
 *
 * 这一页是「承诺制」的界面：券不由服务者发放，服务者只能承诺可核销额度。所以测试盯的是
 * **契约里那三条会被拒绝的规则在界面上有没有前置**：
 *   - 承诺是新建（`POST`），调整是 `PUT`（同一模板不新建第二条）；
 *   - 调整额度**不能低于「已核销 + 占用中」**——低于下限时应当不发请求（后端会回 40001）；
 *   - 撤回只收回未发放的部分（`DELETE`），要二次确认。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises } from "@vue/test-utils";
import type { ContributionRow, CouponContributionView, CouponRow, TemplateRow } from "../api/providerApi";
import CouponsView from "../views/CouponsView.vue";
import { apiFailure, mountPage } from "./support";

const listCouponContributions = vi.fn();
const listCouponTemplates = vi.fn();
const createCouponContribution = vi.fn();
const updateCouponContribution = vi.fn();
const withdrawCouponContribution = vi.fn();
const getCouponContribution = vi.fn();
const listContributionCoupons = vi.fn();

vi.mock("../api/providerApi", () => ({
  providerApp: {
    listCouponContributions: (...args: unknown[]) => listCouponContributions(...args),
    listCouponTemplates: (...args: unknown[]) => listCouponTemplates(...args),
    createCouponContribution: (...args: unknown[]) => createCouponContribution(...args),
    updateCouponContribution: (...args: unknown[]) => updateCouponContribution(...args),
    withdrawCouponContribution: (...args: unknown[]) => withdrawCouponContribution(...args),
    getCouponContribution: (...args: unknown[]) => getCouponContribution(...args),
    listContributionCoupons: (...args: unknown[]) => listContributionCoupons(...args),
  },
}));

const CONTRIBUTION: ContributionRow = {
  id: 7,
  template_id: 5,
  template_code: "CP-001",
  template_name: "基础体检立减 30 元",
  face_value: "30.00",
  min_amount: "100.00",
  scope_desc: "限分类：医院",
  total_count: 50,
  issued_count: 20,
  redeemed_count: 18,
  reserved_count: 2,
  expired_count: 0,
  available_count: 30,
  completion_rate: "0.36",
  status: 1,
  created_at: "2026-09-01 10:00:00",
  updated_at: "2026-09-20 10:00:00",
};

const TEMPLATE: TemplateRow = {
  id: 5,
  code: "CP-001",
  name: "基础体检立减 30 元",
  face_value: "30.00",
  min_amount: "100.00",
  valid_days: 30,
  cost_bearer: 1,
  scope_desc: "限分类：医院",
  issued_count: 120,
  status: 1,
};

const COUPON: CouponRow = {
  id: 91,
  code: "C-20260930-0001",
  user_id: 33,
  template_id: 5,
  template_name: "基础体检立减 30 元",
  face_value: "30.00",
  min_amount: "100.00",
  source: 1,
  status: 3,
  issued_at: "2026-09-10 09:00:00",
  valid_until: "2026-10-10 23:59:59",
  redeemed_at: "2026-09-30 14:02:00",
};

function page<T>(list: T[]) {
  return { list, page: 1, page_size: 20, total: list.length, has_more: false };
}

beforeEach(() => {
  listCouponContributions.mockReset().mockResolvedValue(page([CONTRIBUTION]));
  listCouponTemplates.mockReset().mockResolvedValue(page([TEMPLATE]));
  createCouponContribution.mockReset().mockResolvedValue(CONTRIBUTION as CouponContributionView);
  updateCouponContribution.mockReset().mockResolvedValue({ ...CONTRIBUTION, total_count: 60 } as CouponContributionView);
  withdrawCouponContribution.mockReset().mockResolvedValue({ ...CONTRIBUTION, available_count: 0, status: 2 } as CouponContributionView);
  getCouponContribution.mockReset().mockResolvedValue({
    ...CONTRIBUTION,
    logs: [
      { id: 1, action: 1, total_count: 50, remark: "开业承诺", operator_id: 9, created_at: "2026-09-01 10:00:00" },
      { id: 2, action: 2, total_count: 50, remark: "维持", operator_id: 9, created_at: "2026-09-20 10:00:00" },
    ],
  } as CouponContributionView);
  listContributionCoupons.mockReset().mockResolvedValue(page([COUPON]));
});

describe("券管理：我的贡献", () => {
  it("渲染额度账与完成率（0.36 的比率字符串 → 36%），并说明「可发放」的口径", async () => {
    const { wrapper } = await mountPage(CouponsView, "/b/coupons");

    expect(wrapper.text()).toContain("基础体检立减 30 元");
    expect(wrapper.text()).toContain("¥30.00");
    expect(wrapper.text()).toContain("50 / 20 / 18 / 2 / 0 / 30");
    expect(wrapper.text()).toContain("36%");
    expect(wrapper.text()).toContain("生效中");
    expect(listCouponContributions).toHaveBeenCalledWith({ status: undefined, page: 1, pageSize: 20 }, expect.anything());
  });

  it("空态：说明去券池挑一张券，而不是「暂无数据」", async () => {
    listCouponContributions.mockResolvedValue(page([]));

    const { wrapper } = await mountPage(CouponsView, "/b/coupons");

    expect(wrapper.find(".ph-state--empty").exists()).toBe(true);
    expect(wrapper.text()).toContain("还没有贡献任何券");
  });

  it("错误态：显示后端那句话与请求 ID，并能重试", async () => {
    listCouponContributions.mockRejectedValueOnce(apiFailure(50000, "服务器内部错误", "req-500"));

    const { wrapper } = await mountPage(CouponsView, "/b/coupons");

    expect(wrapper.text()).toContain("服务器内部错误");
    expect(wrapper.text()).toContain("req-500");

    listCouponContributions.mockResolvedValueOnce(page([CONTRIBUTION]));
    await wrapper.get(".ph-state--error button").trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("基础体检立减 30 元");
  });

  it("未登录：闸门先拦，券的请求一个都不发", async () => {
    const { wrapper } = await mountPage(CouponsView, "/b/coupons", "anonymous");

    expect(wrapper.text()).toContain("尚未登录服务者账号");
    expect(listCouponContributions).not.toHaveBeenCalled();
  });

  it("展开明细：拉额度流水与已发券明细，并把领券人显示成「用户 #id」（服务者看不到身份）", async () => {
    const { wrapper } = await mountPage(CouponsView, "/b/coupons");

    const detail = wrapper.findAll(".ph-table__action").find((button) => button.text() === "明细 / 流水");
    await detail?.trigger("click");
    await flushPromises();

    expect(getCouponContribution).toHaveBeenCalledWith(7, expect.anything());
    expect(listContributionCoupons).toHaveBeenCalledWith(
      7,
      { status: undefined, page: 1, pageSize: 10 },
      expect.anything(),
    );
    expect(wrapper.text()).toContain("承诺");
    expect(wrapper.text()).toContain("C-20260930-0001");
    expect(wrapper.text()).toContain("用户 #33");
    expect(wrapper.text()).toContain("领券人的身份不在服务者的可见范围内");
  });
});

describe("券管理：券池模板与承诺", () => {
  it("券池模板：只列服务者成本券（页面说明写明平台补贴券不在池子里）", async () => {
    const { wrapper } = await mountPage(CouponsView, "/b/coupons");

    const pool = wrapper.findAll(".ph-tabs__item").find((tab) => tab.text() === "券池模板");
    await pool?.trigger("click");
    await flushPromises();

    expect(listCouponTemplates).toHaveBeenCalledWith({ keyword: undefined, page: 1, pageSize: 20 }, expect.anything());
    expect(wrapper.text()).toContain("CP-001");
    expect(wrapper.text()).toContain("发放后 30 天内有效");
    expect(wrapper.text()).toContain("服务者成本的券");
  });

  it("从模板贡献额度：POST 带 template_id 与张数（remark 为空时传 null）", async () => {
    const { wrapper } = await mountPage(CouponsView, "/b/coupons");
    const pool = wrapper.findAll(".ph-tabs__item").find((tab) => tab.text() === "券池模板");
    await pool?.trigger("click");
    await flushPromises();

    const contribute = wrapper.findAll(".ph-table__action").find((button) => button.text() === "贡献这张券");
    await contribute?.trigger("click");
    await flushPromises();

    await wrapper.get(".ph-coupons__count input").setValue("80");
    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(createCouponContribution).toHaveBeenCalledWith({ template_id: 5, total_count: 80, remark: null });
    expect(wrapper.text()).toContain("已承诺额度：可发放张数立刻生效");
  });

  it("额度不是正整数时不发请求（后端会回 40001，先在这里拦住）", async () => {
    const { wrapper } = await mountPage(CouponsView, "/b/coupons");
    const pool = wrapper.findAll(".ph-tabs__item").find((tab) => tab.text() === "券池模板");
    await pool?.trigger("click");
    await flushPromises();

    await wrapper.findAll(".ph-table__action").find((button) => button.text() === "贡献这张券")?.trigger("click");
    await flushPromises();
    await wrapper.get(".ph-coupons__count input").setValue("0");
    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(createCouponContribution).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain("额度要是正整数");
  });

  it("调整额度低于「已核销 + 占用中」时**不发请求**，并把下限算给用户看", async () => {
    const { wrapper } = await mountPage(CouponsView, "/b/coupons");

    await wrapper.findAll(".ph-table__action").find((button) => button.text() === "调整额度")?.trigger("click");
    await flushPromises();
    expect(wrapper.text()).toContain("不能低于 20（已核销 18 + 占用中 2）");

    await wrapper.get(".ph-coupons__count input").setValue("19");
    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(updateCouponContribution).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain("已经发到用户手里的券不能被收回");
  });

  it("调整额度合法时 PUT 带上原来的 template_id（同一个模板只留一条额度账）", async () => {
    const { wrapper } = await mountPage(CouponsView, "/b/coupons");

    await wrapper.findAll(".ph-table__action").find((button) => button.text() === "调整额度")?.trigger("click");
    await flushPromises();
    await wrapper.get(".ph-coupons__count input").setValue("60");
    await wrapper.get(".ph-coupons__remark input").setValue("旺季加量");
    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(updateCouponContribution).toHaveBeenCalledWith(7, {
      template_id: 5,
      total_count: 60,
      remark: "旺季加量",
    });
  });

  it("撤回要二次确认，确认后调用 DELETE（只收回未发放的部分）", async () => {
    const { wrapper } = await mountPage(CouponsView, "/b/coupons");

    await wrapper.findAll(".ph-table__action").find((button) => button.text() === "撤回")?.trigger("click");
    await flushPromises();
    expect(wrapper.text()).toContain("撤回未发放的额度：基础体检立减 30 元");
    expect(withdrawCouponContribution).not.toHaveBeenCalled();

    await wrapper.findAll("button").find((button) => button.text() === "确认撤回")?.trigger("click");
    await flushPromises();

    expect(withdrawCouponContribution).toHaveBeenCalledWith(7);
    expect(wrapper.text()).toContain("已撤回未发放的额度");
  });
});
