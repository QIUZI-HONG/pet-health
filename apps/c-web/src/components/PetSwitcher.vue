<script setup lang="ts">
/**
 * 宠物切换（多宠家庭）。当前宠物以服务端 `active_pet_id` 为准（切片 #94），
 * 切完立刻回写服务端，换浏览器也一致。
 */
import { computed, ref } from "vue";
import { useRouter } from "vue-router";
import { useSessionStore } from "../stores/session";

const session = useSessionStore();
const router = useRouter();
const open = ref(false);
const saving = ref(false);

const label = computed(() => session.activePet?.name ?? "还没有宠物");

async function pick(petId: number): Promise<void> {
  open.value = false;
  if (saving.value || petId === session.activePet?.id) return;
  saving.value = true;
  try {
    await session.activatePet(petId);
  } finally {
    saving.value = false;
  }
}

function goCreate(): void {
  open.value = false;
  void router.push({ name: "profile", query: { action: "create-pet" } });
}
</script>

<template>
  <div class="ph-switcher">
    <button type="button" class="ph-switcher__trigger" :disabled="saving" @click="open = !open">
      <span aria-hidden="true">🐶</span>
      <span class="ph-switcher__label">{{ label }}</span>
      <span class="ph-switcher__caret" aria-hidden="true">▾</span>
    </button>

    <div v-if="open" class="ph-switcher__menu" role="menu">
      <button
        v-for="pet in session.pets"
        :key="pet.id"
        type="button"
        role="menuitem"
        class="ph-switcher__item"
        :class="{ 'ph-switcher__item--active': pet.id === session.activePet?.id }"
        @click="pick(pet.id)"
      >
        <span>{{ pet.name }}</span>
        <span class="ph-text-weak">{{ pet.breed ?? (pet.species === 2 ? "猫" : "犬") }}</span>
      </button>
      <button v-if="session.pets.length === 0" type="button" class="ph-switcher__item" @click="goCreate">
        还没有宠物，去建档
      </button>
      <button v-else type="button" class="ph-switcher__item ph-text-sub" @click="goCreate">
        ＋ 添加宠物
      </button>
    </div>
  </div>
</template>

<style scoped>
.ph-switcher {
  position: relative;
}

.ph-switcher__trigger {
  display: inline-flex;
  align-items: center;
  gap: var(--ph-space-2);
  height: 36px;
  padding: 0 var(--ph-space-3);
  background: var(--ph-color-bg);
  border: 1px solid var(--ph-color-border);
  border-radius: var(--ph-radius-input);
  font-family: inherit;
  font-size: 14px;
  color: var(--ph-color-text);
  cursor: pointer;
}

.ph-switcher__caret {
  color: var(--ph-color-text-weak);
  font-size: 12px;
}

.ph-switcher__menu {
  position: absolute;
  top: calc(100% + var(--ph-space-2));
  left: 0;
  z-index: 20;
  min-width: 180px;
  padding: var(--ph-space-2);
  background: var(--ph-color-surface);
  border: 1px solid var(--ph-color-border);
  border-radius: var(--ph-radius-input);
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.ph-switcher__item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ph-space-3);
  padding: var(--ph-space-2) var(--ph-space-3);
  background: transparent;
  border: 0;
  border-radius: var(--ph-radius-input);
  font-family: inherit;
  font-size: 14px;
  color: var(--ph-color-text);
  text-align: left;
  cursor: pointer;
}

.ph-switcher__item:hover {
  background: var(--ph-color-bg);
}

.ph-switcher__item--active {
  color: var(--ph-color-primary);
  font-weight: 600;
}
</style>
