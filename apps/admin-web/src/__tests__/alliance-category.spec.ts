/**
 * 服务者审核页里的「联盟分类维度」分段（一期验收标准的「服务者联盟分类：分类维度维护与归属」那一项）。
 *
 * 验四件事，都是「一眼看过去没问题、实际串了」的类型：
 *   - 分段真的接上了（点「联盟分类维度」才渲染面板，默认那一段不受影响）；
 *   - 列表把服务端给的取值、编码、归属门店数与启停态原样摆出来——**取值列显示的是 `id`**，
 *     因为那就是 `provider.category` 里存的数，运营指派时用的也是它；
 *   - 停用是**独立的动作**（不走编辑表单）：点一下发的是 status 接口，传 0；
 *   - 编辑路径上**编码字段是禁用**的（契约写明修改会忽略 code，界面就不该假装它能改）。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises } from "@vue/test-utils";
import ProviderAuditView from "../views/ProviderAuditView.vue";
import { buttonByText, mountPage } from "./support";

const listApplications = vi.fn();
const listAllianceCategories = vi.fn();
const createAllianceCategory = vi.fn();
const updateAllianceCategory = vi.fn();
const updateAllianceCategoryStatus = vi.fn();

vi.mock("../api/adminApi", () => ({
  adminApp: {
    listApplications: (...args: unknown[]) => listApplications(...args),
    listAllianceCategories: (...args: unknown[]) => listAllianceCategories(...args),
    createAllianceCategory: (...args: unknown[]) => createAllianceCategory(...args),
    updateAllianceCategory: (...args: unknown[]) => updateAllianceCategory(...args),
    updateAllianceCategoryStatus: (...args: unknown[]) => updateAllianceCategoryStatus(...args),
  },
}));

const SEEDED = [
  { id: 1, code: "DIRECT_PEER", name: "直接同业", description: "同业门店", sort_order: 1, enabled: 1, provider_count: 4, updated_at: "2026-09-01 10:00:00" },
  { id: 2, code: "DIRECT_CROSS", name: "直接异业", description: null, sort_order: 2, enabled: 0, provider_count: 0, updated_at: "2026-09-01 10:00:00" },
];

/** 一页结果的形状（契约的 PageResult）。 */
function page(list: unknown[]) {
  return { list, page: 1, page_size: 20, total: list.length, has_more: false };
}

beforeEach(() => {
  listApplications.mockReset().mockResolvedValue(page([]));
  listAllianceCategories.mockReset().mockResolvedValue(SEEDED);
  createAllianceCategory.mockReset().mockResolvedValue(SEEDED[0]);
  updateAllianceCategory.mockReset().mockResolvedValue(SEEDED[0]);
  updateAllianceCategoryStatus.mockReset().mockResolvedValue(SEEDED[0]);
});

describe("联盟分类维度：分段与列表", () => {
  it("默认在「入驻与资质审核」分段，切到联盟分类才去读维度", async () => {
    const { wrapper } = await mountPage(ProviderAuditView, "/admin/providers");

    expect(listAllianceCategories).not.toHaveBeenCalled();

    await buttonByText(wrapper, "联盟分类维度").trigger("click");
    await flushPromises();

    expect(listAllianceCategories).toHaveBeenCalledTimes(1);
    expect(wrapper.text()).toContain("直接同业");
    // 取值列就是 provider.category 里存的数：运营指派时用的也是它
    expect(wrapper.text()).toContain("DIRECT_PEER");
    expect(wrapper.text()).toContain("同业门店");
  });

  it("归属门店数与启停态原样摆出来：停用档也显示（它仍承载既有归属）", async () => {
    const { wrapper } = await mountPage(ProviderAuditView, "/admin/providers");
    await buttonByText(wrapper, "联盟分类维度").trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("直接异业");
    expect(wrapper.text()).toContain("停用");
    // 「停用不移动既有归属」这句话必须在页面上，否则运营不敢点
    expect(wrapper.text()).toContain("停用不移动既有归属");
  });
});

describe("联盟分类维度：写操作", () => {
  it("停用是独立动作：点一下发 status 接口、传 0", async () => {
    const { wrapper } = await mountPage(ProviderAuditView, "/admin/providers");
    await buttonByText(wrapper, "联盟分类维度").trigger("click");
    await flushPromises();

    await buttonByText(wrapper, "停用").trigger("click");
    await flushPromises();

    expect(updateAllianceCategoryStatus).toHaveBeenCalledWith(1, 0);
    expect(wrapper.text()).toContain("已停用");
  });

  it("编辑：编码字段禁用（契约里修改路径会忽略 code）", async () => {
    const { wrapper } = await mountPage(ProviderAuditView, "/admin/providers");
    await buttonByText(wrapper, "联盟分类维度").trigger("click");
    await flushPromises();

    await buttonByText(wrapper, "编辑").trigger("click");
    await flushPromises();

    const codeInput = wrapper.find("input[placeholder='DIRECT_PEER']");
    expect(codeInput.exists()).toBe(true);
    expect(codeInput.attributes("disabled")).toBeDefined();
  });

  it("新建：表单拼成契约要求的形状（编码、名称、说明、顺序）", async () => {
    const { wrapper } = await mountPage(ProviderAuditView, "/admin/providers");
    await buttonByText(wrapper, "联盟分类维度").trigger("click");
    await flushPromises();

    await buttonByText(wrapper, "新建维度").trigger("click");
    await flushPromises();

    const inputs = wrapper.findAll(".ph-alliance__form input");
    await inputs[0].setValue("TEST_GROUP");
    await inputs[1].setValue("测试联盟");
    await inputs[3].setValue("用例说明");
    await wrapper.get("form.ph-alliance__form").trigger("submit");
    await flushPromises();

    expect(createAllianceCategory).toHaveBeenCalledWith({
      code: "TEST_GROUP",
      name: "测试联盟",
      description: "用例说明",
      sort_order: 0,
    });
  });

  it("本地校验先拦住格式错的编码（不发请求）", async () => {
    const { wrapper } = await mountPage(ProviderAuditView, "/admin/providers");
    await buttonByText(wrapper, "联盟分类维度").trigger("click");
    await flushPromises();

    await buttonByText(wrapper, "新建维度").trigger("click");
    await flushPromises();

    const inputs = wrapper.findAll(".ph-alliance__form input");
    await inputs[0].setValue("测试编码");
    await inputs[1].setValue("测试联盟");
    await wrapper.get("form.ph-alliance__form").trigger("submit");
    await flushPromises();

    expect(createAllianceCategory).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain("大写字母开头");
  });
});
