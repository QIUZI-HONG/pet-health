/**
 * 订单四态与「这个状态下能做什么」的映射（ADR-0038 第一节 / ADR-0048）。
 *
 * 为什么单独一个文件：状态到动作的映射是**一条规则**，而订单列表、订单详情、下单一处都要用。
 * 散在页面里就会出现「列表上还能点取消、详情里按钮是灰的」这种自相矛盾——而取消按阶段分
 * （待接单直接取消、已预约只是申请、履约中不可取消）恰恰是最容易被抄错的一处。
 */

/** 契约的四个状态码：0 待接单 / 1 已预约 / 2 履约中 / 3 已完成 / 4 已取消。 */
export const ORDER_STATUS = {
  PENDING_ACCEPT: 0,
  BOOKED: 1,
  IN_SERVICE: 2,
  COMPLETED: 3,
  CANCELLED: 4,
} as const;

/** 取消申请的三个状态码（`cancel_request_status`）。 */
export const CANCEL_REQUEST = {
  PENDING: 1,
  APPROVED: 2,
  REJECTED: 3,
} as const;

export interface OrderStatusView {
  /** 展示用的状态名（用契约里的词：待接单 / 已预约 / 履约中 / 已完成 / 已取消）。 */
  label: string;
  /** 状态说明：这个阶段用户该干什么、能期待什么。 */
  hint: string;
  /** 语气修饰：用于给标签配色（走 var(--ph-*)，不写死色值）。 */
  tone: "waiting" | "active" | "done" | "muted";
}

const STATUS_VIEWS: Record<number, OrderStatusView> = {
  [ORDER_STATUS.PENDING_ACCEPT]: {
    label: "待接单",
    hint: "已占用预约时段，等门店接单。这期间可以直接取消。",
    tone: "waiting",
  },
  [ORDER_STATUS.BOOKED]: {
    label: "已预约",
    hint: "门店已接单。到预约时间后可在订单详情查看核销码，到店出示给门店核销。",
    tone: "active",
  },
  [ORDER_STATUS.IN_SERVICE]: {
    label: "履约中",
    hint: "服务进行中。这个阶段不能取消，服务完成后由门店报工结束。",
    tone: "active",
  },
  [ORDER_STATUS.COMPLETED]: {
    label: "已完成",
    hint: "服务已完成。",
    tone: "done",
  },
  [ORDER_STATUS.CANCELLED]: {
    label: "已取消",
    hint: "订单已取消，号源与锁定的券都已释放。",
    tone: "muted",
  },
};

/** 未知状态码不猜：照实说看不清，但给一个中性的展示（契约只有 0–4）。 */
export function orderStatusView(status: number | null | undefined): OrderStatusView {
  if (status === null || status === undefined) {
    return { label: "未知状态", hint: "", tone: "muted" };
  }
  return STATUS_VIEWS[status] ?? { label: `状态 ${status}`, hint: "", tone: "muted" };
}

/**
 * 取消入口的形态——**一个状态只对应一种结果**：
 *
 * - `cancel`：待接单，点了直接取消（号源与券一并释放）；
 * - `request`：已预约且没有待处理的申请，点了是**发起取消申请**（订单状态不变，等门店同意）；
 * - `pending`：已预约且申请待处理，不能再点（后端会 40900「已经提交过取消申请」）；
 * - `rejected`：门店拒绝了上一次申请，可以再申请一次；
 * - `none`：履约中 / 已完成 / 已取消，**没有取消入口**。
 */
export type CancelMode = "cancel" | "request" | "pending" | "rejected" | "none";

export function cancelModeOf(status: number | null | undefined, cancelRequestStatus: number | null | undefined): CancelMode {
  if (status === ORDER_STATUS.PENDING_ACCEPT) {
    return "cancel";
  }
  if (status !== ORDER_STATUS.BOOKED) {
    return "none";
  }
  if (cancelRequestStatus === CANCEL_REQUEST.PENDING) return "pending";
  if (cancelRequestStatus === CANCEL_REQUEST.REJECTED) return "rejected";
  return "request";
}

/** 取消入口的按钮文案；`none` 时为空串（调用方据此不渲染按钮）。 */
export function cancelActionLabel(mode: CancelMode): string {
  switch (mode) {
    case "cancel":
      return "取消订单";
    case "request":
      return "申请取消";
    case "rejected":
      return "再次申请取消";
    default:
      return "";
  }
}

/** 「已取消」是谁推动的（`cancelled_by`）。 */
export function cancelledByLabel(cancelledBy: number | null | undefined): string {
  switch (cancelledBy) {
    case 1:
      return "我取消的";
    case 2:
      return "门店取消的";
    case 3:
      return "平台介入取消的";
    default:
      return "";
  }
}
