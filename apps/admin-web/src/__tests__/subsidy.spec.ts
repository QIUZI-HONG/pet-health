/**
 * 补贴券窗口页（SubsidyView）的组件测试。
 *
 * 这一页最要紧的一条是**「契约里没有窗口实体」被如实处理**：没有起止时间输入框，窗口由
 * 「发放上限 + 模板启停」两件事表达。所以测试除了四态与写操作，还要盯住「关停窗口 = 停用模板」
 * 这个映射真的走了模板 status 接口，而不是前端自己记了一个开关。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises } from "@vue/test-utils";
import type { CouponTemplateRow } from "../api/adminApi";
import SubsidyView from "../views/SubsidyView.vue";
import { apiFailure, buttonByText, mountPage } from "./support";

const listCouponTemplates = vi.fn();
const updateCouponTemplateStatus = vi.fn();
const listCoupons = vi.fn();
const issueCoupon = vi.fn();

vi.mock("../api/adminApi", () => ({
  adminApp: {
    listCouponTemplates: (...args: unknown[]) => listCouponTemplates(...args),
    updateCouponTemplateStatus: (...args: unknown[]) => updateCouponTemplateStatus(...args),
    listCoupons: (...args: unknown[]) => listCoupons(...args),
    issueCoupon: (...args: unknown[]) => issueCoupon(...args),
  },
}));

const SUBSIDY_TEMPLATE: CouponTemplateRow = {
  id: 9,
  code: "CP-002",
  name: "平台补贴 30 元",
  face_value: "30.00",
  min_amount: "200.00",
  valid_days: 15,
  cost_bearer: 2,
  scope_type: 1,
  scope_codes: ["HOSPITAL"],
  scope_desc: "限医疗健康",
  status: 1,
  issued_count: 3,
  issue_limit: 10,
  updated_at: "2026-09-25 09:00:00",
};

function page(list: unknown[]) {
  return { list, page: 1, page_size: 20, total: list.length, has_more: false };
}

beforeEach(() => {
  // 页面列表与发放面板都会读模板：同一个桩同时给两处（面板要的是只含启用中的平台补贴券）
  listCouponTemplates.mockReset().mockResolvedValue(page([SUBSIDY_TEMPLATE]));
  updateCouponTemplateStatus.mockReset().mockResolvedValue(SUBSIDY_TEMPLATE);
  listCoupons.mockReset().mockResolvedValue(page([]));
  issueCoupon.mockReset().mockResolvedValue({});
});

describe("补贴券窗口：四态", () => {
  it("渲染额度进度：已发 / 上限与百分比（上限为空时显示「不限」）", async () => {
    const { wrapper } = await mountPage(SubsidyView, "/admin/subsidy");

    expect(wrapper.text()).toContain("平台补贴 30 元");
    expect(wrapper.text()).toContain("3 / 10");
    expect(wrapper.text()).toContain("30%");
    expect(wrapper.text()).toContain("¥30.00");
  });

  it("空态：说清「补贴券的定义就是一个平台补贴模板」并指路", async () => {
    listCouponTemplates.mockResolvedValue(page([]));

    const { wrapper } = await mountPage(SubsidyView, "/admin/subsidy");

    expect(wrapper.text()).toContain("还没有平台补贴券模板");
    expect(wrapper.text()).toContain("去「券池管理」建一个");
  });

  it("错误态：显示后端那句话与请求 ID，并能在原地重试", async () => {
    // 这一页有**两处**会读模板（窗口列表 + 发放面板的模板下拉），所以先让所有请求都失败，
    // 再把桩换成成功、点重试——这样断言的成败与「谁先发请求」无关
    listCouponTemplates.mockReset().mockRejectedValue(apiFailure(50000, "服务器内部错误", "req-500"));

    const { wrapper } = await mountPage(SubsidyView, "/admin/subsidy");

    expect(wrapper.text()).toContain("服务器内部错误");
    expect(wrapper.text()).toContain("req-500");

    listCouponTemplates.mockResolvedValue(page([SUBSIDY_TEMPLATE]));
    await wrapper.get(".ph-state--error button").trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("平台补贴 30 元");
    // 发放面板的模板下拉是**另一处**请求，它有自己的重试：点它才清掉那一处错误
    await buttonByText(wrapper, "重新加载模板").trigger("click");
    await flushPromises();

    expect(wrapper.text()).not.toContain("服务器内部错误");
  });

  it("未登录：闸门说话，且一个业务请求都不发", async () => {
    const { wrapper } = await mountPage(SubsidyView, "/admin/subsidy", "anonymous");

    expect(wrapper.find(".ph-state--forbidden").exists()).toBe(true);
    expect(listCouponTemplates).not.toHaveBeenCalled();
  });
});

describe("补贴券窗口：写操作真的调了接口", () => {
  it("关停窗口 = 停用模板（停发但已发出的券照常核销）", async () => {
    const { wrapper } = await mountPage(SubsidyView, "/admin/subsidy");

    await buttonByText(wrapper, "关停窗口").trigger("click");
    await flushPromises();

    expect(updateCouponTemplateStatus).toHaveBeenCalledWith(9, 0);
  });

  it("定向发放：选模板 + 用户 id → POST /coupons（一次一个用户）", async () => {
    const { wrapper } = await mountPage(SubsidyView, "/admin/subsidy");

    await wrapper.get(".ph-issue__form select").setValue("9");
    await wrapper.get('input[placeholder="如：1001, 1002"]').setValue("1001");
    await wrapper.get("form.ph-issue__form").trigger("submit");
    await flushPromises();

    expect(issueCoupon).toHaveBeenCalledWith({ user_id: 1001, template_id: 9, remark: null });
  });
});
