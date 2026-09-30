<script setup lang="ts">
/**
 * 健康档案（切片 #94 / #95 / #99 / #102 / #115 / #116）。
 *
 * 一页装六块，都是**同一只宠物**的视角：照护模式、健康报告、8 个档案分项、时间轴、
 * 防疫记录、档案照片，最后是宠物信息。
 *
 * 三条口径写在代码里也写在 ADR 里：
 *  - 分项是原始记录、评分与报告是派生视图（ADR-0030 第一节）；
 *  - 时间轴只收四类事件，正常打卡不进（ADR-0030 第四节）；
 *  - 报告由规则模板拼装，界面叫「健康周报 / 健康月报」，**不写成 AI 生成**（ADR-0031）。
 *
 * 切宠物时**整块重载**：清空 + 序号守卫（`createLatestGuard`）——多宠家庭切宠物时，
 * 旧宠物的数据挂在新宠物标题下是确定性错误，不是竞态。
 */
import { computed, reactive, ref, watch } from "vue";
import {
  cApp,
  createLatestGuard,
  formatDate,
  genderLabel,
  shiftDate,
  speciesLabel,
  toApiFailure,
  toUserMessage,
  todayIso,
  type ArchiveRecordView,
  type ArchiveSectionView,
  type CareModeView,
  type EpidemicRecordView,
} from "@pet-health/shared";
import SessionGate from "../components/SessionGate.vue";
import PhotoUploader from "../components/PhotoUploader.vue";
import CareModeCard from "../components/CareModeCard.vue";
import ArchiveSectionsCard from "../components/ArchiveSectionsCard.vue";
import HealthReportCard from "../components/HealthReportCard.vue";
import TimelineCard from "../components/TimelineCard.vue";
import StateEmpty from "../components/states/StateEmpty.vue";
import StateError from "../components/states/StateError.vue";
import StateLoading from "../components/states/StateLoading.vue";
import { useSessionStore } from "../stores/session";

const session = useSessionStore();

const sections = ref<ArchiveSectionView[]>([]);
const careMode = ref<CareModeView | null>(null);
const records = ref<EpidemicRecordView[]>([]);
/** 就医记录（F004）：它是可写分项 `medical`，但**不属于那 8 个分项**（ADR-0030），所以单独一块。 */
const medicalRecords = ref<ArchiveRecordView[]>([]);
const loading = ref(false);
const errorMessage = ref("");
const requestId = ref("");
const saving = ref(false);
const showingForm = ref(false);
const formError = ref("");

/** 分项记录的可录窗口（天）：与后端 `ArchiveSectionService.WRITE_WINDOW_DAYS` 同一口径。 */
const WRITE_WINDOW_DAYS = 7;

const form = reactive({
  kind: 1,
  name: "",
  givenOn: "",
  nextDueOn: "",
});

/** 就医记录的录入表单（字段就是契约 `ArchiveRecordRequest` 的那几个，单位/到期对就诊没有意义）。 */
const medicalForm = reactive({
  date: "",
  title: "",
  value: "",
  note: "",
  abnormal: false,
});
const medicalFormError = ref("");
const medicalSaving = ref(false);
const showingMedicalForm = ref(false);

/** 窗口两端：今天与「今天 - 7 天」。真正的闸门在服务端，前端只提前拦一道。 */
const today = todayIso();
const earliestDate = shiftDate(today, -WRITE_WINDOW_DAYS);

/** 选了窗口外的日期就不让提交：后端会回 40001（`ArchiveSectionService.parseWithinWindow`）。 */
const medicalDateOutOfWindow = computed(
  () => medicalForm.date !== "" && (medicalForm.date > today || medicalForm.date < earliestDate),
);

const pet = computed(() => session.activePet);
const petId = computed(() => pet.value?.id ?? 0);

/**
 * 加载与切宠物。**清空 + loading 是必须的**：多宠家庭切宠物时，若不清空，旧宠物的档案会
 * 挂在新宠物的标题下（确定性错误，不是竞态）；序号用于丢弃过期响应。
 */
const latest = createLatestGuard();

async function load(): Promise<void> {
  if (!pet.value) return;
  const id = pet.value.id;
  const { token: seq, signal } = latest.claim();
  loading.value = true;
  errorMessage.value = "";
  sections.value = [];
  careMode.value = null;
  records.value = [];
  medicalRecords.value = [];
  try {
    // 四件事一起发：它们互不依赖，串起来只会让切宠物更慢
    const [loadedSections, loadedCareMode, loadedRecords, loadedMedical] = await Promise.all([
      cApp.listArchiveSections(id, signal),
      cApp.getCareMode(id, signal),
      cApp.listEpidemicRecords(id, signal),
      // 就医记录不属于那 8 个分项，所以它的列表要自己拉（section=medical）
      cApp.listArchiveRecords(id, { section: "medical", pageSize: 20 }, signal),
    ]);
    if (!latest.isCurrent(seq)) return;
    sections.value = loadedSections;
    careMode.value = loadedCareMode;
    records.value = loadedRecords;
    medicalRecords.value = loadedMedical.list;
  } catch (error) {
    if (!latest.isCurrent(seq)) return;
    const failure = toApiFailure(error, "加载失败，请稍后重试");
    errorMessage.value = failure.message;
    requestId.value = failure.requestId;
  } finally {
    if (latest.isCurrent(seq)) {
      loading.value = false;
    }
  }
}

watch(() => pet.value?.id, () => void load(), { immediate: true });

/** 照护模式一变，老年专项分项的可用状态也跟着变——重载入口列表（ADR-0032 的四项变化之一）。 */
function onCareModeChanged(mode: CareModeView): void {
  careMode.value = mode;
  void load();
}

/** 新增防疫记录：成功后重载整页——到期天数是服务端算的，不在本地拼一条。 */
async function submit(): Promise<void> {
  if (!pet.value) return;
  formError.value = "";
  saving.value = true;
  try {
    await cApp.createEpidemicRecord(pet.value.id, {
      kind: form.kind === 2 ? 2 : 1,
      name: form.name.trim(),
      given_on: form.givenOn,
      // 下次应接种日期**不填就没有提醒**：不替用户猜周期（ADR-0019）
      next_due_on: form.nextDueOn || undefined,
    });
    showingForm.value = false;
    form.name = "";
    form.givenOn = "";
    form.nextDueOn = "";
    await load();
  } catch (error) {
    formError.value = toUserMessage(error, "保存失败，请稍后重试");
  } finally {
    saving.value = false;
  }
}

/** 删除记录后重载：疫苗提醒由服务端按记录重算（ADR-0019），本地摘一条了事会留下对不上的提醒。 */
async function remove(record: EpidemicRecordView): Promise<void> {
  if (!pet.value) return;
  try {
    await cApp.deleteEpidemicRecord(pet.value.id, record.id);
    await load();
  } catch (error) {
    errorMessage.value = toUserMessage(error, "删除失败");
  }
}

/** 到期提示要区分「还有 N 天」与「已过期 N 天」——过期的更急，不能只显示负数。 */
function dueText(record: EpidemicRecordView): string {
  if (record.days_until_due === null || record.days_until_due === undefined) return "未设置下次日期";
  if (record.days_until_due < 0) return `已过期 ${Math.abs(record.days_until_due)} 天`;
  if (record.days_until_due === 0) return "今天应接种";
  return `还有 ${record.days_until_due} 天`;
}

/** 契约里的 kind 数字码 → 词（1 疫苗 / 2 驱虫）；码适合传不适合显示，只在这类地方翻。 */
function kindLabel(kind: number): string {
  return kind === 2 ? "驱虫" : "疫苗";
}

// ---------------------------------------------------------------- 就医记录（F004）

/**
 * 打开就医记录表单。日期默认今天——就诊当天回来补记是最常见的用法。
 *
 * 这一块为什么单独写：`medical` 是**可写分项**，但它不在交付文档 4.16.4 的 8 个分项里
 * （ADR-0030 把它与 8 个模块并列），所以 `listArchiveSections` 的入口列表里没有它，
 * 前端要自己给出入口。它录进去的记录会进时间轴（`type=medical`）与报告。
 */
function openMedicalForm(): void {
  medicalFormError.value = "";
  medicalForm.date = today;
  medicalForm.title = "";
  medicalForm.value = "";
  medicalForm.note = "";
  medicalForm.abnormal = false;
  showingMedicalForm.value = true;
}

/**
 * 提交一条就医记录。窗口外的日期不发（后端也会拒，见 medicalDateOutOfWindow）；
 * 成功后整页重载——时间轴、报告与分项条数都由服务端算，本地拼一条会与它们对不上。
 */
async function submitMedical(): Promise<void> {
  if (!pet.value || medicalDateOutOfWindow.value) return;
  medicalSaving.value = true;
  medicalFormError.value = "";
  try {
    await cApp.createArchiveRecord(pet.value.id, {
      section: "medical",
      date: medicalForm.date,
      title: medicalForm.title.trim(),
      value: medicalForm.value.trim() || undefined,
      note: medicalForm.note.trim() || undefined,
      abnormal: medicalForm.abnormal,
    });
    showingMedicalForm.value = false;
    await load();
  } catch (error) {
    medicalFormError.value = toUserMessage(error, "保存失败，请稍后重试");
  } finally {
    medicalSaving.value = false;
  }
}

/** 删除一条就医记录：同样整页重载（时间轴与报告都要跟着变）。 */
async function removeMedical(record: ArchiveRecordView): Promise<void> {
  if (!pet.value) return;
  try {
    await cApp.deleteArchiveRecord(pet.value.id, record.id);
    await load();
  } catch (error) {
    errorMessage.value = toUserMessage(error, "删除失败");
  }
}
</script>

<template>
  <section>
    <h2 class="ph-page-title">健康档案</h2>
    <p class="ph-page-desc">疫苗、驱虫、就医、打卡与服务报工，都汇成同一条时间轴。</p>

    <SessionGate forbidden-description="健康档案跟着宠物走，登录后查看。">
      <StateError v-if="errorMessage" :message="errorMessage" :request-id="requestId" @retry="load" />
      <StateLoading v-else-if="loading && !sections.length" :rows="6" />
      <StateEmpty
        v-else-if="!pet"
        icon="🐾"
        title="还没有宠物"
        description="到「我的」里建第一份档案，档案页就有内容了。"
      />

      <div v-else class="ph-columns">
        <div class="ph-stack">
          <ArchiveSectionsCard v-if="sections.length" :pet-id="petId" :sections="sections" />
          <TimelineCard :pet-id="petId" />
        </div>

        <div class="ph-stack">
          <CareModeCard v-if="careMode" :pet-id="petId" :care-mode="careMode" @changed="onCareModeChanged" />
          <HealthReportCard :pet-id="petId" />

          <article class="ph-card">
            <div class="ph-facts__head">
              <h3 class="ph-card__title">防疫记录（疫苗 / 驱虫）</h3>
              <button type="button" class="ph-button ph-button--secondary" @click="showingForm = !showingForm">
                ＋ 添加
              </button>
            </div>
            <p class="ph-text-sub ph-card__note">
              填了「下次应接种日期」才会在到期前提醒你；不填就不会提醒（不替你猜周期）。
            </p>

            <StateEmpty
              v-if="records.length === 0 && !showingForm"
              icon="💉"
              title="还没有防疫记录"
              description="记下疫苗与驱虫日期，到期前会提醒你。"
            />

            <ul v-else-if="records.length" class="ph-epi">
              <li v-for="record in records" :key="record.id" class="ph-epi__row">
                <div class="ph-epi__info">
                  <span class="ph-epi__name">{{ record.name }}</span>
                  <span class="ph-text-weak">
                    {{ kindLabel(record.kind) }} · 接种 {{ formatDate(record.given_on) }}
                  </span>
                  <span class="ph-epi__due">{{ dueText(record) }}</span>
                </div>
                <button type="button" class="ph-button ph-button--text" @click="remove(record)">删除</button>
              </li>
            </ul>

            <form v-if="showingForm" class="ph-form" @submit.prevent="submit">
              <div class="ph-form__grid">
                <label class="ph-field">
                  <span class="ph-field__label">类型</span>
                  <select v-model.number="form.kind" class="ph-field__input">
                    <option :value="1">疫苗</option>
                    <option :value="2">驱虫</option>
                  </select>
                </label>
                <label class="ph-field">
                  <span class="ph-field__label">名称 *</span>
                  <input v-model="form.name" class="ph-field__input" maxlength="64" placeholder="如：狂犬疫苗" />
                </label>
                <label class="ph-field">
                  <span class="ph-field__label">接种日期 *</span>
                  <input v-model="form.givenOn" type="date" class="ph-field__input" :max="todayIso()" />
                </label>
                <label class="ph-field">
                  <span class="ph-field__label">下次应接种日期</span>
                  <input v-model="form.nextDueOn" type="date" class="ph-field__input" :min="form.givenOn || undefined" />
                </label>
              </div>
              <p v-if="formError" class="ph-form__error">{{ formError }}</p>
              <div class="ph-form__actions">
                <button
                  type="submit"
                  class="ph-button ph-button--primary"
                  :disabled="saving || !form.name.trim() || !form.givenOn"
                >
                  {{ saving ? "保存中…" : "保存" }}
                </button>
                <button type="button" class="ph-button ph-button--secondary" @click="showingForm = false">取消</button>
              </div>
            </form>
          </article>

          <!-- 就医记录（F004）：`medical` 是可写分项，但不在 8 个分项里，所以自己一块入口。
               录进去的记录会进时间轴与健康报告（用户自述，不是平台产出的医疗记录）。 -->
          <article class="ph-card">
            <div class="ph-facts__head">
              <h3 class="ph-card__title">就医记录</h3>
              <button
                type="button"
                class="ph-button ph-button--secondary"
                @click="showingMedicalForm ? (showingMedicalForm = false) : openMedicalForm()"
              >
                ＋ 添加
              </button>
            </div>
            <p class="ph-text-sub ph-card__note">
              记一次就诊：什么情况、去了哪家门店、结论是什么。这是<strong>你自己的记录</strong>，
              不是诊断；写下来会进时间轴与健康报告。
            </p>

            <StateEmpty
              v-if="medicalRecords.length === 0 && !showingMedicalForm"
              icon="🩺"
              title="还没有就医记录"
              description="就诊后记一条，下次复诊时能翻到当时的情况。"
            />

            <ul v-else-if="medicalRecords.length" class="ph-epi">
              <li v-for="record in medicalRecords" :key="record.id" class="ph-epi__row">
                <div class="ph-epi__info">
                  <span class="ph-epi__name">{{ record.title }}</span>
                  <span class="ph-text-weak">
                    {{ formatDate(record.record_date) }} · {{ record.source_label }}
                    <template v-if="record.value"> · {{ record.value }}</template>
                    <template v-if="record.abnormal"> · 已标注异常</template>
                    <template v-if="record.backfilled"> · 补录</template>
                  </span>
                  <span v-if="record.note" class="ph-text-sub">{{ record.note }}</span>
                </div>
                <button type="button" class="ph-button ph-button--text" @click="removeMedical(record)">删除</button>
              </li>
            </ul>

            <form v-if="showingMedicalForm" class="ph-form" @submit.prevent="submitMedical">
              <div class="ph-form__grid">
                <label class="ph-field">
                  <span class="ph-field__label">日期 *</span>
                  <input
                    v-model="medicalForm.date"
                    type="date"
                    class="ph-field__input"
                    :min="earliestDate"
                    :max="today"
                  />
                </label>
                <label class="ph-field">
                  <span class="ph-field__label">事由 / 标题 *</span>
                  <input
                    v-model="medicalForm.title"
                    class="ph-field__input"
                    maxlength="64"
                    placeholder="如：呕吐就诊 / 年度体检"
                  />
                </label>
                <label class="ph-field">
                  <span class="ph-field__label">结论与处理</span>
                  <input
                    v-model="medicalForm.value"
                    class="ph-field__input"
                    maxlength="128"
                    placeholder="如：急性肠胃炎，开了三天药"
                  />
                </label>
                <label class="ph-field">
                  <span class="ph-field__label">备注</span>
                  <input v-model="medicalForm.note" class="ph-field__input" maxlength="500" placeholder="选填" />
                </label>
              </div>
              <label class="ph-file__switch">
                <input v-model="medicalForm.abnormal" type="checkbox" />
                <span>这次属于异常情况（会在健康评分里计一次异常）</span>
              </label>
              <p v-if="medicalDateOutOfWindow" class="ph-field__hint">
                只能录入今天或最近 7 天（{{ earliestDate }} 起）的记录。
              </p>
              <p v-if="medicalFormError" class="ph-form__error">{{ medicalFormError }}</p>
              <div class="ph-form__actions">
                <button
                  type="submit"
                  class="ph-button ph-button--primary"
                  :disabled="medicalSaving || medicalDateOutOfWindow || !medicalForm.title.trim() || !medicalForm.date"
                >
                  {{ medicalSaving ? "保存中…" : "保存" }}
                </button>
                <button type="button" class="ph-button ph-button--secondary" @click="showingMedicalForm = false">
                  取消
                </button>
              </div>
            </form>
          </article>

          <!-- 照片（切片 #95）：体检单、疫苗本、患处特写这些「纸面材料」的落点。
               它是档案分项里的「照片视频」，只是内容存在文件模块（ADR-0023 第二条）：
               打卡与防疫的照片在各自记录里，服务留痕照片（#107 的三道照片墙）不在这里。 -->
          <PhotoUploader
            :pet-id="petId"
            biz-type="profile"
            title="档案照片"
            hint="疫苗本、体检单、患处照片都可以。一次可选多张：JPEG / PNG，单张不超过 10MB，最多 9 张。"
          />

          <article class="ph-card">
            <h3 class="ph-card__title">宠物信息</h3>
            <ul v-if="session.pets.length" class="ph-facts">
              <li v-for="item in session.pets" :key="item.id" class="ph-facts__pet">
                <p class="ph-facts__name">
                  {{ item.name }}
                  <span v-if="item.id === session.activePet?.id" class="ph-facts__badge">当前</span>
                </p>
                <dl class="ph-facts__list">
                  <div><dt>物种</dt><dd>{{ speciesLabel(item.species) }}</dd></div>
                  <div><dt>品种</dt><dd>{{ item.breed ?? "未填" }}</dd></div>
                  <div><dt>性别</dt><dd>{{ genderLabel(item.gender) }}</dd></div>
                  <div><dt>生日</dt><dd>{{ item.birthday ? formatDate(item.birthday) : "未填" }}</dd></div>
                  <div><dt>体重</dt><dd>{{ item.weight ? `${item.weight} kg` : "未填" }}</dd></div>
                  <div><dt>绝育</dt><dd>{{ item.is_sterilized ? "已绝育" : "未绝育" }}</dd></div>
                  <div>
                    <dt>慢病</dt>
                    <dd>{{ item.is_chronic ? item.chronic_desc ?? "已标记" : "无" }}</dd>
                  </div>
                </dl>
              </li>
            </ul>
          </article>
        </div>
      </div>
    </SessionGate>
  </section>
</template>

<style scoped>
.ph-facts__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ph-space-4);
}

.ph-facts__head .ph-card__title {
  margin-bottom: 0;
}

.ph-epi {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-3);
}

.ph-epi__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ph-space-3);
  padding-bottom: var(--ph-space-3);
  border-bottom: 1px solid var(--ph-color-divider);
}

.ph-epi__row:last-child {
  border-bottom: 0;
  padding-bottom: 0;
}

.ph-epi__info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.ph-epi__name {
  font-weight: 600;
}

.ph-epi__due {
  font-size: 12px;
  color: var(--ph-color-primary);
}

/* 勾选行（「这次属于异常情况」）：与打卡卡里那一行同形态，但样式是这一页自己的 */
.ph-file__switch {
  display: flex;
  align-items: center;
  gap: var(--ph-space-2);
  font-size: 13px;
  color: var(--ph-color-text-sub);
}

.ph-facts {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-4);
}

.ph-facts__pet:not(:last-child) {
  padding-bottom: var(--ph-space-4);
  border-bottom: 1px solid var(--ph-color-divider);
}

.ph-facts__name {
  margin: 0 0 var(--ph-space-3);
  font-size: 15px;
  font-weight: 600;
}

.ph-facts__badge {
  margin-left: var(--ph-space-2);
  padding: 2px var(--ph-space-2);
  background: var(--ph-color-primary-light);
  border-radius: var(--ph-radius-input);
  color: var(--ph-color-primary);
  font-size: 12px;
  font-weight: 500;
}

.ph-facts__list {
  margin: 0;
  display: grid;
  gap: var(--ph-space-2);
}

.ph-facts__list div {
  display: flex;
  justify-content: space-between;
  gap: var(--ph-space-3);
}

.ph-facts__list dt {
  color: var(--ph-color-text-sub);
}

.ph-facts__list dd {
  margin: 0;
  text-align: right;
}
</style>
