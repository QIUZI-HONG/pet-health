/**
 * 打卡卡与评分卡的组件测试（切片 #97 的验收标准：六项可提交、首页评分卡与打卡进度展示正确）。
 *
 * 这两块的产品规则都在 ADR-0018 里，测试逐条对应：
 *  - 任一项即算当日打卡（进度 n/6 但完成看 done）；
 *  - 「全部正常」一次提交六项、「和昨天一样」复制昨天的取值；
 *  - 没有数据时显示「还没有评分」，**不是 0 分**；
 *  - 未计入的维度显示原因（待录入 / 未开启），不显示成 0 分。
 */
import { describe, expect, it } from "vitest";
import { mount } from "@vue/test-utils";
import CheckInCard from "../components/CheckInCard.vue";
import HealthScoreCard from "../components/HealthScoreCard.vue";
import type { CheckInDay, HealthScoreView } from "@pet-health/shared";

function makeDay(overrides: Partial<CheckInDay> = {}): CheckInDay {
  const names = ["体重", "饮食", "排泄", "行为", "情绪", "卫生"];
  return {
    date: "2026-09-27",
    items: names.map((name, index) => ({
      category: (index + 1) as 1 | 2 | 3 | 4 | 5 | 6,
      name,
      filled: false,
      abnormal: false,
      value: undefined,
      note: undefined,
      backfilled: false,
    })),
    completed_count: 0,
    total_count: 6,
    done: false,
    backfilled: false,
    ...overrides,
  } as CheckInDay;
}

function makeScore(overrides: Partial<HealthScoreView> = {}): HealthScoreView {
  return {
    total_score: 66,
    grade: "需关注",
    dimensions: [
      { key: "physiology", name: "生理", score: 67, included: true, detail: "近 7 天记录 4 天，异常 1 次" },
      { key: "behavior", name: "行为", score: 50, included: true, detail: "近 7 天记录 2 天" },
      { key: "hygiene", name: "卫生", score: 80, included: true, detail: "近 7 天记录 5 天" },
      { key: "epidemic", name: "防疫", included: false, excluded_reason: "待录入" },
      { key: "elderly", name: "老年专项", included: false, excluded_reason: "未开启" },
    ],
    trend: [],
    calc_date: "2026-09-27",
    disclaimer: "评分用于观察趋势，不能替代兽医诊断。",
    ...overrides,
  } as HealthScoreView;
}

describe("打卡卡", () => {
  it("进度按已填项数显示，任一项即算当日已打卡", () => {
    const day = makeDay({
      completed_count: 1,
      done: true,
      items: makeDay().items.map((item, index) => (index === 0 ? { ...item, filled: true, value: "12.50" } : item)),
    });

    const wrapper = mount(CheckInCard, { props: { day, streakDays: 0 } });

    expect(wrapper.text()).toContain("1/6");
    expect(wrapper.text()).toContain("今天已打卡");   // 只记了一项也提示已打卡（ADR-0018）
    expect(wrapper.text()).toContain("12.50");
  });

  it("连续天数有值时才显示", () => {
    expect(mount(CheckInCard, { props: { day: makeDay(), streakDays: 0 } }).text()).not.toContain("连续");
    expect(mount(CheckInCard, { props: { day: makeDay(), streakDays: 5 } }).text()).toContain("连续 5 天");
  });

  it("线值要翻译成中文：界面不出现 normal / low / high（切片 #97 的缺陷回归）", async () => {
  const day = makeDay();
  day.items[0] = { ...day.items[0], filled: true, value: "8.25" };
  day.items[1] = { ...day.items[1], filled: true, value: "normal" };
  day.items[2] = { ...day.items[2], filled: true, value: "low" };
  const wrapper = mount(CheckInCard, { props: { day, streakDays: 1 } });

  const text = wrapper.text();
  expect(text).toContain("8.25");
  expect(text).toContain("正常");
  expect(text).toContain("偏少");
  expect(text, "英文线值不该出现在界面上").not.toMatch(/normal|low|high/);
});

it("点行内展开 → 勾「不太正常」→ 保存，提交里带 abnormal: true", async () => {
    const wrapper = mount(CheckInCard, { props: { day: makeDay(), streakDays: 0 } });

    await wrapper.findAll(".ph-checkin__line")[2].trigger("click");     // 第 3 行 = 排泄
    const abnormalBox = wrapper.get(".ph-checkin__switch input");
    await abnormalBox.setValue(true);
    await wrapper.get(".ph-checkin__input").setValue("有点软");
    await wrapper.get(".ph-checkin__actions button").trigger("click");

    const submitted = wrapper.emitted("submit")?.[0]?.[0] as Array<{ category: number; abnormal: boolean; note?: string }>;
    expect(submitted).toHaveLength(1);
    expect(submitted[0].category).toBe(3);
    expect(submitted[0].abnormal).toBe(true);
    expect(submitted[0].note).toBe("有点软");
  });

  it("「全部正常」一次提交六项，且体重不填「normal」这种无意义取值", async () => {    const wrapper = mount(CheckInCard, { props: { day: makeDay(), streakDays: 0 } });

    await wrapper.get(".ph-checkin__bulk button").trigger("click");

    const submitted = wrapper.emitted("submit")?.[0]?.[0] as Array<{ category: number; abnormal: boolean; value?: string }>;
    expect(submitted).toHaveLength(6);
    expect(submitted.every((item) => item.abnormal === false)).toBe(true);
    expect(submitted.find((item) => item.category === 1)?.value).toBeUndefined();
    expect(submitted.find((item) => item.category === 2)?.value).toBe("normal");
  });

  it("「和昨天一样」只在有昨天的数据时出现，且复制昨天的取值", async () => {
    const noYesterday = mount(CheckInCard, { props: { day: makeDay(), streakDays: 0, yesterdayValues: {} } });
    expect(noYesterday.text()).not.toContain("和昨天一样");

    const wrapper = mount(CheckInCard, {
      props: { day: makeDay(), streakDays: 0, yesterdayValues: { 2: { value: "low", note: "吃得少" } } },
    });
    await wrapper.findAll(".ph-checkin__bulk button")[1].trigger("click");

    const submitted = wrapper.emitted("submit")?.[0]?.[0] as Array<{ category: number; value?: string; note?: string }>;
    expect(submitted).toHaveLength(1);
    expect(submitted[0]).toMatchObject({ category: 2, value: "low", note: "吃得少" });
  });

  it("勾了异常就不再提交占位符「normal」（避免库里存下「异常 + normal」）", async () => {
    const wrapper = mount(CheckInCard, { props: { day: makeDay(), streakDays: 0 } });

    await wrapper.findAll(".ph-checkin__line")[2].trigger("click");
    await wrapper.get(".ph-checkin__switch input").setValue(true);
    await wrapper.get(".ph-checkin__actions button").trigger("click");

    const submitted = wrapper.emitted("submit")?.[0]?.[0] as Array<{ abnormal: boolean; value?: string }>;
    expect(submitted[0].abnormal).toBe(true);
    expect(submitted[0].value).toBeUndefined();
  });

  it("异常项显示备注而不是「异常 · normal」", () => {
    const day = makeDay({
      completed_count: 1,
      items: makeDay().items.map((item, index) =>
        index === 2 ? { ...item, filled: true, abnormal: true, value: "normal", note: "有点软" } : item,
      ),
    });

    const text = mount(CheckInCard, { props: { day, streakDays: 0 } }).text();
    expect(text).toContain("异常 · 有点软");
    expect(text).not.toContain("异常 · normal");
  });

  it("已填项可以撤销", async () => {
    const day = makeDay({
      completed_count: 1,
      items: makeDay().items.map((item, index) => (index === 0 ? { ...item, filled: true } : item)),
    });
    const wrapper = mount(CheckInCard, { props: { day, streakDays: 0 } });

    await wrapper.findAll(".ph-checkin__line")[0].trigger("click");
    const buttons = wrapper.findAll(".ph-checkin__actions button");
    await buttons[1].trigger("click");     // 「撤销这一项」

    expect(wrapper.emitted("undo")?.[0]).toEqual([1]);
  });

  it("补录的记录带「补录」标记", () => {
    const day = makeDay({
      backfilled: true,
      completed_count: 1,
      items: makeDay().items.map((item, index) =>
        index === 0 ? { ...item, filled: true, value: "12.00", backfilled: true } : item,
      ),
    });

    expect(mount(CheckInCard, { props: { day, streakDays: 0 } }).text()).toContain("补录");
  });

  /**
   * 体重这一项**后端不校验**（`CheckInItemRequest.value` 是自由字符串），`abc` / `0` / `-5` / `99999`
   * 都收得下并落进档案、参与体重趋势——实测首页会出现「体重下降了 2000080%」这种提醒。
   * 前端拦一道：非法值不发请求，并当场告诉用户哪里不对。
   */
  it("体重填非法值时：保存禁用、给出提示、不发请求", async () => {
    const wrapper = mount(CheckInCard, { props: { day: makeDay(), streakDays: 0 } });

    await wrapper.findAll(".ph-checkin__line")[0].trigger("click"); // 第 1 行 = 体重
    const input = wrapper.get(".ph-checkin__input");
    const save = wrapper.get(".ph-checkin__actions button");

    for (const bad of ["abc", "0", "-5", "99999", "8.256", "１２"]) {
      await input.setValue(bad);
      expect(await save.attributes("disabled"), `${bad} 应被拒绝`).toBeDefined();
      expect(wrapper.text()).toContain("0.01–999.99");
      await save.trigger("click");
    }
    expect(wrapper.emitted("submit")).toBeUndefined();

    await input.setValue("8.25");
    expect(await save.attributes("disabled")).toBeUndefined();
    await save.trigger("click");
    const submitted = wrapper.emitted("submit")?.[0]?.[0] as Array<{ category: number; value?: string }>;
    expect(submitted[0]).toEqual({ category: 1, abnormal: false, value: "8.25", note: undefined });
  });

  it("体重留空是允许的：这一项先不记，不算填错", async () => {
    const wrapper = mount(CheckInCard, { props: { day: makeDay(), streakDays: 0 } });

    await wrapper.findAll(".ph-checkin__line")[0].trigger("click");
    expect(wrapper.text()).not.toContain("0.01–999.99");
    expect(await wrapper.get(".ph-checkin__actions button").attributes("disabled")).toBeUndefined();
  });
});

describe("评分卡", () => {
  it("显示总分、档位与五维；每维都能看到「为什么是这个分」", () => {
    const wrapper = mount(HealthScoreCard, { props: { score: makeScore() } });

    expect(wrapper.text()).toContain("66");
    expect(wrapper.text()).toContain("需关注");
    expect(wrapper.text()).toContain("生理");
    expect(wrapper.text()).toContain("近 7 天记录 4 天，异常 1 次");
  });

  it("未计入的维度显示原因，不显示成 0 分", () => {
    const wrapper = mount(HealthScoreCard, { props: { score: makeScore() } });

    expect(wrapper.text()).toContain("待录入");     // 防疫无疫苗/驱虫数据
    expect(wrapper.text()).toContain("未开启");     // 老年专项
    // 这两维不该画出进度条
    expect(wrapper.findAll(".ph-score__bar-fill")).toHaveLength(3);
  });

  it("一条记录都没有时显示「还没有评分」，不是 0 分", () => {
    const wrapper = mount(HealthScoreCard, { props: { score: makeScore({ total_score: undefined, grade: "暂无数据" }) } });

    expect(wrapper.text()).toContain("还没有评分");
    expect(wrapper.text()).not.toContain("0");
  });

  it("记录天数少时提醒「分数偏低是因为记录不连续」，避免被读成宠物病了", () => {
    const fewDays = makeScore({ trend: [{ date: "2026-09-27", total_score: 32 }] });
    const wrapper = mount(HealthScoreCard, { props: { score: fewDays } });
    expect(wrapper.text()).toContain("记录天数还少");

    // 记录满 7 天后不再提示
    const fullWeek = makeScore({
      trend: Array.from({ length: 7 }, (_, index) => ({ date: `2026-09-2${index + 1}`, total_score: 80 })),
    });
    expect(mount(HealthScoreCard, { props: { score: fullWeek } }).text()).not.toContain("记录天数还少");
  });

  it("必须展示免责声明（医疗场景宁严勿松）", () => {
    expect(mount(HealthScoreCard, { props: { score: makeScore() } }).text()).toContain("不能替代兽医诊断");
  });
});
