<script setup lang="ts">
/**
 * 门店信息与营业时间：**读不设门禁、写要门禁**（contract/provider.yaml 的 `/profile`）。
 *
 * 两件事分开放，因为两条规则不一样：
 * - 门店信息：整体覆盖（`PUT /profile`）。**只提交表单里的字段**——接口是整体覆盖，
 *   没带上的字段会被清空，所以 `lng` / `lat` 也放进表单（留空 = 确实要清掉坐标，页面写明这一点），
 *   不替用户做「保留原值」这种接口没承诺的事。
 * - 营业时间：整体替换（`PUT /profile/business-hours`），**只列营业的那几天**，数组里没有的星期几
 *   就是休息（契约明确：不出现 = 不营业，而不是 `closed: true` 标记）。
 *
 * 电话那一条要留意：`GET` 回来的是**脱敏值**（ADR-0013），不能当明文回填，所以保存时要求重新
 * 填写完整号码——否则会把 `138****8888` 当成新号码存回去（接口是整体覆盖，少填就是覆盖掉）。
 */
import { computed, ref, watch } from "vue";
import { ConsoleState } from "@pet-health/ui";
import {
  providerApp,
  type BusinessHour,
  type ProviderProfileRequest,
  type ProviderProfileView,
} from "../api/providerApi";
import { useSubmitAction } from "@pet-health/ui";
import { businessHoursText, dayOfWeekLabel, providerStatusLabel, providerStatusTone, providerTypeLabel } from "@pet-health/shared";
import { providerCategoryLabel } from "../utils/labels";

const props = defineProps<{
  profile: ProviderProfileView | null;
  loading: boolean;
  errorMessage: string;
  requestId: string;
  /** 账号还没绑定服务者（`GET /profile` 回 40400）：不是错误，是「先提交入驻申请」 */
  notLinked: boolean;
}>();

const emit = defineEmits<{ retry: []; updated: [profile: ProviderProfileView] }>();

const profileSubmit = useSubmitAction("门店信息保存失败，请稍后重试");
const hoursSubmit = useSubmitAction("营业时间保存失败，请稍后重试");

const form = ref({
  name: "",
  intro: "",
  address: "",
  phone: "",
  lng: "",
  lat: "",
});

/** 周一到周日七行；`enabled` 为假的那天不会进请求体（= 休息）。 */
interface HourRow {
  enabled: boolean;
  open: string;
  close: string;
}

const hours = ref<HourRow[]>(emptyWeek());
/** 保存成功后回显的服务端版本（营业时间单独保存时会刷新它） */
const savedHours = ref<BusinessHour[]>([]);

function emptyWeek(): HourRow[] {
  return Array.from({ length: 7 }, () => ({ enabled: false, open: "09:00", close: "19:00" }));
}

/** 服务端读回来的门店信息 → 表单（`phone` 留空：读回来的是脱敏值，不能回填） */
function fillFromProfile(profile: ProviderProfileView | null): void {
  form.value = {
    name: profile?.name ?? "",
    intro: profile?.intro ?? "",
    address: profile?.address ?? "",
    phone: "",
    lng: profile?.lng ?? "",
    lat: profile?.lat ?? "",
  };
  savedHours.value = profile?.business_hours ?? [];
  const week = emptyWeek();
  for (const hour of savedHours.value) {
    const index = (hour.day_of_week ?? 0) - 1;
    if (index >= 0 && index < 7) {
      week[index] = { enabled: true, open: hour.open_time ?? "09:00", close: hour.close_time ?? "19:00" };
    }
  }
  hours.value = week;
}

watch(() => props.profile, fillFromProfile, { immediate: true });

const currentPhone = computed(() => props.profile?.phone ?? "—");
const hoursText = computed(() => businessHoursText(savedHours.value));

/** 能不能写：只有「正常」状态能改（其余状态后端回 40300，页面先说清楚） */
const writable = computed(() => props.profile?.status === 1);
const writeHint = computed(() => {
  const status = props.profile?.status;
  if (writable.value) return "";
  if (status === 0) return "入驻申请审核通过后才能维护门店信息。";
  if (status === 2) return "入驻申请已被驳回：先按驳回原因改好材料并重新提交。";
  if (status === 3) return "门店已被冻结：冻结期间不能修改门店信息与营业时间。";
  return "还不能维护门店信息。";
});

/** 本地校验：格式不对就别打一次注定 40001 的请求 */
const profileLocalError = computed(() => {
  if (form.value.name.trim() === "") return "请填写门店名称";
  if (form.value.address.trim() === "") return "请填写门店地址";
  if (!/^(1[3-9]\d{9}|0\d{2,3}-?\d{7,8})$/.test(form.value.phone.trim())) {
    return "联系电话填手机号或带区号的固定电话（已脱敏展示，保存需重新填写完整号码）";
  }
  for (const [label, value, limit] of [["经度", form.value.lng, 180], ["纬度", form.value.lat, 90]] as const) {
    if (value.trim() === "") continue;
    if (!/^-?\d{1,3}(\.\d{1,6})?$/.test(value.trim())) return `${label}要填数字（最多 6 位小数）`;
    if (Math.abs(Number(value.trim())) > limit) return `${label}超出合法范围（±${limit}）`;
  }
  return "";
});

async function saveProfile(): Promise<void> {
  if (profileLocalError.value !== "") {
    profileSubmit.errorMessage.value = profileLocalError.value;
    return;
  }
  const body: ProviderProfileRequest = {
    name: form.value.name.trim(),
    intro: form.value.intro.trim() === "" ? null : form.value.intro.trim(),
    address: form.value.address.trim(),
    phone: form.value.phone.trim(),
    lng: form.value.lng.trim() === "" ? null : form.value.lng.trim(),
    lat: form.value.lat.trim() === "" ? null : form.value.lat.trim(),
  };
  const outcome = await profileSubmit.run(() => providerApp.updateProfile(body), "门店信息已保存");
  if (outcome.ok) emit("updated", outcome.value);
}

const hoursLocalError = computed(() => {
  const active = hours.value.filter((row) => row.enabled);
  const badIndex = active.findIndex((row) => row.open >= row.close);
  if (badIndex >= 0) {
    const label = dayOfWeekLabel(hours.value.indexOf(active[badIndex]!) + 1);
    return `${label}的结束时间要晚于开始时间（跨天时段本期不支持）`;
  }
  return "";
});

async function saveHours(): Promise<void> {
  if (hoursLocalError.value !== "") {
    hoursSubmit.errorMessage.value = hoursLocalError.value;
    return;
  }
  const body = hours.value
    .map((row, index) => ({ row, day_of_week: index + 1 }))
    .filter((item) => item.row.enabled)
    .map<BusinessHour>((item) => ({
      day_of_week: item.day_of_week,
      open_time: item.row.open,
      close_time: item.row.close,
    }));
  const outcome = await hoursSubmit.run(() => providerApp.updateBusinessHours({ hours: body }), "营业时间已保存");
  if (outcome.ok) emit("updated", outcome.value);
}

/** 页头状态标签：让服务者一眼看到「现在能不能改」 */
const statusLabel = computed(() => providerStatusLabel(props.profile?.status));
</script>

<template>
  <ConsoleState v-if="props.loading" variant="loading" title="正在加载门店信息" />
  <ConsoleState
    v-else-if="props.notLinked"
    variant="empty"
    title="这个账号还没有关联的服务者"
    description="先在上面提交入驻申请；审核通过后这里才会出现门店信息与营业时间。"
  />
  <ConsoleState v-else-if="props.profile === null" variant="empty" title="没有读到门店信息" />
  <div v-else class="ph-stack">
    <p v-if="writeHint" class="ph-alert ph-alert--warn">{{ writeHint }}</p>

    <dl class="ph-kv">
      <dt>门店状态</dt>
      <dd><span class="ph-tag" :class="providerStatusTone(props.profile.status)">{{ statusLabel }}</span></dd>
      <dt>门店类型</dt>
      <dd>{{ providerTypeLabel(props.profile.type) }} · {{ providerCategoryLabel(props.profile.category_name) }}</dd>
      <dt>当前营业时间</dt>
      <dd>{{ hoursText }}</dd>
      <dt>当前联系电话</dt>
      <dd>{{ currentPhone }}<span class="ph-text-weak">（脱敏展示）</span></dd>
    </dl>

    <form class="ph-stack" @submit.prevent="saveProfile">
      <h4 class="ph-card__title">门店信息</h4>
      <div class="ph-form-grid">
        <label class="ph-field">
          <span class="ph-field__label">门店名称</span>
          <input v-model="form.name" class="ph-input" maxlength="256" :disabled="!writable" />
        </label>
        <label class="ph-field">
          <span class="ph-field__label">联系电话</span>
          <input
            v-model="form.phone"
            class="ph-input"
            maxlength="20"
            placeholder="重新填写完整号码"
            :disabled="!writable"
          />
          <span class="ph-field__hint">接口是整体覆盖；不填会覆盖掉原号码，所以必须重填一次</span>
        </label>
        <label class="ph-field ph-form-grid--full">
          <span class="ph-field__label">门店地址</span>
          <input v-model="form.address" class="ph-input" maxlength="512" :disabled="!writable" />
        </label>
        <label class="ph-field ph-form-grid--full">
          <span class="ph-field__label">门店简介</span>
          <textarea v-model="form.intro" class="ph-textarea" maxlength="1024" :disabled="!writable" />
        </label>
        <label class="ph-field">
          <span class="ph-field__label">经度（可选）</span>
          <input v-model="form.lng" class="ph-input" placeholder="如 120.155070" :disabled="!writable" />
        </label>
        <label class="ph-field">
          <span class="ph-field__label">纬度（可选）</span>
          <input v-model="form.lat" class="ph-input" placeholder="如 30.274084" :disabled="!writable" />
          <span class="ph-field__hint">坐标暂无地图选点，留空即清除原有坐标（整体覆盖）</span>
        </label>
      </div>

      <p v-if="profileSubmit.errorMessage.value" class="ph-alert ph-alert--error">
        {{ profileSubmit.errorMessage.value }}
        <span v-if="profileSubmit.requestId.value" class="ph-text-weak">（请求 ID：{{ profileSubmit.requestId.value }}）</span>
      </p>
      <p v-if="profileSubmit.doneMessage.value" class="ph-alert ph-alert--info">{{ profileSubmit.doneMessage.value }}</p>

      <div>
        <button type="submit" class="ph-button ph-button--primary" :disabled="!writable || profileSubmit.submitting.value">
          {{ profileSubmit.submitting.value ? "保存中…" : "保存门店信息" }}
        </button>
      </div>
    </form>

    <form class="ph-stack" @submit.prevent="saveHours">
      <h4 class="ph-card__title">营业时间</h4>
      <p class="ph-field__hint">
        只勾选营业的日子；没勾的就是休息（接口按「不出现 = 不营业」处理）。跨天时段（22:00–02:00）本期不支持。
      </p>
      <div class="ph-hours">
        <div v-for="(row, index) in hours" :key="index" class="ph-hours__row">
          <label class="ph-hours__day">
            <input v-model="row.enabled" type="checkbox" :disabled="!writable" />
            <span>{{ dayOfWeekLabel(index + 1) }}</span>
          </label>
          <input v-model="row.open" type="time" class="ph-input" :disabled="!writable || !row.enabled" />
          <span class="ph-text-weak">至</span>
          <input v-model="row.close" type="time" class="ph-input" :disabled="!writable || !row.enabled" />
        </div>
      </div>

      <p v-if="hoursLocalError" class="ph-alert ph-alert--error">{{ hoursLocalError }}</p>
      <p v-if="hoursSubmit.errorMessage.value" class="ph-alert ph-alert--error">
        {{ hoursSubmit.errorMessage.value }}
        <span v-if="hoursSubmit.requestId.value" class="ph-text-weak">（请求 ID：{{ hoursSubmit.requestId.value }}）</span>
      </p>
      <p v-if="hoursSubmit.doneMessage.value" class="ph-alert ph-alert--info">{{ hoursSubmit.doneMessage.value }}</p>

      <div>
        <button type="submit" class="ph-button ph-button--primary" :disabled="!writable || hoursSubmit.submitting.value">
          {{ hoursSubmit.submitting.value ? "保存中…" : "保存营业时间" }}
        </button>
      </div>
    </form>
  </div>
</template>

<style scoped>
.ph-hours {
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-2);
  max-width: 420px;
}

.ph-hours__row {
  display: grid;
  grid-template-columns: 96px 1fr max-content 1fr;
  align-items: center;
  gap: var(--ph-space-2);
}

.ph-hours__day {
  display: flex;
  align-items: center;
  gap: var(--ph-space-2);
  font-size: 13px;
}
</style>
