/**
 * 积分中心（切片 #113）的组件测试。
 *
 * 重点是**兑换这条写路径**：积分是钱以外唯一会「花掉」的东西——
 *   - 幂等键必须带（契约点名：一次兑换没有天然的业务引用，不带键的重复提交会扣两次分）；
 *   - 余额不足时按钮就是灰的，服务端再判一次 40900；
 *   - 兑换失败后余额**不许变**（不能乐观地把分先扣掉）。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises } from "@vue/test-utils";
import type { PointsCenterView } from "../api/commerce";
import PointsView from "../views/PointsView.vue";
import { apiFailure, mountPage } from "./support";

const getPoints = vi.fn();
const exchangePoints = vi.fn();
const signIn = vi.fn();
vi.mock("../api/commerce", () => ({
  commerce: {
    getPoints: (...args: unknown[]) => getPoints(...args),
    exchangePoints: (...args: unknown[]) => exchangePoints(...args),
    signIn: (...args: unknown[]) => signIn(...args),
  },
}));

const CENTER: PointsCenterView = {
  balance: 50,
  today_earned: 4,
  daily_earn_limit: 20,
  tasks: [
    { id: 1, code: "DAILY_CHECK_IN", name: "每日签到", period: 1, target_count: 1, current_count: 1, completed: true, points: 1 },
    { id: 2, code: "WEEKLY_CHECK_IN", name: "每周打卡 5 次", period: 2, target_count: 5, current_count: 2, completed: false, points: 3 },
  ],
  behaviors: [
    { code: "CHECK_IN", name: "每日签到", points: 1, counts_toward_daily_cap: 1, once_only: 0, status: 1, sort_order: 1 },
    { code: "INVITE_VALID", name: "邀请有效注册", points: 20, counts_toward_daily_cap: 0, once_only: 0, status: 1, sort_order: 3 },
  ],
  exchange_options: [
    { id: 31, name: "10 元券", points_cost: 30, coupon_template_name: "平台 10 元券", coupon_face_value: "10.00", sort_order: 1, status: 1 },
    { id: 32, name: "50 元券", points_cost: 200, coupon_template_name: "平台 50 元券", coupon_face_value: "50.00", sort_order: 2, status: 1 },
  ],
};

beforeEach(() => {
  getPoints.mockReset();
  exchangePoints.mockReset();
  signIn.mockReset();
});

describe("积分中心：四态", () => {
  it("加载中给骨架", async () => {
    getPoints.mockReturnValue(new Promise(() => undefined));

    const { wrapper } = await mountPage(PointsView, "/points");

    expect(wrapper.find(".ph-state--loading").exists()).toBe(true);
  });

  it("错误态：带请求 ID 与重试", async () => {
    getPoints.mockRejectedValueOnce(apiFailure(50000, "服务器内部错误", "req-500"));
    getPoints.mockResolvedValueOnce(CENTER);

    const { wrapper } = await mountPage(PointsView, "/points");
    expect(wrapper.text()).toContain("req-500");

    await wrapper.get(".ph-state--error button").trigger("click");
    await flushPromises();
    expect(wrapper.text()).toContain("50");
  });

  it("无权限态：未登录时不发请求", async () => {
    const { wrapper } = await mountPage(PointsView, "/points", "anonymous");

    expect(wrapper.find(".ph-state--forbidden").exists()).toBe(true);
    expect(getPoints).not.toHaveBeenCalled();
  });

  it("没有兑换档位时给空态，不给一个空列表", async () => {
    getPoints.mockResolvedValue({ ...CENTER, tasks: [], exchange_options: [] });

    const { wrapper } = await mountPage(PointsView, "/points");

    expect(wrapper.text()).toContain("暂无可兑换档位");
  });
});

describe("积分中心：展示口径", () => {
  it("余额、今日已获得与每日上限、任务进度、行为分值都在", async () => {
    getPoints.mockResolvedValue(CENTER);

    const { wrapper } = await mountPage(PointsView, "/points");

    expect(wrapper.text()).toContain("50");
    expect(wrapper.text()).toContain("今日已获得 4 分");
    expect(wrapper.text()).toContain("每日上限 20 分");
    expect(wrapper.text()).toContain("每周打卡 5 次");
    expect(wrapper.text()).toContain("2 / 5");
    expect(wrapper.text()).toContain("邀请有效注册");
    // 积分不是钱：不能提现这条必须写在页面上
    expect(wrapper.text()).toContain("不能提现");
  });

  it("没有积分流水接口时不列假数据，明确标「待接口」", async () => {
    getPoints.mockResolvedValue(CENTER);

    const { wrapper } = await mountPage(PointsView, "/points");

    expect(wrapper.text()).toContain("还没有积分流水的查询接口");
  });
});

describe("积分中心：兑换（写路径）", () => {
  it("积分不足：按钮是灰的并写明原因，不发请求", async () => {
    getPoints.mockResolvedValue(CENTER);

    const { wrapper } = await mountPage(PointsView, "/points");
    const buttons = wrapper.findAll("button");
    const short = buttons.find((button) => button.text() === "积分不足");

    expect(short).toBeTruthy();
    expect(short?.attributes("disabled")).toBeDefined();
  });

  it("兑换成功：带上档位 id 与幂等键，余额以服务端返回的为准", async () => {
    getPoints.mockResolvedValueOnce(CENTER).mockResolvedValue({ ...CENTER, balance: 20 });
    exchangePoints.mockResolvedValue({
      points_cost: 30,
      balance_after: 20,
      coupon: { id: 88, code: "C-88", template_name: "平台 10 元券", face_value: "10.00", min_amount: "0.00", status: 1, source: 3 },
    });

    const { wrapper } = await mountPage(PointsView, "/points");
    await wrapper.findAll("button").find((button) => button.text() === "兑换")?.trigger("click");
    await flushPromises();

    const [optionId, key] = exchangePoints.mock.calls[0] as [number, string];
    expect(optionId).toBe(31);
    // 幂等键必须带：不带的话连点两次会扣两次分（契约点名）
    expect(typeof key).toBe("string");
    expect(key.length).toBeGreaterThan(0);

    expect(wrapper.text()).toContain("20");
    expect(wrapper.text()).toContain("已兑换");
    expect(wrapper.text()).toContain("C-88");
  });

  it("兑换失败（40900 余额不足）：展示后端那句话与请求 ID，且**余额不变**", async () => {
    getPoints.mockResolvedValue(CENTER);
    exchangePoints.mockRejectedValue(apiFailure(40900, "积分余额不足", "req-409"));

    const { wrapper } = await mountPage(PointsView, "/points");
    await wrapper.findAll("button").find((button) => button.text() === "兑换")?.trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("积分余额不足");
    expect(wrapper.text()).toContain("req-409");
    // 失败就是整笔不发：余额必须还是 50，也不能冒出「已兑换」
    expect(wrapper.text()).toContain("50");
    expect(wrapper.text()).not.toContain("已兑换");
  });

  it("档位停用（status=0）：按钮不可点", async () => {
    getPoints.mockResolvedValue({
      ...CENTER,
      exchange_options: [{ ...CENTER.exchange_options![0]!, status: 0 }],
    });

    const { wrapper } = await mountPage(PointsView, "/points");
    const disabled = wrapper.findAll("button").find((button) => button.text() === "已停用");

    expect(disabled?.attributes("disabled")).toBeDefined();
  });
});

/** 签到那条任务在契约里是 DAILY_SIGN_IN（进度按流水聚合，所以它同时就是「签到过没有」的答案）。 */
const SIGN_IN_TASK = {
  id: 9, code: "DAILY_SIGN_IN", name: "每日签到", period: 1,
  target_count: 1, current_count: 0, completed: false, points: 1,
};

function signInButton(wrapper: Awaited<ReturnType<typeof mountPage>>["wrapper"]) {
  return wrapper.findAll("button").find((button) => button.text().includes("签到"));
}

describe("积分中心：签到（写路径）", () => {
  it("签到成功：带上服务端给的那句话，余额与任务进度以服务端返回值/重拉为准", async () => {
    // 第一次拉回来「未签到」，签完之后重拉回来「已签到、余额 +1」
    getPoints.mockResolvedValueOnce({ ...CENTER, tasks: [...CENTER.tasks!, SIGN_IN_TASK] });
    getPoints.mockResolvedValue({
      ...CENTER,
      balance: 51,
      tasks: [...CENTER.tasks!, { ...SIGN_IN_TASK, current_count: 1, completed: true }],
    });
    signIn.mockResolvedValue({ awarded: true, points: 1, balance: 51, notice: "签到成功，+1 积分" });

    const { wrapper } = await mountPage(PointsView, "/points");
    await flushPromises();
    expect(signInButton(wrapper)?.text()).toBe("签到");

    await signInButton(wrapper)!.trigger("click");
    await flushPromises();

    expect(signIn).toHaveBeenCalledTimes(1);
    expect(wrapper.text()).toContain("签到成功，+1 积分");
    expect(wrapper.text()).toContain("51");
    // 前端不自己记「今天签过」：按钮的新文案来自服务端的任务进度
    expect(signInButton(wrapper)?.text()).toBe("今天已签到");
  });

  it("服务端说今天已经签过（awarded=false）：不报错，也不改动余额", async () => {
    getPoints.mockResolvedValue({ ...CENTER, tasks: [...CENTER.tasks!, SIGN_IN_TASK] });
    signIn.mockResolvedValue({ awarded: false, points: 0, balance: 50, notice: "今天已经签过了" });

    const { wrapper } = await mountPage(PointsView, "/points");
    await flushPromises();
    await signInButton(wrapper)!.trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("今天已经签过了");
    expect(wrapper.find(".ph-form__error").exists()).toBe(false);
    expect(wrapper.text()).toContain("50");
  });

  it("任务已完成时按钮是「今天已签到」且不可点：一次无用请求都不发", async () => {
    getPoints.mockResolvedValue({
      ...CENTER,
      tasks: [...CENTER.tasks!, { ...SIGN_IN_TASK, current_count: 1, completed: true }],
    });

    const { wrapper } = await mountPage(PointsView, "/points");
    await flushPromises();

    const button = signInButton(wrapper);
    expect(button?.text()).toBe("今天已签到");
    expect(button?.attributes("disabled")).toBeDefined();
    expect(signIn).not.toHaveBeenCalled();
  });
});
