/**
 * 订单管理（本轮接的页面）的组件测试。
 *
 * 验的是「肉眼看不出来、串一次就骗人」的五件事：
 *   - **四态**：加载 / 空 / 错误（带请求 ID 与重试）/ 无权限（40300；未登录则由闸门先拦）；
 *   - **状态到动作的映射**：接单只出现在「待接单」、核销只出现在「已预约」、取消在「履约中」不能说能点——
 *     判据与服务端 `UPDATE ... WHERE status = ?` 的条件一致，不然用户点了只会吃 40900；
 *   - **写操作真的调了接口**：接单 / 核销 / 取消各自打到 `providerApp` 的哪个方法、带什么参数；
 *   - **取消必须带理由**：不填理由不发请求（契约里门店取消与拒绝取消申请都必填）；
 *   - **三道照片墙与报工**（切片 #107）：缺一道不放行、`file_id` 挂到槽位、报工的那句话原样来自服务端。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises } from "@vue/test-utils";
import type {
  FileView,
  OrderPhotoSlotView,
  OrderPhotoWallView,
  OrderRow,
  OrderView,
} from "../api/providerApi";
import OrdersView from "../views/OrdersView.vue";
import { apiFailure, mountPage } from "./support";

const listOrders = vi.fn();
const acceptOrder = vi.fn();
const redeemOrder = vi.fn();
const cancelOrder = vi.fn();
const approveOrderCancel = vi.fn();
const rejectOrderCancel = vi.fn();
const getOrder = vi.fn();
const saveOrderPhotoSlot = vi.fn();
const reportOrder = vi.fn();
const uploadCarePhotos = vi.fn();

vi.mock("../api/providerApi", () => ({
  providerApp: {
    listOrders: (...args: unknown[]) => listOrders(...args),
    acceptOrder: (...args: unknown[]) => acceptOrder(...args),
    redeemOrder: (...args: unknown[]) => redeemOrder(...args),
    cancelOrder: (...args: unknown[]) => cancelOrder(...args),
    approveOrderCancel: (...args: unknown[]) => approveOrderCancel(...args),
    rejectOrderCancel: (...args: unknown[]) => rejectOrderCancel(...args),
    getOrder: (...args: unknown[]) => getOrder(...args),
    saveOrderPhotoSlot: (...args: unknown[]) => saveOrderPhotoSlot(...args),
    reportOrder: (...args: unknown[]) => reportOrder(...args),
  },
  uploadCarePhotos: (...args: unknown[]) => uploadCarePhotos(...args),
}));

const PENDING: OrderRow = {
  id: 11,
  order_no: "PH2026093000012345",
  status: 0,
  user_nickname: "张*三",
  user_phone: "138****8888",
  pet_name: "豆豆",
  pet_species: 1,
  service_name: "洗护套餐",
  appointment_date: "2026-09-30",
  start_time: "10:00",
  end_time: "11:00",
  total_amount: "128.00",
  coupon_discount: "20.00",
  estimated_pay_amount: "108.00",
  created_at: "2026-09-28 09:12:00",
};

const BOOKED: OrderRow = { ...PENDING, id: 12, order_no: "PH2026093000067890", status: 1 };
const ONGOING: OrderRow = { ...PENDING, id: 13, order_no: "PH2026093000090909", status: 2 };
const DONE: OrderRow = { ...PENDING, id: 14, order_no: "PH2026093000040404", status: 3 };
const CANCELLED: OrderRow = { ...PENDING, id: 16, order_no: "PH2026093000030303", status: 4 };
const CANCEL_REQUESTED: OrderRow = { ...BOOKED, id: 15, order_no: "PH2026093000070707", cancel_request_status: 1 };

function page(list: OrderRow[]) {
  return { list, page: 1, page_size: 20, total: list.length, has_more: false };
}

/** 核销/接单/取消都返回订单详情：详情与列表是同一条订单，只有状态与时间点不同 */
function detail(row: OrderRow, status: number, redeemedAt: string | null = null): OrderView {
  return { ...row, status, redeemed_at: redeemedAt };
}

/** 一道照片墙：`slot_name` 由服务端给（界面不许自己命名），`satisfied` 与 `missing_slots` 同理。 */
function wall(options: {
  slot?: 1 | 2 | 3;
  photos?: number[];
  satisfied?: boolean;
  missing?: number[];
}): OrderPhotoWallView {
  const at = options.slot ?? 1;
  const photos: FileView[] = (options.photos ?? []).map((id) => ({
    id,
    biz_type: "care",
    role: "original",
    mime: "image/jpeg",
    size_bytes: 1024,
    url: `/api/v1/open/files/${id}?token=r`,
    thumb_url: `/api/v1/open/files/${id}?token=t`,
  }));
  const satisfied = options.satisfied !== false && photos.length > 0;
  const slots: OrderPhotoSlotView[] = [
    { slot: 1, slot_name: "接宠检查", photos: at === 1 ? photos : [], satisfied: at === 1 && satisfied },
    { slot: 2, slot_name: "服务防护", photos: at === 2 ? photos : [], satisfied: at === 2 && satisfied },
    { slot: 3, slot_name: "取宠对比", photos: at === 3 ? photos : [], satisfied: at === 3 && satisfied },
  ];
  const missing = options.missing ?? [1, 2, 3];
  return { slots, reportable: missing.length === 0, missing_slots: missing };
}

/** 「履约中」的订单详情：带一面照片墙（`pet_id` 是照片归属的锚点，上传时要带上）。 */
function ongoingWith(photoWall: OrderPhotoWallView): OrderView {
  return { ...detail(ONGOING, 2), pet_id: 7, photo_wall: photoWall };
}

/** 选文件：jsdom 的 input.files 是只读的，按 c-web 的上传用例同一手法造。 */
async function pick(wrapper: Awaited<ReturnType<typeof mountPage>>["wrapper"], files: File[]): Promise<void> {
  const input = wrapper.get('input[type="file"]');
  Object.defineProperty(input.element, "files", { value: files, configurable: true });
  await input.trigger("change");
  await flushPromises();
}

function image(name: string): File {
  return new File(["x"], name, { type: "image/jpeg" });
}

beforeEach(() => {
  listOrders.mockReset().mockResolvedValue(page([PENDING]));
  acceptOrder.mockReset().mockResolvedValue(detail(PENDING, 1));
  redeemOrder.mockReset().mockResolvedValue(detail(BOOKED, 2, "2026-09-30 10:05:00"));
  cancelOrder.mockReset().mockResolvedValue(detail(PENDING, 4));
  approveOrderCancel.mockReset().mockResolvedValue(detail(CANCEL_REQUESTED, 4));
  rejectOrderCancel.mockReset().mockResolvedValue(detail(CANCEL_REQUESTED, 1));
  getOrder.mockReset().mockResolvedValue(ongoingWith(wall({ missing: [1, 2, 3] })));
  saveOrderPhotoSlot.mockReset();
  reportOrder.mockReset();
  uploadCarePhotos.mockReset().mockResolvedValue([201]);
});

describe("订单管理：列表与四态", () => {
  it("渲染接口来的订单：单号、脱敏客户、宠物与到店应收（金额走 formatAmount）", async () => {
    const { wrapper } = await mountPage(OrdersView, "/b/orders");

    expect(wrapper.text()).toContain("PH2026093000012345");
    expect(wrapper.text()).toContain("张*三");
    expect(wrapper.text()).toContain("138****8888");
    expect(wrapper.text()).toContain("豆豆");
    expect(wrapper.text()).toContain("¥108.00");
    expect(wrapper.text()).toContain("总额 ¥128.00");
    expect(listOrders).toHaveBeenCalledWith(
      { status: undefined, appointmentDate: undefined, keyword: undefined, page: 1, pageSize: 20 },
      expect.anything(),
    );
  });

  it("加载中先给骨架（不把等待显示成「还没有订单」）", async () => {
    listOrders.mockReturnValue(new Promise(() => undefined));

    const { wrapper } = await mountPage(OrdersView, "/b/orders");

    expect(wrapper.find(".ph-state--loading").exists()).toBe(true);
  });

  it("空态：说明订单从哪来、下一步做什么", async () => {
    listOrders.mockResolvedValue(page([]));

    const { wrapper } = await mountPage(OrdersView, "/b/orders");

    expect(wrapper.find(".ph-state--empty").exists()).toBe(true);
    expect(wrapper.text()).toContain("还没有订单");
  });

  it("错误态：显示后端那句话与请求 ID，并能重试", async () => {
    listOrders.mockRejectedValueOnce(apiFailure(50000, "服务器内部错误", "req-500"));
    listOrders.mockResolvedValueOnce(page([PENDING]));

    const { wrapper } = await mountPage(OrdersView, "/b/orders");

    expect(wrapper.text()).toContain("服务器内部错误");
    expect(wrapper.text()).toContain("req-500");

    await wrapper.get(".ph-state--error button").trigger("click");
    await flushPromises();

    expect(listOrders).toHaveBeenCalledTimes(2);
    expect(wrapper.text()).toContain("PH2026093000012345");
  });

  it("无权限态：40300 进「暂无权限」而不是「重试」（重试还是同样的拒绝）", async () => {
    listOrders.mockRejectedValue(apiFailure(40300, "无权限", "req-403"));

    const { wrapper } = await mountPage(OrdersView, "/b/orders");

    expect(wrapper.find(".ph-state--forbidden").exists()).toBe(true);
    expect(wrapper.find(".ph-state--error").exists()).toBe(false);
  });

  it("未登录：闸门先拦，连订单请求都不发（别用一串 40100 盖住闸门）", async () => {
    const { wrapper } = await mountPage(OrdersView, "/b/orders", "anonymous");

    expect(wrapper.text()).toContain("尚未登录服务者账号");
    expect(listOrders).not.toHaveBeenCalled();
  });

  it("筛状态与履约日期都会传给接口，并回到第一页", async () => {
    const { wrapper } = await mountPage(OrdersView, "/b/orders");

    await wrapper.get(".ph-orders__filter select").setValue("1");
    await wrapper.get(".ph-orders__filter select").trigger("change");
    await flushPromises();
    const dateInput = wrapper.findAll(".ph-orders__filter input")[0];
    await dateInput?.setValue("2026-10-01");
    await dateInput?.trigger("change");
    await flushPromises();

    expect(listOrders).toHaveBeenLastCalledWith(
      { status: 1, appointmentDate: "2026-10-01", keyword: undefined, page: 1, pageSize: 20 },
      expect.anything(),
    );
  });

  it("关键字要按「查询」才发给服务端（边打字边发请求会把一个手机号打成十几次查询）", async () => {
    const { wrapper } = await mountPage(OrdersView, "/b/orders");
    expect(listOrders).toHaveBeenCalledTimes(1);

    await wrapper.get(".ph-orders__search input").setValue("13800008888");
    await flushPromises();
    expect(listOrders).toHaveBeenCalledTimes(1);

    const search = wrapper.findAll("button").find((button) => button.text() === "查询");
    await search?.trigger("click");
    await flushPromises();

    expect(listOrders).toHaveBeenLastCalledWith(
      { status: undefined, appointmentDate: undefined, keyword: "13800008888", page: 1, pageSize: 20 },
      expect.anything(),
    );
  });
});

describe("订单管理：状态决定能给的动作", () => {
  it("待接单：接单可点；核销不可点（核销只对「已预约」）；取消可点（待接单可直接取消）", async () => {
    listOrders.mockResolvedValue(page([PENDING]));

    const { wrapper } = await mountPage(OrdersView, "/b/orders");
    const actions = wrapper.findAll(".ph-table__action");

    expect(actions[0]?.attributes("disabled")).toBeUndefined();
    expect(actions[1]?.attributes("disabled")).toBeDefined();
    expect(actions[2]?.attributes("disabled")).toBeUndefined();
  });

  it("履约中：取消按钮禁用，并写明「不可取消，只能报工完成」（与服务端 40900 同一口径）", async () => {
    listOrders.mockResolvedValue(page([ONGOING]));

    const { wrapper } = await mountPage(OrdersView, "/b/orders");

    expect(wrapper.text()).toContain("履约中：不可取消，只能报工完成");
    const cancel = wrapper.findAll(".ph-table__action").find((button) => button.text() === "取消");
    expect(cancel?.attributes("disabled")).toBeDefined();
  });

  it("已完成：状态迁移的三个动作都不能点（终态），只留只读的「看照片墙」", async () => {
    listOrders.mockResolvedValue(page([DONE]));

    const { wrapper } = await mountPage(OrdersView, "/b/orders");

    // 接单 / 核销 / 取消是状态迁移，终态一律禁用；照片墙那一条是回看（写入口在面板里按状态关掉）
    const stateActions = wrapper.findAll(".ph-table__action").filter((button) => button.text() !== "看照片墙");
    expect(stateActions).toHaveLength(3);
    for (const button of stateActions) {
      expect(button.attributes("disabled")).toBeDefined();
    }
    expect(wrapper.findAll(".ph-table__action").find((button) => button.text() === "看照片墙")
      ?.attributes("disabled")).toBeUndefined();
  });

  it("有取消申请（1 待门店处理）：多出「同意取消 / 拒绝取消」两个入口", async () => {
    listOrders.mockResolvedValue(page([CANCEL_REQUESTED]));

    const { wrapper } = await mountPage(OrdersView, "/b/orders");

    expect(wrapper.text()).toContain("用户申请取消：待处理");
    expect(wrapper.text()).toContain("同意取消");
    expect(wrapper.text()).toContain("拒绝取消");
  });
});

describe("订单管理：写操作打到接口", () => {
  it("接单：调用 acceptOrder，并提示「接单即承诺」", async () => {
    const { wrapper } = await mountPage(OrdersView, "/b/orders");

    await wrapper.findAll(".ph-table__action")[0]?.trigger("click");
    await flushPromises();

    expect(acceptOrder).toHaveBeenCalledWith(11);
    expect(wrapper.text()).toContain("接单即承诺");
  });

  it("核销：调用 redeemOrder，并把结果面板里的到店应收与「钱在门店付」摆出来", async () => {
    listOrders.mockResolvedValue(page([BOOKED]));

    const { wrapper } = await mountPage(OrdersView, "/b/orders");
    const redeem = wrapper.findAll(".ph-table__action").find((button) => button.text() === "核销");
    await redeem?.trigger("click");
    await flushPromises();

    expect(redeemOrder).toHaveBeenCalledWith(12);
    expect(wrapper.text()).toContain("核销结果：PH2026093000067890");
    expect(wrapper.text()).toContain("本次服务费用请在门店直接付给服务者");
    expect(wrapper.text()).toContain("¥108.00");
  });

  it("门店取消：填了理由才发请求，理由原样带给 cancelOrder", async () => {
    const { wrapper } = await mountPage(OrdersView, "/b/orders");

    const cancel = wrapper.findAll(".ph-table__action").find((button) => button.text() === "取消");
    await cancel?.trigger("click");
    await flushPromises();

    // 先不填理由直接提交：不发请求，只提示（契约里 reason 必填）
    await wrapper.get("form").trigger("submit");
    await flushPromises();
    expect(cancelOrder).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain("取消要填理由");

    await wrapper.get(".ph-orders__reason input").setValue("门店设备检修，约不上工位");
    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(cancelOrder).toHaveBeenCalledWith(11, "门店设备检修，约不上工位");
  });

  it("拒绝用户的取消申请：理由同样必填，拒绝后订单仍在「已预约」", async () => {
    listOrders.mockResolvedValue(page([CANCEL_REQUESTED]));

    const { wrapper } = await mountPage(OrdersView, "/b/orders");
    const reject = wrapper.findAll(".ph-table__action").find((button) => button.text() === "拒绝取消");
    await reject?.trigger("click");
    await flushPromises();

    await wrapper.get("form").trigger("submit");
    await flushPromises();
    expect(rejectOrderCancel).not.toHaveBeenCalled();

    await wrapper.get(".ph-orders__reason input").setValue("已有其他客户排好工位");
    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(rejectOrderCancel).toHaveBeenCalledWith(15, "已有其他客户排好工位");
  });

  it("同意取消：调用 approveOrderCancel", async () => {
    listOrders.mockResolvedValue(page([CANCEL_REQUESTED]));

    const { wrapper } = await mountPage(OrdersView, "/b/orders");
    const approve = wrapper.findAll(".ph-table__action").find((button) => button.text() === "同意取消");
    await approve?.trigger("click");
    await flushPromises();

    expect(approveOrderCancel).toHaveBeenCalledWith(15);
  });

  it("写操作失败：把后端那句话与请求 ID 显示出来（用户要能报障）", async () => {
    redeemOrder.mockRejectedValue(apiFailure(40900, "订单不在「已预约」", "req-409"));

    const { wrapper } = await mountPage(OrdersView, "/b/orders");
    await flushPromises();
    listOrders.mockResolvedValue(page([BOOKED]));
    await wrapper.get(".ph-orders__filter select").setValue("1");
    await wrapper.get(".ph-orders__filter select").trigger("change");
    await flushPromises();

    const redeem = wrapper.findAll(".ph-table__action").find((button) => button.text() === "核销");
    await redeem?.trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("订单不在「已预约」");
    expect(wrapper.text()).toContain("req-409");
  });

  it("照片墙入口只在「履约中」与「已完成」上给：待接单 / 已预约 / 已取消没有它", async () => {
    listOrders.mockResolvedValue(page([PENDING, BOOKED, CANCELLED]));

    const { wrapper } = await mountPage(OrdersView, "/b/orders");

    expect(wrapper.findAll(".ph-table__action").some((button) => button.text() === "照片墙 / 报工")).toBe(false);
    expect(wrapper.text()).toContain("报工与三道照片墙");
  });
});

describe("订单管理：三道照片墙与报工（切片 #107）", () => {
  it("点入口加载订单详情，渲染服务端给的三道墙（名字与「还缺」都由服务端给）", async () => {
    listOrders.mockResolvedValue(page([ONGOING]));

    const { wrapper } = await mountPage(OrdersView, "/b/orders");
    const entry = wrapper.findAll(".ph-table__action").find((button) => button.text() === "照片墙 / 报工");
    await entry?.trigger("click");
    await flushPromises();

    expect(getOrder).toHaveBeenCalledWith(13);
    expect(wrapper.text()).toContain("接宠检查");
    expect(wrapper.text()).toContain("服务防护");
    expect(wrapper.text()).toContain("取宠对比");
    expect(wrapper.text()).toContain("还缺：接宠检查、服务防护、取宠对比");
  });

  it("缺槽位时不放行：报工按钮禁用，点了也不发请求（硬约束在服务端，前端只是别让用户白点）", async () => {
    listOrders.mockResolvedValue(page([ONGOING]));

    const { wrapper } = await mountPage(OrdersView, "/b/orders");
    const entry = wrapper.findAll(".ph-table__action").find((button) => button.text() === "照片墙 / 报工");
    await entry?.trigger("click");
    await flushPromises();

    const reportButton = wrapper.findAll("button").find((button) => button.text() === "报工完成");
    expect(reportButton?.attributes("disabled")).toBeDefined();
    await reportButton?.trigger("click");
    await flushPromises();

    expect(reportOrder).not.toHaveBeenCalled();
  });

  it("只差一道时点名缺的那一道（读服务端的 missing_slots，不自己数照片）", async () => {
    listOrders.mockResolvedValue(page([ONGOING]));
    getOrder.mockResolvedValue(ongoingWith(wall({ slot: 1, photos: [101], missing: [2, 3] })));

    const { wrapper } = await mountPage(OrdersView, "/b/orders");
    const entry = wrapper.findAll(".ph-table__action").find((button) => button.text() === "照片墙 / 报工");
    await entry?.trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("还缺：服务防护、取宠对比");
    expect(wrapper.text()).not.toContain("还缺：接宠检查");
  });

  it("三道齐了才能报工：调用 reportOrder，提示里说清已写入健康档案，并刷新列表", async () => {
    listOrders.mockResolvedValue(page([ONGOING]));
    getOrder.mockResolvedValue(ongoingWith(wall({ slot: 3, photos: [103], missing: [] })));
    reportOrder.mockResolvedValue({ ...detail(ONGOING, 3), reported_at: "2026-09-30 11:20:00" });

    const { wrapper } = await mountPage(OrdersView, "/b/orders");
    const entry = wrapper.findAll(".ph-table__action").find((button) => button.text() === "照片墙 / 报工");
    await entry?.trigger("click");
    await flushPromises();

    const reportButton = wrapper.findAll("button").find((button) => button.text() === "报工完成");
    expect(reportButton?.attributes("disabled")).toBeUndefined();
    await reportButton?.trigger("click");
    await flushPromises();

    expect(reportOrder).toHaveBeenCalledWith(13, { remark: undefined });
    expect(wrapper.text()).toContain("已报工");
    expect(wrapper.text()).toContain("健康档案");
    expect(listOrders).toHaveBeenCalledTimes(2);
  });

  it("报工被拒（40900）：后端那句话原样显示——它会点名缺哪一道", async () => {
    listOrders.mockResolvedValue(page([ONGOING]));
    getOrder.mockResolvedValue(ongoingWith(wall({ slot: 3, photos: [103], missing: [] })));
    reportOrder.mockRejectedValue(
      apiFailure(40900, "三道照片墙还缺：接宠检查、服务防护，每道至少一张照片才能报工", "req-409-wall"),
    );

    const { wrapper } = await mountPage(OrdersView, "/b/orders");
    const entry = wrapper.findAll(".ph-table__action").find((button) => button.text() === "照片墙 / 报工");
    await entry?.trigger("click");
    await flushPromises();

    const reportButton = wrapper.findAll("button").find((button) => button.text() === "报工完成");
    await reportButton?.trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("三道照片墙还缺：接宠检查、服务防护，每道至少一张照片才能报工");
    expect(wrapper.text()).toContain("req-409-wall");
  });

  it("选择照片：先直传（带 pet_id），再把 file_id 挂到那一道槽位上，并用服务端返回的墙刷新", async () => {
    listOrders.mockResolvedValue(page([ONGOING]));
    getOrder.mockResolvedValue(ongoingWith(wall({ slot: 1, photos: [101], missing: [2, 3] })));
    saveOrderPhotoSlot.mockResolvedValue(wall({ slot: 1, photos: [101, 201], missing: [2, 3] }));

    const { wrapper } = await mountPage(OrdersView, "/b/orders");
    const entry = wrapper.findAll(".ph-table__action").find((button) => button.text() === "照片墙 / 报工");
    await entry?.trigger("click");
    await flushPromises();

    await pick(wrapper, [image("checkin.jpg")]);

    expect(uploadCarePhotos).toHaveBeenCalledTimes(1);
    expect(uploadCarePhotos.mock.calls[0][0]).toBe(7);
    expect(saveOrderPhotoSlot).toHaveBeenCalledWith(13, 1, { file_ids: [101, 201], remark: undefined });
    expect(wrapper.findAll(".ph-wall__photo")).toHaveLength(2);
  });

  it("每槽位最多 9 张：已有 9 张时再选一张，本地就拦住（一次请求都不发）", async () => {
    listOrders.mockResolvedValue(page([ONGOING]));
    getOrder.mockResolvedValue(
      ongoingWith(wall({ slot: 1, photos: [1, 2, 3, 4, 5, 6, 7, 8, 9], missing: [2, 3] })),
    );

    const { wrapper } = await mountPage(OrdersView, "/b/orders");
    const entry = wrapper.findAll(".ph-table__action").find((button) => button.text() === "照片墙 / 报工");
    await entry?.trigger("click");
    await flushPromises();

    await pick(wrapper, [image("tenth.jpg")]);

    expect(wrapper.text()).toContain("最多 9 张");
    expect(uploadCarePhotos).not.toHaveBeenCalled();
    expect(saveOrderPhotoSlot).not.toHaveBeenCalled();
  });

  it("40901（已终结不可改）：保存槽位被拒时补上「只能走运营干预」这句话", async () => {
    listOrders.mockResolvedValue(page([ONGOING]));
    getOrder.mockResolvedValue(ongoingWith(wall({ slot: 1, photos: [101], missing: [2, 3] })));
    saveOrderPhotoSlot.mockRejectedValue(apiFailure(40901, "订单已报工完成，照片与备注不能再改", "req-901"));

    const { wrapper } = await mountPage(OrdersView, "/b/orders");
    const entry = wrapper.findAll(".ph-table__action").find((button) => button.text() === "照片墙 / 报工");
    await entry?.trigger("click");
    await flushPromises();

    const save = wrapper.findAll("button").find((button) => button.text() === "保存本道");
    await save?.trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("订单已报工完成，照片与备注不能再改");
    expect(wrapper.text()).toContain("修正只能走运营干预");
  });
});
