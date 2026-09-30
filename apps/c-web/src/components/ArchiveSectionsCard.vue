<script setup lang="ts">
/**
 * 档案分项（切片 #102，字段清单与多源规则见 ADR-0030）。
 *
 * 三件展示上的判断：
 *
 *  1. **入口固定 8 个**（交付文档 4.16.4 的模块列表）：未开启的老年专项与内容在文件模块的
 *     照片视频也显示出来，只是灰掉并写明原因——「没有这个入口」和「现在还不能用」是两件事。
 *  2. **展开才拉记录**：8 个分项一起加载就是 8 次请求，而用户一次只看一个分项。
 *  3. **每条记录都标来源与时间**：多源写入时**冲突的两条都保留**（本轮拍板），
 *     权威那条给一个「当前值」标记，其余照列——不做静默覆盖，用户才不会觉得「我明明记过」。
 */
import { ref } from "vue";
import {
  cApp,
  formatDate,
  toApiFailure,
  toUserMessage,
  todayIso,
  type ArchiveRecordView,
  type ArchiveRecordRequest,
  type ArchiveSectionView,
} from "@pet-health/shared";
import StateEmpty from "./states/StateEmpty.vue";
import StateError from "./states/StateError.vue";
import StateLoading from "./states/StateLoading.vue";

const props = defineProps<{ petId: number; sections: ArchiveSectionView[] }>();

const expanded = ref<string | null>(null);
const records = ref<ArchiveRecordView[]>([]);
const loading = ref(false);
const errorMessage = ref("");
const requestId = ref("");

const form = ref<ArchiveRecordRequest | null>(null);
const saving = ref(false);
const formError = ref("");

function isOpen(section: ArchiveSectionView): boolean {
  return expanded.value === section.code;
}

async function toggle(section: ArchiveSectionView): Promise<void> {
  if (isOpen(section)) {
    expanded.value = null;
    return;
  }
  expanded.value = section.code;
  form.value = null;
  records.value = [];
  if (!section.recordable) {
    // 打卡六项、防疫、照片视频各有自己的入口：这里只说明去哪儿录，不发请求
    return;
  }
  loading.value = true;
  errorMessage.value = "";
  try {
    records.value = (await cApp.listArchiveRecords(props.petId, { section: section.code, pageSize: 20 })).list;
  } catch (error) {
    const failure = toApiFailure(error, "加载失败，请稍后重试");
    errorMessage.value = failure.message;
    requestId.value = failure.requestId;
  } finally {
    loading.value = false;
  }
}

function startForm(section: ArchiveSectionView): void {
  formError.value = "";
  form.value = {
    section: section.code as ArchiveRecordRequest["section"],
    date: todayIso(),
    title: "",
    // 契约里 abnormal 带默认值 false，生成的类型因此是必填的（与打卡的 item 同形）：
    // 这里显式给 false，用户要在表单里标异常时再改
    abnormal: false,
  };
}

async function submit(): Promise<void> {
  if (!form.value) return;
  saving.value = true;
  formError.value = "";
  try {
    await cApp.createArchiveRecord(props.petId, form.value);
    const section = expanded.value;
    form.value = null;
    const target = props.sections.find((item) => item.code === section);
    expanded.value = null;
    if (target) await toggle(target);
  } catch (error) {
    formError.value = toUserMessage(error, "保存失败，请稍后重试");
  } finally {
    saving.value = false;
  }
}

async function remove(record: ArchiveRecordView): Promise<void> {
  try {
    await cApp.deleteArchiveRecord(props.petId, record.id);
    records.value = records.value.filter((item) => item.id !== record.id);
  } catch (error) {
    errorMessage.value = toUserMessage(error, "删除失败");
  }
}

/** 副行说明 + 已录条数：入口列表要在不展开的情况下就能看出「这里有没有东西」。 */
function summary(section: ArchiveSectionView): string {
  if (!section.enabled) return section.disabled_reason ?? "暂不可用";
  if (section.content_source === "files") return "体检单、疫苗本、患处照片都在这一块上传";
  if (section.record_count === null || section.record_count === 0) return `${section.description}（还没有记录）`;
  return `${section.record_count} 条${section.latest_date ? ` · 最近 ${formatDate(section.latest_date)}` : ""}`;
}
</script>

<template>
  <article class="ph-card">
    <h3 class="ph-card__title">档案分项</h3>
    <p class="ph-text-sub ph-card__note">
      每一项都保留原始记录：谁录的（用户 / AI 建议 / 服务者报工）都会标出来，来源冲突时两条都在。
    </p>

    <ul class="ph-sections">
      <li v-for="section in sections" :key="section.code" class="ph-sections__item">
        <button
          type="button"
          class="ph-sections__head"
          :class="{ 'ph-sections__head--disabled': !section.enabled }"
          @click="toggle(section)"
        >
          <span class="ph-sections__name">{{ section.name }}</span>
          <span class="ph-text-weak ph-sections__summary">{{ summary(section) }}</span>
          <span class="ph-sections__arrow" aria-hidden="true">
            {{ isOpen(section) ? "▾" : "›" }}
          </span>
        </button>

        <div v-if="isOpen(section)" class="ph-sections__body">
          <p v-if="!section.recordable" class="ph-text-sub">
            {{ section.content_source === "files"
              ? "照片视频在下方「档案照片」区上传与管理（打卡与防疫的照片在各自记录里，服务留痕照片不在这里）。"
              : "这一项的录入在对应的入口（打卡 / 防疫记录）里，这里是它的记录。" }}
          </p>

          <StateError v-else-if="errorMessage" :message="errorMessage" :request-id="requestId" @retry="toggle(section)" />
          <StateLoading v-else-if="loading" :rows="2" />
          <StateEmpty
            v-else-if="records.length === 0 && !form"
            icon="🗂"
            title="还没有记录"
            description="这一项可以单独录入，写一次就会出现在时间轴与报告里。"
          />

          <ul v-if="records.length" class="ph-records">
            <li v-for="record in records" :key="record.id" class="ph-records__row">
              <div class="ph-records__info">
                <span class="ph-records__title">
                  {{ record.title }}
                  <span v-if="record.authoritative" class="ph-records__auth">当前值</span>
                </span>
                <span class="ph-text-weak">
                  {{ formatDate(record.record_date) }} · {{ record.source_label }}
                  <template v-if="record.value"> · {{ record.value }}{{ record.unit ?? "" }}</template>
                  <template v-if="record.due_on"> · 到期 {{ formatDate(record.due_on) }}</template>
                  <template v-if="record.abnormal"> · 已标注异常</template>
                  <template v-if="record.backfilled"> · 补录</template>
                </span>
                <span v-if="record.note" class="ph-text-sub">{{ record.note }}</span>
              </div>
              <button type="button" class="ph-button ph-button--text" @click="remove(record)">删除</button>
            </li>
          </ul>

          <form v-if="form" class="ph-form" @submit.prevent="submit">
            <div class="ph-form__grid">
              <label class="ph-field">
                <span class="ph-field__label">标题 *</span>
                <input v-model="form.title" class="ph-field__input" maxlength="64" placeholder="如：免疫证 / 体温 / 复查" />
              </label>
              <label class="ph-field">
                <span class="ph-field__label">日期 *</span>
                <input v-model="form.date" type="date" class="ph-field__input" :max="todayIso()" />
              </label>
              <label class="ph-field">
                <span class="ph-field__label">取值</span>
                <input v-model="form.value" class="ph-field__input" maxlength="128" placeholder="如：38.5 / 证件号" />
              </label>
              <label class="ph-field">
                <span class="ph-field__label">单位</span>
                <input v-model="form.unit" class="ph-field__input" maxlength="16" placeholder="如：℃" />
              </label>
              <label class="ph-field">
                <span class="ph-field__label">到期 / 下次日期</span>
                <input v-model="form.due_on" type="date" class="ph-field__input" :min="form.date" />
              </label>
              <label class="ph-field">
                <span class="ph-field__label">备注</span>
                <input v-model="form.note" class="ph-field__input" maxlength="500" placeholder="选填" />
              </label>
            </div>
            <p v-if="formError" class="ph-form__error">{{ formError }}</p>
            <div class="ph-form__actions">
              <button type="submit" class="ph-button ph-button--primary" :disabled="saving || !form.title.trim()">
                {{ saving ? "保存中…" : "保存" }}
              </button>
              <button type="button" class="ph-button ph-button--secondary" @click="form = null">取消</button>
            </div>
          </form>

          <button
            v-else-if="section.recordable && section.enabled"
            type="button"
            class="ph-button ph-button--secondary"
            @click="startForm(section)"
          >
            ＋ 录入一条
          </button>
        </div>
      </li>
    </ul>
  </article>
</template>

<style scoped>
.ph-sections {
  list-style: none;
  margin: 0;
  padding: 0;
}

.ph-sections__item + .ph-sections__item {
  border-top: 1px solid var(--ph-color-divider);
}

.ph-sections__head {
  width: 100%;
  display: flex;
  align-items: center;
  gap: var(--ph-space-3);
  padding: var(--ph-space-3) 0;
  background: none;
  border: 0;
  text-align: left;
  cursor: pointer;
  font: inherit;
  color: inherit;
}

.ph-sections__head--disabled {
  cursor: default;
}

.ph-sections__name {
  font-weight: 600;
  flex: none;
}

.ph-sections__summary {
  flex: 1;
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ph-sections__arrow {
  flex: none;
  color: var(--ph-color-text-weak);
}

.ph-sections__body {
  padding: 0 0 var(--ph-space-4) var(--ph-space-4);
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-3);
}

.ph-records {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-3);
}

.ph-records__row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--ph-space-3);
}

.ph-records__info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  font-size: 13px;
}

.ph-records__title {
  font-weight: 600;
}

.ph-records__auth {
  margin-left: var(--ph-space-2);
  padding: 1px var(--ph-space-2);
  background: var(--ph-color-primary-light);
  color: var(--ph-color-primary);
  border-radius: var(--ph-radius-input);
  font-size: 12px;
  font-weight: 500;
}
</style>
