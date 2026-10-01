/**
 * 「选一张图 → 直传 → 拿回 file_id」的选图机制（资质材料的两处表单共用）。
 *
 * 为什么是 composable 而不是一个 `*Uploader.vue` 组件：两处的**版式不同**——申请表单是
 * 一组材料行、材料编辑是另一种行布局——抽成组件就要把行结构也塞进去，两边都会变形成怪东西。
 * 真正重复的是**机制**，不是版式，所以这里只收机制：
 *
 * - **一个隐藏 input 服务所有行**：每一行不各挂一个 input（几十行就是几十个 DOM 节点与 ref），
 *   点哪一行先记住下标，再把同一个 input 叫起来；
 * - **读完清空 input.value**：不清的话连续两次选**同一个文件**不会再触发 change
 *   （三道照片墙踩过同一个坑，注释在那边也留着）；
 * - **上传失败的文案统一带请求 ID**（ADR-0029 的可排查性口径），并且**只影响这一行**：错误落在
 *   `uploadError` 上由页面展示，别的行照常可编辑；
 * - `uploadingIndex` 让那一行的按钮转圈、其余行不受影响。
 *
 * 调用方只负责两件事：给一个 `fileInput` 的 ref 挂到隐藏 input 上、在 `pick()` 里说清
 * 「拿到文件之后往哪一行写什么」。
 */
import { ref, type Ref } from "vue";
import { toApiFailure } from "@pet-health/shared";

export interface ImagePicker {
  /** 正在上传的行下标；-1 表示没有 */
  uploadingIndex: Ref<number>;
  /** 最近一次失败的文案（带请求 ID）；下一次选图时清空 */
  uploadError: Ref<string>;
  /** 点某一行的「选择图片」：记住下标并把 input 叫起来 */
  pick: (index: number, upload: (file: File) => Promise<void>) => void;
  /** 挂到 input 的 `@change` 上 */
  handleChange: (event: Event) => Promise<void>;
}

/**
 * @param fileInput 隐藏 input 的 ref。**由调用方持有并传进来**：模板里只能用字符串 ref
 *                  （`ref="fileInput"`）去绑它，而字符串 ref 不会被 TypeScript 当成一次「使用」——
 *                  放在这里创建的话，调用方那个变量就永远是「未使用」，vue-tsc 会报错。
 */
export function useImagePicker(fileInput: Ref<HTMLInputElement | null>,
                               failureText = "图片上传失败，请重试"): ImagePicker {
  const uploadingIndex = ref(-1);
  const uploadError = ref("");

  let pickingIndex = -1;
  let pending: ((file: File) => Promise<void>) | null = null;

  function pick(index: number, upload: (file: File) => Promise<void>): void {
    uploadError.value = "";
    pickingIndex = index;
    pending = upload;
    fileInput.value?.click();
  }

  async function handleChange(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const picked = input.files?.[0] ?? null;
    // 读完必须清空：不清的话，连续两次选**同一个文件**不会触发 change（三道照片墙踩过同一个坑）
    input.value = "";
    const index = pickingIndex;
    const upload = pending;
    pickingIndex = -1;
    pending = null;
    if (!picked || !upload || index < 0) return;

    uploadError.value = "";
    uploadingIndex.value = index;
    try {
      await upload(picked);
    } catch (error) {
      const failure = toApiFailure(error, failureText);
      uploadError.value = failure.requestId
        ? `${failure.message}（请求 ID：${failure.requestId}）`
        : failure.message;
    } finally {
      uploadingIndex.value = -1;
    }
  }

  return { uploadingIndex, uploadError, pick, handleChange };
}
