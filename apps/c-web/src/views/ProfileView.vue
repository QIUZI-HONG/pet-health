<script setup lang="ts">
/**
 * 我的：账号信息 + 宠物管理（建档 / 编辑 / 删除 / 恢复 / 切换当前）。
 *
 * 这是当前唯一有真实写操作的页面——账号与宠物档案的接口（切片 #94）已经在了，
 * 所以这里把「请求层 → 会话 → 服务端」整条链路走通，同时当作用户可见的冒烟测试。
 *
 * 权益、积分、券、社群这些入口先不放：它们还没有数据源，摆一排点不动的入口不如不摆。
 */
import { computed, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { toApiFailure, cApp, formatDate, genderLabel, speciesLabel, todayIso, toUserMessage, type PetView } from "@pet-health/shared";
import { useSessionStore } from "../stores/session";
import ComplianceCard from "../components/ComplianceCard.vue";
import SessionGate from "../components/SessionGate.vue";
import StateEmpty from "../components/states/StateEmpty.vue";
import StateError from "../components/states/StateError.vue";

const session = useSessionStore();
const route = useRoute();
const router = useRouter();

const recycleBin = ref<PetView[]>([]);
const errorMessage = ref("");
const requestId = ref("");
const formError = ref("");
const saving = ref(false);
const showingForm = ref(false);

// 资料编辑（昵称 / 头像 / 性别）——与宠物表单各自独立，互不干扰
const editingProfile = ref(false);
const profileSaving = ref(false);
const profileError = ref("");
const profileForm = reactive({ nickname: "", avatar: "", gender: 0 });

/** 正在编辑的宠物 id；null 表示这是「新建」。 */
const editingId = ref<number | null>(null);

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

/** 回收站是这一页独有的数据；宠物列表本身在会话 store 里（会话加载时就一起取了）。 */
async function loadRecycleBin(): Promise<void> {
  if (!session.isLoggedIn) return;
  errorMessage.value = "";
  requestId.value = "";
  try {
    recycleBin.value = await cApp.listPets(true);
  } catch (error) {
    const failure = toApiFailure(error, "加载失败，请稍后重试");
    errorMessage.value = failure.message;
    requestId.value = failure.requestId;
  }
}

/** 会话一恢复就重新拉回收站——所以「重新加载」点完，这一页的数据也会跟着回来。 */
watch(
  () => session.status,
  (status) => {
    if (status === "authenticated") {
      void loadRecycleBin();
    }
  },
  { immediate: true },
);

/** 整页刷新：会话 + 本页数据。 */
async function reloadPage(): Promise<void> {
  await session.reload();
  await loadRecycleBin();
}

/**
 * 顶栏的「还没有宠物 ▾ / ＋ 添加宠物」会带 `?action=create-pet` 跳进来（PetSwitcher.goCreate）。
 * 认下这个参数：登录态就绪后把建档表单展开，并把参数从地址里去掉——不然后续刷新会又弹一次，
 * 用户点过「取消」也白点。
 */
watch(
  () => [route.query.action, session.isLoggedIn] as const,
  ([action, loggedIn]) => {
    if (action === "create-pet" && loggedIn) {
      openForm();
      void router.replace({ name: "profile" });
    }
  },
  { immediate: true },
);

/** 打开建档表单：editingId 归零——这张表单两用，不清就会走成「编辑」。 */
function openForm(): void {
  formError.value = "";
  editingId.value = null;
  showingForm.value = true;
}

/** 编辑：把现有值填进同一个表单，省得再写一套。 */
function openEdit(pet: PetView): void {
  formError.value = "";
  editingId.value = pet.id;
  form.name = pet.name;
  form.species = pet.species;
  form.breed = pet.breed ?? "";
  form.gender = pet.gender;
  form.birthday = pet.birthday ?? "";
  form.weight = pet.weight ?? "";
  // 契约里这两个字段是可选的（未填时不返回），表单里当 false 处理
  form.isSterilized = pet.is_sterilized ?? false;
  form.isChronic = pet.is_chronic ?? false;
  form.chronicDesc = pet.chronic_desc ?? "";
  showingForm.value = true;
}

/** 收起表单顺带清空：留着上次的值，下一次建档会带出上一只宠物的数据。 */
function closeForm(): void {
  showingForm.value = false;
  editingId.value = null;
  form.name = "";
  form.breed = "";
  form.birthday = "";
  form.weight = "";
  form.isSterilized = false;
  form.isChronic = false;
  form.chronicDesc = "";
}

/** 建档与编辑共用（靠 editingId 分叉）；成功后两处都要重拉：宠物列表在 store，回收站只在本页。 */
async function submit(): Promise<void> {
  if (!canSubmit.value) return;
  formError.value = "";
  saving.value = true;
  try {
    const payload = {
      name: form.name.trim(),
      // 表单控件给回来的是 number，契约里 species 是 1|2、gender 是 0|1|2——
      // 在这里收窄一次，别把 any 撒进请求体
      species: (form.species === 2 ? 2 : 1) as 1 | 2,
      breed: form.breed.trim(),
      gender: (form.gender === 1 ? 1 : form.gender === 2 ? 2 : 0) as 0 | 1 | 2,
      birthday: form.birthday || undefined,
      weight: form.weight.trim() || undefined,
      is_sterilized: form.isSterilized,
      is_chronic: form.isChronic,
      chronic_desc: form.isChronic ? form.chronicDesc.trim() : "",
    };
    if (editingId.value === null) {
      await cApp.createPet(payload);
    } else {
      await cApp.updatePet(editingId.value, payload);
    }
    closeForm();
    await session.refreshPets();
    await loadRecycleBin();
  } catch (error) {
    formError.value = toUserMessage(
      error,
      // 兜底文案跟着「新建还是编辑」走：同一个 catch 覆盖两个分支
      editingId.value === null ? "建档失败，请稍后重试" : "保存失败，请稍后重试",
    );
  } finally {
    saving.value = false;
  }
}

/**
 * 资料编辑（昵称 / 头像 / 性别）。
 *
 * <p>契约把「不改」与「清空」分成两件事（`UpdateProfileRequest` 的说明）：**字段不传 = 不改，
 * `avatar` 传空串 = 清空**。所以这里的模型是：输入框留空表示「这次不改头像」，
 * 真要清掉已设置的头像请点「清除头像」——不让「留空」同时兼任两种意思，
 * 否则用户想改昵称却顺手清掉了头像。
 */
async function submitProfile(): Promise<void> {
  const nickname = profileForm.nickname.trim();
  if (!nickname || profileSaving.value) {
    return;
  }
  const avatar = profileForm.avatar.trim();
  profileError.value = "";
  profileSaving.value = true;
  try {
    const updated = await cApp.updateMe({
      nickname,
      gender: profileForm.gender as 0 | 1 | 2,
      // 留空 = **不传这个字段**（不是传空串——空串在契约里是「清空」）。
      // 用条件展开而不是 `avatar: undefined`：后者在 JS 对象里仍然有这个键，
      // 「不传」这件事就只靠 JSON.stringify 顺手丢掉 undefined 来兜着，读代码时看不出来。
      ...(avatar === "" ? {} : { avatar }),
    });
    session.applyProfile(updated);
    editingProfile.value = false;
  } catch (error) {
    profileError.value = toUserMessage(error, "保存失败，请稍后重试");
  } finally {
    profileSaving.value = false;
  }
}

/** 清空头像：`avatar: ""` 是契约里「清掉」的写法（与「不传」区分开）。 */
async function clearAvatar(): Promise<void> {
  if (profileSaving.value) {
    return;
  }
  profileError.value = "";
  profileSaving.value = true;
  try {
    const updated = await cApp.updateMe({ avatar: "" });
    session.applyProfile(updated);
    profileForm.avatar = "";
  } catch (error) {
    profileError.value = toUserMessage(error, "清除失败，请稍后重试");
  } finally {
    profileSaving.value = false;
  }
}

/** 打开表单时用当前资料填初值（每次打开都重填，免得留下上一次没保存的输入）。 */
function openProfileForm(): void {
  profileForm.nickname = session.user?.nickname ?? "";
  profileForm.avatar = session.user?.avatar ?? "";
  profileForm.gender = session.user?.gender ?? 0;
  profileError.value = "";
  editingProfile.value = true;
}

/** 删除＝软删除（30 天内可恢复）；两处列表都要重拉，否则一边还留着它。 */
async function remove(pet: PetView): Promise<void> {
  try {
    await cApp.deletePet(pet.id);
    // 刷新也可能失败（后端抖一下），一并收在这一层——否则会变成没人接的 Promise 拒绝
    await session.refreshPets();
    await loadRecycleBin();
  } catch (error) {
    errorMessage.value = toUserMessage(error, "删除失败");
  }
}

/** 恢复同理：会话里的宠物列表与本页回收站都要重拉，两边才是同一份事实。 */
async function restore(pet: PetView): Promise<void> {
  try {
    await cApp.restorePet(pet.id);
    await session.refreshPets();
    await loadRecycleBin();
  } catch (error) {
    errorMessage.value = toUserMessage(error, "恢复失败");
  }
}

/** 设为当前宠物：接口返回更新后的 UserProfile，store 直接换掉；本页没有跟着变的字段，不必再刷。 */
async function activate(pet: PetView): Promise<void> {
  try {
    await session.activatePet(pet.id);
  } catch (error) {
    errorMessage.value = toUserMessage(error, "切换失败");
  }
}

/** 回收站里的「可恢复到」文案：日期是服务端按 30 天算好的（restorable_until），前端不再算一遍。 */
function restorableUntil(pet: PetView): string {
  return formatDate(pet.restorable_until);
}
</script>

<template>
  <section>
    <h2 class="ph-page-title">我的</h2>
    <p class="ph-page-desc">账号、宠物档案与回收站。</p>

    <SessionGate forbidden-description="登录后管理你的账号与宠物。">
      <StateError
        v-if="errorMessage"
        :message="errorMessage"
        :request-id="requestId"
        @retry="reloadPage"
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
                  {{ speciesLabel(pet.species) }}
                  <template v-if="pet.breed"> · {{ pet.breed }}</template>
                  <template v-if="pet.gender"> · {{ genderLabel(pet.gender) }}</template>
                  <template v-if="pet.weight"> · {{ pet.weight }} kg</template>
                  <template v-if="pet.birthday"> · {{ formatDate(pet.birthday) }}</template>
                </span>
                <span v-if="pet.is_chronic" class="ph-pets__tag">慢病照护</span>
              </div>
              <div class="ph-pets__actions">
                <span v-if="pet.id === session.activePet?.id" class="ph-text-sub">当前</span>
                <button v-else type="button" class="ph-button ph-button--text" @click="activate(pet)">设为当前</button>
                <button type="button" class="ph-button ph-button--text" @click="openEdit(pet)">编辑</button>
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
                <input v-model="form.birthday" type="date" class="ph-field__input" :max="todayIso()" />
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
                {{ saving ? "保存中…" : editingId === null ? "建档" : "保存修改" }}
              </button>
              <button type="button" class="ph-button ph-button--secondary" @click="closeForm">取消</button>
            </div>
          </form>
        </article>

        <!-- 回收站 -->
        <article class="ph-card">
          <h3 class="ph-card__title">回收站</h3>
          <p class="ph-text-sub ph-card__note">删除的宠物 30 天内可以恢复。</p>
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
        <!-- 合规（切片 #74）：条款入口 + 数据导出 + 注销 -->
        <ComplianceCard />

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
            手机号是登录凭证，暂不提供修改；昵称与头像在这里改。数据导出与账号注销见上方「账号与条款」。
          </p>

          <form v-if="editingProfile" class="ph-stack" @submit.prevent="submitProfile">
            <label class="ph-field">
              <span class="ph-field__label">昵称 *</span>
              <input v-model="profileForm.nickname" class="ph-input" type="text" maxlength="64" />
            </label>
            <label class="ph-field">
              <span class="ph-field__label">头像地址</span>
              <input v-model="profileForm.avatar" class="ph-input" type="text" maxlength="512"
                     placeholder="留空表示不设置；清掉已设置的头像请点「清除头像」" />
            </label>
            <label class="ph-field">
              <span class="ph-field__label">性别</span>
              <select v-model.number="profileForm.gender" class="ph-input">
                <option :value="0">未设置</option>
                <option :value="1">男</option>
                <option :value="2">女</option>
              </select>
            </label>
            <p v-if="profileError" class="ph-error">{{ profileError }}</p>
            <div class="ph-row">
              <button class="ph-button" type="submit" :disabled="profileSaving || !profileForm.nickname.trim()">
                {{ profileSaving ? "保存中…" : "保存资料" }}
              </button>
              <button class="ph-button ph-button--secondary" type="button" :disabled="profileSaving"
                      @click="clearAvatar">
                清除头像
              </button>
              <button class="ph-button ph-button--secondary" type="button" :disabled="profileSaving"
                      @click="editingProfile = false">
                取消
              </button>
            </div>
          </form>
          <button v-else class="ph-button ph-button--secondary" type="button" @click="openProfileForm">
            编辑资料
          </button>
        </article>
        </div>
      </div>
    </SessionGate>
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
