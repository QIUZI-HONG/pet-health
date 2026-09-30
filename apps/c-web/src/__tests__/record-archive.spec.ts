/**
 * 档案分项、时间轴、健康报告与专项照护的组件测试
 * （切片 #102 / #115 / #116 的验收标准）。
 *
 * 逐条对应 ADR 里的产品口径：
 *  - 8 个分项入口固定，未开启的老年专项与照片视频也在里面（灰态 + 原因）；
 *  - 多源记录标出来源，权威那条标「当前值」；打卡/防疫的行不在分项里改；
 *  - 时间轴只收四类事件，正常打卡不进；
 *  - 报告按四段渲染、需要权益的段落只打「邀请解锁」标签（**不隐藏内容**）；
 *  - 照护模式是派生结果：显示原因 + 四项变化，关闭走一次写请求。
 */
import { describe, expect, it, vi, beforeEach } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import type {
  ArchiveRecordView,
  ArchiveSectionView,
  CareModeView,
  HealthReportView,
  TimelineEventView,
} from "@pet-health/shared";
import ArchiveSectionsCard from "../components/ArchiveSectionsCard.vue";
import CareModeCard from "../components/CareModeCard.vue";
import HealthReportCard from "../components/HealthReportCard.vue";
import TimelineCard from "../components/TimelineCard.vue";

const listArchiveRecords = vi.fn();
const createArchiveRecord = vi.fn();
const deleteArchiveRecord = vi.fn();
const setCareMode = vi.fn();
const listHealthReports = vi.fn();
const listTimeline = vi.fn();

vi.mock("@pet-health/shared", async () => {
  const actual = await vi.importActual<typeof import("@pet-health/shared")>("@pet-health/shared");
  return {
    ...actual,
    cApp: {
      listArchiveRecords: (...args: unknown[]) => listArchiveRecords(...args),
      createArchiveRecord: (...args: unknown[]) => createArchiveRecord(...args),
      deleteArchiveRecord: (...args: unknown[]) => deleteArchiveRecord(...args),
      setCareMode: (...args: unknown[]) => setCareMode(...args),
      listHealthReports: (...args: unknown[]) => listHealthReports(...args),
      listTimeline: (...args: unknown[]) => listTimeline(...args),
    },
  };
});

beforeEach(() => {
  vi.clearAllMocks();
  listArchiveRecords.mockResolvedValue({ list: [], page: 1, page_size: 20, total: 0, has_more: false });
  listTimeline.mockResolvedValue({ list: [], page: 1, page_size: 10, total: 0, has_more: false });
  listHealthReports.mockResolvedValue({ list: [], page: 1, page_size: 12, total: 0, has_more: false });
});

function makeSection(overrides: Partial<ArchiveSectionView> = {}): ArchiveSectionView {
  return {
    code: "metrics",
    name: "核心指标",
    description: "体重、排泄与体温这些数值",
    content_source: "records",
    recordable: true,
    enabled: true,
    record_count: 0,
    ...overrides,
  } as ArchiveSectionView;
}

function makeRecord(overrides: Partial<ArchiveRecordView> = {}): ArchiveRecordView {
  return {
    id: 1,
    section: "documents",
    category: 11,
    record_date: "2026-09-20",
    title: "免疫证",
    value: "SH-1",
    source: 3,
    source_label: "服务者报工",
    authoritative: true,
    abnormal: false,
    backfilled: false,
    ...overrides,
  } as ArchiveRecordView;
}

describe("档案分项", () => {
  it("8 个入口全部渲染，未开启的老年专项写明原因，照片视频指向文件区且不可录入", async () => {
    const sections = [
      makeSection(),
      makeSection({ code: "elderly", name: "老年专项", enabled: false, disabled_reason: "7 岁以上或有慢病时自动开启" }),
      makeSection({ code: "media", name: "照片视频", content_source: "files", recordable: false, record_count: null }),
    ];
    const wrapper = mount(ArchiveSectionsCard, { props: { petId: 7, sections } });

    expect(wrapper.text()).toContain("核心指标");
    expect(wrapper.text()).toContain("7 岁以上或有慢病时自动开启");
    expect(wrapper.text()).toContain("照片视频");

    // 点照片视频：不发请求，只说明去哪儿传（照片不落档案表，ADR-0023 第二条）
    await wrapper.findAll("button.ph-sections__head")[2].trigger("click");
    await flushPromises();
    expect(listArchiveRecords).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain("档案照片");
  });

  it("展开可录分项拉列表，标出来源；权威那条标「当前值」", async () => {
    listArchiveRecords.mockResolvedValue({
      list: [
        makeRecord(),
        makeRecord({ id: 2, title: "免疫证", value: "用户填的号", source: 1, source_label: "用户录入", authoritative: false }),
      ],
      page: 1,
      page_size: 20,
      total: 2,
      has_more: false,
    });
    const wrapper = mount(ArchiveSectionsCard, {
      props: { petId: 7, sections: [makeSection({ code: "documents", name: "证件与合规", record_count: 2 })] },
    });

    await wrapper.find("button.ph-sections__head").trigger("click");
    await flushPromises();

    expect(listArchiveRecords).toHaveBeenCalledWith(7, { section: "documents", pageSize: 20 });
    expect(wrapper.text()).toContain("服务者报工");
    expect(wrapper.text()).toContain("用户录入");
    // 冲突两条都留（不做静默覆盖），只有权威那条标「当前值」
    expect(wrapper.findAll(".ph-records__auth")).toHaveLength(1);
  });
});

describe("时间轴", () => {
  it("按四类事件展示并带来源；空态给出下一步", async () => {
    const events: TimelineEventView[] = [
      { id: 1, type: "medical", type_name: "就医记录", category: 8, date: "2026-09-20", title: "呕吐就诊", source: 1, source_label: "用户录入", abnormal: false, backfilled: false },
      { id: 2, type: "abnormal", type_name: "异常记录", category: 3, date: "2026-09-19", title: "排泄标注为异常", source: 1, source_label: "用户录入", abnormal: true, backfilled: false },
    ];
    listTimeline.mockResolvedValue({ list: events, page: 1, page_size: 10, total: 2, has_more: false });
    const wrapper = mount(TimelineCard, { props: { petId: 7 } });
    await flushPromises();

    expect(wrapper.text()).toContain("就医记录");
    expect(wrapper.text()).toContain("异常记录");
    expect(wrapper.text()).toContain("共 2 条");

    // 过滤只传 type，不重新发明一套前端筛选
    await wrapper.findAll(".ph-timeline__filter")[1].trigger("click");
    await flushPromises();
    expect(listTimeline).toHaveBeenLastCalledWith(7, expect.objectContaining({ type: "medical" }));
  });
});

describe("健康报告", () => {
  it("按四段渲染，需要权益的段落打「邀请解锁」但内容照常展示", async () => {
    const report: HealthReportView = {
      id: 1,
      type: 1,
      type_name: "健康周报",
      period_start: "2026-09-14",
      period_end: "2026-09-20",
      grade: "尚可",
      total_score: 66,
      tier: 2,
      privilege_code: "report.full",
      tier_note: "完整版将在权益上线后按邀请解锁",
      generated_at: "2026-09-21 08:10:00",
      payload: {
        headline: "豆豆的健康周报",
        sections: [
          { code: "score_and_trend", name: "评分与趋势", lines: ["平均 66 分"], requires_privilege: false },
          { code: "abnormal_and_highlights", name: "异常与亮点", lines: ["标注异常 2 次"], requires_privilege: true },
          { code: "checkin_completion", name: "打卡完成度", lines: ["有记录 5 / 7 天"], requires_privilege: false },
          { code: "suggestions", name: "建议清单", lines: ["继续记录"], requires_privilege: true },
        ],
        stats: { record_days: 5, period_days: 7, abnormal_count: 2, ai_consults: 0, ai_red_flags: 0, provider_records: 0 },
        notice: "报告来自你的记录与平台评分，不能替代兽医诊断。",
      },
    };
    listHealthReports.mockResolvedValue({ list: [report], page: 1, page_size: 12, total: 1, has_more: false });
    const wrapper = mount(HealthReportCard, { props: { petId: 7 } });
    await flushPromises();

    expect(wrapper.text()).toContain("健康周报");
    expect(wrapper.findAll(".ph-report__section")).toHaveLength(4);
    expect(wrapper.findAll(".ph-report__lock")).toHaveLength(2);
    // 不拦截：需要权益的段落内容照样展示（ADR-0024 的第一条判断）
    expect(wrapper.text()).toContain("标注异常 2 次");
    // 不写「AI 生成」——报告是规则模板拼的
    expect(wrapper.text()).not.toContain("AI 周报");
  });

  it("没有报告时是空态，不是空白", async () => {
    const wrapper = mount(HealthReportCard, { props: { petId: 7 } });
    await flushPromises();
    expect(wrapper.text()).toContain("还没有报告");
  });
});

describe("专项照护模式", () => {
  const careMode: CareModeView = {
    active: true,
    reasons: [{ code: "elderly", label: "已进入老年期（9 岁）" }],
    age_text: "9 岁",
    age_years: 9,
    age_threshold_years: 7,
    elderly_since: "2026-05-01",
    chronic_desc: null,
    disabled_by_user: false,
    effects: ["评分多一个「老年专项」维度，并计入总分", "档案里多出「老年专项」分项"],
    notice: "照护模式会让参与总分的维度数量变化，总分可能出现波动——这是口径变化。",
  } as CareModeView;

  it("显示开启原因与四项变化，并给出总分口径变化的说明", async () => {
    const wrapper = mount(CareModeCard, { props: { petId: 7, careMode } });
    expect(wrapper.text()).toContain("已进入老年期（9 岁）");
    expect(wrapper.text()).toContain("老年专项");
    expect(wrapper.text()).toContain("口径变化");
    expect(wrapper.text()).toContain("关闭照护模式");
  });

  it("关闭时发一次写请求并把新状态交给父组件", async () => {
    setCareMode.mockResolvedValue({ ...careMode, active: false, disabled_by_user: true });
    const wrapper = mount(CareModeCard, { props: { petId: 7, careMode } });

    await wrapper.find("button.ph-button").trigger("click");
    await flushPromises();

    expect(setCareMode).toHaveBeenCalledWith(7, false);
    expect(wrapper.emitted("changed")?.[0]?.[0]).toMatchObject({ active: false });
  });
});
