/**
 * 订单状态 → 能做什么的映射（ADR-0038 第一节的取消规则）。
 *
 * 这条规则是本轮**最容易抄错的一处**：同一个「取消」按钮在待接单是直接取消、在已预约只是申请、
 * 在履约中是根本没有。规则收在 `utils/order-status.ts` 一份、由列表与详情共用，
 * 所以这里直接钉住那张表——页面级的表现另有两组用例（orders / order-detail）。
 */
import { describe, expect, it } from "vitest";
import {
  CANCEL_REQUEST,
  ORDER_STATUS,
  cancelActionLabel,
  cancelModeOf,
  cancelledByLabel,
  orderStatusView,
} from "../utils/order-status";

describe("取消入口按阶段分", () => {
  it("待接单：直接取消（号源与券一并释放）", () => {
    expect(cancelModeOf(ORDER_STATUS.PENDING_ACCEPT, null)).toBe("cancel");
    expect(cancelActionLabel("cancel")).toBe("取消订单");
  });

  it("已预约：只是发起取消申请，不是取消", () => {
    expect(cancelModeOf(ORDER_STATUS.BOOKED, null)).toBe("request");
    expect(cancelActionLabel("request")).toBe("申请取消");
  });

  it("已预约且申请待处理：没有按钮（重复申请服务端会给 40900）", () => {
    expect(cancelModeOf(ORDER_STATUS.BOOKED, CANCEL_REQUEST.PENDING)).toBe("pending");
    expect(cancelActionLabel("pending")).toBe("");
  });

  it("门店拒绝过：还能再申请一次", () => {
    expect(cancelModeOf(ORDER_STATUS.BOOKED, CANCEL_REQUEST.REJECTED)).toBe("rejected");
    expect(cancelActionLabel("rejected")).toBe("再次申请取消");
  });

  it("履约中：**不可取消**（服务已开始，只能等完成或由平台介入）", () => {
    expect(cancelModeOf(ORDER_STATUS.IN_SERVICE, null)).toBe("none");
    expect(cancelActionLabel("none")).toBe("");
  });

  it("终态（已完成 / 已取消）：没有取消动作", () => {
    expect(cancelModeOf(ORDER_STATUS.COMPLETED, null)).toBe("none");
    expect(cancelModeOf(ORDER_STATUS.CANCELLED, null)).toBe("none");
  });

  it("状态缺失时不猜：给「没有取消动作」，不给一个会打后端 40900 的按钮", () => {
    expect(cancelModeOf(null, null)).toBe("none");
    expect(cancelModeOf(undefined, undefined)).toBe("none");
  });
});

describe("状态文案", () => {
  it("四个状态各有自己的名字与说明（用契约里的词）", () => {
    expect(orderStatusView(0).label).toBe("待接单");
    expect(orderStatusView(1).label).toBe("已预约");
    expect(orderStatusView(2).label).toBe("履约中");
    expect(orderStatusView(3).label).toBe("已完成");
    expect(orderStatusView(4).label).toBe("已取消");
    // 履约中的说明必须说清「不能取消」——不然用户会找取消按钮
    expect(orderStatusView(2).hint).toContain("不能取消");
  });

  it("认不出的状态码不编含义", () => {
    expect(orderStatusView(99).label).toContain("99");
    expect(orderStatusView(null).label).toBe("未知状态");
  });

  it("已取消是谁推动的：用户 / 门店 / 平台", () => {
    expect(cancelledByLabel(1)).toBe("我取消的");
    expect(cancelledByLabel(2)).toBe("门店取消的");
    expect(cancelledByLabel(3)).toBe("平台介入取消的");
    expect(cancelledByLabel(null)).toBe("");
  });
});
