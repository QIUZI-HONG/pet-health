<script setup lang="ts">
/**
 * 照片上传（切片 #95，决策见 ADR-0020）：一次选多张，逐张直传到签名地址。
 *
 * 它是「相机拍到电脑再上传」这条流程的落点——服务者在店里拍完照，回电脑一次性选进来。
 * 所以两件事必须是常态而不是例外：**多选**，以及**一次说清哪张不合规**（九个文件一个个报错没人受得了）。
 *
 * 缩略图不是前端缩的，是后端在上传落定时生成的（ADR-0020）：前端缩图只能省流量，
 * 但会把「图被压成什么样」交给一个可以绕过的客户端。
 */
import { onMounted, ref, watch } from "vue";
import {
  cApp,
  uploadFiles,
  toUserMessage,
  type FilePresignRequest,
  type FileView,
} from "@pet-health/shared";

const props = defineProps<{
  petId?: number | null;
  /** 用途取值来自契约枚举（切片 #95）：写错了编译期就红 */
  bizType: NonNullable<FilePresignRequest["biz_type"]>;
  title?: string;
  hint?: string;
}>();

const files = ref<FileView[]>([]);
const uploading = ref(false);
const progress = ref("");
const errorMessage = ref("");
const fileInput = ref<HTMLInputElement | null>(null);

async function refresh(): Promise<void> {
  if (!props.petId) {
    files.value = [];
    return;
  }
  try {
    files.value = await cApp.listFiles({ petId: props.petId, bizType: props.bizType });
    errorMessage.value = "";
  } catch (error) {
    files.value = [];
    errorMessage.value = toUserMessage(error, "照片加载失败");
  }
}

onMounted(refresh);
watch(() => props.petId, refresh);

async function pick(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement;
  const picked = Array.from(input.files ?? []);
  // 清空 input：同一张图删掉后再选一次也要能触发 change
  input.value = "";
  if (picked.length === 0) return;

  uploading.value = true;
  progress.value = `正在上传 0/${picked.length}`;
  errorMessage.value = "";
  try {
    files.value = await uploadFiles({
      petId: props.petId ?? undefined,
      bizType: props.bizType,
      files: picked,
      onProgress: (done, total) => {
        progress.value = `正在上传 ${done}/${total}`;
      },
    });
  } catch (error) {
    errorMessage.value = toUserMessage(error, "上传失败，请重试");
  } finally {
    uploading.value = false;
    progress.value = "";
  }
}

async function remove(file: FileView): Promise<void> {
  try {
    await cApp.deleteFile(file.id);
    files.value = files.value.filter((item) => item.id !== file.id);
  } catch (error) {
    errorMessage.value = toUserMessage(error, "删除失败");
  }
}
</script>

<template>
  <article class="ph-card ph-photo">
    <header class="ph-photo__head">
      <h3 class="ph-card__title">{{ title ?? "照片" }}</h3>
      <button
        type="button"
        class="ph-button ph-button--secondary"
        :disabled="uploading || !petId"
        @click="fileInput?.click()"
      >
        {{ uploading ? progress : "选择照片" }}
      </button>
    </header>

    <p class="ph-text-sub">
      {{ hint ?? "一次可选多张。JPEG / PNG，单张不超过 10MB，最多 9 张。" }}
    </p>
    <input
      ref="fileInput"
      class="ph-photo__input"
      type="file"
      accept="image/jpeg,image/png"
      multiple
      @change="pick"
    />

    <p v-if="errorMessage" class="ph-photo__error">{{ errorMessage }}</p>

    <ul v-if="files.length > 0" class="ph-photo__grid">
      <li v-for="file in files" :key="file.id" class="ph-photo__item">
        <img :src="file.thumb_url" :alt="`照片 ${file.id}`" class="ph-photo__thumb" />
        <button type="button" class="ph-button ph-button--text" @click="remove(file)">删除</button>
      </li>
    </ul>
    <p v-else-if="!uploading" class="ph-text-weak">还没有照片。拍完拷到电脑，一次选进来。</p>
  </article>
</template>

<style scoped>
.ph-photo {
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-3);
}

.ph-photo__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ph-space-3);
}

.ph-photo__input {
  display: none;
}

.ph-photo__error {
  margin: 0;
  font-size: 14px;
  color: var(--ph-color-danger);
}

.ph-photo__grid {
  list-style: none;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
  gap: var(--ph-space-3);
  margin: 0;
  padding: 0;
}

.ph-photo__item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--ph-space-1);
}

.ph-photo__thumb {
  width: 100%;
  aspect-ratio: 4 / 3;
  object-fit: cover;
  border-radius: var(--ph-radius-input);
  border: 1px solid var(--ph-color-border);
  background: var(--ph-color-bg);
}
</style>
