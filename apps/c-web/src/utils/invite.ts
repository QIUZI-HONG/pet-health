/**
 * 邀请链路上**属于前端的两件事**（ADR-0039 第一节定的口径）：
 *
 *   1. **落地页记住 7 天**：分享链接带 `?invite=CODE` 时记住这个码，用户过几天回来注册也能预填。
 *      契约写明「记住多久」是前端行为——服务端只负责「注册那一刻」的归因（不做事后补填）；
 *   2. **设备标识**：反作弊判据 `SAME_DEVICE` / `SAME_IP_SEGMENT` 要用它（契约原话：不传则这两条
 *      判据不成立）。没有它，邀请反作弊就少一层。
 *
 * 另外把「被拦下时给用户看的那句话」收在这里：契约规定**不暴露反作弊判据的细节**
 * （ADR-0046 第三节），所以三个反作弊原因共用一句笼统的话，只有「已经归因过」「码不存在」
 * 这两个**与反作弊无关**的原因才说实话。
 */
import { commerce } from "../api/commerce";

/** 落地页记住邀请码的时长（ADR-0039 第一节：链接带 `?invite=CODE`，落地页记住 7 天）。 */
const KEEP_MS = 7 * 24 * 60 * 60 * 1000;
const INVITE_STORAGE_KEY = "ph.c.invite";
const DEVICE_STORAGE_KEY = "ph.c.device";

/** 访问 localStorage 一律包一层：隐私模式下它会抛异常，而这两件事都不值得让页面白屏。 */
function readStorage(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

function writeStorage(key: string, value: string): void {
  try {
    window.localStorage.setItem(key, value);
  } catch {
    // 存不下就算了：邀请码只是预填，设备标识缺失只会让反作弊少一条判据
  }
}

export interface RememberedInvite {
  code: string;
  /** 1 分享链接（预填）——从落地页链接记下来的都是这一种（契约的 `channel` 取值）。 */
  channel: number;
}

/**
 * 应用启动时调一次：地址栏里有 `?invite=CODE` 就记下来（7 天）。
 *
 * 为什么在启动时调而不是在注册页调：用户可能从分享链接落到首页、逛一会儿再去注册，
 * 「落地页记住」说的就是这个时间差。
 */
export function rememberInviteFromUrl(search: string = window.location.search): void {
  const code = new URLSearchParams(search).get("invite")?.trim();
  if (!code) return;
  writeStorage(INVITE_STORAGE_KEY, JSON.stringify({ code, at: Date.now() }));
}

/** 读出仍在 7 天内的邀请码；过期就顺手清掉（免得下次读到一个过期的码去预填）。 */
export function readRememberedInvite(now: number = Date.now()): RememberedInvite | null {
  const raw = readStorage(INVITE_STORAGE_KEY);
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as { code?: unknown; at?: unknown };
    const code = typeof parsed.code === "string" ? parsed.code.trim() : "";
    const at = typeof parsed.at === "number" ? parsed.at : 0;
    if (!code || now - at > KEEP_MS) {
      clearRememberedInvite();
      return null;
    }
    return { code, channel: 1 };
  } catch {
    // 存进去的不是我们自己写的形状（被别的东西覆盖了）：清掉，不要用它去预填
    clearRememberedInvite();
    return null;
  }
}

export function clearRememberedInvite(): void {
  try {
    window.localStorage.removeItem(INVITE_STORAGE_KEY);
  } catch {
    // 清不掉无妨（下次读取时仍会按过期处理）
  }
}

/** 本机的设备标识（第一次访问时生成并持久化）。它只用于反作弊判据，不参与身份识别。 */
export function deviceId(): string {
  const existing = readStorage(DEVICE_STORAGE_KEY);
  if (existing) return existing;
  const created = globalThis.crypto?.randomUUID?.() ?? `dev-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
  writeStorage(DEVICE_STORAGE_KEY, created);
  return created;
}

/**
 * 归因结果 → 给用户看的那句话（**兜底用，正常路径读服务端的 `notice`**）。
 *
 * 为什么还要留着它：服务端从本轮起把文案放在 `data.notice`（契约点名前端直接展示那个字段），
 * 这里是**旧响应的兜底**——后端版本比前端旧、或某个中间层把 `notice` 丢了时，
 * 界面至少不会空白（拼出来的三句与服务端逐字相同）。
 *
 * 三个反作弊原因（`SELF_INVITE` / `SAME_DEVICE` / `SAME_IP_SEGMENT`）**共用一句笼统的话**：
 * 把判据说清楚等于教人怎么绕过（ADR-0046 第三节）。
 */
export function attributionNotice(reason: string | null | undefined): string {
  switch (reason) {
    case "ALREADY_ATTRIBUTED":
      return "该账号已绑定邀请关系";
    case "CODE_NOT_FOUND":
      return "邀请码不存在或已失效";
    default:
      return "邀请码不可用";
  }
}

/** 本地兜底里「归因成功」的那句（与服务端逐字相同）。 */
const ATTRIBUTED_NOTICE = "邀请关系已记录，待生效";

/**
 * 待展示的归因提示 + 订阅者。
 *
 * **为什么要有这个中转**：归因是注册成功后的一次**旁路调用**（不能 await 它——那会把跳转
 * 压在一次网络往返上，网络卡住时用户就卡在注册页），而它返回的那句话要显示在**跳转之后的页面**上。
 * 调用先回来还是页面先挂载，两种顺序都可能，所以两头都接：已到达的取走
 * （{@link takeInviteNotice}），还没到的订阅（{@link onInviteNotice}）。
 */
let pendingNotice = "";
const noticeListeners = new Set<(notice: string) => void>();

function publishNotice(notice: string): void {
  if (!notice) return;
  pendingNotice = notice;
  for (const listener of [...noticeListeners]) {
    listener(notice);
  }
}

/** 取走当前待展示的归因提示（**取走即清空**：同一句话只展示一次）。 */
export function takeInviteNotice(): string {
  const notice = pendingNotice;
  pendingNotice = "";
  return notice;
}

/** 订阅归因提示（页面挂载时调，返回取消订阅的函数）。 */
export function onInviteNotice(listener: (notice: string) => void): () => void {
  noticeListeners.add(listener);
  return () => {
    noticeListeners.delete(listener);
  };
}

/**
 * 注册成功后的那一次归因调用。
 *
 * **注册流程的收尾，不是注册的前置**（ADR-0039 第一节：归因时点就是注册那一刻，不做补填）。
 * 所以它**不抛异常、不阻塞**：注册已经成功了，归因失败或被反作弊拦下都不该把用户留在注册页
 * （ADR-0046 第一节：不能因为邀请码可疑就不让用户注册）。
 *
 * **给用户看的那句话来自服务端**：契约把它放在 `data.notice` 里，并点名「前端直接展示这个字段」
 * （信封的 `message` 在 `code === 0` 时到不了页面——共享请求层只解包 `data`，
 * 这条事实写在 contract/common.yaml 的 `ApiResponse.message` 上）。
 * 所以文案只有服务端一份实现，前端不再按 `reason` 自己拼——{@link attributionNotice}
 * 只作为旧响应的兜底。
 *
 * 拿到的那句话会被交给**跳转之后的页面**展示（见上面的中转）：注册页马上就卸载了，
 * 留在这里显示等于没显示。
 */
export async function attributeAfterRegister(code: string, channel: number): Promise<string> {
  const trimmed = code.trim();
  if (!trimmed) return "";
  try {
    const result = await commerce.attributeInvite({
      invite_code: trimmed,
      channel,
      device_id: deviceId(),
    });
    // 服务端那句优先；它缺失时才退回本地兜底（旧后端 / 中间层丢了字段），两种分支都不抛错
    const notice =
      result.notice?.trim() ||
      (result.attributed
        ? // 归因成功时关系是「待生效」：观察窗内有没有行为由服务端的结算批算判（ADR-0046 第二节）
          ATTRIBUTED_NOTICE
        : attributionNotice(result.reason));
    if (!result.attributed) {
      console.warn("[invite] 注册归因未建立邀请关系", { reason: result.reason, notice });
    }
    publishNotice(notice);
    return notice;
  } catch (error) {
    // 不吞异常：日志里留一条，但界面不受影响（这是旁路调用）
    console.warn("[invite] 注册归因调用失败，注册结果不受影响", error);
    return "";
  }
}

