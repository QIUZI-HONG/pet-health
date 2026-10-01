<script setup lang="ts">
/**
 * 三道照片墙与报工（交付文档 3.2 P020/P029–P032，切片 #107；决策见 ADR-0040 第四节）。
 *
 * 一条链路：门店拍照 → 直传文件域拿 `file_id`（ADR-0020，字节不经业务接口）→ 把 `file_id`
 * 挂到槽位（**整体替换**）→ 三道各至少一张才允许报工。
 *
 * 四条必须由**服务端**说了算、这里只照搬的东西：
 *
 *   1. **还缺哪一道**看 `photo_wall.missing_slots`，槽位名看 `slots[].slot_name`——界面不数数组长度、
 *      也不自己命名（ADR-0040 第四节：硬约束在服务端，前端禁用按钮不算数）；
 *   2. **能不能报工**看 `photo_wall.reportable`，按钮的可用性只是它的镜像；
 *   3. 报工被拒（40900）时**原样显示后端那句话**——它会点名缺哪一道，比这里能编的任何文案都准；
 *   4. 槽位写失败时同理：40400（照片不存在 / 不是这只宠物 / 已挂在别的订单）、40001（超过 9 张）、
 *      40901（报工后固化）都是后端的话，这里只给 40901 补上「出路只有运营干预」。
 *
 * 照片的删除是「从这一道墙上移除」：照片本体留在文件域（本端没有删除接口），移除后别的槽位
 * 仍可以用它——但同一张图不许挂在两道槽位上（服务端 40001）。
 */
import { computed, onMounted, ref } from "vue";
import { ApiError, toApiFailure, toUserMessage } from "@pet-health/shared";
import {
  providerApp,
  uploadCarePhotos,
  type FileView,
  type OrderPhotoSlotView,
  type OrderView,
} from "../api/providerApi";
import { finalizedHint, photoSlotName } from "../utils/labels";

/** 槽位码（契约枚举 1 接宠检查 / 2 服务防护 / 3 取宠对比）。 */
type SlotNo = 1 | 2 | 3;

/** 一个可操作的槽位：服务端把码放在 `slot` 里（三个槽位恒在，缺码的行不参与渲染）。 */
interface SlotEntry {
  no: SlotNo;
  view: OrderPhotoSlotView;
}

const props = defineProps<{ orderId: number; orderNo?: string }>();
const emit = defineEmits<{ done: [OrderView]; close: [] }>();

/** 单槽位上限：docs/conventions.md 的图片规格「最多 9 张」，一次直传也是这个上限（契约 items.maxItems） */
const MAX_PHOTOS_PER_SLOT = 9;

const order = ref<OrderView | null>(null);
const loading = ref(false);
const loadError = ref("");
const loadRequestId = ref("");
/** 写操作的失败提示与请求 ID（保存槽位 / 报工共用一处显示） */
const errorMessage = ref("");
const requestId = ref("");
const doneMessage = ref("");
/** 正在写哪一道（>0 = 忙碌）：上传与保存都算，避免同一道被连点两次 */
const savingSlot = ref(0);
const reporting = ref(false);
const reportRemark = ref("");

/** 备注输入框的当前值，键是槽位号：初值取自服务端，改完按「保存本道」写回去 */
const remarks = ref<Record<number, string>>({});
const fileInput = ref<HTMLInputElement | null>(null);
const pickingSlot = ref<SlotNo>(1);

const wall = computed(() => order.value?.photo_wall ?? null);
const slotEntries = computed<SlotEntry[]>(() =>
  (wall.value?.slots ?? [])
    .filter((slot): slot is OrderPhotoSlotView & { slot: SlotNo } =>
      slot.slot === 1 || slot.slot === 2 || slot.slot === 3)
    .map((slot) => ({ no: slot.slot, view: slot })),
);
const reportable = computed(() => wall.value?.reportable === true);
/** 已完成 / 已取消：照片墙固化（ADR-0049 §一），界面上不再给写入口 */
const finalized = computed(() => (order.value?.status ?? 0) >= 3);

/** 还缺哪几道的**名字**：先从 `missing_slots` 拿码，再按槽位找服务端给的名字。 */
const missingNames = computed(() =>
  (wall.value?.missing_slots ?? []).map((code) => {
    const entry = slotEntries.value.find((item) => item.no === code);
    return photoSlotName(code, entry?.view.slot_name);
  }),
);

function reportableHint(): string {
  if (finalized.value) return "订单已结束：照片与备注在报工后固化";
  return reportable.value
    ? "三道照片墙已齐，可以报工"
    : `还缺：${missingNames.value.join("、")}——每道至少一张照片才能报工`;
}

function photosOf(entry: SlotEntry): FileView[] {
  return entry.view.photos ?? [];
}

function slotNameOf(no: SlotNo): string {
  const entry = slotEntries.value.find((item) => item.no === no);
  return photoSlotName(no, entry?.view.slot_name);
}

function remarkOf(no: SlotNo): string {
  return remarks.value[no] ?? "";
}

function syncRemarks(slots: OrderPhotoSlotView[]): void {
  const next: Record<number, string> = {};
  for (const slot of slots) {
    if (slot.slot != null) next[slot.slot] = slot.remark ?? "";
  }
  remarks.value = next;
}

async function load(): Promise<void> {
  loading.value = true;
  loadError.value = "";
  try {
    order.value = await providerApp.getOrder(props.orderId);
    syncRemarks(order.value.photo_wall?.slots ?? []);
  } catch (error) {
    const failure = toApiFailure(error, "照片墙加载失败，请稍后重试");
    loadError.value = failure.message;
    loadRequestId.value = failure.requestId;
  } finally {
    loading.value = false;
  }
}

/** 写失败的统一收尾：契约保证 message 是给用户看的一句话，原样展示；40901 补一句出路。 */
function showWriteFailure(error: unknown, fallback: string): void {
  const failure = toApiFailure(error, fallback);
  errorMessage.value = error instanceof ApiError && error.code === 40901
    ? finalizedHint(failure.message)
    : failure.message;
  requestId.value = failure.requestId;
}

/** 槽位保存：成功就用服务端返回的**整面墙**刷新（前端不自己拼状态）。 */
async function saveSlot(no: SlotNo, fileIds: number[], remark: string): Promise<boolean> {
  savingSlot.value = no;
  errorMessage.value = "";
  requestId.value = "";
  doneMessage.value = "";
  try {
    const next = await providerApp.saveOrderPhotoSlot(props.orderId, no, {
      file_ids: fileIds,
      remark: remark.trim() === "" ? undefined : remark.trim(),
    });
    if (order.value) order.value = { ...order.value, photo_wall: next };
    syncRemarks(next.slots ?? []);
    doneMessage.value = `已保存「${slotNameOf(no)}」`;
    return true;
  } catch (error) {
    showWriteFailure(error, "保存失败，请稍后重试");
    return false;
  } finally {
    savingSlot.value = 0;
  }
}

/** 打开某个槽位的文件选择框（一个 input 服务三道墙，选完就知道是哪一道）。 */
function pickFor(no: SlotNo): void {
  pickingSlot.value = no;
  fileInput.value?.click();
}

async function onPicked(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement;
  const picked = Array.from(input.files ?? []);
  // 清空 input：同一张图移除后再选一次也要能触发 change
  input.value = "";
  const entry = slotEntries.value.find((item) => item.no === pickingSlot.value);
  if (picked.length === 0 || !entry) return;
  if (picked.length > MAX_PHOTOS_PER_SLOT) {
    errorMessage.value = `一次最多选 ${MAX_PHOTOS_PER_SLOT} 张照片`;
    return;
  }
  const current = photosOf(entry).map((photo) => photo.id);
  if (current.length + picked.length > MAX_PHOTOS_PER_SLOT) {
    errorMessage.value = `「${slotNameOf(entry.no)}」这一道最多 ${MAX_PHOTOS_PER_SLOT} 张（已有 ${current.length} 张）`;
    return;
  }
  if (!order.value?.pet_id) {
    // pet_id 是「照片属于这只宠物」的锚点：缺了它，服务端会在挂载时按 40400 拒（ADR-0048）
    errorMessage.value = "这一单没有宠物信息，无法登记服务照片，请联系运营核实订单";
    return;
  }

  savingSlot.value = entry.no;
  errorMessage.value = "";
  try {
    const fileIds = await uploadCarePhotos(order.value.pet_id, picked);
    await saveSlot(entry.no, [...current, ...fileIds], remarkOf(entry.no));
  } catch (error) {
    errorMessage.value = toUserMessage(error, "上传失败，请重试");
  } finally {
    savingSlot.value = 0;
  }
}

/** 从这一道墙上移除一张照片（整体替换：把剩下的重新提交一遍）。 */
async function removePhoto(entry: SlotEntry, photo: FileView): Promise<void> {
  const rest = photosOf(entry).filter((item) => item.id !== photo.id).map((item) => item.id);
  await saveSlot(entry.no, rest, remarkOf(entry.no));
}

async function report(): Promise<void> {
  // 缺一道就不发请求：硬约束在服务端（它会拒绝），这里只是别让用户白点一次
  if (!reportable.value || reporting.value || finalized.value) return;
  reporting.value = true;
  errorMessage.value = "";
  requestId.value = "";
  doneMessage.value = "";
  try {
    const reported = await providerApp.reportOrder(props.orderId, {
      remark: reportRemark.value.trim() === "" ? undefined : reportRemark.value.trim(),
    });
    order.value = reported;
    syncRemarks(reported.photo_wall?.slots ?? []);
    doneMessage.value = `已报工：订单 ${reported.order_no ?? ""} 转「已完成」，这次服务已写入宠物的健康档案`;
    emit("done", reported);
  } catch (error) {
    // 缺哪一道由服务端点名（40900），原样显示
    showWriteFailure(error, "报工失败，请稍后重试");
  } finally {
    reporting.value = false;
  }
}

onMounted(load);
</script>

<template>
  <section class="ph-card ph-wall">
    <header class="ph-wall__head">
      <h3 class="ph-card__title">三道照片墙与报工{{ orderNo ? `：${orderNo}` : "" }}</h3>
      <button type="button" class="ph-button ph-button--secondary" @click="emit('close')">收起</button>
    </header>

    <p class="ph-text-sub">
      接宠检查 / 服务防护 / 取宠对比，三道各至少一张照片才能报工——这条由服务端校验，
      界面上的按钮只是它的镜像（ADR-0040）。照片先直传文件域再把 file_id 挂到订单上，字节不经业务接口。
    </p>

    <p v-if="loading" class="ph-text-sub">正在加载照片墙…</p>
    <p v-else-if="loadError" class="ph-alert ph-alert--error">
      {{ loadError }}
      <span v-if="loadRequestId" class="ph-text-weak">（请求 ID：{{ loadRequestId }}）</span>
      <button type="button" class="ph-button ph-button--secondary ph-wall__retry" @click="load">重试</button>
    </p>

    <template v-else-if="order">
      <p v-if="errorMessage" class="ph-alert ph-alert--error">
        {{ errorMessage }}
        <span v-if="requestId" class="ph-text-weak">（请求 ID：{{ requestId }}）</span>
      </p>
      <p v-else-if="doneMessage" class="ph-alert ph-alert--info">{{ doneMessage }}</p>

      <ul class="ph-wall__slots">
        <li v-for="entry in slotEntries" :key="entry.no" class="ph-wall__slot">
          <div class="ph-wall__slot-head">
            <span class="ph-wall__name">{{ photoSlotName(entry.no, entry.view.slot_name) }}</span>
            <span class="ph-wall__state" :class="{ 'ph-wall__state--done': entry.view.satisfied }">
              {{ entry.view.satisfied ? `已上传 ${photosOf(entry).length} 张` : "待上传" }}
            </span>
            <span class="ph-toolbar__spacer" />
            <span class="ph-text-weak ph-wall__count">
              {{ photosOf(entry).length }}/{{ MAX_PHOTOS_PER_SLOT }} 张
            </span>
            <button
              type="button"
              class="ph-button ph-button--secondary"
              :disabled="finalized || savingSlot === entry.no"
              :title="finalized ? '报工后照片墙固化（40901）' : ''"
              @click="pickFor(entry.no)"
            >
              {{ savingSlot === entry.no ? "处理中…" : "选择照片" }}
            </button>
          </div>

          <div v-if="photosOf(entry).length > 0" class="ph-wall__photos">
            <figure v-for="photo in photosOf(entry)" :key="photo.id" class="ph-wall__photo">
              <img
                class="ph-thumb"
                :src="photo.thumb_url ?? photo.url"
                :alt="`${photoSlotName(entry.no, entry.view.slot_name)}照片`"
              />
              <figcaption>
                <button
                  type="button"
                  class="ph-button ph-button--text"
                  :disabled="finalized || savingSlot === entry.no"
                  @click="removePhoto(entry, photo)"
                >
                  移除
                </button>
              </figcaption>
            </figure>
          </div>
          <p v-else class="ph-text-sub ph-wall__empty">还没有照片（每道至少一张才能报工）</p>

          <label class="ph-field ph-wall__remark">
            <span class="ph-field__label">备注（如「皮肤有红点，已拍照留痕」）</span>
            <input
              v-model="remarks[entry.no]"
              class="ph-input"
              maxlength="255"
              :disabled="finalized"
            />
          </label>
          <button
            type="button"
            class="ph-button ph-button--secondary"
            :disabled="finalized || savingSlot === entry.no"
            @click="saveSlot(entry.no, photosOf(entry).map((item) => item.id), remarkOf(entry.no))"
          >
            保存本道
          </button>
        </li>
      </ul>

      <div class="ph-wall__report">
        <label class="ph-field ph-wall__report-remark">
          <span class="ph-field__label">报工说明（可留空，会展现在订单与宠物的健康档案里）</span>
          <input v-model="reportRemark" class="ph-input" maxlength="255" :disabled="finalized" />
        </label>
        <p class="ph-wall__report-hint" :class="{ 'ph-wall__report-hint--done': reportable }">
          {{ reportableHint() }}
        </p>
        <button
          type="button"
          class="ph-button ph-button--primary"
          :disabled="!reportable || reporting || finalized"
          :title="reportable ? '' : `还缺：${missingNames.join('、')}，每一道至少一张照片才能报工`"
          @click="report"
        >
          {{ reporting ? "提交中…" : "报工完成" }}
        </button>
        <span class="ph-text-weak">
          报工成功后订单转「已完成」，这次服务会以「服务者报工」为来源写入宠物的健康档案。
        </span>
      </div>

      <p class="ph-text-weak ph-wall__note">
        移除只是把照片从这一道墙上取下：照片本体留在文件域（本端没有删除接口），
        同一张图也不能同时挂在两道槽位上（服务端 40001）。
      </p>
    </template>

    <input
      ref="fileInput"
      class="ph-wall__input"
      type="file"
      accept="image/jpeg,image/png"
      multiple
      @change="onPicked"
    />
  </section>
</template>

<style scoped>
.ph-wall__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ph-space-3);
}

.ph-wall__retry {
  margin-left: var(--ph-space-3);
}

.ph-wall__slots {
  list-style: none;
  margin: var(--ph-space-4) 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-4);
}

.ph-wall__slot {
  padding: var(--ph-space-3);
  background: var(--ph-color-bg);
  border-radius: var(--ph-radius-input);
}

.ph-wall__slot-head {
  display: flex;
  align-items: center;
  gap: var(--ph-space-3);
}

.ph-wall__name {
  font-weight: 600;
}

.ph-wall__state {
  font-size: 12px;
  color: var(--ph-color-text-weak);
}

.ph-wall__state--done {
  color: var(--ph-color-success);
}

.ph-wall__count {
  font-size: 12px;
}

.ph-wall__photos {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ph-space-2);
  margin-top: var(--ph-space-2);
}

.ph-wall__photo {
  margin: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
}

/* 尺寸与裁切走共享的 .ph-thumb（packages/ui/src/console.css）：模板上是 `class="ph-thumb"` */

.ph-wall__empty {
  margin: var(--ph-space-2) 0 0;
  font-size: 12px;
}

.ph-wall__remark {
  max-width: 520px;
  margin-top: var(--ph-space-3);
}

.ph-wall__report {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ph-space-3);
  margin-top: var(--ph-space-4);
}

.ph-wall__report-remark {
  flex-basis: 100%;
  max-width: 520px;
}

.ph-wall__report-hint {
  margin: 0;
  font-size: 13px;
  color: var(--ph-color-orange);
}

.ph-wall__report-hint--done {
  color: var(--ph-color-primary);
}

.ph-wall__note {
  margin: var(--ph-space-3) 0 0;
  font-size: 12px;
}

.ph-wall__input {
  display: none;
}
</style>
