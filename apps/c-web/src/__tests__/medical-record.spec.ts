/**
 * 就医记录录入（F004）的测试。
 *
 * `medical` 是**可写分项**，但它不在交付文档 4.16.4 的 8 个分项里（ADR-0030 把它与 8 个模块并列），
 * 所以 `listArchiveSections` 的入口列表里没有它——这块入口是档案页自己给的。
 *
 * 钉住三条：
 *  1. 入口真的存在，且提交的 `section` 就是 `medical`（写错了会落成别的分项）；
 *  2. 日期超出可录窗口（今天 + 过去 7 天）时**不发请求**：按钮禁用 + 给出提示；
 *  3. 删除走 `deleteArchiveRecord` 并整页重载（时间轴与报告跟着变）。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, type VueWrapper } from "@vue/test-utils";
import { shiftDate, todayIso, type ArchiveRecordView } from "@pet-health/shared";
import RecordsView from "../views/RecordsView.vue";
import { apiFailure, mountPage } from "./support";

const listArchiveSections = vi.fn();
const listArchiveRecords = vi.fn();
const createArchiveRecord = vi.fn();
const deleteArchiveRecord = vi.fn();
const listEpidemicRecords = vi.fn();
const getCareMode = vi.fn();
const listHealthReports = vi.fn();
const listTimeline = vi.fn();
const getHealthScore = vi.fn();
const getCheckInStreak = vi.fn();

vi.mock("@pet-health/shared", async () => {
  const actual = await vi.importActual<typeof import("@pet-health/shared")>("@pet-health/shared");
  return {
    ...actual,
    cApp: {
      listArchiveSections: (...args: unknown[]) => listArchiveSections(...args),
      listArchiveRecords: (...args: unknown[]) => listArchiveRecords(...args),
      createArchiveRecord: (...args: unknown[]) => createArchiveRecord(...args),
      deleteArchiveRecord: (...args: unknown[]) => deleteArchiveRecord(...args),
      listEpidemicRecords: (...args: unknown[]) => listEpidemicRecords(...args),
      getCareMode: (...args: unknown[]) => getCareMode(...args),
      listHealthReports: (...args: unknown[]) => listHealthReports(...args),
      listTimeline: (...args: unknown[]) => listTimeline(...args),
      // 档案页现在首块还挂评分卡（4.16.4），所以这两个也要打桩
      getHealthScore: (...args: unknown[]) => getHealthScore(...args),
      getCheckInStreak: (...args: unknown[]) => getCheckInStreak(...args),
    },
  };
});

const TODAY = todayIso();
const TOO_OLD = shiftDate(TODAY, -8);   // 窗口是「今天 + 过去 7 天」，第 8 天必须被拦

function makeMedical(overrides: Partial<ArchiveRecordView> = {}): ArchiveRecordView {
  return {
    id: 41,
    section: "medical",
    category: 8,
    record_date: TODAY,
    title: "呕吐就诊",
    value: "急性肠胃炎，开了三天药",
    source: 1,
    source_label: "用户录入",
    authoritative: false,
    abnormal: true,
    backfilled: false,
    ...overrides,
  } as ArchiveRecordView;
}

function pageOf(list: ArchiveRecordView[]) {
  return { list, page: 1, page_size: 20, total: list.length, has_more: false };
}

/**
 * 就医记录那一张卡片。**必须按卡片定位**：档案页上「＋ 添加」有两个（防疫记录那块也有一个），
 * 直接找第一个会点到隔壁那块去——测试就变成了「验另一件东西」。
 */
function medicalCard(wrapper: VueWrapper) {
  const card = wrapper
    .findAll("article.ph-card")
    .find((item) => item.find("h3.ph-card__title").exists() && item.find("h3.ph-card__title").text() === "就医记录");
  if (!card) throw new Error("档案页上没有「就医记录」这张卡片");
  return card;
}

async function openMedicalForm(wrapper: VueWrapper): Promise<void> {
  const add = medicalCard(wrapper).findAll("button").find((button) => button.text().includes("添加"));
  await add?.trigger("click");
  await flushPromises();
}

beforeEach(() => {
  vi.clearAllMocks();
  listArchiveSections.mockResolvedValue([]);
  listArchiveRecords.mockResolvedValue(pageOf([]));
  listEpidemicRecords.mockResolvedValue([]);
  getCareMode.mockResolvedValue({ active: false, reasons: [], effects: [], notice: "" });
  listHealthReports.mockResolvedValue({ list: [], page: 1, page_size: 12, total: 0, has_more: false });
  getHealthScore.mockResolvedValue({ total_score: null, dimensions: [], trend: [] });
  getCheckInStreak.mockResolvedValue({ streak_days: 0, checked_today: false });
  listTimeline.mockResolvedValue({ list: [], page: 1, page_size: 10, total: 0, has_more: false });
  createArchiveRecord.mockResolvedValue(makeMedical());
  deleteArchiveRecord.mockResolvedValue(undefined);
});

describe("就医记录（F004）", () => {
  it("档案页有这一块的入口，空态给出下一步", async () => {
    const { wrapper } = await mountPage(RecordsView, "/records");
    expect(wrapper.text()).toContain("就医记录");
    expect(wrapper.text()).toContain("还没有就医记录");
    // 它不属于 8 个分项，所以自己拉一次 section=medical 的列表
    expect(listArchiveRecords).toHaveBeenCalledWith(7, { section: "medical", pageSize: 20 }, expect.anything());
  });

  it("录入一条：提交的 section 是 medical，成功后整页重载", async () => {
    const { wrapper } = await mountPage(RecordsView, "/records");
    await openMedicalForm(wrapper);

    await medicalCard(wrapper).find('input[placeholder*="呕吐就诊"]').setValue("复查");
    await medicalCard(wrapper).find('input[placeholder*="急性肠胃炎"]').setValue("指标正常");
    listArchiveRecords.mockClear();

    // 提交走表单的 submit 事件（jsdom 里 trigger("click") 不会触发原生表单提交的默认行为）
    await medicalCard(wrapper).find("form").trigger("submit");
    await flushPromises();

    expect(createArchiveRecord).toHaveBeenCalledWith(7, {
      section: "medical",
      date: TODAY,
      title: "复查",
      value: "指标正常",
      note: undefined,
      abnormal: false,
    });
    // 成功后重载：时间轴、报告与分项条数都由服务端算，本地拼一条会与它们对不上
    expect(listArchiveRecords).toHaveBeenCalled();
  });

  it("日期超出可录窗口：按钮禁用、说明原因，且不发请求", async () => {
    const { wrapper } = await mountPage(RecordsView, "/records");
    await openMedicalForm(wrapper);

    await medicalCard(wrapper).find('input[placeholder*="呕吐就诊"]').setValue("补记一次就诊");
    await medicalCard(wrapper).find('input[type="date"]').setValue(TOO_OLD);
    await flushPromises();

    expect(medicalCard(wrapper).text()).toContain("只能录入今天或最近 7 天");
    const save = medicalCard(wrapper).findAll("button").find((button) => button.text() === "保存");
    expect(save?.attributes("disabled")).toBeDefined();

    await save?.trigger("click");
    expect(createArchiveRecord).not.toHaveBeenCalled();
  });

  it("删除一条：走 deleteArchiveRecord 并重载", async () => {
    listArchiveRecords.mockResolvedValue(pageOf([makeMedical()]));
    const { wrapper } = await mountPage(RecordsView, "/records");
    expect(wrapper.text()).toContain("呕吐就诊");

    listArchiveRecords.mockClear();
    await medicalCard(wrapper).findAll("button").find((button) => button.text() === "删除")?.trigger("click");
    await flushPromises();

    expect(deleteArchiveRecord).toHaveBeenCalledWith(7, 41);
    expect(listArchiveRecords).toHaveBeenCalled();
  });

  it("保存失败时显示后端那句话（不静默吞掉）", async () => {
    createArchiveRecord.mockRejectedValueOnce(apiFailure(40001, "只能录入今天或最近 7 天的记录", "req-med-1"));
    const { wrapper } = await mountPage(RecordsView, "/records");
    await openMedicalForm(wrapper);
    await medicalCard(wrapper).find('input[placeholder*="呕吐就诊"]').setValue("复查");

    // 提交走表单的 submit 事件（jsdom 里 trigger("click") 不会触发原生表单提交的默认行为）
    await medicalCard(wrapper).find("form").trigger("submit");
    await flushPromises();

    expect(medicalCard(wrapper).text()).toContain("只能录入今天或最近 7 天的记录");
  });
});
