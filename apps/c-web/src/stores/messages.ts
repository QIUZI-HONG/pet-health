/**
 * 未读消息数：一个全局状态，谁产生/消费消息谁负责刷新。
 *
 * 为什么单独放一个 store：顶栏角标在上，产生提醒的动作在下（打卡、录疫苗、标记已读），
 * 如果角标状态只活在顶栏组件里，这些动作发生后角标就是**旧的**——
 * 用户在首页打完卡、看到提醒出现了，铃铛却还是不响（踩过）。
 *
 * 拉取失败不打扰用户：角标是增强，不是主流程（与交付文档 2.4「推送失败不影响主流程」同一口径）。
 */
import { defineStore } from "pinia";
import { cApp } from "@pet-health/shared";

export const useMessageStore = defineStore("messages", {
  state: () => ({
    unread: 0,
    unreadReminders: 0,
  }),

  actions: {
    async refresh(): Promise<void> {
      try {
        const count = await cApp.getUnreadCount();
        this.unread = count.unread;
        this.unreadReminders = count.unread_reminders;
      } catch {
        // 静默：角标拉不到就不显示，不弹错
        this.unread = 0;
        this.unreadReminders = 0;
      }
    },

    clear(): void {
      this.unread = 0;
      this.unreadReminders = 0;
    },
  },
});
