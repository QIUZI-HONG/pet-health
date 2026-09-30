/**
 * 用户管理页（UsersView）的组件测试。
 *
 * **这一页没有写操作，所以没有「写操作调接口」那一条**：契约里没有用户状态接口（没有 `/users`
 * 资源，也没有禁用 / 注销），页面按缺口如实处理成只读——测试就把这件事钉住（页面里不该出现
 * 任何提交按钮），并把「查询这个动作真的把用户 id 传给了三块只读视图」验掉：那是这一页的核心行为。
 *
 * 四态里「空」有两层意思，都要验：**还没查**（先填 id）与**查了但没数据**（这个用户没有积分流水）。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises } from "@vue/test-utils";
import type { GrantRow, InviteRelationRow, PointRecordRow } from "../api/adminApi";
import UsersView from "../views/UsersView.vue";
import { apiFailure, buttonByText, mountPage } from "./support";

const listPointRecords = vi.fn();
const getUserRights = vi.fn();
const listRightsGrants = vi.fn();
const listInviteRelations = vi.fn();

vi.mock("../api/adminApi", () => ({
  adminApp: {
    listPointRecords: (...args: unknown[]) => listPointRecords(...args),
    getUserRights: (...args: unknown[]) => getUserRights(...args),
    listRightsGrants: (...args: unknown[]) => listRightsGrants(...args),
    listInviteRelations: (...args: unknown[]) => listInviteRelations(...args),
  },
}));

const RECORD: PointRecordRow = {
  id: 1,
  user_id: 1001,
  behavior_code: "CHECK_IN",
  behavior_name: "打卡",
  change: 3,
  balance_after: 11,
  counts_toward_daily_cap: 1,
  source_ref: "2026-09-30",
  created_at: "2026-09-30 08:00:00",
};

const GRANT: GrantRow = {
  id: 5,
  user_id: 1001,
  code: "ai.unlimited",
  code_name: "无限 AI 问答",
  source: 4,
  status: 1,
  source_ref: "客诉补偿-2026-09",
  created_at: "2026-09-01 09:00:00",
};

const RELATION: InviteRelationRow = {
  id: 7,
  inviter_user_id: 1001,
  invitee_user_id: 2002,
  invite_code: "PH8K2M",
  channel: 1,
  status: 3,
  reject_reason: "SAME_DEVICE",
  attributed_at: "2026-09-20 10:00:00",
};

function page(list: unknown[]) {
  return { list, page: 1, page_size: 20, total: list.length, has_more: false };
}

beforeEach(() => {
  listPointRecords.mockReset().mockResolvedValue(page([RECORD]));
  getUserRights.mockReset().mockResolvedValue({
    user_id: 1001,
    rights: [{ code: "ai.unlimited", name: "无限 AI 问答", effective: true, source_name: "运营补偿", expire_at: null }],
  });
  listRightsGrants.mockReset().mockResolvedValue(page([GRANT]));
  listInviteRelations.mockReset().mockResolvedValue(page([RELATION]));
});

/** 填一个用户 id 并查询（这一页所有数据都挂在这一次查询之后） */
async function queryUser(wrapper: Awaited<ReturnType<typeof mountPage>>["wrapper"], userId = "1001"): Promise<void> {
  await wrapper.get('input[placeholder="如：1001"]').setValue(userId);
  await buttonByText(wrapper, "查询").trigger("click");
  await flushPromises();
}

describe("用户管理：查询与渲染", () => {
  it("还没查时给「先填一个用户 id」，不发任何请求", async () => {
    const { wrapper } = await mountPage(UsersView, "/admin/users");

    expect(wrapper.text()).toContain("先填一个用户 id 查询");
    expect(listPointRecords).not.toHaveBeenCalled();
    expect(getUserRights).not.toHaveBeenCalled();
  });

  it("查询：把用户 id 传给三块只读视图，并渲染积分流水（余额取自最新一条）", async () => {
    const { wrapper } = await mountPage(UsersView, "/admin/users");
    await queryUser(wrapper);

    expect(listPointRecords).toHaveBeenCalledWith({ userId: 1001, behaviorCode: undefined, page: 1, pageSize: 20 }, expect.anything());
    expect(getUserRights).toHaveBeenCalledWith(1001);
    expect(listRightsGrants).toHaveBeenCalledWith({ userId: 1001, page: 1, pageSize: 20 }, expect.anything());
    // 邀请关系默认看「他邀请了谁」
    expect(listInviteRelations).toHaveBeenCalledWith({ inviterUserId: 1001, page: 1, pageSize: 20 }, expect.anything());

    expect(wrapper.text()).toContain("打卡");
    expect(wrapper.text()).toContain("+3");
    expect(wrapper.text()).toContain("当前余额 11");
  });

  it("切到权益与邀请分段：渲染判定结果、授予来源与反作弊判据", async () => {
    const { wrapper } = await mountPage(UsersView, "/admin/users");
    await queryUser(wrapper);

    await buttonByText(wrapper, "权益").trigger("click");
    expect(wrapper.text()).toContain("无限 AI 问答");
    expect(wrapper.text()).toContain("运营补偿");

    await buttonByText(wrapper, "邀请关系").trigger("click");
    expect(wrapper.text()).toContain("PH8K2M");
    expect(wrapper.text()).toContain("SAME_DEVICE");

    // 换个方向看：改查「他是谁邀请来的」
    await wrapper.get(".ph-users__filter select").setValue("invitee");
    await flushPromises();
    expect(listInviteRelations).toHaveBeenLastCalledWith({ inviteeUserId: 1001, page: 1, pageSize: 20 }, expect.anything());
  });

  it("id 不合法时本地就拦下来，不去打接口", async () => {
    const { wrapper } = await mountPage(UsersView, "/admin/users");
    await queryUser(wrapper, "abc");

    expect(wrapper.text()).toContain("请填一个正整数用户 id");
    expect(listPointRecords).not.toHaveBeenCalled();
  });
});

describe("用户管理：四态", () => {
  it("查了但没数据：空态说的是这个用户没有，而不是「加载失败」", async () => {
    listPointRecords.mockResolvedValue(page([]));

    const { wrapper } = await mountPage(UsersView, "/admin/users");
    await queryUser(wrapper);

    expect(wrapper.find(".ph-state--empty").exists()).toBe(true);
    expect(wrapper.text()).toContain("这个用户还没有积分流水");
  });

  it("错误态：显示后端那句话与请求 ID，重试只重读这一块", async () => {
    listPointRecords.mockRejectedValue(apiFailure(50000, "服务器内部错误", "req-500"));

    const { wrapper } = await mountPage(UsersView, "/admin/users");
    await queryUser(wrapper);

    expect(wrapper.text()).toContain("服务器内部错误");
    expect(wrapper.text()).toContain("req-500");

    listPointRecords.mockResolvedValue(page([RECORD]));
    await wrapper.get(".ph-state--error button").trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("打卡");
  });

  it("未登录：闸门说话，且一个业务请求都不发", async () => {
    const { wrapper } = await mountPage(UsersView, "/admin/users", "anonymous");

    expect(wrapper.find(".ph-state--forbidden").exists()).toBe(true);
    expect(listPointRecords).not.toHaveBeenCalled();
  });

  it("只读：这一页没有任何写入口（契约里没有用户状态接口）", async () => {
    const { wrapper } = await mountPage(UsersView, "/admin/users");
    await queryUser(wrapper);

    expect(wrapper.find("form").exists()).toBe(false);
    expect(wrapper.findAll("button").every((item) => ["查询", "积分", "权益", "邀请关系", "上一页", "下一页"].includes(item.text().trim()))).toBe(true);
  });
});
