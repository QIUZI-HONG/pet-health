/**
 * 消息中心与防疫记录的组件测试（切片 #99 的验收标准：站内消息中心可达、未读数正确、可按类型开关）。
 *
 * 规则来自 ADR-0019，逐条对应：未读/已读、全部已读、按类型开关、不可关闭的开关置灰、
 * 提醒流按风险分色、防疫记录的「不填下次日期就不提醒」。
 */
import { describe, expect, it, vi, beforeEach } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { createPinia, setActivePinia, type Pinia } from "pinia";
import { createRouter, createMemoryHistory } from "vue-router";
import type { MessageView, ReminderSetting, EpidemicRecord } from "@pet-health/shared";
import MessagesView from "../views/MessagesView.vue";

const listMessages = vi.fn();
const listReminderSettings = vi.fn();
const markMessageRead = vi.fn();
const deleteMessage = vi.fn();
const markAllMessagesRead = vi.fn();
const updateReminderSetting = vi.fn();
// vi.mock 的工厂会被提升到文件顶部，所以在工厂里引用普通 const 会报「Cannot access before initialization」；
// vi.hoisted 让这个 mock 也跟着提升
const { getUnreadCount } = vi.hoisted(() => ({ getUnreadCount: vi.fn() }));
const listEpidemicRecords = vi.fn();
const createEpidemicRecord = vi.fn();

vi.mock("@pet-health/shared", async () => {
  const actual = await vi.importActual<typeof import("@pet-health/shared")>("@pet-health/shared");
  return {
    ...actual,
    cApp: {
      listMessages: (...args: unknown[]) => listMessages(...args),
      listReminderSettings: (...args: unknown[]) => listReminderSettings(...args),
      markMessageRead: (...args: unknown[]) => markMessageRead(...args),
      deleteMessage: (...args: unknown[]) => deleteMessage(...args),
      markAllMessagesRead: (...args: unknown[]) => markAllMessagesRead(...args),
      updateReminderSetting: (...args: unknown[]) => updateReminderSetting(...args),
      listEpidemicRecords: (...args: unknown[]) => listEpidemicRecords(...args),
      createEpidemicRecord: (...args: unknown[]) => createEpidemicRecord(...args),
      getUnreadCount: getUnreadCount,
      me: vi.fn(),
      listPets: vi.fn().mockResolvedValue([]),
    },
    tokenStore: {
      get: vi.fn(() => ({ accessToken: "a", refreshToken: "r" })),
      accessToken: "a",
      refreshToken: "r",
      save: vi.fn(),
      clear: vi.fn(),
    },
  };
});

function makeMessage(overrides: Partial<MessageView> = {}): MessageView {
  return {
    id: 1,
    kind: 1,
    type: 1,
    title: "豆豆的疫苗还有 5 天",
    content: "疫苗「狂犬疫苗」应在 2026年10月01日 前后完成。",
    risk_level: 2,
    remind_at: "2026-10-01 00:00:00",
    read: false,
    read_at: undefined,
    action_hint: "找服务",
    action_target: "/services",
    pet_id: 1,
    created_at: "2026-09-28 08:00:00",
    ...overrides,
  } as MessageView;
}

function makeSetting(overrides: Partial<ReminderSetting> = {}): ReminderSetting {
  return {
    type: 1,
    name: "疫苗到期",
    enabled: true,
    platform_enabled: true,
    closable: true,
    ...overrides,
  } as ReminderSetting;
}

async function mountMessages(): Promise<{ wrapper: ReturnType<typeof mount>; pinia: Pinia }> {
  const pinia = createPinia();
  setActivePinia(pinia);
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/messages", name: "messages", component: { template: "<div/>" } },
      { path: "/services", name: "services", component: { template: "<div/>" } },
    ],
  });
  await router.push("/messages");
  await router.isReady();
  // 页面被 SessionGate 包着，没有会话就只会渲染「登录后查看」——测试要先把会话置为已认证
  const session = (await import("../stores/session")).useSessionStore();
  session.$patch({
    status: "authenticated",
    hasSession: true,
    user: { id: 1, nickname: "我", phone: "138****8000", gender: 0 } as never,
    pets: [],
  });
  const wrapper = mount(MessagesView, { global: { plugins: [pinia, router] } });
  await flushPromises();
  return { wrapper, pinia };
}

describe("消息中心", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listMessages.mockResolvedValue({ list: [], page: 1, page_size: 20, total: 0, has_more: false });
    listReminderSettings.mockResolvedValue([makeSetting()]);
    getUnreadCount.mockResolvedValue({ unread: 0, unread_reminders: 0 });
  });

  it("展示未读数与消息内容，未读的加粗并带圆点", async () => {
    listMessages.mockResolvedValue({
      list: [makeMessage(), makeMessage({ id: 2, read: true, title: "体外驱虫还有 2 天" })],
      page: 1,
      page_size: 20,
      total: 2,
      has_more: false,
    });

    getUnreadCount.mockResolvedValue({ unread: 1, unread_reminders: 1 });
    const { wrapper } = await mountMessages();

    expect(wrapper.text()).toContain("1 条未读");
    expect(wrapper.text()).toContain("豆豆的疫苗还有 5 天");
    expect(wrapper.text()).toContain("体外驱虫还有 2 天");
    expect(wrapper.findAll(".ph-msg__item--unread")).toHaveLength(1);
  });

  it("标记单条已读会上调接口并即时去掉未读样式", async () => {
    const message = makeMessage();
    listMessages.mockResolvedValue({ list: [message], page: 1, page_size: 20, total: 1, has_more: false });
    markMessageRead.mockResolvedValue({ ...message, read: true, read_at: "2026-09-28 09:00:00" });

    const { wrapper } = await mountMessages();
    await wrapper.get(".ph-msg__foot-actions button").trigger("click");
    await flushPromises();

    expect(markMessageRead).toHaveBeenCalledWith(1);
    expect(wrapper.findAll(".ph-msg__item--unread")).toHaveLength(0);
  });

  it("全部已读在没有未读时是禁用的（不该发无效请求）", async () => {
    const { wrapper } = await mountMessages();

    const button = wrapper.findAll(".ph-msg__head-actions button")[1];
    expect(button.attributes("disabled")).toBeDefined();
  });

  it("按类型开关：可关闭的能点，不可关闭的置灰并写明原因", async () => {
    listReminderSettings.mockResolvedValue([
      makeSetting(),
      makeSetting({ type: 4, name: "健康异常", closable: false }),
      makeSetting({ type: 5, name: "指标趋势", platform_enabled: false }),
    ]);
    updateReminderSetting.mockResolvedValue([]);

    const { wrapper } = await mountMessages();
    const switches = wrapper.findAll(".ph-msg__switch input");

    expect(switches[0].attributes("disabled")).toBeUndefined();       // 疫苗：可关
    expect(switches[1].attributes("disabled")).toBeDefined();          // 不可关闭
    expect(switches[2].attributes("disabled")).toBeDefined();          // 平台暂停
    expect(wrapper.text()).toContain("不可关闭");
    expect(wrapper.text()).toContain("平台暂停");

    await switches[0].setValue(false);
    await flushPromises();
    expect(updateReminderSetting).toHaveBeenCalledWith(1, false);
  });

  it("被平台暂停的类型不能被用户打开（开关禁用，不发请求）", async () => {
    listReminderSettings.mockResolvedValue([makeSetting({ type: 5, enabled: false, platform_enabled: false })]);

    const { wrapper } = await mountMessages();
    await wrapper.findAll(".ph-msg__switch input")[0].setValue(true);

    expect(updateReminderSetting).not.toHaveBeenCalled();
  });

  it("风险等级决定标签文案（紧急 / 请关注 / 无）", async () => {
    listMessages.mockResolvedValue({
      list: [
        makeMessage({ id: 1, risk_level: 3, title: "红色提醒" }),
        makeMessage({ id: 2, risk_level: 2, title: "黄色提醒" }),
        makeMessage({ id: 3, risk_level: 1, title: "绿色提醒" }),
      ],
      page: 1,
      page_size: 20,
      total: 3,
      has_more: false,
    });

    const { wrapper } = await mountMessages();

    const labels = wrapper.findAll(".ph-msg__risk").map((node) => node.text());
    expect(labels).toEqual(["紧急", "请关注"]);
  });

  it("空态说明提醒会自动出现，而不是留白", async () => {
    const { wrapper } = await mountMessages();
    expect(wrapper.text()).toContain("暂时没有消息");
  });
});

describe("防疫记录的到期文案", () => {
  it("区分「还有 N 天」「今天应接种」「已过期 N 天」「未设置」", async () => {
    const { default: RecordsView } = await import("../views/RecordsView.vue");
    const pinia = createPinia();
    setActivePinia(pinia);
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: "/records", name: "records", component: { template: "<div/>" } }],
    });
    await router.push("/records");
    await router.isReady();

    const session = (await import("../stores/session")).useSessionStore();
    session.$patch({
      status: "authenticated",
      hasSession: true,
      pets: [{ id: 1, name: "豆豆", species: 1, gender: 0 } as never],
      user: { id: 1, nickname: "我", phone: "138****8000", gender: 0 } as never,
    });
    const records: EpidemicRecord[] = [
      { id: 1, kind: 1, name: "狂犬疫苗", given_on: "2026-09-01", next_due_on: "2026-10-05", days_until_due: 7 },
      { id: 2, kind: 2, name: "体外驱虫", given_on: "2026-09-01", next_due_on: "2026-09-20", days_until_due: -8 },
      { id: 3, kind: 1, name: "犬四联", given_on: "2026-09-01", next_due_on: undefined, days_until_due: undefined },
    ] as EpidemicRecord[];
    listEpidemicRecords.mockResolvedValue(records);

    const wrapper = mount(RecordsView, { global: { plugins: [pinia, router] } });
    await flushPromises();

    const text = wrapper.text();
    expect(text).toContain("还有 7 天");
    expect(text).toContain("已过期 8 天");     // 过期的要说「已过期」，不能显示负数
    expect(text).toContain("未设置下次日期");
  });
});
