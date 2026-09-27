<script setup lang="ts">
/**
 * 健康档案：宠物信息 + 时间轴。
 *
 * 宠物基础信息是真的（切片 #94 的接口），档案记录与时间轴要到 #102 才有数据源，
 * 所以那两块显示空态并注明依赖。
 */
import { formatDate, genderLabel, speciesLabel } from "@pet-health/shared";
import { useSessionStore } from "../stores/session";
import StateEmpty from "../components/states/StateEmpty.vue";
import StateForbidden from "../components/states/StateForbidden.vue";

const session = useSessionStore();
</script>

<template>
  <section>
    <h2 class="ph-page-title">健康档案</h2>
    <p class="ph-page-desc">疫苗、驱虫、就医、打卡与服务报工，都汇成同一条时间轴。</p>

    <StateForbidden v-if="!session.isLoggedIn" description="健康档案跟着宠物走，登录后查看。" />

    <div v-else class="ph-columns">
      <div class="ph-stack">
        <article class="ph-card">
          <h3 class="ph-card__title">时间轴</h3>
          <StateEmpty
            icon="📋"
            title="还没有记录"
            description="档案分项与时间轴在 #102 落地；打卡（#97）与服务报工（#107）的记录都会汇到这里。"
          />
        </article>
      </div>

      <div class="ph-stack">
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
  </section>
</template>

<style scoped>
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
