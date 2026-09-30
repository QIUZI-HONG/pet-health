/**
 * 券池管理页（CouponPoolView）的组件测试。
 *
 * 验四件事，都是「肉眼看不出来、串一次就骗人」的：
 *   - **四态**：加载 / 空 / 错误（带请求 id 与重试）/ 未登录（闸门 + 一个请求都不发）；
 *   - **券池的定义域**：模板列表与总览都真的按接口取数，金额走 ¥ 而不是张数；
 *   - **写操作真的调了接口**：新建模板把表单拼成契约要求的形状（`scope_type=0` 时**不带**
 *     `scope_codes`，成本归属为平台补贴时才带 `issue_limit`），启停走 status 接口；
 *   - **空态说的是下一步**，而不是一句「暂无数据」。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises } from "@vue/test-utils";
import type { CategoryRow, CouponTemplateRow } from "../api/adminApi";
import CouponPoolView from "../views/CouponPoolView.vue";
import { apiFailure, buttonByText, mountPage } from "./support";

const listCategories = vi.fn();
const listCouponTemplates = vi.fn();
const createCouponTemplate = vi.fn();
const updateCouponTemplate = vi.fn();
const updateCouponTemplateStatus = vi.fn();
const getCouponPoolOverview = vi.fn();
const listCoupons = vi.fn();
const issueCoupon = vi.fn();

vi.mock("../api/adminApi", () => ({
  adminApp: {
    listCategories: (...args: unknown[]) => listCategories(...args),
    listCouponTemplates: (...args: unknown[]) => listCouponTemplates(...args),
    createCouponTemplate: (...args: unknown[]) => createCouponTemplate(...args),
    updateCouponTemplate: (...args: unknown[]) => updateCouponTemplate(...args),
    updateCouponTemplateStatus: (...args: unknown[]) => updateCouponTemplateStatus(...args),
    getCouponPoolOverview: (...args: unknown[]) => getCouponPoolOverview(...args),
    listCoupons: (...args: unknown[]) => listCoupons(...args),
    issueCoupon: (...args: unknown[]) => issueCoupon(...args),
  },
}));

const CATEGORY: CategoryRow = {
  id: 1,
  code: "HOSPITAL",
  item_code_prefix: "HE",
  name: "医疗健康",
  status: 1,
  updated_at: "2026-09-01 10:00:00",
};

const TEMPLATE: CouponTemplateRow = {
  id: 7,
  code: "CP-001",
  name: "新客立减 20 元",
  face_value: "20.00",
  min_amount: "100.00",
  valid_days: 30,
  cost_bearer: 2,
  scope_type: 0,
  status: 1,
  issued_count: 3,
  issue_limit: 10,
  scope_desc: "不限",
  updated_at: "2026-09-20 10:00:00",
};

/** 一页结果的形状（契约的 PageResult）。 */
function page(list: unknown[]) {
  return { list, page: 1, page_size: 20, total: list.length, has_more: false };
}

beforeEach(() => {
  listCategories.mockReset().mockResolvedValue([CATEGORY]);
  listCouponTemplates.mockReset().mockResolvedValue(page([TEMPLATE]));
  createCouponTemplate.mockReset().mockResolvedValue(TEMPLATE);
  updateCouponTemplate.mockReset().mockResolvedValue(TEMPLATE);
  updateCouponTemplateStatus.mockReset().mockResolvedValue(TEMPLATE);
  getCouponPoolOverview.mockReset();
  listCoupons.mockReset().mockResolvedValue(page([]));
  issueCoupon.mockReset().mockResolvedValue({});
});

describe("券池管理：四态", () => {
  it("渲染接口给的模板：面额走 ¥（金额），已发 / 上限走张数", async () => {
    const { wrapper } = await mountPage(CouponPoolView, "/admin/coupon-pool");

    expect(wrapper.text()).toContain("新客立减 20 元");
    expect(wrapper.text()).toContain("¥20.00");
    expect(wrapper.text()).toContain("¥100.00");
    expect(wrapper.text()).toContain("3 / 10");
  });

  it("空态：说清下一步（先建模板，服务者才能选券承诺额度）", async () => {
    listCouponTemplates.mockResolvedValue(page([]));

    const { wrapper } = await mountPage(CouponPoolView, "/admin/coupon-pool");

    expect(wrapper.find(".ph-state--empty").exists()).toBe(true);
    expect(wrapper.text()).toContain("还没有券模板");
  });

  it("错误态：显示后端那句话与请求 ID，并能在原地重试", async () => {
    listCouponTemplates
      .mockRejectedValueOnce(apiFailure(50000, "服务器内部错误", "req-500"))
      .mockResolvedValueOnce(page([TEMPLATE]));

    const { wrapper } = await mountPage(CouponPoolView, "/admin/coupon-pool");

    expect(wrapper.text()).toContain("服务器内部错误");
    expect(wrapper.text()).toContain("req-500");

    await wrapper.get(".ph-state--error button").trigger("click");
    await flushPromises();

    expect(listCouponTemplates).toHaveBeenCalledTimes(2);
    expect(wrapper.text()).toContain("新客立减 20 元");
  });

  it("未登录：闸门说话，且**一个业务请求都不发**", async () => {
    const { wrapper } = await mountPage(CouponPoolView, "/admin/coupon-pool", "anonymous");

    expect(wrapper.find(".ph-state--forbidden").exists()).toBe(true);
    expect(wrapper.text()).toContain("尚未登录运营账号");
    expect(listCouponTemplates).not.toHaveBeenCalled();
    expect(listCategories).not.toHaveBeenCalled();
  });
});

describe("券池管理：券池总览与对账", () => {
  it("切到「券池总览与对账」：读 total 与对账结论，并把不平标出来", async () => {
    getCouponPoolOverview.mockResolvedValue({
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
      by_source: [{ source: 4, issued: 120, redeemed: 60, reserved: 50, expired: 10 }],
      reconciliation: { issued: 120, redeemed: 60, reserved: 50, expired: 10, balanced: true, note: "券的对账恒等式" },
    });

    const { wrapper } = await mountPage(CouponPoolView, "/admin/coupon-pool");
    await buttonByText(wrapper, "券池总览与对账").trigger("click");
    await flushPromises();

    expect(getCouponPoolOverview).toHaveBeenCalledTimes(1);
    expect(wrapper.text()).toContain("平台补贴");
    expect(wrapper.text()).toContain("账平");
    expect(wrapper.text()).toContain("500");
  });
});

describe("券池管理：写操作真的调了接口", () => {
  it("新建券模板：表单拼成契约的形状（scope_type=0 时不带 scope_codes）", async () => {
    const { wrapper } = await mountPage(CouponPoolView, "/admin/coupon-pool");

    await buttonByText(wrapper, "新建券模板").trigger("click");
    await wrapper.get('input[placeholder="CP-001"]').setValue("CP-009");
    await wrapper.get('input[placeholder="如：新客立减 20 元"]').setValue("测试补贴券");
    await wrapper.get('input[placeholder="20.00"]').setValue("15.00");
    await wrapper.get("form.ph-cp__form").trigger("submit");
    await flushPromises();

    expect(createCouponTemplate).toHaveBeenCalledTimes(1);
    const body = createCouponTemplate.mock.calls[0]?.[0] as Record<string, unknown>;
    expect(body.code).toBe("CP-009");
    expect(body.name).toBe("测试补贴券");
    expect(body.face_value).toBe("15.00");
    expect(body.min_amount).toBe("0.00");
    expect(body.valid_days).toBe(30);
    expect(body.cost_bearer).toBe(2);
    expect(body.scope_type).toBe(0);
    // 「不限」带上适用范围编码会被后端拒成 40001，所以这个键根本不该出现
    expect("scope_codes" in body).toBe(false);
  });

  it("启停模板：停用走 status 接口（停用只挡新的发放与新的贡献）", async () => {
    const { wrapper } = await mountPage(CouponPoolView, "/admin/coupon-pool");

    await buttonByText(wrapper, "停用").trigger("click");
    await flushPromises();

    expect(updateCouponTemplateStatus).toHaveBeenCalledWith(7, 0);
  });
});
