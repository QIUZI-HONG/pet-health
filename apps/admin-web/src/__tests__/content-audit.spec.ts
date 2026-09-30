/**
 * 内容审核页（ContentAuditView）的组件测试。
 *
 * 这一页的形状与别的页不同：**三种内容各一个队列**（契约要求 `content_type` 必填，三种内容各住一张表），
 * 所以测试要盯住「切分段换了的是 `content_type`，不是前端过滤」——如果前端拉一坨再自己筛，
 * 分页的总数就会是错的，而界面上看不出任何区别。
 *
 * 写操作两个（通过 / 驳回），都各占一个用例：`useSubmitAction` 的 2 秒节流是**每个动作一份**的，
 * 但一个用例里连续两次写会被 2 秒冷却挡掉（那是真实行为，不是测试噪音），拆开写更诚实。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises } from "@vue/test-utils";
import type { ContentRow, WordRow } from "../api/adminApi";
import ContentAuditView from "../views/ContentAuditView.vue";
import { apiFailure, buttonByText, mountPage } from "./support";

const listContents = vi.fn();
const approveContent = vi.fn();
const rejectContent = vi.fn();
const listSensitiveWords = vi.fn();
const createSensitiveWord = vi.fn();
const updateSensitiveWord = vi.fn();

vi.mock("../api/adminApi", () => ({
  adminApp: {
    listContents: (...args: unknown[]) => listContents(...args),
    approveContent: (...args: unknown[]) => approveContent(...args),
    rejectContent: (...args: unknown[]) => rejectContent(...args),
    listSensitiveWords: (...args: unknown[]) => listSensitiveWords(...args),
    createSensitiveWord: (...args: unknown[]) => createSensitiveWord(...args),
    updateSensitiveWord: (...args: unknown[]) => updateSensitiveWord(...args),
  },
}));

const CARD: ContentRow = {
  content_type: 1,
  content_type_name: "经验卡片",
  content_id: 5,
  author_id: 1001,
  title: "猫咪换粮的一周记录",
  content: "第一天混了 1/4 新粮，第三天到一半，第七天完全换过来。",
  status: 0,
  status_name: "待审",
  created_at: "2026-09-30 09:00:00",
};

const WORD: WordRow = {
  id: 3,
  word: "加微信",
  category: "引流",
  enabled: true,
  remark: "内容里留联系方式的一律拦",
  updated_at: "2026-09-28 10:00:00",
};

function page(list: unknown[]) {
  return { list, page: 1, page_size: 20, total: list.length, has_more: false };
}

beforeEach(() => {
  listContents.mockReset().mockResolvedValue(page([CARD]));
  approveContent.mockReset().mockResolvedValue({ ...CARD, status: 1 });
  rejectContent.mockReset().mockResolvedValue({ ...CARD, status: 2 });
  listSensitiveWords.mockReset().mockResolvedValue(page([WORD]));
  createSensitiveWord.mockReset().mockResolvedValue(WORD);
  updateSensitiveWord.mockReset().mockResolvedValue(WORD);
});

describe("内容审核：队列", () => {
  it("默认查经验卡片（content_type=1）且默认只看待审，正文给全文", async () => {
    const { wrapper } = await mountPage(ContentAuditView, "/admin/content");

    expect(listContents).toHaveBeenCalledWith({ contentType: 1, status: 0, authorId: undefined, page: 1, pageSize: 20 }, expect.anything());
    expect(wrapper.text()).toContain("猫咪换粮的一周记录");
    expect(wrapper.text()).toContain("第七天完全换过来");
  });

  it("切到「回答」分段：换的是 content_type，不是前端过滤", async () => {
    const { wrapper } = await mountPage(ContentAuditView, "/admin/content");

    await buttonByText(wrapper, "回答").trigger("click");
    await flushPromises();

    expect(listContents).toHaveBeenLastCalledWith({ contentType: 3, status: 0, authorId: undefined, page: 1, pageSize: 20 }, expect.anything());
  });

  it("空态：说清「被机审拦住的内容在已驳回那一档」这条处置路径", async () => {
    listContents.mockResolvedValue(page([]));

    const { wrapper } = await mountPage(ContentAuditView, "/admin/content");

    expect(wrapper.find(".ph-state--empty").exists()).toBe(true);
    expect(wrapper.text()).toContain("这个状态下没有内容");
  });

  it("错误态：显示后端那句话与请求 ID，并能在原地重试", async () => {
    listContents.mockRejectedValue(apiFailure(50000, "服务器内部错误", "req-500"));

    const { wrapper } = await mountPage(ContentAuditView, "/admin/content");

    expect(wrapper.text()).toContain("服务器内部错误");
    expect(wrapper.text()).toContain("req-500");

    listContents.mockResolvedValue(page([CARD]));
    await wrapper.get(".ph-state--error button").trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("猫咪换粮的一周记录");
  });

  it("未登录：闸门说话，且一个业务请求都不发", async () => {
    const { wrapper } = await mountPage(ContentAuditView, "/admin/content", "anonymous");

    expect(wrapper.find(".ph-state--forbidden").exists()).toBe(true);
    expect(listContents).not.toHaveBeenCalled();
  });
});

describe("内容审核：写操作真的调了接口", () => {
  it("通过：调 approve，且**不带请求体**（通过时没有要对作者说的话）", async () => {
    const { wrapper } = await mountPage(ContentAuditView, "/admin/content");

    await buttonByText(wrapper, "通过").trigger("click");
    await flushPromises();

    expect(approveContent).toHaveBeenCalledWith(1, 5);
    expect(approveContent.mock.calls[0]?.length).toBe(2);
  });

  it("驳回：理由必填（空理由只在本地拦下），填了才调 reject", async () => {
    const { wrapper } = await mountPage(ContentAuditView, "/admin/content");

    await buttonByText(wrapper, "驳回").trigger("click");
    await wrapper.get("form.ph-cq__reject").trigger("submit");
    await flushPromises();

    expect(wrapper.text()).toContain("驳回理由必填");
    expect(rejectContent).not.toHaveBeenCalled();

    await wrapper.get("form.ph-cq__reject textarea").setValue("正文含联系方式，不允许在内容里引流");
    await wrapper.get("form.ph-cq__reject").trigger("submit");
    await flushPromises();

    expect(rejectContent).toHaveBeenCalledWith(1, 5, "正文含联系方式，不允许在内容里引流");
  });
});

describe("内容审核：敏感词管理", () => {
  it("切到敏感词分段：列词表，并说明它与 AI 红线词是两套", async () => {
    const { wrapper } = await mountPage(ContentAuditView, "/admin/content");

    await buttonByText(wrapper, "敏感词管理").trigger("click");
    await flushPromises();

    expect(listSensitiveWords).toHaveBeenCalled();
    expect(wrapper.text()).toContain("加微信");
    expect(wrapper.text()).toContain("硬红线词");
  });

  it("新增敏感词：调 createSensitiveWord，词为空时本地拦下", async () => {
    const { wrapper } = await mountPage(ContentAuditView, "/admin/content");
    await buttonByText(wrapper, "敏感词管理").trigger("click");
    await flushPromises();

    await buttonByText(wrapper, "新增敏感词").trigger("click");
    await wrapper.get("form.ph-word__form").trigger("submit");
    await flushPromises();
    expect(createSensitiveWord).not.toHaveBeenCalled();

    await wrapper.get('input[placeholder="如：加微信"]').setValue("包治百病");
    await wrapper.get('input[placeholder="如：引流"]').setValue("夸大宣传");
    await wrapper.get("form.ph-word__form").trigger("submit");
    await flushPromises();

    expect(createSensitiveWord).toHaveBeenCalledWith({ word: "包治百病", category: "夸大宣传", remark: null, enabled: true });
  });
});
