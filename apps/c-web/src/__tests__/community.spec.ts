/**
 * 社区（切片 #84 / F020）的组件测试。
 *
 * 验的都是「肉眼看不出来、串一次就骗人」的东西：
 *   - **匿名**：卡片列表里不许出现任何身份信息（会话里那个昵称一次都不该露出来）；
 *   - **四态**：加载 / 空 / 错误（带请求 ID 与重试）/ 未登录；
 *   - **写操作真的调了接口**：点赞、提问、回答（带幂等键）；
 *   - **权益门禁**：没有 `community.post` 时是「如实说明 + 邀请入口」，**不是错误**；
 *     提交时被 40300 拒也一样（ADR-0051 第四节：40300 与 40100 的引导方向相反）；
 *   - **竞态**：切筛选后先发的请求后回来，不许把新结果盖成旧的（页面用 createLatestGuard）；
 *   - **采纳的唯一入口**：只有提问人看得到按钮，且只给已过审的回答。
 *
 * 接口一律打桩：这一层验的是页面行为，不是后端（后端有 Testcontainers 那套，见 ADR-0014）。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, type VueWrapper } from "@vue/test-utils";
import type { TimelineEventView } from "@pet-health/shared";
import type { RightsEvaluationView } from "../api/commerce";
import type { CommunityAnswerView, CommunityCardView, CommunityQuestionView } from "../api/community";
import CommunityView from "../views/CommunityView.vue";
import { apiFailure, mountPage } from "./support";

const listCards = vi.fn();
const likeCard = vi.fn();
const unlikeCard = vi.fn();
const createCard = vi.fn();
const listQuestions = vi.fn();
const getQuestion = vi.fn();
const createQuestion = vi.fn();
const createAnswer = vi.fn();
const adoptAnswer = vi.fn();
vi.mock("../api/community", () => ({
  community: {
    listCards: (...args: unknown[]) => listCards(...args),
    likeCard: (...args: unknown[]) => likeCard(...args),
    unlikeCard: (...args: unknown[]) => unlikeCard(...args),
    createCard: (...args: unknown[]) => createCard(...args),
    listQuestions: (...args: unknown[]) => listQuestions(...args),
    getQuestion: (...args: unknown[]) => getQuestion(...args),
    createQuestion: (...args: unknown[]) => createQuestion(...args),
    createAnswer: (...args: unknown[]) => createAnswer(...args),
    adoptAnswer: (...args: unknown[]) => adoptAnswer(...args),
  },
}));

/**
 * 写卡片时的来源清单走**时间轴**（它是 C 端唯一同时给出记录 id 与记录类型的出口），
 * 所以这里只把 `cApp.listTimeline` 打桩，其余共享代码（守卫、错误分类、格式化）用真实现。
 */
const listTimeline = vi.fn();
vi.mock("@pet-health/shared", async () => {
  const actual = await vi.importActual<typeof import("@pet-health/shared")>("@pet-health/shared");
  return {
    ...actual,
    cApp: { listTimeline: (...args: unknown[]) => listTimeline(...args) },
  };
});

/** 发帖权益从 ph-privilege 的实时判定来（ADR-0045）：页面只是读结论，不自己拼规则。 */
const getRights = vi.fn();
vi.mock("../api/commerce", () => ({
  commerce: { getRights: (...args: unknown[]) => getRights(...args) },
}));

const RIGHT_HELD: RightsEvaluationView = {
  user_id: 1,
  rights: [
    { code: "community.post", name: "社区发帖", effective: true, source: 3, source_name: "邀请", expire_at: null },
  ],
};

const RIGHT_MISSING: RightsEvaluationView = {
  user_id: 1,
  rights: [
    { code: "community.post", name: "社区发帖", effective: false, source: null, source_name: null, expire_at: null },
  ],
};

const CARD_DOG: CommunityCardView = {
  id: 31,
  source_type: 1,
  source_type_name: "打卡",
  title: "柯基换季掉毛这样处理",
  content: "每天梳两次，换毛期把窝垫换成好清洗的那种。",
  species: 1,
  breed: "柯基",
  disease_tag: null,
  like_count: 3,
  liked: false,
  mine: false,
  status: 1,
  status_name: "已发布",
  reject_reason: null,
  created_at: "2026-09-28 10:00:00",
};

const CARD_CAT: CommunityCardView = {
  ...CARD_DOG,
  id: 32,
  title: "猫的慢性肾病日常护理",
  species: 2,
  breed: "英短",
  disease_tag: "慢性肾病",
  like_count: 7,
};

const QUESTION_OTHER: CommunityQuestionView = {
  id: 71,
  title: "八岁的猫喝水变多要留意什么？",
  content: "这周每天喝掉一整碗，体重没变，精神正常。",
  disease_tag: "慢性肾病",
  answer_count: 1,
  adopted_answer_id: null,
  mine: false,
  status: 1,
  status_name: "已发布",
  reject_reason: null,
  created_at: "2026-09-29 09:00:00",
  answers: [],
};

const ANSWER_PUBLISHED: CommunityAnswerView = {
  id: 501,
  question_id: 71,
  content: "我家猫同样情况，先记了饮水量，然后做了尿检。",
  adopted: false,
  mine: false,
  status: 1,
  status_name: "已发布",
  reject_reason: null,
  created_at: "2026-09-29 12:00:00",
};

/** 我自己写的那条还在待审：详情里要能看到它（契约：可见范围 = 已发布的 + 我自己写的）。 */
const ANSWER_MINE_PENDING: CommunityAnswerView = {
  ...ANSWER_PUBLISHED,
  id: 502,
  content: "补充：饮水量的记录能帮医生判断，带过去。",
  mine: true,
  status: 0,
  status_name: "待审核",
};

/** 一页结果的形状（契约的 PageResult）。 */
function page<T>(list: T[]) {
  return { list, page: 1, page_size: 20, total: list.length, has_more: false };
}

/** 切到「问答互助」那一段：问答列表是第一次切过去才拉的。 */
async function openQuestionsTab(wrapper: VueWrapper): Promise<void> {
  const tab = wrapper.findAll("button").find((button) => button.text() === "问答互助");
  if (!tab) throw new Error("没有找到「问答互助」那一段的入口");
  await tab.trigger("click");
  await flushPromises();
}

beforeEach(() => {
  listCards.mockReset();
  likeCard.mockReset();
  unlikeCard.mockReset();
  createCard.mockReset();
  listQuestions.mockReset();
  getQuestion.mockReset();
  createQuestion.mockReset();
  createAnswer.mockReset();
  adoptAnswer.mockReset();
  listTimeline.mockReset();
  getRights.mockReset();
  getRights.mockResolvedValue(RIGHT_HELD);
});

// ---------------------------------------------------------------- 写一张经验卡片（F020）

/** 一条就医记录（时间轴事件 id 就是档案记录 id）与一条异常打卡：契约允许的两种来源。 */
const MEDICAL_EVENT: TimelineEventView = {
  id: 41,
  type: "medical",
  type_name: "就医记录",
  category: 8,
  date: "2026-09-20",
  title: "呕吐就诊",
  summary: "禁食一天后好转",
  source: 1,
  source_label: "用户录入",
  abnormal: true,
  backfilled: false,
};

const ABNORMAL_EVENT: TimelineEventView = {
  ...MEDICAL_EVENT,
  id: 42,
  type: "abnormal",
  type_name: "异常记录",
  category: 3,
  title: "排泄标注为异常",
  summary: undefined,
};

/** 疫苗事件不是卡片可关联的来源（契约的 `source_type` 只有 1 打卡 / 2 就医记录）。 */
const VACCINE_EVENT: TimelineEventView = {
  ...MEDICAL_EVENT,
  id: 43,
  type: "vaccine",
  type_name: "疫苗",
  title: "狂犬疫苗",
};

/** 打开录入面板（默认是收起的）。 */
async function openCardForm(wrapper: VueWrapper): Promise<void> {
  const toggle = wrapper.findAll("button").find((button) => button.text() === "开始写");
  if (!toggle) throw new Error("没有找到「开始写」入口");
  await toggle.trigger("click");
  await flushPromises();
}

describe("社区：写一张经验卡片（F020 的录入入口）", () => {
  it("来源从记录里来：只列就医与异常打卡，选一条后预填标题与正文", async () => {
    listCards.mockResolvedValue(page([CARD_DOG]));
    listTimeline.mockResolvedValue(page([MEDICAL_EVENT, VACCINE_EVENT, ABNORMAL_EVENT]));

    const { wrapper } = await mountPage(CommunityView, "/community");
    await openCardForm(wrapper);

    expect(listTimeline).toHaveBeenCalledWith(7, { pageSize: 20 }, expect.anything());
    const options = wrapper.findAll(".ph-community__form option").map((option) => option.text());
    expect(options.some((text) => text.includes("呕吐就诊"))).toBe(true);
    expect(options.some((text) => text.includes("排泄标注为异常"))).toBe(true);
    expect(options.some((text) => text.includes("狂犬疫苗"))).toBe(false);   // 疫苗不能挂卡片来源

    await wrapper.find(".ph-community__form select").setValue("medical:41");

    expect((wrapper.find('input[placeholder*="柯基换季掉毛"]').element as HTMLInputElement).value).toBe("呕吐就诊");
    expect((wrapper.find("textarea").element as HTMLTextAreaElement).value).toContain("我当时的处理");
  });

  it("提交：带 pet_id / source_type / source_ref 与幂等键，成功后切到「我发的」看审核状态", async () => {
    listCards.mockResolvedValue(page([]));
    listTimeline.mockResolvedValue(page([MEDICAL_EVENT]));
    createCard.mockResolvedValue({ ...CARD_DOG, id: 99, mine: true, status: 0, status_name: "待审核" });

    const { wrapper } = await mountPage(CommunityView, "/community");
    await openCardForm(wrapper);
    await wrapper.find(".ph-community__form select").setValue("medical:41");

    await wrapper.findAll("button").find((button) => button.text() === "提交卡片")?.trigger("click");
    await flushPromises();

    expect(createCard).toHaveBeenCalledWith(
      expect.objectContaining({
        pet_id: 7,               // 来源记录所属的宠物：服务端会校验归属
        source_type: 2,          // 2 = 就医记录（契约 only 1 打卡 / 2 就医）
        source_ref: "41",        // 就是那条时间轴事件的 id（= 档案记录 id）
        title: "呕吐就诊",
        species: 1,              // 物种 / 品种是卡片上的快照（用于同款聚合）
      }),
      expect.any(String),        // 幂等键
    );
    expect(wrapper.text()).toContain("卡片已提交");
    // 新卡片在待审里：不切过去，用户眼前没有任何变化（与提问同一手法）
    expect(listCards).toHaveBeenLastCalledWith(expect.objectContaining({ mine: true, page: 1 }), expect.anything());
  });

  it("没有可关联的记录：如实说「先去记一条」，不给提交按钮（来源是契约的必填项）", async () => {
    listCards.mockResolvedValue(page([]));
    listTimeline.mockResolvedValue(page([VACCINE_EVENT]));

    const { wrapper } = await mountPage(CommunityView, "/community");
    await openCardForm(wrapper);

    expect(wrapper.text()).toContain("还没有可关联的记录");
    expect(wrapper.findAll("button").some((button) => button.text() === "提交卡片")).toBe(false);
  });

  it("没有发帖权益：入口换成解锁说明（不是错误），也不显示表单", async () => {
    listCards.mockResolvedValue(page([]));
    getRights.mockResolvedValue(RIGHT_MISSING);

    const { wrapper } = await mountPage(CommunityView, "/community");

    expect(wrapper.text()).toContain("发布经验卡片需要邀请好友或打卡解锁");
    expect(wrapper.findAll("button").some((button) => button.text() === "开始写")).toBe(false);
    expect(listTimeline).not.toHaveBeenCalled();
  });
});

describe("社区：同款宠友圈（经验卡片）", () => {
  it("卡片列表：渲染标题、摘要、标签与点赞数，且**不出现任何身份信息**", async () => {
    listCards.mockResolvedValue(page([CARD_DOG]));

    const { wrapper } = await mountPage(CommunityView, "/community");

    expect(wrapper.text()).toContain("柯基换季掉毛这样处理");
    expect(wrapper.text()).toContain("同品种：柯基");
    expect(wrapper.text()).toContain("点赞（3）");
    // 恒匿名（ADR-0051 第二节）：作者标签只有「匿名宠友」这一种，
    // 会话里那个昵称（小明）在这里一次都不该出现
    expect(wrapper.text()).toContain("匿名宠友");
    expect(wrapper.text()).not.toContain("小明");
  });

  it("空态：说明「过了审核才会出现」，而不是只写「暂无数据」", async () => {
    listCards.mockResolvedValue(page([]));

    const { wrapper } = await mountPage(CommunityView, "/community");

    expect(wrapper.find(".ph-state--empty").exists()).toBe(true);
    expect(wrapper.text()).toContain("还没有经验卡片");
    expect(wrapper.text()).toContain("过了审核");
  });

  it("错误态：显示后端那句话与请求 ID，并能重试", async () => {
    listCards.mockRejectedValueOnce(apiFailure(50000, "服务器内部错误", "req-500"));
    listCards.mockResolvedValueOnce(page([CARD_DOG]));

    const { wrapper } = await mountPage(CommunityView, "/community");

    expect(wrapper.text()).toContain("服务器内部错误");
    expect(wrapper.text()).toContain("req-500");

    await wrapper.get(".ph-state--error button").trigger("click");
    await flushPromises();

    expect(listCards).toHaveBeenCalledTimes(2);
    expect(wrapper.text()).toContain("柯基换季掉毛这样处理");
  });

  it("点赞：调点赞接口（带幂等键），并用服务端回来的那张卡片替换这一行", async () => {
    listCards.mockResolvedValue(page([CARD_DOG]));
    likeCard.mockResolvedValue({ ...CARD_DOG, liked: true, like_count: 4 });

    const { wrapper } = await mountPage(CommunityView, "/community");
    const likeButton = wrapper.findAll("button").find((button) => button.text().includes("点赞"));
    expect(likeButton).toBeTruthy();

    await likeButton?.trigger("click");
    await flushPromises();

    const [cardId, idempotencyKey] = likeCard.mock.calls[0] as [number, string];
    expect(cardId).toBe(31);
    // 幂等键由调用点生成（ADR-0028）：点赞会重试，服务端靠它只算一次
    expect(typeof idempotencyKey).toBe("string");
    // 计数以服务端为准：界面上不会出现「本地 +1 与真值不一致」
    expect(wrapper.text()).toContain("已赞（4）");
  });

  it("竞态：切物种筛选后，先发的那次请求后回来，不许覆盖新结果", async () => {
    // 手动的 deferred：先把第一次请求挂在半空，等新筛选的结果落地后再放行它
    let releaseFirst!: (value: unknown) => void;
    const firstRequest = new Promise((resolve) => {
      releaseFirst = resolve;
    });
    listCards.mockReturnValueOnce(firstRequest);
    listCards.mockResolvedValueOnce(page([CARD_CAT]));

    const { wrapper } = await mountPage(CommunityView, "/community");
    // 第一次请求还挂着，用户切到「猫」
    const catFilter = wrapper.findAll(".ph-community__filter").find((button) => button.text() === "猫");
    await catFilter?.trigger("click");
    await flushPromises();

    expect(listCards).toHaveBeenLastCalledWith({ species: 2, page: 1, pageSize: 20 }, expect.anything());
    expect(wrapper.text()).toContain("猫的慢性肾病日常护理");

    // 旧请求现在才回来（先发后回）：它的结果必须被丢弃
    releaseFirst(page([CARD_DOG]));
    await flushPromises();

    expect(wrapper.text()).not.toContain("柯基换季掉毛这样处理");
    expect(wrapper.text()).toContain("猫的慢性肾病日常护理");
  });
});

describe("社区：问答互助", () => {
  it("提问：把标题 / 正文 / 病种标签提交给接口（带幂等键），并把列表切到「我提的」", async () => {
    listCards.mockResolvedValue(page([]));
    listQuestions.mockResolvedValue(page([]));
    createQuestion.mockResolvedValue({ ...QUESTION_OTHER, id: 72, mine: true, status: 0 });

    const { wrapper } = await mountPage(CommunityView, "/community");
    await openQuestionsTab(wrapper);

    await wrapper.get(".ph-community__form input").setValue("猫最近喝水变多");
    await wrapper.get(".ph-community__form textarea.ph-community__textarea").setValue("这周每天喝掉一整碗，体重没变。");
    const tags = wrapper.findAll(".ph-community__form input");
    await tags[tags.length - 1]?.setValue("慢性肾病");

    await wrapper
      .findAll("button")
      .find((button) => button.text() === "提交提问")
      ?.trigger("click");
    await flushPromises();

    const [body, idempotencyKey] = createQuestion.mock.calls[0] as [Record<string, unknown>, string];
    expect(body).toEqual({ title: "猫最近喝水变多", content: "这周每天喝掉一整碗，体重没变。", disease_tag: "慢性肾病" });
    expect(typeof idempotencyKey).toBe("string");
    // 刚提的问题在待审里，默认列表看不到它：界面必须切到「我提的」，否则用户以为没提交成功
    expect(listQuestions).toHaveBeenLastCalledWith({ mine: true, page: 1, pageSize: 20 }, expect.anything());
    expect(wrapper.text()).toContain("提问已提交");
  });

  it("回答：展开一条提问看回答，提交时把正文交给接口（带幂等键）", async () => {
    listCards.mockResolvedValue(page([]));
    listQuestions.mockResolvedValue(page([QUESTION_OTHER]));
    getQuestion.mockResolvedValueOnce({ ...QUESTION_OTHER, answers: [ANSWER_PUBLISHED] });
    getQuestion.mockResolvedValue({ ...QUESTION_OTHER, answers: [ANSWER_PUBLISHED, ANSWER_MINE_PENDING] });
    createAnswer.mockResolvedValue(ANSWER_MINE_PENDING);

    const { wrapper } = await mountPage(CommunityView, "/community");
    await openQuestionsTab(wrapper);

    // 列表里不带回答正文（契约）：点开才拉详情
    expect(wrapper.text()).not.toContain(ANSWER_PUBLISHED.content);
    await wrapper
      .findAll("button")
      .find((button) => button.text().includes("查看回答"))
      ?.trigger("click");
    await flushPromises();

    expect(getQuestion).toHaveBeenCalledWith(71, expect.anything());
    expect(wrapper.text()).toContain(ANSWER_PUBLISHED.content);

    await wrapper.get(".ph-community__answers textarea").setValue("带上饮水量的记录去查尿检。");
    await wrapper
      .findAll("button")
      .find((button) => button.text() === "提交回答")
      ?.trigger("click");
    await flushPromises();

    const [questionId, body, idempotencyKey] = createAnswer.mock.calls[0] as [number, Record<string, unknown>, string];
    expect(questionId).toBe(71);
    expect(body).toEqual({ content: "带上饮水量的记录去查尿检。" });
    expect(typeof idempotencyKey).toBe("string");
    // 自己写的回答即使还在待审也要看得到（否则「我明明答了」与「详情里没有」会同时成立）
    expect(wrapper.text()).toContain("待审核");
  });

  it("采纳：只有提问人看得到按钮，且只给已过审的回答", async () => {
    listCards.mockResolvedValue(page([]));
    listQuestions.mockResolvedValue(page([{ ...QUESTION_OTHER, mine: true }]));
    getQuestion.mockResolvedValue({ ...QUESTION_OTHER, mine: true, answers: [ANSWER_PUBLISHED, ANSWER_MINE_PENDING] });
    adoptAnswer.mockResolvedValue({ ...QUESTION_OTHER, mine: true, adopted_answer_id: ANSWER_PUBLISHED.id });

    const { wrapper } = await mountPage(CommunityView, "/community");
    await openQuestionsTab(wrapper);
    await wrapper
      .findAll("button")
      .find((button) => button.text().includes("查看回答"))
      ?.trigger("click");
    await flushPromises();

    const adoptButtons = wrapper.findAll("button").filter((button) => button.text().includes("采纳这条"));
    // 两条回答里只有已过审的那条能被采纳（没审过的回答提问者也看不见，看不见的不能被采纳）
    expect(adoptButtons).toHaveLength(1);
    await adoptButtons[0]?.trigger("click");
    await flushPromises();

    const [questionId, answerId, idempotencyKey] = adoptAnswer.mock.calls[0] as [number, number, string];
    expect(questionId).toBe(71);
    expect(answerId).toBe(501);
    expect(typeof idempotencyKey).toBe("string");
  });

  it("采纳：别人提的问题上不给采纳入口（采纳按钮只出现在提问者界面）", async () => {
    listCards.mockResolvedValue(page([]));
    listQuestions.mockResolvedValue(page([QUESTION_OTHER]));
    getQuestion.mockResolvedValue({ ...QUESTION_OTHER, mine: false, answers: [ANSWER_PUBLISHED] });

    const { wrapper } = await mountPage(CommunityView, "/community");
    await openQuestionsTab(wrapper);
    await wrapper
      .findAll("button")
      .find((button) => button.text().includes("查看回答"))
      ?.trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain(ANSWER_PUBLISHED.content);
    expect(wrapper.findAll("button").some((button) => button.text().includes("采纳这条"))).toBe(false);
    expect(adoptAnswer).not.toHaveBeenCalled();
  });
});

describe("社区：发帖权益与身份", () => {
  it("未持有 community.post：如实说明「邀请好友或打卡解锁」并给外出入口，**不显示成错误**", async () => {
    listCards.mockResolvedValue(page([]));
    listQuestions.mockResolvedValue(page([]));
    getRights.mockResolvedValue(RIGHT_MISSING);

    const { wrapper } = await mountPage(CommunityView, "/community");
    await openQuestionsTab(wrapper);

    expect(wrapper.text()).toContain("发布内容需要邀请好友或打卡解锁");
    const inviteLink = wrapper.findAll("a").find((link) => link.attributes("href") === "/invites");
    expect(inviteLink).toBeTruthy();
    // 它不是错误：没有错误态、也没有「重试」；发文表单干脆不给（不给就行不了的按钮）
    expect(wrapper.find(".ph-state--error").exists()).toBe(false);
    expect(wrapper.find(".ph-form__error").exists()).toBe(false);
    expect(wrapper.find(".ph-community__form").exists()).toBe(false);
  });

  it("提交时被 40300 拒：换成权益说明（含后端那句话与请求 ID），不是「失败 + 重试」", async () => {
    listCards.mockResolvedValue(page([]));
    listQuestions.mockResolvedValue(page([]));
    createQuestion.mockRejectedValue(apiFailure(40300, "没有社区发帖权益", "req-403"));

    const { wrapper } = await mountPage(CommunityView, "/community");
    await openQuestionsTab(wrapper);

    await wrapper.get(".ph-community__form input").setValue("猫最近喝水变多");
    await wrapper.get(".ph-community__form textarea.ph-community__textarea").setValue("这周每天喝掉一整碗。");
    await wrapper
      .findAll("button")
      .find((button) => button.text() === "提交提问")
      ?.trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("发布内容需要邀请好友或打卡解锁");
    expect(wrapper.text()).toContain("没有社区发帖权益");
    expect(wrapper.text()).toContain("req-403");
    expect(wrapper.find(".ph-form__error").exists()).toBe(false);
  });

  it("未登录：整页按契约要求登录（社区七条路径都没有 security: []），不发任何请求", async () => {
    const { wrapper } = await mountPage(CommunityView, "/community", "anonymous");

    expect(wrapper.find(".ph-state--forbidden").exists()).toBe(true);
    expect(wrapper.text()).toContain("登录后查看");
    expect(wrapper.text()).toContain("去登录");
    expect(listCards).not.toHaveBeenCalled();
    expect(getRights).not.toHaveBeenCalled();
  });
});
