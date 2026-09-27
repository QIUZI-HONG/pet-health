<script setup lang="ts">
/**
 * 我的：账号信息 + 宠物管理（建档 / 编辑 / 删除 / 恢复 / 切换当前）。
 *
 * 这是当前唯一有真实写操作的页面——账号与宠物档案的接口（切片 #94）已经在了，
 * 所以这里把「请求层 → 会话 → 服务端」整条链路走通，同时当作用户可见的冒烟测试。
 *
 * 权益、积分、券、社群这些入口先不放：它们还没有数据源，摆一排点不动的入口不如不摆。
 */
import { computed, onMounted, reactive, ref } from "vue";
import { ApiError, cApp, type Pet } from "@pet-health/shared";
import { useSessionStore } from "../stores/session";
import StateEmpty from "../components/states/StateEmpty.vue";
import StateError from "../components/states/StateError.vue";
import StateForbidden from "../components/states/StateForbidden.vue";
import StateLoading from "../components/states/StateLoading.vue";

const session = useSessionStore();

const recycleBin = ref<Pet[]>([]);
const loading = ref(false);
const errorMessage = ref("");
const requestId = ref("");
const formError = ref("");
const saving = ref(false);
const showingForm = ref(false);

const form = reactive({
  name: "",
  species: 1,
  breed: "",
  gender: 0,
  birthday: "",
  weight: "",
  isSterilized: false,
  isChronic: false,
  chronicDesc: "",
});

const canSubmit = computed(() => form.name.trim().length > 0 && !saving.value);

async function load(): Promise<void> {
  loading.value = true;
  errorMessage.value = "";
  try {
    await session.refreshPets();
    recycleBin.value = await cApp.listPets(true);
  } catch (error) {
    if (error instanceof ApiError) {
      errorMessage.value = error.message;
      requestId.value = error.requestId;
    } else {
      errorMessage.value = "加载失败，请稍后重试";
    }
  } finally {
    loading.value = false;
  }
}

onMounted(() => {
  if (session.isLoggedIn) {
    void load();
  }
});

function openForm(): void {
  formError.value = "";
  showingForm.value = true;
}

function closeForm(): void {
  showingForm.value = false;
  form.name = "";
  form.breed = "";
  form.birthday = "";
  form.weight = "";
  form.isSterilized = false;
  form.isChronic = false;
  form.chronicDesc = "";
}

async function submit(): Promise<void> {
  if (!canSubmit.value) return;
  formError.value = "";
  saving.value = true;
  try {
    await cApp.createPet({
      name: form.name.trim(),
      // 表单控件给回来的是 number，契约里 species 是 1|2、gender 是 0|1|2——
      // 在这里收窄一次，别把 any 撒进请求体
      species: form.species === 2 ? 2 : 1,
      breed: form.breed.trim() || undefined,
      gender: form.gender === 1 ? 1 : form.gender === 2 ? 2 : 0,
      birthday: form.birthday || undefined,
      weight: form.weight.trim() || undefined,
      is_sterilized: form.isSterilized,
      is_chronic: form.isChronic,
      chronic_desc: form.isChronic ? form.chronicDesc.trim() : undefined,
    });
    closeForm();
    await load();
  } catch (error) {
    formError.value = error instanceof ApiError ? error.message : "建档失败，请稍后重试";
  } finally {
    saving.value = false;
  }
}

async function remove(pet: Pet): Promise<void> {
  await cApp.deletePet(pet.id).catch((error: unknown) => {
    errorMessage.value = error instanceof ApiError ? error.message : "删除失败";
  });
  await load();
}

async function restore(pet: Pet): Promise<void> {
  await cApp.restorePet(pet.id).catch((error: unknown) => {
    errorMessage.value = error instanceof ApiError ? error.message : "恢复失败";
  });
  await load();
}

async function activate(pet: Pet): Promise<void> {
  await session.activatePet(pet.id).catch((error: unknown) => {
    errorMessage.value = error instanceof ApiError ? error.message : "切换失败";
  });
}

function restorableUntil(pet: Pet): string {
  return pet.restorable_until ? pet.restorable_until.slice(0, 10) : "";
}
</script>

<template>
  <section>
    <h2 class="ph-page-title">我的</h2>
    <p class="ph-page-desc">账号、宠物档案与回收站。</p>

    <StateForbidden v-if="!session.isLoggedIn" description="登录后管理你的账号与宠物。" />

    <StateLoading v-else-if="session.status === 'loading' || loading" :rows="4" />

    <StateError
      v-else-if="errorMessage"
      :message="errorMessage"
      :request-id="requestId"
      @retry="load"
    />

    <div v-else class="ph-columns">
      <div class="ph-stack">
        <!-- 宠物 -->
        <article class="ph-card">
          <div class="ph-card__head">
            <h3 class="ph-card__title">我的宠物（{{ session.pets.length }}）</h3>
            <button type="button" class="ph-button ph-button--secondary" @click="openForm">＋ 添加宠物</button>
          </div>

          <StateEmpty v-if="session.pets.length === 0 && !showingForm" icon="🐾" title="还没有宠物" description="建一份档案，评分、提醒和档案页才有对象。" />

          <ul v-else-if="session.pets.length" class="ph-pets">
            <li v-for="pet in session.pets" :key="pet.id" class="ph-pets__row">
              <div class="ph-pets__info">
                <span class="ph-pets__name">{{ pet.name }}</span>
                <span class="ph-text-weak">
                  {{ pet.species === 2 ? "猫" : "犬" }}
                  <template v-if="pet.breed"> · {{ pet.breed }}</template>
                  <template v-if="pet.weight"> · {{ pet.weight }} kg</template>
                </span>
                <span v-if="pet.is_chronic" class="ph-pets__tag">慢病照护</span>
              </div>
              <div class="ph-pets__actions">
                <span v-if="pet.id === session.activePet?.id" class="ph-text-sub">当前</span>
                <button v-else type="button" class="ph-button ph-button--text" @click="activate(pet)">设为当前</button>
                <button type="button" class="ph-button ph-button--text" @click="remove(pet)">删除</button>
              </div>
            </li>
          </ul>

          <!-- 建档表单 -->
          <form v-if="showingForm" class="ph-form" @submit.prevent="submit">
            <div class="ph-form__grid">
              <label class="ph-field">
                <span class="ph-field__label">昵称 *</span>
                <input v-model="form.name" class="ph-field__input" maxlength="64" placeholder="豆豆" />
              </label>
              <label class="ph-field">
                <span class="ph-field__label">物种 *</span>
                <select v-model.number="form.species" class="ph-field__input">
                  <option :value="1">犬</option>
                  <option :value="2">猫</option>
                </select>
              </label>
              <label class="ph-field">
                <span class="ph-field__label">品种</span>
                <input v-model="form.breed" class="ph-field__input" maxlength="64" placeholder="柯基" />
              </label>
              <label class="ph-field">
                <span class="ph-field__label">性别</span>
                <select v-model.number="form.gender" class="ph-field__input">
                  <option :value="0">未知</option>
                  <option :value="1">公</option>
                  <option :value="2">母</option>
                </select>
              </label>
              <label class="ph-field">
                <span class="ph-field__label">生日</span>
                <input v-model="form.birthday" type="date" class="ph-field__input" />
              </label>
              <label class="ph-field">
                <span class="ph-field__label">体重（kg）</span>
                <input v-model="form.weight" class="ph-field__input" placeholder="12.50" />
              </label>
            </div>

            <label class="ph-field ph-field--inline">
              <input v-model="form.isSterilized" type="checkbox" />
              <span>已绝育</span>
            </label>
            <label class="ph-field ph-field--inline">
              <input v-model="form.isChronic" type="checkbox" />
              <span>有慢病（会纳入专项照护）</span>
            </label>
            <label v-if="form.isChronic" class="ph-field">
              <span class="ph-field__label">慢病描述 *</span>
              <input v-model="form.chronicDesc" class="ph-field__input" maxlength="512" placeholder="如：髋关节发育不良" />
            </label>

            <p v-if="formError" class="ph-form__error">{{ formError }}</p>
            <div class="ph-form__actions">
              <button type="submit" class="ph-button ph-button--primary" :disabled="!canSubmit">
                {{ saving ? "保存中…" : "建档" }}
              </button>
              <button type="button" class="ph-button ph-button--secondary" @click="closeForm">取消</button>
            </div>
          </form>
        </article>

        <!-- 回收站 -->
        <article class="ph-card">
          <h3 class="ph-card__title">回收站</h3>
          <p class="ph-text-sub ph-card__note">删除的宠物 30 天内可以恢复（对照切片 #94 的验收标准）。</p>
          <ul v-if="recycleBin.length" class="ph-pets">
            <li v-for="pet in recycleBin" :key="pet.id" class="ph-pets__row">
              <div class="ph-pets__info">
                <span class="ph-pets__name">{{ pet.name }}</span>
                <span class="ph-text-weak">可恢复到 {{ restorableUntil(pet) }}</span>
              </div>
              <div class="ph-pets__actions">
                <button type="button" class="ph-button ph-button--secondary" @click="restore(pet)">恢复</button>
              </div>
            </li>
          </ul>
          <StateEmpty v-else icon="🗑️" title="回收站是空的" description="删除宠物后会先放到这里。" />
        </article>
      </div>

      <div class="ph-stack">
        <article class="ph-card">
          <h3 class="ph-card__title">账号</h3>
          <ul class="ph-facts">
            <li><span class="ph-text-sub">手机号</span><span>{{ session.user?.phone }}</span></li>
            <li><span class="ph-text-sub">昵称</span><span>{{ session.user?.nickname }}</span></li>
            <li>
              <span class="ph-text-sub">当前宠物</span>
              <span>{{ session.activePet?.name ?? "未选择" }}</span>
            </li>
          </ul>
          <p class="ph-note">
            资料编辑、数据导出与账号注销分别在 #120（合规）与资料接口的后续迭代里，
            这里先只读展示。
          </p>
        </article>
      </div>
    </div>
  </section>
</template>

<style scoped>
.ph-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ph-space-4);
}

.ph-card__head .ph-card__title {
  margin-bottom: 0;
}

.ph-card__note {
  margin: var(--ph-space-1) 0 var(--ph-space-4);
  font-size: 13px;
}

.ph-pets {
  list-style: none;
  margin: var(--ph-space-4) 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-3);
}

.ph-pets__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ph-space-4);
  padding: var(--ph-space-3);
  background: var(--ph-color-bg);
  border-radius: var(--ph-radius-card);
}

.ph-pets__info {
  display: flex;
  align-items: center;
  gap: var(--ph-space-3);
  min-width: 0;
}

.ph-pets__name {
  font-weight: 600;
}

.ph-pets__tag {
  padding: 2px var(--ph-space-2);
  background: var(--ph-color-orange-light);
  border-radius: var(--ph-radius-input);
  color: var(--ph-color-orange);
  font-size: 12px;
}

.ph-pets__actions {
  display: flex;
  align-items: center;
  gap: var(--ph-space-2);
  white-space: nowrap;
}

.ph-form {
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-3);
  margin-top: var(--ph-space-4);
  padding-top: var(--ph-space-4);
  border-top: 1px solid var(--ph-color-divider);
}

.ph-form__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--ph-space-3);
}

.ph-field {
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-1);
}

.ph-field--inline {
  flex-direction: row;
  align-items: center;
  gap: var(--ph-space-2);
}

.ph-field__label {
  font-size: 13px;
  color: var(--ph-color-text-sub);
}

.ph-field__input {
  height: 36px;
  padding: 0 var(--ph-space-3);
  background: var(--ph-color-surface);
  border: 1px solid var(--ph-color-border);
  border-radius: var(--ph-radius-input);
  font-family: inherit;
  font-size: 14px;
  color: var(--ph-color-text);
}

.ph-form__error {
  margin: 0;
  color: var(--ph-color-danger);
}

.ph-form__actions {
  display: flex;
  gap: var(--ph-space-3);
}

.ph-facts {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-2);
}

.ph-facts li {
  display: flex;
  justify-content: space-between;
  gap: var(--ph-space-3);
}

.ph-note {
  margin: var(--ph-space-4) 0 0;
  font-size: 12px;
  color: var(--ph-color-text-weak);
  line-height: 1.6;
}
</style>
