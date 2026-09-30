/**
 * 轮询兜底（F026 的限定② / ADR-0040 第三节）的测试。
 *
 * 第二条保留条款原文是「**必须保留轮询兜底**」，所以要验的不是「有没有 setInterval」，
 * 而是三条**行为规则**：
 *  1. 页面可见时按周期刷新；
 *  2. **页面隐藏时停表**——后台标签页还在打接口是白烧流量，也是最常见的实现漏洞；
 *  3. 组件卸载后定时器不再跑，且**回到前台会立刻补一次**（用户切回来时数据最可能是旧的）。
 *
 * 只把 `setInterval` / `clearInterval` 换成假的：`flushPromises` 依赖 `setTimeout`，
 * 一起换掉会让测试挂在一个永远不 resolve 的 Promise 上。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises } from "@vue/test-utils";
import type { MessageView, ReminderSettingView } from "@pet-health/shared";
import MessagesView from "../views/MessagesView.vue";
import InviteView from "../views/InviteView.vue";
import type { InviteCenterView } from "../api/commerce";
import { POLL_INTERVAL_MS, startVisiblePolling } from "../utils/poll";
import { mountPage, type MountedPage } from "./support";

const listMessages = vi.fn();
const listReminderSettings = vi.fn();
const getUnreadCount = vi.fn();

vi.mock("@pet-health/shared", async () => {
  const actual = await vi.importActual<typeof import("@pet-health/shared")>("@pet-health/shared");
  return {
    ...actual,
    cApp: {
      listMessages: (...args: unknown[]) => listMessages(...args),
      listReminderSettings: (...args: unknown[]) => listReminderSettings(...args),
      getUnreadCount: (...args: unknown[]) => getUnreadCount(...args),
    },
  };
});

const getInviteCenter = vi.fn();
vi.mock("../api/commerce", () => ({
  commerce: {
    getInviteCenter: (...args: unknown[]) => getInviteCenter(...args),
    createInviteCode: vi.fn(),
    attributeInvite: vi.fn(),
  },
}));

/**
 * 切页面可见性（jsdom 默认恒为 visible，且不给直接赋值）。**同时派发 `visibilitychange`**：
 * 实现监听的是这个事件，只改属性不派事件就等于什么都没发生。
 */
function setVisibility(state: "visible" | "hidden"): void {
  Object.defineProperty(document, "visibilityState", { configurable: true, get: () => state });
  document.dispatchEvent(new Event("visibilitychange"));
}

const MESSAGE: MessageView = {
  id: 1,
  kind: 1,
  type: 1,
  title: "豆豆的疫苗还有 5 天",
  content: "疫苗「狂犬疫苗」应在 2026年10月01日 前后完成。",
  risk_level: 2,
  read: false,
  created_at: "2026-09-26 00:00:00",
} as MessageView;

const SETTING: ReminderSettingView = {
  type: 1,
  name: "疫苗",
  enabled: true,
  closable: true,
  platform_enabled: true,
} as ReminderSettingView;

const CENTER: InviteCenterView = {
  codes: [
    { id: 1, code: "ABC123", channel: 1, invite_path: "/register?invite=ABC123", status: 1, created_at: "2026-09-20 10:00:00" },
  ],
  registered_count: 2,
  pending_count: 0,
  effective_count: 1,
  invalid_count: 0,
  ladder: [],
  next_threshold: 3,
  next_remaining: 2,
};

/**
 * 挂过的页面。**每个用例结束都要卸载**：组件不卸载，它的定时器还挂在假时钟上，
 * 会跑到下一个用例里去（表现为「未登录也发了一次请求」这种假红）。
 */
const mounted: MountedPage[] = [];

async function open(component: Parameters<typeof mountPage>[0], path: string, kind?: Parameters<typeof mountPage>[2]) {
  const page = await mountPage(component, path, kind);
  mounted.push(page);
  return page;
}

beforeEach(() => {
  vi.clearAllMocks();
  setVisibility("visible");
  // 只换定时器：flushPromises 依赖真 setTimeout，一起换掉测试会挂住
  vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
  listMessages.mockResolvedValue({ list: [MESSAGE], page: 1, page_size: 20, total: 1, has_more: false });
  listReminderSettings.mockResolvedValue([SETTING]);
  getUnreadCount.mockResolvedValue({ unread: 1, unread_reminders: 1 });
  getInviteCenter.mockResolvedValue(CENTER);
});

afterEach(() => {
  for (const page of mounted.splice(0)) {
    page.wrapper.unmount();
  }
  vi.clearAllTimers();
  vi.useRealTimers();
});

describe("轮询兜底：工具本身", () => {
  it("可见时按周期跑；隐藏时停表；回到前台立刻补一次", async () => {
    const task = vi.fn();
    const stop = startVisiblePolling(task, 30_000);

    // 起表时不立刻跑：首屏加载是页面自己的事，轮询只负责「之后」
    expect(task).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(30_000);
    expect(task).toHaveBeenCalledTimes(1);

    setVisibility("hidden");
    await vi.advanceTimersByTimeAsync(5 * 60_000);
    expect(task).toHaveBeenCalledTimes(1);   // 停表：隐藏期间一次都不发

    setVisibility("visible");
    expect(task).toHaveBeenCalledTimes(2);   // 回到前台先补一次，不等一个完整周期
    await vi.advanceTimersByTimeAsync(30_000);
    expect(task).toHaveBeenCalledTimes(3);

    stop();
    await vi.advanceTimersByTimeAsync(5 * 60_000);
    expect(task).toHaveBeenCalledTimes(3);   // 停表之后再也不跑
  });

  it("在后台标签里打开这一页时不起表", async () => {
    setVisibility("hidden");
    const task = vi.fn();
    const stop = startVisiblePolling(task, 30_000);

    await vi.advanceTimersByTimeAsync(5 * 60_000);
    expect(task).not.toHaveBeenCalled();

    stop();
  });
});

describe("轮询兜底：两个页面", () => {
  it("消息中心：到点重新拉消息；隐藏后不再发请求；卸载后定时器不残留", async () => {
    const { wrapper } = await open(MessagesView, "/messages");
    expect(listMessages).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    await flushPromises();
    expect(listMessages).toHaveBeenCalledTimes(2);

    setVisibility("hidden");
    await vi.advanceTimersByTimeAsync(3 * POLL_INTERVAL_MS);
    await flushPromises();
    expect(listMessages).toHaveBeenCalledTimes(2);

    setVisibility("visible");
    await flushPromises();
    expect(listMessages).toHaveBeenCalledTimes(3);

    wrapper.unmount();
    await vi.advanceTimersByTimeAsync(3 * POLL_INTERVAL_MS);
    await flushPromises();
    expect(listMessages).toHaveBeenCalledTimes(3);   // 卸载后没有还在跑的定时器
  });

  it("消息中心：轮询是静默刷新（不整页进加载态，也不覆盖已翻到的那一页）", async () => {
    const { wrapper } = await open(MessagesView, "/messages");
    expect(wrapper.find(".ph-state--loading").exists()).toBe(false);

    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    await flushPromises();

    // 后台刷新不该让用户眼前的列表变成骨架屏
    expect(wrapper.find(".ph-state--loading").exists()).toBe(false);
    expect(wrapper.text()).toContain("豆豆的疫苗还有 5 天");
  });

  it("邀请页：到点重新拉邀请进度，也是静默刷新；隐藏后停表", async () => {
    const { wrapper } = await open(InviteView, "/invites");
    expect(getInviteCenter).toHaveBeenCalledTimes(1);
    expect(wrapper.text()).toContain("ABC123");

    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    await flushPromises();
    expect(getInviteCenter).toHaveBeenCalledTimes(2);
    expect(wrapper.find(".ph-state--loading").exists()).toBe(false);   // 不进加载态

    setVisibility("hidden");
    await vi.advanceTimersByTimeAsync(3 * POLL_INTERVAL_MS);
    await flushPromises();
    expect(getInviteCenter).toHaveBeenCalledTimes(2);
  });

  it("邀请页：轮询失败不把页面变成错误态（下一个周期还会再试）", async () => {
    const { wrapper } = await open(InviteView, "/invites");
    getInviteCenter.mockRejectedValueOnce(new Error("boom"));

    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    await flushPromises();

    expect(wrapper.find(".ph-state--error").exists()).toBe(false);
    expect(wrapper.text()).toContain("ABC123");   // 用户还在看原来的数据
  });

  it("未登录时不轮询：闸门后面没有要刷新的数据，别每 30 秒换回一个 40100", async () => {
    await open(MessagesView, "/messages", "anonymous");
    await open(InviteView, "/invites", "anonymous");

    await vi.advanceTimersByTimeAsync(3 * POLL_INTERVAL_MS);
    await flushPromises();

    expect(listMessages).not.toHaveBeenCalled();
    expect(getInviteCenter).not.toHaveBeenCalled();
  });
});
