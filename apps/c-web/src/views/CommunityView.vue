<script setup lang="ts">
/**
 * 社区（切片 #84 / F020）：**同款宠友圈（经验卡片）+ 问答互助**。
 * 决策见 ADR-0041 第一节（三种内容、发文走权益码、内容进审核）、
 * ADR-0050 第一节（本期范围，慢病社群后置）、ADR-0051（匿名、审核状态机、权益门禁、采纳）。
 *
 * 一页两段（Tab）而不是两条路由：两段各自有筛选与分页器，叠在一页上就是两个「下一页」
 * 互相顶掉——点了上面那个，下面那段已经被滚出视口了。而且它们本来就是两件事：
 * 「看别人的经验」与「问 / 答自己的问题」。
 *
 * **未登录**：这一页套 `SessionGate`。判断依据是**契约**——`/community/**` 七条路径
 * 没有一条标 `security: []`（app.yaml 顶部的 `security: bearerAuth` 是全局的），
 * 而未登录时后端回 40100。对照着看更清楚：`/providers` 与 `/catalog` 那几条只读浏览
 * 都**逐条**标了 `security: []`，后端的 `PublicReadRoute` 白名单里也只有它们。
 * 所以「浏览不需要登录」这条 C 端口径**不适用于社区**：不套闸门，未登录访客只会拿到一串 40100，
 * 把「登录后查看」变成一条报错。会话中途失效时另有兜底（`identityNotice` 那条引导条，
 * 行内错误只负责非身份类的失败）。
 *
 * 几条**不许自己发明**的口径（都在契约原文里）：
 *  - **卡片恒匿名**：视图里没有作者 id、昵称、宠物昵称。界面只说「匿名宠友」，
 *    绝不用 `mine` / `source_type` 之类去反推「这是谁家的猫」——`mine` 说的是看的人自己；
 *  - **点赞不需要 `community.post`**（「它不是发文，是互动」）：所以点赞按钮不挂在权益门上；
 *  - **发文（提问 / 回答）要 `community.post`**：没有 → **40300**，而没登录是 **40100**。
 *    两个码的引导方向相反，所以这里分开记账（见 `noteIdentityFailure`）；
 *  - **采纳只有提问者**，且只给**已过审**的回答（没审过的回答提问者也看不见 → 采纳会 40400）。
 *    一条提问只能采纳一个（重复 → 40900），采纳**不回退**（没有「取消采纳」这个动作）。
 *
 * 与 ADR-0041 的一处**已知偏离**：那条决策写的是「嵌在首页与档案页的侧栏，**不做独立 Tab**」。
 * 这一版把两段落成了一页、入口挂在首页「快捷服务」（见 HomeView 的 `quickEntries`），
 * **没有加进左侧主导航**——守住「不做独立 Tab」的本意（不新增导航层级），
 * 也不去改首页 / 档案页的版式（那是那两个页面的所有者的事）。
 */
import { computed, ref, watch } from "vue";
import { useRoute } from "vue-router";
import {
  ApiError,
  cApp,
  createLatestGuard,
  formatDate,
  newIdempotencyKey,
  speciesLabel,
  toApiFailure,
  type TimelineEventView,
} from "@pet-health/shared";
import { useSubmitAction, type SubmitAction } from "@pet-health/ui";
import {
  community,
  type CommunityAnswerView,
  type CommunityCardView,
  type CommunityQuestionView,
} from "../api/community";
import { commerce } from "../api/commerce";
import { useSessionStore } from "../stores/session";
import SessionGate from "../components/SessionGate.vue";
import StateEmpty from "../components/states/StateEmpty.vue";
import StateError from "../components/states/StateError.vue";
import StateLoading from "../components/states/StateLoading.vue";

const PAGE_SIZE = 20;

/** 物种聚合：契约的 `species` 只有 1 犬 / 2 猫（不传表示全部）。 */
const SPECIES_FILTERS: Array<{ value: number | null; label: string }> = [
  { value: null, label: "全部物种" },
  { value: 1, label: "犬" },
  { value: 2, label: "猫" },
];

/** 发帖权益码：契约与 ADR-0041 第一节写死的就是这个字符串，不是前端自己起的名字。 */
const POSTING_RIGHT = "community.post";

type TabKey = "cards" | "questions";

const TABS: Array<{ key: TabKey; label: string }> = [
  { key: "cards", label: "同款宠友圈" },
  { key: "questions", label: "问答互助" },
];

const route = useRoute();
const session = useSessionStore();
const activeTab = ref<TabKey>("cards");

// ---------------------------------------------------------------- 同款宠友圈（经验卡片）

const cards = ref<CommunityCardView[]>([]);
const cardsLoading = ref(true);
const cardsError = ref("");
const cardsRequestId = ref("");
const species = ref<number | null>(null);
/** 输入框里的字；改了**不等于**要立刻发请求——与 ServicesView 的搜索同一口径，
 *  免得输入过程中的每个字都变成一次查询。 */
const breedInput = ref("");
const diseaseInput = ref("");
/** 已提交的筛选词：请求只用它。 */
const appliedBreed = ref("");
const appliedDisease = ref("");
/**
 * `mine=true`：「我发的」——连待审与被拒的一起给。
 * 契约把理由写得很直白：作者要能看到自己的内容停在哪一步，否则「发布」在他眼里就是石沉大海。
 */
const mineCards = ref(false);
const cardsPage = ref(1);
const cardsTotal = ref(0);
const cardsHasMore = ref(false);

/** 并发守卫：连点物种标签或翻页时，先发的请求可能后回来（实现见 shared）。 */
const cardsGuard = createLatestGuard();

const cardFiltersActive = computed(
  () => species.value !== null || appliedBreed.value !== "" || appliedDisease.value !== "",
);

async function loadCards(targetPage = 1): Promise<void> {
  const { token, signal } = cardsGuard.claim();
  cardsLoading.value = true;
  cardsError.value = "";
  cardsRequestId.value = "";
  try {
    const breed = appliedBreed.value.trim();
    const diseaseTag = appliedDisease.value.trim();
    const result = await community.listCards(
      {
        species: species.value ?? undefined,
        // 空白关键词不发：服务端把它当「不过滤」，白送一个参数没有意义
        breed: breed === "" ? undefined : breed,
        diseaseTag: diseaseTag === "" ? undefined : diseaseTag,
        mine: mineCards.value ? true : undefined,
        page: targetPage,
        pageSize: PAGE_SIZE,
      },
      signal,
    );
    if (!cardsGuard.isCurrent(token)) return;
    cards.value = result.list ?? [];
    cardsPage.value = result.page ?? targetPage;
    cardsTotal.value = result.total ?? cards.value.length;
    // 没有 has_more 时退化成「这一页塞满了就还能翻」：两份判据都在契约里（PageResult）
    cardsHasMore.value = result.has_more ?? cards.value.length >= PAGE_SIZE;
  } catch (error) {
    if (!cardsGuard.isCurrent(token)) return;
    const failure = toApiFailure(error, "加载经验卡片失败，请稍后重试");
    cardsError.value = failure.message;
    cardsRequestId.value = failure.requestId;
  } finally {
    if (cardsGuard.isCurrent(token)) {
      cardsLoading.value = false;
    }
  }
}

function selectSpecies(value: number | null): void {
  if (species.value === value) return;
  species.value = value;
  void loadCards(1);
}

/** 关键词与病种**提交时才筛**（回车或点按钮），理由同 ServicesView。 */
function submitCardFilters(): void {
  appliedBreed.value = breedInput.value;
  appliedDisease.value = diseaseInput.value;
  void loadCards(1);
}

function toggleMineCards(): void {
  mineCards.value = !mineCards.value;
  void loadCards(1);
}

/** 只清筛选，不动「只看我发的」——那是「看谁的」不是筛选条件。 */
function clearCardFilters(): void {
  species.value = null;
  breedInput.value = "";
  diseaseInput.value = "";
  appliedBreed.value = "";
  appliedDisease.value = "";
  void loadCards(1);
}

// ---------------------------------------------------------------- 点赞

/**
 * 点赞用**一个**提交状态机 + 记住点的是哪一行：错误与禁用落在被点的那一行上。
 * （`useSubmitAction` 的 2 秒闸门是「同一个动作」级别的——点赞是每行一个按钮，
 * 一行一个状态机反而会让「同一时刻只能有一个点赞在飞」这条约束失效。）
 */
const likeAction = useSubmitAction("点赞失败，请稍后重试");
const likeTargetId = ref<number | null>(null);

async function toggleLike(card: CommunityCardView): Promise<void> {
  const cardId = card.id;
  if (!cardId) return;
  likeTargetId.value = cardId;
  const result = await likeAction.run(
    trackCode(() =>
      card.liked
        ? // 取消点赞是「宠物名下的子资源」口径：本来就没点过也返回成功，不是错误
          community.unlikeCard(cardId)
        : community.likeCard(cardId, newIdempotencyKey()),
    ),
  );
  if (!result.ok) {
    noteIdentityFailure(likeAction);
    return;
  }
  // 服务端回来的那张卡片是**唯一真值**（`liked` + 最新 `like_count`）：本地只替换这一行，
  // 不自己 +1——自己加会与别人的赞、与重复点击算出三个数
  const updated = result.value;
  cards.value = cards.value.map((row) => (row.id === updated.id ? { ...row, ...updated } : row));
}

// ---------------------------------------------------------------- 问答互助

const questions = ref<CommunityQuestionView[]>([]);
const questionsLoading = ref(false);
const questionsError = ref("");
const questionsRequestId = ref("");
const questionDiseaseInput = ref("");
const appliedQuestionDisease = ref("");
const mineQuestions = ref(false);
const questionsPage = ref(1);
const questionsTotal = ref(0);
const questionsHasMore = ref(false);
/** 问答那一段**第一次切过去才拉**：只想看卡片的人不该为另一段付一次请求。 */
const questionsLoaded = ref(false);
const questionsGuard = createLatestGuard();

/** 展开中的那条提问（同一时刻只展开一条：问答页一屏十个问题，全展开等于没有分页）。 */
const expandedQuestionId = ref<number | null>(null);
const detail = ref<CommunityQuestionView | null>(null);
const detailLoading = ref(false);
const detailError = ref("");
const detailRequestId = ref("");
/** 40400：这条提问不在了（还没过审且不是我的，或已被下架）。重试没有意义，也不是「加载失败」。 */
const detailGone = ref(false);
const detailGuard = createLatestGuard();

/** 回答只数已过审的那些（`answer_count` 由服务端维护）；列表里没有 `answers` 时是空数组。 */
const answers = computed(() => detail.value?.answers ?? []);

function collapseDetail(): void {
  expandedQuestionId.value = null;
  detail.value = null;
  detailError.value = "";
  detailGone.value = false;
  // 作废还在飞的那次详情请求：折叠之后它的应答不该再落到界面上
  detailGuard.claim();
}

async function loadQuestions(targetPage = 1): Promise<void> {
  const { token, signal } = questionsGuard.claim();
  questionsLoading.value = true;
  questionsError.value = "";
  questionsRequestId.value = "";
  try {
    const diseaseTag = appliedQuestionDisease.value.trim();
    const result = await community.listQuestions(
      {
        diseaseTag: diseaseTag === "" ? undefined : diseaseTag,
        mine: mineQuestions.value ? true : undefined,
        page: targetPage,
        pageSize: PAGE_SIZE,
      },
      signal,
    );
    if (!questionsGuard.isCurrent(token)) return;
    questions.value = result.list ?? [];
    questionsPage.value = result.page ?? targetPage;
    questionsTotal.value = result.total ?? questions.value.length;
    questionsHasMore.value = result.has_more ?? questions.value.length >= PAGE_SIZE;
    // 展开的那条可能已经被筛掉 / 翻到别的页了：顺手折叠，免得留着一个悬空的详情
    if (expandedQuestionId.value !== null && !questions.value.some((row) => row.id === expandedQuestionId.value)) {
      collapseDetail();
    }
  } catch (error) {
    if (!questionsGuard.isCurrent(token)) return;
    const failure = toApiFailure(error, "加载提问失败，请稍后重试");
    questionsError.value = failure.message;
    questionsRequestId.value = failure.requestId;
  } finally {
    if (questionsGuard.isCurrent(token)) {
      questionsLoading.value = false;
    }
  }
}

function submitQuestionFilters(): void {
  appliedQuestionDisease.value = questionDiseaseInput.value;
  void loadQuestions(1);
}

function toggleMineQuestions(): void {
  mineQuestions.value = !mineQuestions.value;
  void loadQuestions(1);
}

// ---------------------------------------------------------------- 提问详情（回答列表）

async function toggleQuestion(question: CommunityQuestionView): Promise<void> {
  const questionId = question.id;
  if (!questionId) return;
  const sameQuestion = expandedQuestionId.value === questionId;
  collapseDetail();
  // 换一条提问就看新的那条：上一条留下的提示（提交成功 / 失败）到此为止，
  // 否则 A 的失败文案会挂到 B 的回答区上
  answerAction.clear();
  adoptAction.clear();
  if (sameQuestion) return;
  expandedQuestionId.value = questionId;
  await loadDetail(questionId);
}

async function loadDetail(questionId: number | undefined): Promise<void> {
  if (!questionId) return;
  const { token, signal } = detailGuard.claim();
  detailLoading.value = true;
  detailError.value = "";
  detailRequestId.value = "";
  detailGone.value = false;
  try {
    const result = await community.getQuestion(questionId, signal);
    if (!detailGuard.isCurrent(token)) return;
    detail.value = result;
  } catch (error) {
    if (!detailGuard.isCurrent(token)) return;
    if (error instanceof ApiError && error.code === 40400) {
      // 「不存在」与「越权」同码（docs/conventions.md）：所以不能猜「是不是你的」，
      // 照契约的答复说「这条提问不在了」即可
      detailGone.value = true;
      return;
    }
    const failure = toApiFailure(error, "加载回答失败，请稍后重试");
    detailError.value = failure.message;
    detailRequestId.value = failure.requestId;
  } finally {
    if (detailGuard.isCurrent(token)) {
      detailLoading.value = false;
    }
  }
}

/**
 * 采纳入口只出现在**提问者**的界面上（契约：`mine` 是「这条问题是不是我提的」）。
 *
 * 另外两条同样是契约口径：**已经采纳过的不再给按钮**（一条提问只能采纳一个，重复采纳是 40900），
 * 且只给**已过审**的回答——没审过的回答提问者也看不见，看不见的东西不能被采纳（否则等于给
 * 「提议外人看不到的答案」开了一条路）。这里不显示「看起来能点其实不行」的按钮。
 */
function canAdopt(questionView: CommunityQuestionView, answer: CommunityAnswerView): boolean {
  return (
    questionView.mine === true &&
    !questionView.adopted_answer_id &&
    answer.status === 1 &&
    answer.id !== undefined
  );
}

function replaceQuestion(updated: CommunityQuestionView): void {
  questions.value = questions.value.map((row) => (row.id === updated.id ? { ...row, ...updated } : row));
}

// ---------------------------------------------------------------- 写操作（发文 / 采纳）

const questionAction = useSubmitAction("提问失败，请稍后重试");
const answerAction = useSubmitAction("提交回答失败，请稍后重试");
const adoptAction = useSubmitAction("采纳失败，请稍后重试");

const questionTitle = ref("");
const questionContent = ref("");
const questionDiseaseTag = ref("");
const answerDraft = ref("");
const adoptingAnswerId = ref<number | null>(null);

/**
 * 这次写操作失败的业务码。`useSubmitAction` 只给一个 `forbidden` 布尔，而
 * **40300（没这项权益）与 40100（没登录）的引导方向相反**（ADR-0051 第四节：两个码混用
 * 会让引导文案指向错误的方向），所以顺手把码留下来，页面据此选文案与去处。
 */
const lastFailureCode = ref(0);

function trackCode<T>(action: () => Promise<T>): () => Promise<T> {
  return async () => {
    try {
      const value = await action();
      lastFailureCode.value = 0;
      return value;
    } catch (error) {
      lastFailureCode.value = error instanceof ApiError ? error.code : 0;
      throw error;
    }
  };
}

/** 身份类的拒绝：说清是「去登录」还是「去解锁权益」，两种状态各自记账。 */
const identityNotice = ref<"" | "login" | "posting">("");
const noticeMessage = ref("");
const noticeRequestId = ref("");

function noteIdentityFailure(action: SubmitAction): void {
  if (!action.forbidden.value) return;
  const denied = lastFailureCode.value === 40300;
  identityNotice.value = denied ? "posting" : "login";
  noticeMessage.value = action.errorMessage.value;
  noticeRequestId.value = action.requestId.value;
  // 明确是 40300（登录了但没这项权益）时，把发文入口换成权益说明——
  // 它不是错误，是当前确实没有这项能力（ADR-0051 第四节）
  if (denied) postingDenied.value = true;
}

// ---------------------------------------------------------------- 发帖权益（community.post）

/**
 * 权益判定的三个状态。**只读服务端的实时结果**，界面不自己拼规则——各模块自己拼一套，
 * 会让客服与用户看到两个答案（ADR-0045）。
 */
const postingState = ref<"unknown" | "held" | "missing">("unknown");
/** 服务端明确回过 40300：以它的答复为准（权益可能刚被撤销），不再拿缓存里的结论说话。 */
const postingDenied = ref(false);

/**
 * 能不能给发文入口。
 *
 * **读不到权益时照给**：发文这事最终由服务端判（40300，`PostingAccess` 是唯一的闸门），
 * 前端读不到就先不拦人——被拦下来的那次会把原因说清楚（`noteIdentityFailure`）。
 * 反过来「先拦下来」的代价更大：一次权益接口抖动就把所有人都挡在门外。
 */
const canPost = computed(() => !postingDenied.value && postingState.value !== "missing");

async function loadPostingRight(): Promise<void> {
  try {
    const evaluation = await commerce.getRights();
    const item = (evaluation.rights ?? []).find((right) => right.code === POSTING_RIGHT);
    // 端点会把 `effective=false` 的码也列出来，所以「有这个码但不生效」与「名单里没有」
    // 都判得出来——两者都是「没持有」，界面说同一句话
    postingState.value = item?.effective === true ? "held" : "missing";
  } catch {
    // 权益读不到**不把整页变成错误态**（社区的主体是内容，不是权益），留「unknown」
    postingState.value = "unknown";
  }
}

// ---------------------------------------------------------------- 写一张经验卡片（F020 的录入入口）

/**
 * 「从记录预填」的来源清单。
 *
 * 卡片是**从一条来源记录生成**的（契约 `CommunityCardCreateRequest`：`pet_id` + `source_type`
 * + `source_ref` 都必填），所以这里不能凭空捏一个 `source_ref`——候选记录来自**时间轴**，
 * 它是 C 端唯一同时给出「记录 id」与「记录类型」的出口：
 *
 *  - `type=medical` → `source_type=2`（就医记录，档案记录 id）；
 *  - `type=abnormal` → `source_type=1`（打卡）。**只有异常打卡**：正常打卡不进时间轴
 *    （ADR-0030 第四条），而打卡接口也不下发打卡记录 id——所以能关联的打卡来源就是这些。
 *
 * 正文由前端按选中的那条记录**预填**、用户改完再提交（契约原文：服务端不重读档案正文，
 * 社区是公开场域、档案是私域）。
 */
const cardFormOpen = ref(false);
const cardSources = ref<TimelineEventView[]>([]);
const cardSourcesLoading = ref(false);
const cardSourcesError = ref("");
/** 选中的来源（`type:id`）：它同时决定 `source_type` 与 `source_ref`。 */
const cardSourceKey = ref("");
const cardTitle = ref("");
const cardContent = ref("");
const cardDiseaseTag = ref("");
const cardAction = useSubmitAction("发布经验卡片失败，请稍后重试");

const cardSourcesGuard = createLatestGuard();

const activePetId = computed(() => session.activePet?.id ?? 0);

/** 选中的那条来源记录；没选就是 null（此时不给提交——`source_ref` 是必填）。 */
const selectedSource = computed(
  () => cardSources.value.find((event) => sourceKeyOf(event) === cardSourceKey.value) ?? null,
);

/**
 * 换宠物时把这张表单收掉并清空：来源记录属于**原来那只**宠物，提交时服务端按归属校验
 * （不属于我就回 40400），留着它等于把一个会被拒的表单摆在用户面前。
 */
watch(activePetId, () => {
  cardFormOpen.value = false;
  cardSourceKey.value = "";
  cardTitle.value = "";
  cardContent.value = "";
});

function sourceKeyOf(event: TimelineEventView): string {
  return `${event.type}:${event.id}`;
}

/** 时间轴事件 → 契约的 `source_type`（1 打卡 / 2 就医记录）；其余类型不上这一行的表单。 */
function sourceTypeOf(event: TimelineEventView): 1 | 2 | null {
  if (event.type === "medical") return 2;
  if (event.type === "abnormal") return 1;
  return null;
}

/** 打开 / 收起录入面板。每次打开都重拉来源（切了宠物或刚记完一条，这里要跟着变）。 */
async function toggleCardForm(): Promise<void> {
  if (cardFormOpen.value) {
    cardFormOpen.value = false;
    return;
  }
  cardFormOpen.value = true;
  cardAction.clear();
  await loadCardSources();
}

async function loadCardSources(): Promise<void> {
  const petId = activePetId.value;
  if (!petId) {
    cardSources.value = [];
    return;
  }
  const { token, signal } = cardSourcesGuard.claim();
  cardSourcesLoading.value = true;
  cardSourcesError.value = "";
  try {
    const result = await cApp.listTimeline(petId, { pageSize: 20 }, signal);
    if (!cardSourcesGuard.isCurrent(token)) return;
    cardSources.value = (result.list ?? []).filter((event) => sourceTypeOf(event) !== null);
  } catch (error) {
    if (!cardSourcesGuard.isCurrent(token)) return;
    cardSourcesError.value = toApiFailure(error, "加载可关联的记录失败，请稍后重试").message;
  } finally {
    if (cardSourcesGuard.isCurrent(token)) {
      cardSourcesLoading.value = false;
    }
  }
}

/** 选一条来源：把标题与正文预填成一段草稿，用户改完再发（预填是起点，不是最终正文）。 */
function selectCardSource(): void {
  const event = selectedSource.value;
  if (!event) {
    cardTitle.value = "";
    cardContent.value = "";
    return;
  }
  cardTitle.value = event.title ?? "";
  const summary = event.summary ? `：${event.summary}` : "";
  cardContent.value = `${formatDate(event.date)} ${event.type_name}${summary}。\n\n我当时的处理与后来观察到的：`;
}

async function submitCard(): Promise<void> {
  const pet = session.activePet;
  const source = selectedSource.value;
  const sourceType = source ? sourceTypeOf(source) : null;
  const title = cardTitle.value.trim();
  const content = cardContent.value.trim();
  if (!pet || !source || !sourceType || title === "" || content === "") return;

  const result = await cardAction.run(
    trackCode(() =>
      community.createCard(
        {
          pet_id: pet.id,
          source_type: sourceType,
          source_ref: String(source.id),
          title,
          content,
          // 物种 / 品种是**卡片上的快照**（用于「同物种 / 同品种」聚合）：取当前宠物档案里的值，
          // 不带品种时后端要求物种也为空，所以只在有品种时才一起带（见 CardService.applyTagRules）
          species: pet.species,
          breed: pet.breed ?? null,
          disease_tag: cardDiseaseTag.value.trim() || null,
        },
        newIdempotencyKey(),
      ),
    ),
    "卡片已提交。内容进审核：通过后才会出现在公开列表里，先给你切到「我发的」。",
  );
  if (!result.ok) {
    noteIdentityFailure(cardAction);
    return;
  }
  cardFormOpen.value = false;
  cardTitle.value = "";
  cardContent.value = "";
  cardDiseaseTag.value = "";
  cardSourceKey.value = "";
  // 与提问同一手法：**切到「只看我发的」**——新卡片在待审里，默认列表看不到它，
  // 不切过去用户眼前没有任何变化（契约那句「否则发布在他眼里就是石沉大海」）
  mineCards.value = true;
  void loadCards(1);
}

// ---------------------------------------------------------------- 提交动作

async function submitQuestion(): Promise<void> {
  const title = questionTitle.value.trim();
  const content = questionContent.value.trim();
  if (title === "" || content === "") return;
  const result = await questionAction.run(
    trackCode(() =>
      community.createQuestion(
        { title, content, disease_tag: questionDiseaseTag.value.trim() || null },
        newIdempotencyKey(),
      ),
    ),
    "提问已提交。内容进审核：通过后其他宠友才看得到，下面是「我提的」（含待审）。",
  );
  if (!result.ok) {
    noteIdentityFailure(questionAction);
    return;
  }
  questionTitle.value = "";
  questionContent.value = "";
  questionDiseaseTag.value = "";
  // **切到「我提的」**：刚提的问题在待审里，默认列表看不到它——不切过去，用户眼前没有任何变化，
  // 会以为没提交成功（契约那句「否则发布在他眼里就是石沉大海」）
  mineQuestions.value = true;
  void loadQuestions(1);
}

async function submitAnswer(questionId: number | undefined): Promise<void> {
  const content = answerDraft.value.trim();
  if (!questionId || content === "") return;
  const result = await answerAction.run(
    trackCode(() => community.createAnswer(questionId, { content }, newIdempotencyKey())),
    "回答已提交。内容进审核：通过后才会出现在这条提问下（你自己能先看到它）。",
  );
  if (!result.ok) {
    noteIdentityFailure(answerAction);
    return;
  }
  answerDraft.value = "";
  // 拉一次详情：可见范围是「已发布的 + 我自己写的」，所以我刚写的那条会出现（带待审状态）
  void loadDetail(questionId);
}

async function adopt(questionId: number | undefined, answer: CommunityAnswerView): Promise<void> {
  const answerId = answer.id;
  if (!questionId || !answerId) return;
  adoptingAnswerId.value = answerId;
  // 成功不给额外的成功提示：反馈就是**重拉的详情**——那条回答带上「已采纳」、
  // 采纳按钮消失、提问变成「已解决」，三处同时变（比再加一句「已采纳」更清楚）
  const result = await adoptAction.run(
    trackCode(() => community.adoptAnswer(questionId, answerId, newIdempotencyKey())),
  );
  adoptingAnswerId.value = null;
  if (!result.ok) {
    // 40900（这条提问已经采纳过）说明界面上的按钮已经过期：拉一次最新详情，
    // 别让用户对着一个死按钮反复点
    if (lastFailureCode.value === 40900) void loadDetail(questionId);
    return;
  }
  // **重新拉详情，而不是拿采纳的应答覆盖它**：契约里 `answers` 只在详情接口下发，
  // 用应答覆盖详情会把回答列表清空（界面看起来就是「刚采纳完，回答全没了」）
  void loadDetail(questionId);
  replaceQuestion(result.value);
}

// ---------------------------------------------------------------- 首屏

watch(
  () => session.isLoggedIn,
  (loggedIn) => {
    if (!loggedIn) return;
    void loadCards(1);
    // 与列表并行：权益决定发文入口的形态，晚到一步会让表单先闪一下再被替换
    void loadPostingRight();
  },
  { immediate: true },
);

watch(activeTab, (tab) => {
  if (tab !== "questions" || questionsLoaded.value) return;
  questionsLoaded.value = true;
  void loadQuestions(1);
});
</script>

<template>
  <section>
    <h2 class="ph-page-title">社区</h2>
    <p class="ph-page-desc">
      同款宠友圈把经验卡片按物种 / 品种 / 病种聚合起来；问答互助里可以提问、回答、采纳。
      内容一律进审核，卡片与回答都匿名——这里看不到任何宠友的身份。
    </p>

    <SessionGate forbidden-description="登录后浏览宠友的经验卡片与问答互助。">
      <!-- 身份类拒绝的两句话必须分开：没登录 → 去登录；登录了但没发帖权益 → 去邀请 / 打卡 -->
      <p v-if="identityNotice === 'login'" class="ph-community__notice">
        {{ noticeMessage || "登录状态已失效，请重新登录后再操作。" }}
        <RouterLink class="ph-community__notice-link" :to="{ name: 'login', query: { redirect: route.fullPath } }">
          去登录
        </RouterLink>
      </p>

      <div class="ph-community__tabs" role="tablist">
        <button
          v-for="tab in TABS"
          :key="tab.key"
          type="button"
          role="tab"
          class="ph-community__tab"
          :class="{ 'ph-community__tab--active': activeTab === tab.key }"
          :aria-selected="activeTab === tab.key"
          @click="activeTab = tab.key"
        >
          {{ tab.label }}
        </button>
      </div>

      <!-- ============================ 同款宠友圈 ============================ -->
      <template v-if="activeTab === 'cards'">
        <div class="ph-community__filters">
          <button
            v-for="filter in SPECIES_FILTERS"
            :key="String(filter.value)"
            type="button"
            class="ph-community__filter"
            :class="{ 'ph-community__filter--active': species === filter.value && !mineCards }"
            @click="selectSpecies(filter.value)"
          >
            {{ filter.label }}
          </button>
          <button
            type="button"
            class="ph-community__filter"
            :class="{ 'ph-community__filter--active': mineCards }"
            @click="toggleMineCards"
          >
            只看我发的
          </button>
        </div>

        <div class="ph-community__search">
          <input
            v-model="breedInput"
            class="ph-community__input"
            type="search"
            maxlength="32"
            placeholder="按品种（如「柯基」）"
            aria-label="按品种筛选"
            @keyup.enter="submitCardFilters"
          />
          <input
            v-model="diseaseInput"
            class="ph-community__input"
            type="search"
            maxlength="32"
            placeholder="按病种（如「慢性肾病」）"
            aria-label="按病种筛选"
            @keyup.enter="submitCardFilters"
          />
          <button type="button" class="ph-button ph-button--primary" @click="submitCardFilters">筛选</button>
          <button
            v-if="cardFiltersActive"
            type="button"
            class="ph-button ph-button--secondary"
            @click="clearCardFilters"
          >
            清空筛选
          </button>
        </div>

        <p class="ph-community__hint ph-text-weak">
          点赞是互动、不是发文，所以不需要发帖权益；发布内容（提问 / 回答 / 经验卡片）才需要。
        </p>

        <!-- 写一张经验卡片（F020）：**从一条来源记录预填**，用户改完再提交。
             契约要求 pet_id + source_type + source_ref，所以没有来源记录就没法发卡片——
             这一点如实说明，不凭空捏一个来源（见脚本里的说明）。 -->
        <article v-if="!canPost" class="ph-card ph-community__rights">
          <p class="ph-text-sub">
            发布经验卡片需要邀请好友或打卡解锁（与提问、回答同一道门）。邀请好友得永久权益，
            打卡阶梯得当月权益。
          </p>
          <div class="ph-community__actions">
            <RouterLink class="ph-button ph-button--secondary" :to="{ name: 'invites' }">去邀请好友</RouterLink>
            <RouterLink class="ph-button ph-button--text" :to="{ name: 'records' }">去打卡</RouterLink>
          </div>
        </article>

        <article v-else class="ph-card ph-community__form">
          <div class="ph-community__form-head">
            <h3 class="ph-card__title">写一张经验卡片</h3>
            <button type="button" class="ph-button ph-button--secondary" @click="toggleCardForm">
              {{ cardFormOpen ? "收起" : "开始写" }}
            </button>
          </div>
          <p class="ph-card__note ph-text-sub">
            卡片从<strong>你的一条记录</strong>生成：选一条来源，标题与正文会自动填好，
            改成你自己的话再发。内容进审核，通过后其他宠友才看得到，并且一律匿名。
          </p>
          <!-- 提交成功的反馈放在折叠区**外面**：成功后表单收起，放里面就等于没有反馈 -->
          <p v-if="cardAction.doneMessage.value" class="ph-community__done">
            {{ cardAction.doneMessage.value }}
          </p>

          <template v-if="cardFormOpen">
            <StateLoading v-if="cardSourcesLoading" :rows="2" />
            <p v-else-if="cardSourcesError" class="ph-form__error">{{ cardSourcesError }}</p>
            <StateEmpty
              v-else-if="cardSources.length === 0"
              icon="📝"
              title="还没有可关联的记录"
              description="卡片要挂在一条来源记录上（契约要求）：先在健康档案里记一条就医记录，或出现一次异常打卡。"
            >
              <RouterLink class="ph-button ph-button--secondary" :to="{ name: 'records' }">去健康档案</RouterLink>
            </StateEmpty>

            <template v-else>
              <label class="ph-field">
                <span class="ph-field__label">来源记录（必选）</span>
                <select v-model="cardSourceKey" class="ph-field__input" @change="selectCardSource">
                  <option value="">请选择一条记录</option>
                  <option v-for="event in cardSources" :key="sourceKeyOf(event)" :value="sourceKeyOf(event)">
                    {{ event.type_name }} · {{ formatDate(event.date) }} · {{ event.title }}
                  </option>
                </select>
              </label>

              <label class="ph-field">
                <span class="ph-field__label">标题</span>
                <input
                  v-model="cardTitle"
                  class="ph-field__input"
                  maxlength="64"
                  placeholder="例如：柯基换季掉毛这样处理"
                />
              </label>

              <label class="ph-field">
                <span class="ph-field__label">正文</span>
                <textarea
                  v-model="cardContent"
                  class="ph-field__input ph-community__textarea"
                  maxlength="2000"
                  rows="5"
                  placeholder="写清情况、你怎么处理的、后来怎么样"
                />
              </label>

              <label class="ph-field">
                <span class="ph-field__label">慢病标签（可选，用来聚合「同病」）</span>
                <input v-model="cardDiseaseTag" class="ph-field__input" maxlength="32" placeholder="例如：慢性肾病" />
              </label>
              <p class="ph-text-weak ph-community__hint">
                会带上「同物种 / 同品种」标签（来自当前宠物档案的快照），发出去只看得到标签，看不到你和宠物的身份。
              </p>

              <div class="ph-form__actions">
                <button
                  type="button"
                  class="ph-button ph-button--primary"
                  :disabled="
                    cardAction.submitting.value ||
                    !selectedSource ||
                    cardTitle.trim() === '' ||
                    cardContent.trim() === ''
                  "
                  @click="submitCard"
                >
                  {{ cardAction.submitting.value ? "提交中…" : "提交卡片" }}
                </button>
              </div>

              <p v-if="cardAction.errorMessage.value && !cardAction.forbidden.value" class="ph-form__error">
                {{ cardAction.errorMessage.value }}
                <span v-if="cardAction.requestId.value" class="ph-text-weak">
                  （请求 ID：{{ cardAction.requestId.value }}）
                </span>
              </p>
            </template>
          </template>
        </article>

        <StateLoading v-if="cardsLoading" :rows="4" />
        <StateError
          v-else-if="cardsError"
          :message="cardsError"
          :request-id="cardsRequestId"
          @retry="loadCards(cardsPage)"
        />

        <article v-else-if="cards.length === 0" class="ph-card">
          <StateEmpty
            icon="🐾"
            :title="mineCards ? '你还没有发过经验卡片' : '还没有经验卡片'"
            :description="
              mineCards
                ? '点上面的「写一张经验卡片」，从你自己的一条记录生成；发布前经审核，被驳回的会带上原因。'
                : '内容过了审核才会出现在这里。如果你已经发过，切到「只看我发的」能看到它停在哪一步。'
            "
          >
            <button
              v-if="cardFiltersActive"
              type="button"
              class="ph-button ph-button--secondary"
              @click="clearCardFilters"
            >
              清空筛选
            </button>
          </StateEmpty>
        </article>

        <template v-else>
          <ul class="ph-community__list">
            <li v-for="card in cards" :key="card.id" class="ph-card ph-community__item">
              <div class="ph-community__meta">
                <!-- 恒匿名：这个标签是契约里唯一能给的作者信息，不多不少 -->
                <span class="ph-community__tag">匿名宠友</span>
                <span v-if="card.source_type_name" class="ph-community__tag">{{ card.source_type_name }}</span>
                <span v-if="card.species" class="ph-community__tag">同物种：{{ speciesLabel(card.species) }}</span>
                <span v-if="card.breed" class="ph-community__tag">同品种：{{ card.breed }}</span>
                <span v-if="card.disease_tag" class="ph-community__tag ph-community__tag--disease">
                  同病：{{ card.disease_tag }}
                </span>
                <!-- 已发布的卡片不显示「已发布」：那是噪音；待审 / 被驳回才要说出来（仅在「我发的」里会遇到） -->
                <span v-if="card.status !== undefined && card.status !== 1" class="ph-community__tag ph-community__tag--review">
                  {{ card.status_name ?? "待审核" }}
                </span>
                <span class="ph-text-weak">{{ formatDate(card.created_at) }}</span>
              </div>

              <p class="ph-community__title">{{ card.title ?? "经验卡片" }}</p>
              <p class="ph-community__excerpt ph-text-sub">{{ card.content }}</p>
              <p v-if="card.reject_reason" class="ph-community__reason">未通过的原因：{{ card.reject_reason }}</p>

              <div class="ph-community__actions">
                <button
                  type="button"
                  class="ph-button"
                  :class="card.liked ? 'ph-button--secondary' : 'ph-button--primary'"
                  :disabled="likeAction.submitting.value"
                  @click="toggleLike(card)"
                >
                  {{ card.liked ? "已赞" : "点赞" }}（{{ card.like_count ?? 0 }}）
                </button>
              </div>

              <!-- 失败要说清是哪一行、后端那句话是什么、请求 ID 是多少（身份类失败走顶部的引导条） -->
              <p
                v-if="likeTargetId === card.id && likeAction.errorMessage.value && !likeAction.forbidden.value"
                class="ph-community__row-error"
              >
                {{ likeAction.errorMessage.value }}
                <span v-if="likeAction.requestId.value" class="ph-text-weak">
                  （请求 ID：{{ likeAction.requestId.value }}）
                </span>
              </p>
            </li>
          </ul>

          <div class="ph-community__pager">
            <span class="ph-text-weak">共 {{ cardsTotal }} 张，第 {{ cardsPage }} 页</span>
            <button
              type="button"
              class="ph-button ph-button--secondary"
              :disabled="cardsPage <= 1 || cardsLoading"
              @click="loadCards(cardsPage - 1)"
            >
              上一页
            </button>
            <button
              type="button"
              class="ph-button ph-button--secondary"
              :disabled="!cardsHasMore || cardsLoading"
              @click="loadCards(cardsPage + 1)"
            >
              下一页
            </button>
          </div>
        </template>
      </template>

      <!-- ============================== 问答互助 ============================== -->
      <template v-else>
        <!-- 没有发帖权益时**如实说明**，不显示成错误：去邀请 / 打卡就能解锁 -->
        <article v-if="!canPost" class="ph-card ph-community__rights">
          <h3 class="ph-card__title">发布内容需要权益</h3>
          <p class="ph-text-sub">
            发布内容需要邀请好友或打卡解锁：邀请好友得永久权益，打卡阶梯得当月权益。
            这项权益是否生效以服务端实时判定为准。
          </p>
          <p v-if="postingState === 'missing'" class="ph-text-weak">当前账号还没有 community.post 这项权益。</p>
          <div class="ph-community__actions">
            <RouterLink class="ph-button ph-button--secondary" :to="{ name: 'invites' }">去邀请好友</RouterLink>
            <RouterLink class="ph-button ph-button--text" :to="{ name: 'records' }">去打卡</RouterLink>
            <RouterLink class="ph-button ph-button--text" :to="{ name: 'rights' }">查看我的权益</RouterLink>
          </div>
          <p v-if="noticeMessage" class="ph-text-weak">
            服务端的答复：{{ noticeMessage }}
            <span v-if="noticeRequestId">（请求 ID：{{ noticeRequestId }}）</span>
          </p>
        </article>

        <article v-else class="ph-card ph-community__form">
          <h3 class="ph-card__title">我要提问</h3>
          <p class="ph-card__note ph-text-sub">
            内容进审核：通过后其他宠友才看得到。问题里可以写症状、年龄与已经做过的处理。
          </p>

          <label class="ph-field">
            <span class="ph-field__label">问题标题</span>
            <input
              v-model="questionTitle"
              class="ph-field__input"
              maxlength="64"
              placeholder="例如：八岁的猫最近喝水变多，要留意什么？"
            />
          </label>

          <label class="ph-field">
            <span class="ph-field__label">补充说明</span>
            <textarea
              v-model="questionContent"
              class="ph-field__input ph-community__textarea"
              maxlength="2000"
              rows="4"
              placeholder="例如：这周每天喝掉一整碗，体重没变，精神正常"
            />
          </label>

          <label class="ph-field">
            <span class="ph-field__label">慢病标签（可选，用来聚合「同病」的问答）</span>
            <input v-model="questionDiseaseTag" class="ph-field__input" maxlength="32" placeholder="例如：慢性肾病" />
          </label>

          <div class="ph-form__actions">
            <button
              type="button"
              class="ph-button ph-button--primary"
              :disabled="questionAction.submitting.value || questionTitle.trim() === '' || questionContent.trim() === ''"
              @click="submitQuestion"
            >
              {{ questionAction.submitting.value ? "提交中…" : "提交提问" }}
            </button>
          </div>

          <p v-if="questionAction.errorMessage.value && !questionAction.forbidden.value" class="ph-form__error">
            {{ questionAction.errorMessage.value }}
            <span v-if="questionAction.requestId.value" class="ph-text-weak">
              （请求 ID：{{ questionAction.requestId.value }}）
            </span>
          </p>
          <p v-if="questionAction.doneMessage.value" class="ph-community__done">
            {{ questionAction.doneMessage.value }}
          </p>
        </article>

        <div class="ph-community__search">
          <input
            v-model="questionDiseaseInput"
            class="ph-community__input"
            type="search"
            maxlength="32"
            placeholder="按病种筛问题（如「慢性肾病」）"
            aria-label="按病种筛选提问"
            @keyup.enter="submitQuestionFilters"
          />
          <button type="button" class="ph-button ph-button--primary" @click="submitQuestionFilters">筛选</button>
          <button
            type="button"
            class="ph-community__filter"
            :class="{ 'ph-community__filter--active': mineQuestions }"
            @click="toggleMineQuestions"
          >
            只看我提的
          </button>
        </div>

        <StateLoading v-if="questionsLoading" :rows="4" />
        <StateError
          v-else-if="questionsError"
          :message="questionsError"
          :request-id="questionsRequestId"
          @retry="loadQuestions(questionsPage)"
        />

        <article v-else-if="questions.length === 0" class="ph-card">
          <StateEmpty
            icon="❓"
            :title="mineQuestions ? '你还没有提过问题' : '还没有已发布的提问'"
            :description="
              mineQuestions
                ? '提问之后，待审与被驳回的都会出现在这里，能看到卡在哪一步。'
                : '问题过了审核就会出现在这里。第一个提问的可能是你。'
            "
          />
        </article>

        <template v-else>
          <ul class="ph-community__list">
            <li v-for="question in questions" :key="question.id" class="ph-card ph-community__item">
              <div class="ph-community__meta">
                <span v-if="question.adopted_answer_id" class="ph-community__tag ph-community__tag--adopted">已解决</span>
                <span v-else class="ph-community__tag">待解答</span>
                <span v-if="question.disease_tag" class="ph-community__tag ph-community__tag--disease">
                  同病：{{ question.disease_tag }}
                </span>
                <span v-if="question.mine" class="ph-community__tag">我提的</span>
                <!-- 待审 / 被驳回只在「我提的」里会遇到（公开列表只给已发布的） -->
                <span v-if="question.status !== undefined && question.status !== 1" class="ph-community__tag ph-community__tag--review">
                  {{ question.status_name ?? "待审核" }}
                </span>
                <span class="ph-text-weak">{{ formatDate(question.created_at) }}</span>
              </div>

              <p class="ph-community__title">{{ question.title ?? "提问" }}</p>
              <p class="ph-community__excerpt ph-text-sub">{{ question.content }}</p>
              <p v-if="question.reject_reason" class="ph-community__reason">未通过的原因：{{ question.reject_reason }}</p>

              <div class="ph-community__actions">
                <button type="button" class="ph-button ph-button--secondary" @click="toggleQuestion(question)">
                  {{ expandedQuestionId === question.id ? "收起回答" : `查看回答（${question.answer_count ?? 0}）` }}
                </button>
              </div>

              <div v-if="expandedQuestionId === question.id" class="ph-community__answers">
                <StateLoading v-if="detailLoading" :rows="3" />
                <StateError
                  v-else-if="detailError"
                  :message="detailError"
                  :request-id="detailRequestId"
                  @retry="loadDetail(question.id)"
                />
                <p v-else-if="detailGone" class="ph-text-weak">
                  这条提问不在了（可能还没过审，或者已经被下架）。
                </p>

                <template v-else-if="detail">
                  <p class="ph-text-weak">
                    共 {{ detail.answer_count ?? 0 }} 条已通过的回答
                    <template v-if="detail.adopted_answer_id"> · 提问者已采纳其中一条</template>
                  </p>

                  <p v-if="answers.length === 0" class="ph-text-weak">
                    还没有回答。你的经验会帮到下一位遇到同样问题的宠友。
                  </p>
                  <ul v-else class="ph-community__answer-list">
                    <li v-for="answer in answers" :key="answer.id" class="ph-community__answer">
                      <div class="ph-community__meta">
                        <span class="ph-community__tag">匿名宠友</span>
                        <span v-if="answer.mine" class="ph-community__tag">我写的</span>
                        <span v-if="answer.adopted" class="ph-community__tag ph-community__tag--adopted">已采纳</span>
                        <span v-if="answer.status !== undefined && answer.status !== 1" class="ph-community__tag ph-community__tag--review">
                          {{ answer.status_name ?? "待审核" }}
                        </span>
                        <span class="ph-text-weak">{{ formatDate(answer.created_at) }}</span>
                      </div>
                      <p class="ph-community__answer-text">{{ answer.content }}</p>
                      <p v-if="answer.reject_reason" class="ph-community__reason">
                        未通过的原因：{{ answer.reject_reason }}
                      </p>
                      <div v-if="canAdopt(detail, answer)" class="ph-community__actions">
                        <button
                          type="button"
                          class="ph-button ph-button--secondary"
                          :disabled="adoptAction.submitting.value"
                          @click="adopt(question.id, answer)"
                        >
                          {{ adoptingAnswerId === answer.id && adoptAction.submitting.value ? "采纳中…" : "采纳这条" }}
                        </button>
                      </div>
                    </li>
                  </ul>

                  <p v-if="adoptAction.errorMessage.value && !adoptAction.forbidden.value" class="ph-form__error">
                    {{ adoptAction.errorMessage.value }}
                    <span v-if="adoptAction.requestId.value" class="ph-text-weak">
                      （请求 ID：{{ adoptAction.requestId.value }}）
                    </span>
                  </p>

                  <template v-if="canPost">
                    <label class="ph-field">
                      <span class="ph-field__label">我的回答（只说经验与建议，不下诊断结论）</span>
                      <textarea
                        v-model="answerDraft"
                        class="ph-field__input ph-community__textarea"
                        maxlength="2000"
                        rows="3"
                        placeholder="例如：我家猫同样情况，先查了饮水量与尿检，医生说……"
                      />
                    </label>
                    <div class="ph-form__actions">
                      <button
                        type="button"
                        class="ph-button ph-button--primary"
                        :disabled="answerAction.submitting.value || answerDraft.trim() === ''"
                        @click="submitAnswer(question.id)"
                      >
                        {{ answerAction.submitting.value ? "提交中…" : "提交回答" }}
                      </button>
                    </div>
                    <p v-if="answerAction.errorMessage.value && !answerAction.forbidden.value" class="ph-form__error">
                      {{ answerAction.errorMessage.value }}
                      <span v-if="answerAction.requestId.value" class="ph-text-weak">
                        （请求 ID：{{ answerAction.requestId.value }}）
                      </span>
                    </p>
                    <p v-if="answerAction.doneMessage.value" class="ph-community__done">
                      {{ answerAction.doneMessage.value }}
                    </p>
                  </template>
                  <p v-else class="ph-text-weak">回答也是发文：需要发帖权益才能提交（见上方的权益说明）。</p>
                </template>
              </div>
            </li>
          </ul>

          <div class="ph-community__pager">
            <span class="ph-text-weak">共 {{ questionsTotal }} 条，第 {{ questionsPage }} 页</span>
            <button
              type="button"
              class="ph-button ph-button--secondary"
              :disabled="questionsPage <= 1 || questionsLoading"
              @click="loadQuestions(questionsPage - 1)"
            >
              上一页
            </button>
            <button
              type="button"
              class="ph-button ph-button--secondary"
              :disabled="!questionsHasMore || questionsLoading"
              @click="loadQuestions(questionsPage + 1)"
            >
              下一页
            </button>
          </div>
        </template>
      </template>
    </SessionGate>
  </section>
</template>

<style scoped>
.ph-community__tabs {
  display: flex;
  gap: var(--ph-space-2);
  margin-bottom: var(--ph-space-4);
  border-bottom: 1px solid var(--ph-color-divider);
}

.ph-community__tab {
  height: 36px;
  padding: 0 var(--ph-space-4);
  border: none;
  border-bottom: 2px solid transparent;
  background: none;
  font-family: inherit;
  font-size: 14px;
  color: var(--ph-color-text-sub);
  cursor: pointer;
}

.ph-community__tab--active {
  border-bottom-color: var(--ph-color-primary);
  color: var(--ph-color-primary);
  font-weight: 600;
}

.ph-community__filters {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ph-space-2);
  margin-bottom: var(--ph-space-3);
}

.ph-community__filter {
  height: 32px;
  padding: 0 var(--ph-space-3);
  background: var(--ph-color-surface);
  border: 1px solid var(--ph-color-border);
  border-radius: var(--ph-radius-button);
  font-family: inherit;
  font-size: 13px;
  color: var(--ph-color-text);
  cursor: pointer;
}

.ph-community__filter--active {
  background: var(--ph-color-primary-light);
  border-color: var(--ph-color-primary);
  color: var(--ph-color-primary);
  font-weight: 600;
}

.ph-community__search {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ph-space-2);
  margin-bottom: var(--ph-space-3);
}

.ph-community__input {
  flex: 1;
  min-width: 180px;
  height: 40px;
  padding: 0 var(--ph-space-3);
  background: var(--ph-color-surface);
  border: 1px solid var(--ph-color-border);
  border-radius: var(--ph-radius-input);
  font-family: inherit;
  font-size: 14px;
  color: var(--ph-color-text);
}

.ph-community__hint {
  margin: 0 0 var(--ph-space-4);
  font-size: 12px;
}

.ph-community__notice {
  display: flex;
  align-items: center;
  gap: var(--ph-space-2);
  margin: 0 0 var(--ph-space-4);
  padding: var(--ph-space-3) var(--ph-space-4);
  background: var(--ph-color-primary-light);
  border-radius: var(--ph-radius-input);
  color: var(--ph-color-primary-dark);
  font-size: 13px;
}

.ph-community__notice-link {
  color: var(--ph-color-primary);
  font-weight: 600;
}

.ph-community__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-3);
}

.ph-community__meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--ph-space-2);
  font-size: 12px;
}

.ph-community__tag {
  padding: 1px var(--ph-space-2);
  border-radius: var(--ph-radius-input);
  background: var(--ph-color-bg);
  color: var(--ph-color-text-sub);
}

.ph-community__tag--disease {
  background: var(--ph-color-purple-light);
  color: var(--ph-color-purple);
}

.ph-community__tag--adopted {
  background: var(--ph-color-primary-light);
  color: var(--ph-color-primary);
}

.ph-community__tag--review {
  background: var(--ph-color-orange-light);
  color: var(--ph-color-orange);
}

.ph-community__title {
  margin: var(--ph-space-3) 0 0;
  font-size: 15px;
  font-weight: 600;
}

/* 正文只给摘要：卡片正文可以写到 2000 字，整篇摊在列表里会把后面的卡片顶到看不见 */
.ph-community__excerpt {
  display: -webkit-box;
  -webkit-line-clamp: 4;
  -webkit-box-orient: vertical;
  overflow: hidden;
  margin: var(--ph-space-2) 0 0;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
}

.ph-community__reason {
  margin: var(--ph-space-2) 0 0;
  font-size: 12px;
  color: var(--ph-color-orange);
}

.ph-community__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ph-space-2);
  margin-top: var(--ph-space-3);
}

.ph-community__row-error {
  margin: var(--ph-space-2) 0 0;
  font-size: 12px;
  color: var(--ph-color-danger);
}

.ph-community__done {
  margin: var(--ph-space-2) 0 0;
  font-size: 13px;
  color: var(--ph-color-primary-dark);
}

.ph-community__rights {
  border-left: 3px solid var(--ph-color-primary);
}

.ph-community__form {
  margin-bottom: var(--ph-space-4);
}

/* 标题 + 收起按钮同一行：表单默认收起，标题那一行就是它的开关 */
.ph-community__form-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ph-space-4);
}

.ph-community__form-head .ph-card__title {
  margin-bottom: 0;
}

.ph-community__textarea {
  height: auto;
  min-height: 88px;
  padding: var(--ph-space-2) var(--ph-space-3);
  line-height: 1.7;
  resize: vertical;
}

.ph-community__answers {
  margin-top: var(--ph-space-3);
  padding-top: var(--ph-space-3);
  border-top: 1px solid var(--ph-color-divider);
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-2);
}

.ph-community__answers .ph-field,
.ph-community__answers .ph-form__actions,
.ph-community__answers .ph-form__error {
  margin-top: var(--ph-space-2);
}

.ph-community__answer-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-3);
}

.ph-community__answer {
  padding: var(--ph-space-3);
  background: var(--ph-color-bg);
  border-radius: var(--ph-radius-card);
}

.ph-community__answer-text {
  margin: var(--ph-space-2) 0 0;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
}

.ph-community__pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--ph-space-3);
  margin-top: var(--ph-space-4);
}
</style>
