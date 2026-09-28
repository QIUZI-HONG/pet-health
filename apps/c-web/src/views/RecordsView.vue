<script setup lang="ts">
/**
 * 健康档案：宠物信息 + 防疫记录（疫苗 / 驱虫）+ 时间轴占位。
 *
 * 为什么这里先放防疫记录：它是**疫苗/驱虫提醒**的唯一依据（ADR-0019），
 * 也让健康评分的「防疫」维度从「待录入」变成可计分。
 * 完整的档案分项与时间轴属 #102，那一块如实显示空态。
 */
import { computed, reactive, ref, watch } from "vue";
import {
  ApiError,
  cApp,
  createLatestGuard,
  formatDate,
  genderLabel,
  speciesLabel,
  todayIso,
  type EpidemicRecord,
} from "@pet-health/shared";
import SessionGate from "../components/SessionGate.vue";
import PhotoUploader from "../components/PhotoUploader.vue";
import StateEmpty from "../components/states/StateEmpty.vue";
import StateError from "../components/states/StateError.vue";
import StateLoading from "../components/states/StateLoading.vue";
import { useSessionStore } from "../stores/session";

const session = useSessionStore();

const records = ref<EpidemicRecord[]>([]);
const loading = ref(false);
const errorMessage = ref("");
const requestId = ref("");
const saving = ref(false);
const showingForm = ref(false);
const formError = ref("");

const form = reactive({
  kind: 1,
  name: "",
  givenOn: "",
  nextDueOn: "",
});

const pet = computed(() => session.activePet);

/**
 * 加载与切宠物。**清空 + loading 是必须的**：多宠家庭切宠物时，若不清空，旧宠物的防疫记录会
 * 挂在新宠物的标题下（确定性错误，不是竞态）；首页那种「空态先闪一下」也是同一原因。
 * 序号用于丢弃过期响应——先发的请求后回来，会把新宠物的数据盖掉。
 */
const latest = createLatestGuard();

async function load(): Promise<void> {
  if (!pet.value) return;
  const seq = latest.claim();
  loading.value = true;
  errorMessage.value = "";
  records.value = [];
  try {
    const list = await cApp.listEpidemicRecords(pet.value.id);
    if (!latest.isCurrent(seq)) return;
    records.value = list;
  } catch (error) {
    if (!latest.isCurrent(seq)) return;
    if (error instanceof ApiError) {
      errorMessage.value = error.message;
      requestId.value = error.requestId;
    } else {
      errorMessage.value = "加载失败，请稍后重试";
    }
  } finally {
    if (latest.isCurrent(seq)) {
      loading.value = false;
    }
  }
}

watch(() => pet.value?.id, () => void load(), { immediate: true });

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
    formError.value = error instanceof ApiError ? error.message : "保存失败，请稍后重试";
  } finally {
    saving.value = false;
  }
}

async function remove(record: EpidemicRecord): Promise<void> {
  if (!pet.value) return;
  try {
    await cApp.deleteEpidemicRecord(pet.value.id, record.id);
    await load();
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : "删除失败";
  }
}

/** 到期提示要区分「还有 N 天」与「已过期 N 天」——过期的更急，不能只显示负数。 */
function dueText(record: EpidemicRecord): string {
  if (record.days_until_due === null || record.days_until_due === undefined) return "未设置下次日期";
  if (record.days_until_due < 0) return `已过期 ${Math.abs(record.days_until_due)} 天`;
  if (record.days_until_due === 0) return "今天应接种";
  return `还有 ${record.days_until_due} 天`;
}

function kindLabel(kind: number): string {
  return kind === 2 ? "驱虫" : "疫苗";
}
</script>

<template>
  <section>
    <h2 class="ph-page-title">健康档案</h2>
    <p class="ph-page-desc">疫苗、驱虫、就医、打卡与服务报工，都汇成同一条时间轴。</p>

    <SessionGate forbidden-description="健康档案跟着宠物走，登录后查看。">
      <div class="ph-columns">
      <div class="ph-stack">
        <article class="ph-card">
          <h3 class="ph-card__title">时间轴</h3>
          <StateEmpty
            icon="📋"
            title="还没有记录"
            description="打卡记录、就医记录、服务报工都会汇到这条时间轴上。"
          />
        </article>
      </div>

      <div class="ph-stack">
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

          <StateError
            v-if="errorMessage"
            :message="errorMessage"
            :request-id="requestId"
            @retry="load"
          />

          <StateLoading v-else-if="loading" :rows="3" />

          <StateEmpty
            v-else-if="records.length === 0 && !showingForm"
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

        <!-- 照片（切片 #95）：体检单、疫苗本、患处特写这些「纸面材料」的落点 -->
        <PhotoUploader
          :pet-id="session.activePet?.id ?? null"
          biz-type="profile"
          title="档案照片"
          hint="疫苗本、体检单、患处照片都可以。一次可选多张：JPEG / PNG，单张不超过 10MB，最多 9 张。"
        />

        <article class="ph-card">
          <h3 class="ph-card__title">宠物信息</h3>
          <ul v-if="session.pets.length" class="ph-facts">
            <li v-for="pet in session.pets" :key="pet.id" class="ph-facts__pet">
              <p class="ph-facts__name">
                {{ pet.name }}
                <span v-if="pet.id === session.activePet?.id" class="ph-facts__badge">当前</span>
              </p>
              <dl class="ph-facts__list">
                <div><dt>物种</dt><dd>{{ speciesLabel(pet.species) }}</dd></div>
                <div><dt>品种</dt><dd>{{ pet.breed ?? "未填" }}</dd></div>
                <div><dt>性别</dt><dd>{{ genderLabel(pet.gender) }}</dd></div>
                <div><dt>生日</dt><dd>{{ pet.birthday ? formatDate(pet.birthday) : "未填" }}</dd></div>
                <div><dt>体重</dt><dd>{{ pet.weight ? `${pet.weight} kg` : "未填" }}</dd></div>
                <div><dt>绝育</dt><dd>{{ pet.is_sterilized ? "已绝育" : "未绝育" }}</dd></div>
                <div>
                  <dt>慢病</dt>
                  <dd>{{ pet.is_chronic ? pet.chronic_desc ?? "已标记" : "无" }}</dd>
                </div>
              </dl>
            </li>
          </ul>
          <StateEmpty
            v-else
            icon="🐾"
            title="还没有宠物"
            description="到「我的」里建第一份档案，档案页就有内容了。"
          />
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

.ph-card__note {
  margin: var(--ph-space-2) 0 var(--ph-space-3);
  font-size: 12px;
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
