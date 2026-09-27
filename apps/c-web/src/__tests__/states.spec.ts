/**
 * 四态组件的行为测试（切片 #96 验收标准：「空态/加载/错误/无权限四种状态组件可复用」）。
 *
 * 「可复用」体现在：它们都靠 props 驱动、不依赖具体页面、事件交给调用方。
 * 所以这里逐个验「props 生效 + 对外事件发出」。
 */
import { describe, expect, it } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createRouter, createMemoryHistory } from "vue-router";
import StateEmpty from "../components/states/StateEmpty.vue";
import StateLoading from "../components/states/StateLoading.vue";
import StateError from "../components/states/StateError.vue";
import StateForbidden from "../components/states/StateForbidden.vue";

describe("四态组件", () => {
  it("空态：标题、说明与默认插槽的按钮都能自定义", () => {
    const wrapper = mount(StateEmpty, {
      props: { title: "还没有宠物", description: "先建一份档案" },
      slots: { default: "<button>去建档</button>" },
    });

    expect(wrapper.text()).toContain("还没有宠物");
    expect(wrapper.text()).toContain("先建一份档案");
    expect(wrapper.get("button").text()).toBe("去建档");
  });

  it("空态：不传 props 时给合理默认值（可以直接 <StateEmpty />）", () => {
    const wrapper = mount(StateEmpty);
    expect(wrapper.text()).toContain("还没有内容");
  });

  it("加载态：骨架条数量跟 rows 走，并带 aria 标记", () => {
    const wrapper = mount(StateLoading, { props: { rows: 5 } });
    expect(wrapper.findAll(".ph-state__skeleton")).toHaveLength(5);
    expect(wrapper.get('[role="status"]').attributes("aria-label")).toBe("加载中");
  });

  it("错误态：显示请求 ID，并对外抛 retry 事件", async () => {
    const wrapper = mount(StateError, {
      props: { message: "加载失败", requestId: "abc123" },
    });

    expect(wrapper.text()).toContain("加载失败");
    expect(wrapper.text()).toContain("abc123");

    await wrapper.get("button").trigger("click");
    expect(wrapper.emitted("retry")).toHaveLength(1);
  });

  it("无权限态：默认动作是去登录，并带上当前位置做回跳", async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: "/records", name: "records", component: { template: "<div/>" } },
        { path: "/login", name: "login", component: { template: "<div/>" } },
      ],
    });
    await router.push("/records");
    await router.isReady();

    const wrapper = mount(StateForbidden, { global: { plugins: [router] } });
    expect(wrapper.text()).toContain("登录后查看");

    await wrapper.get("button").trigger("click");
    // 跳转是异步的：trigger 只发事件，等 promise 落地再看路由
    await flushPromises();

    expect(router.currentRoute.value.name).toBe("login");
    expect(router.currentRoute.value.query.redirect).toBe("/records");
  });
});
