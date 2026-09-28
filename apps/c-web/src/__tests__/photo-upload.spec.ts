/**
 * 照片上传（切片 #95 / ADR-0020）的组件测试。
 *
 * **桩打在 axios 这一层**，而不是 mock 掉 `@pet-health/shared`：上传是「先取凭证、再直传字节」
 * 两步，若把 shared 整个换掉，被测的恰好就是这两步本身的实现，测试会变成自说自话。
 * 这里让组件、`uploadFiles`、请求封装都跑真代码，只把网络出口换掉。
 *
 * 守住三件在浏览器里容易写错、一眼又看不出来的事：
 *  1. 字节走的是**凭证给的地址**（`open` 域），不是业务接口——直传一旦退化成后端中转，
 *     功能测试照样绿，但照片墙的大图会把应用接口一起拖慢；
 *  2. 多选时**一次申请、逐个直传**；
 *  3. 后端拒绝的原因要显示给用户（九张图里有一张不合规，用户得知道错在哪）。
 */
import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import PhotoUploader from "../components/PhotoUploader.vue";

const transport = vi.hoisted(() => ({
  request: vi.fn(),
  put: vi.fn(),
  refreshPost: vi.fn(),
}));

vi.mock("axios", () => {
  const instance = {
    request: transport.request,
    post: transport.refreshPost,
    interceptors: { request: { use: () => undefined } },
  };
  const axios = { create: () => instance, put: transport.put };
  return { default: axios, ...axios };
});

/** 统一响应的信封（后端成功响应的形状）。 */
function ok<T>(data: T) {
  return { data: { code: 0, message: "success", data, request_id: "test" } };
}

function fail(code: number, message: string) {
  return { data: { code, message, data: null, request_id: "test" } };
}

function image(name: string, type = "image/jpeg", size = 1024): File {
  const file = new File(["x"], name, { type });
  Object.defineProperty(file, "size", { value: size });
  return file;
}

async function pickFiles(wrapper: ReturnType<typeof mount>, files: File[]): Promise<void> {
  const input = wrapper.find('input[type="file"]');
  Object.defineProperty(input.element, "files", { value: files, configurable: true });
  await input.trigger("change");
  await flushPromises();
}

/** 找到发往某个路径的那次 JSON 调用。 */
function callTo(path: string) {
  return transport.request.mock.calls.find((call) => call[0]?.url === path);
}

describe("PhotoUploader", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    transport.put.mockResolvedValue({ status: 204, data: "" });
    transport.request.mockResolvedValue(ok([]));
  });

  it("一次申请、逐个直传到凭证地址，完成后刷新列表", async () => {
    transport.request.mockImplementation((config: { url: string }) => {
      if (config.url === "/api/v1/app/files/presign") {
        return Promise.resolve(ok([
          { file_id: 1, upload_url: "/api/v1/open/files/1/content?token=a", role: "original" },
          { file_id: 2, upload_url: "/api/v1/open/files/2/content?token=b", role: "original" },
        ]));
      }
      return Promise.resolve(ok([
        { id: 1, biz_type: "profile", role: "original", mime: "image/jpeg", size_bytes: 1024, url: "/u1", thumb_url: "/t1" },
        { id: 2, biz_type: "profile", role: "original", mime: "image/jpeg", size_bytes: 1024, url: "/u2", thumb_url: "/t2" },
      ]));
    });

    const wrapper = mount(PhotoUploader, { props: { petId: 7, bizType: "profile" } });
    await flushPromises();
    await pickFiles(wrapper, [image("a.jpg"), image("b.jpg")]);

    const presign = callTo("/api/v1/app/files/presign");
    expect(presign, "应当向 presign 接口申请一次凭证").toBeTruthy();
    expect(presign![0].data.biz_type).toBe("profile");
    expect(presign![0].data.pet_id).toBe(7);
    expect(presign![0].data.items).toHaveLength(2);

    expect(transport.put).toHaveBeenCalledTimes(2);
    expect(transport.put.mock.calls[0][0]).toBe("/api/v1/open/files/1/content?token=a");
    expect(transport.put.mock.calls[1][0]).toBe("/api/v1/open/files/2/content?token=b");

    expect(wrapper.findAll(".ph-photo__thumb")).toHaveLength(2);
    expect(wrapper.find(".ph-photo__thumb").attributes("src")).toBe("/t1");
  });

  it("PNG 按 PNG 声明，JPEG 按 JPEG 声明", async () => {
    transport.request.mockResolvedValue(ok([]));
    const wrapper = mount(PhotoUploader, { props: { petId: 7, bizType: "profile" } });
    await flushPromises();

    await pickFiles(wrapper, [image("a.png", "image/png")]);

    expect(callTo("/api/v1/app/files/presign")![0].data.items[0].mime).toBe("image/png");
  });

  it("后端拒绝时把原因显示出来，且不发第二次请求", async () => {
    transport.request.mockImplementation((config: { url: string }) =>
      config.url === "/api/v1/app/files/presign"
        ? Promise.resolve(fail(40001, "只支持 JPEG 与 PNG"))
        : Promise.resolve(ok([])));

    const wrapper = mount(PhotoUploader, { props: { petId: 7, bizType: "profile" } });
    await flushPromises();
    await pickFiles(wrapper, [image("a.gif", "image/gif")]);

    expect(wrapper.text()).toContain("只支持 JPEG 与 PNG");
    expect(transport.put).not.toHaveBeenCalled();
  });

  it("没有宠物时不列照片，选择按钮禁用", async () => {
    const wrapper = mount(PhotoUploader, { props: { petId: null, bizType: "profile" } });
    await flushPromises();

    expect(transport.request).not.toHaveBeenCalled();
    expect(wrapper.find("button").attributes("disabled")).toBeDefined();
  });

  it("删除调用后端并从列表里去掉", async () => {
    transport.request.mockImplementation((config: { url: string; method?: string }) => {
      if (config.method === "DELETE") return Promise.resolve(ok(null));
      return Promise.resolve(ok([
        { id: 9, biz_type: "profile", role: "original", mime: "image/jpeg", size_bytes: 1024, url: "/u", thumb_url: "/t" },
      ]));
    });

    const wrapper = mount(PhotoUploader, { props: { petId: 7, bizType: "profile" } });
    await flushPromises();
    expect(wrapper.findAll(".ph-photo__thumb")).toHaveLength(1);

    await wrapper.find(".ph-photo__item button").trigger("click");
    await flushPromises();

    expect(callTo("/api/v1/app/files/9")![0].method).toBe("DELETE");
    expect(wrapper.findAll(".ph-photo__thumb")).toHaveLength(0);
  });
});
