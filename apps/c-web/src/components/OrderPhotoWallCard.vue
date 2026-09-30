<script setup lang="ts">
/**
 * 三道照片墙的进度（订单详情，只读）。
 *
 * 「接宠检查 / 服务防护 / 取宠对比，三者缺一不可」是**服务端的硬约束**（ADR-0040 第四节，
 * 交付文档 2.5 的验收标准）：照片由服务者在服务者后台上传，用户在这里看进度。
 * 所以这一块**不给任何上传 / 报工入口**——C 端没有这个能力，放一个灰按钮只会让人以为
 * 「是我没操作对」。缺哪一道由服务端算（`missing_slots`），界面不自己数数组长度。
 */
import type { FileView } from "@pet-health/shared";
import type { OrderPhotoSlotView, OrderPhotoWallView } from "../api/commerce";

const props = withDefaults(defineProps<{ photoWall?: OrderPhotoWallView | null }>(), { photoWall: null });

/** 槽位名以服务端给的为准；它没给时按契约的码兜底（1 接宠检查 / 2 服务防护 / 3 取宠对比）。 */
const SLOT_NAMES: Record<number, string> = { 1: "接宠检查", 2: "服务防护", 3: "取宠对比" };

function slotName(slot: OrderPhotoSlotView): string {
  return slot.slot_name ?? SLOT_NAMES[slot.slot ?? 0] ?? "槽位";
}

function photoCount(slot: OrderPhotoSlotView): number {
  return slot.photos?.length ?? 0;
}

/** 缩略图地址是**签名地址**、有有效期，所以不缓存、也不预拼（契约：文件私有）。 */
function photoSrc(photo: FileView): string {
  return photo.thumb_url ?? photo.url ?? "";
}

function photoSlots(): OrderPhotoSlotView[] {
  return props.photoWall?.slots ?? [];
}

function missingSlots(): string[] {
  return (props.photoWall?.missing_slots ?? []).map((code) => {
    const found = photoSlots().find((slot) => slot.slot === code);
    return found ? slotName(found) : `槽位 ${code}`;
  });
}
</script>

<template>
  <section class="ph-wall">
    <p v-if="!photoWall" class="ph-text-sub">服务开始后，门店会上传接宠检查、服务防护与取宠对比三组照片。</p>
    <template v-else>
      <p class="ph-text-sub ph-wall__intro">
        三道照片墙由门店在服务过程中上传，三者缺一不可（服务端校验）：缺任何一道都不允许报工完成。
      </p>

      <ul class="ph-wall__slots">
        <li v-for="slot in photoSlots()" :key="slot.slot" class="ph-wall__slot">
          <div class="ph-wall__head">
            <span class="ph-wall__name">{{ slotName(slot) }}</span>
            <span class="ph-wall__state" :class="{ 'ph-wall__state--done': slot.satisfied }">
              {{ slot.satisfied ? `已上传 ${photoCount(slot)} 张` : "待上传" }}
            </span>
          </div>

          <div v-if="slot.satisfied" class="ph-wall__photos">
            <img
              v-for="(photo, index) in slot.photos ?? []"
              :key="photo.id"
              class="ph-wall__photo"
              :src="photoSrc(photo)"
              :alt="`${slotName(slot)}照片 ${index + 1}`"
            />
          </div>
          <p v-if="slot.remark" class="ph-wall__remark">{{ slot.remark }}</p>
        </li>
      </ul>

      <p class="ph-wall__report" :class="{ 'ph-wall__report--done': photoWall.reportable }">
        {{
          photoWall.reportable
            ? "三道照片墙已齐，门店可以报工完成。"
            : `还缺：${missingSlots().join("、") || "—"}（每道至少一张照片才能报工）`
        }}
      </p>
    </template>
  </section>
</template>

<style scoped>
.ph-wall {
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-3);
}

.ph-wall__intro {
  margin: 0;
  font-size: 13px;
}

.ph-wall__slots {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-3);
}

.ph-wall__slot {
  padding: var(--ph-space-3);
  background: var(--ph-color-bg);
  border-radius: var(--ph-radius-input);
}

.ph-wall__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
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

.ph-wall__photos {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ph-space-2);
  margin-top: var(--ph-space-2);
}

.ph-wall__photo {
  width: 96px;
  height: 96px;
  object-fit: cover;
  border-radius: var(--ph-radius-input);
  border: 1px solid var(--ph-color-border);
  background: var(--ph-color-surface);
}

.ph-wall__remark {
  margin: var(--ph-space-2) 0 0;
  font-size: 12px;
  color: var(--ph-color-text-sub);
}

.ph-wall__report {
  margin: 0;
  font-size: 13px;
  color: var(--ph-color-orange);
}

.ph-wall__report--done {
  color: var(--ph-color-primary);
}
</style>
