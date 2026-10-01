/**
 * 资料编辑（F002）的组件测试。
 *
 * 这一块最容易写错的是**「不改」与「清空」的区别**——契约的 `UpdateProfileRequest` 说明写得很直白：
 * 字段不传（或传 `null`）= 不改，`avatar` 传空串 = 清空头像。两者混起来的表现是
 * 「用户只想改昵称，头像被顺手清掉了」，而接口那边一切正常、没有任何报错。
 * 所以三条规则各占一条用例：
 *   1. 头像输入框留空 → 请求体里**没有** `avatar` 字段（= 不改）；
 *   2. 点「清除头像」 → 请求体里 `avatar: ""`（= 清空）；
 *   3. 昵称为空 → 提交按钮禁用（昵称是库里的 NOT NULL，传空串后端回 40001）。
 *
 * 桩打在 axios 传输层（与 compliance / photo-upload 同一手法），所以走的是真的请求层：
 * `cApp.updateMe` 拼路径、信封解包、错误归类都被真跑一遍。
 */
import { flushPromises } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import ProfileView from "../views/ProfileView.vue";
import { mountPage } from "./support";

const transport = vi.hoisted(() => ({ request: vi.fn() }));

vi.mock("axios", () => {
  const instance = {
    request: transport.request,
    get: transport.request,
    post: transport.request,
    put: transport.request,
    delete: transport.request,
    interceptors: { request: { use: () => undefined } },
  };
  const axios = { create: () => instance, put: transport.request, post: transport.request };
  return { default: axios, ...axios };
});

function ok<T>(data: T) {
  return { data: { code: 0, message: "success", data, request_id: "t" }, status: 200 };
}

/** 用户资料（契约里的形状）。 */
function profile(overrides: Record<string, unknown> = {}) {
  return {
    id: 1,
    phone: "139****0001",
    nickname: "小明",
    avatar: "https://cdn.example.com/a.png",
    gender: 0,
    active_pet_id: null,
    created_at: "2026-09-01 10:00:00",
    ...overrides,
  };
}

/** 按 URL 分派：回收站走 GET，资料更新走 PUT。 */
function stubTransport(updated = profile()) {
  transport.request.mockImplementation((config: { url: string; method?: string }) => {
    if (config.url.includes("/users/me") && (config.method ?? "").toLowerCase() === "put") {
      return Promise.resolve(ok(updated));
    }
    return Promise.resolve(ok([]));
  });
}

/** 找到资料更新那一次请求（页面首屏还会拉回收站，别拿错）。 */
function updateCall() {
  return transport.request.mock.calls
    .map((call) => call[0] as { url: string; method?: string; data?: Record<string, unknown> })
    .find((config) => config.url.includes("/users/me") && (config.method ?? "").toLowerCase() === "put");
}

async function mountProfile() {
  return mountPage(ProfileView, "/profile", "authenticated");
}

/**
 * 提交资料表单。
 *
 * **直接触发 `form` 的 submit 事件，不点按钮**：jsdom 不实现「点击 submit 按钮 → 表单提交」
 * 这条默认行为，点按钮在测试里等于什么都没发生（第一版就踩了，表现为「请求一次都没发出去」）。
 * 按钮本身的禁用态由最后一条用例单独断言。
 */
async function saveProfile(wrapper: Awaited<ReturnType<typeof mountProfile>>["wrapper"]) {
  await wrapper.find("form").trigger("submit");
  await flushPromises();
}

describe("我的 · 资料编辑", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    stubTransport();
  });

  it("头像留空时请求体里没有 avatar 字段——留空是「不改」，不是「清空」", async () => {
    const { wrapper } = await mountProfile();

    await wrapper.findAll("button").find((b) => b.text() === "编辑资料")!.trigger("click");
    const inputs = wrapper.findAll("input");
    await inputs[0].setValue("新名字");   // 昵称
    await inputs[1].setValue("");          // 头像留空
    await saveProfile(wrapper);

    const call = updateCall();
    expect(call).toBeTruthy();
    expect(call!.data).toEqual({ nickname: "新名字", gender: 0 });
    expect(Object.prototype.hasOwnProperty.call(call!.data, "avatar")).toBe(false);
  });

  it("「清除头像」发的是 avatar 空串——契约里「清空」与「不改」是两件事", async () => {
    const { wrapper } = await mountProfile();

    await wrapper.findAll("button").find((b) => b.text() === "编辑资料")!.trigger("click");
    await wrapper.findAll("button").find((b) => b.text() === "清除头像")!.trigger("click");
    await flushPromises();

    expect(updateCall()!.data).toEqual({ avatar: "" });
  });

  it("保存成功后把返回的资料收进会话（不额外再拉一次 /users/me）", async () => {
    stubTransport(profile({ nickname: "改过的名字" }));
    const { wrapper } = await mountProfile();

    await wrapper.findAll("button").find((b) => b.text() === "编辑资料")!.trigger("click");
    await wrapper.findAll("input")[0].setValue("改过的名字");
    await saveProfile(wrapper);

    // 表单收起 + 页面上显示的是新昵称
    expect(wrapper.text()).toContain("改过的名字");
    expect(wrapper.findAll("button").some((b) => b.text() === "编辑资料")).toBe(true);
  });

  it("昵称为空时按钮禁用，且处理器本身也拦一道（不靠按钮的 disabled）", async () => {
    const { wrapper } = await mountProfile();

    await wrapper.findAll("button").find((b) => b.text() === "编辑资料")!.trigger("click");
    await wrapper.findAll("input")[0].setValue("   ");
    await flushPromises();

    const submit = wrapper.findAll("button").find((b) => b.text() === "保存资料")!;
    expect(submit.attributes("disabled")).toBeDefined();

    // 绕过按钮直接提表单：处理器自己也要拦住（昵称是库里的 NOT NULL）
    await saveProfile(wrapper);
    expect(updateCall()).toBeUndefined();
  });
});

describe("我的页：订单与福利两块（4.16.6 的第 2、4 块）", () => {
  it("订单按进行中 / 已完成分组，福利块给券的最近到期天数", async () => {
    const until = new Date(Date.now() + 5 * 86_400_000).toISOString().slice(0, 19).replace("T", " ");
    transport.request.mockImplementation((config: { url: string }) => {
      if (config.url.includes("/orders")) {
        return Promise.resolve(ok({
          list: [
            { id: 1, order_no: "PH1", status: 2, service_name: "洗护套餐", appointment_date: "2026-10-05" },
            { id: 2, order_no: "PH2", status: 3, service_name: "基础体检", appointment_date: "2026-09-20" },
          ],
          page: 1, page_size: 4, total: 2, has_more: false,
        }));
      }
      if (config.url.includes("/coupons")) {
        return Promise.resolve(ok({
          list: [{ id: 9, code: "C-9", template_name: "洗护券 20 元", face_value: "20.00",
                   min_amount: "0.00", source: 4, status: 1, valid_until: until }],
          page: 1, page_size: 100, total: 1, has_more: false,
        }));
      }
      return Promise.resolve(ok(profile()));
    });

    const { wrapper } = await mountProfile();
    await flushPromises();

    expect(wrapper.text()).toContain("我的订单");
    expect(wrapper.text()).toContain("进行中");
    expect(wrapper.text()).toContain("已完成");
    expect(wrapper.text()).toContain("洗护套餐");
    expect(wrapper.text()).toContain("基础体检");
    expect(wrapper.text()).toContain("我的福利");
    expect(wrapper.text()).toContain("共 1 张可用券");
    expect(wrapper.text()).toContain("洗护券 20 元 5 天后过期");
  });

  it("没有订单与券时不显示那两块（「我的」页不摆空格子）", async () => {
    stubTransport();
    const { wrapper } = await mountProfile();
    await flushPromises();

    expect(wrapper.text()).not.toContain("我的订单");
    expect(wrapper.text()).not.toContain("我的福利");
  });
});
