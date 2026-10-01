/**
 * 提醒的分类（交付文档 4.16.2 首页稿里的**彩色分类 pill**）。
 *
 * 稿子给的是「异常提醒 / 疫苗 / 驱虫 / 体重 / 行为 / 卫生」六类，而服务端的
 * `MessageView.type` 是十类（1疫苗2驱虫3日常4异常5趋势6慢病老年7订单8券9邀请10系统）——
 * 两套不是一回事，所以这里**按服务端的取值做映射**，不硬凑稿子那六个词：
 * 类型是服务端的事实，分类标签是它的展示说法（同一份映射只有这一处）。
 *
 * 色调对应的语义（与 4.16.10 的用色纪律一致）：
 * 红=异常、橙=券与疫苗这类「要去做点什么」、绿=驱虫这类按期完成、
 * 紫=积分/知识/社群那条语言（趋势与慢病照护属于「长期跟踪」）、蓝=信息型。
 */
export type ReminderTone = "danger" | "warning" | "success" | "purple" | "info" | "neutral";

interface ReminderTypeSpec {
  label: string;
  tone: ReminderTone;
}

/** `MessageView.type` → （标签, 色调）。取值见契约 `contract/app.yaml` 的 `MessageView.type`。 */
const TYPES: Record<number, ReminderTypeSpec> = {
  1: { label: "疫苗", tone: "warning" },
  2: { label: "驱虫", tone: "success" },
  3: { label: "日常", tone: "info" },
  4: { label: "异常", tone: "danger" },
  5: { label: "趋势", tone: "purple" },
  6: { label: "慢病照护", tone: "purple" },
  7: { label: "订单", tone: "info" },
  8: { label: "券", tone: "warning" },
  9: { label: "邀请", tone: "warning" },
  10: { label: "系统", tone: "neutral" },
};

/**
 * 认不出的类型给中性标签而不是空着：契约将来加一类时，旧前端要能如实显示
 * 「有一条不属于已知分类的提醒」，而不是把它藏起来（提醒是**要做事**的东西，藏起来最糟）。
 */
export function reminderTypeLabel(type: number | null | undefined): string {
  if (type === null || type === undefined) {
    return "提醒";
  }
  return TYPES[type]?.label ?? "提醒";
}

export function reminderTypeTone(type: number | null | undefined): ReminderTone {
  if (type === null || type === undefined) {
    return "neutral";
  }
  return TYPES[type]?.tone ?? "neutral";
}
