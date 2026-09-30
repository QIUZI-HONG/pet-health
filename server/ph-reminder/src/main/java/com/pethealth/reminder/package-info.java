/**
 * 提醒与消息中心（切片 #99，规则见 ADR-0019）。
 *
 * <p>两件事在这里，它们的关系是「生成」与「展示」：
 *
 * <ul>
 *   <li><b>生成</b>：{@code ReminderGenerator} 按五类规则（疫苗到期 / 驱虫到期 / 日常打卡 /
 *       异常趋势 / 慢病与老年专项）算出该给谁发什么提醒，阈值从 {@code reminder_rule} 读
 *       （ADR-0010：业务可调项入库）；{@code ReminderScheduler} 定时跑，打卡异常则走
 *       {@code CheckInRecordedEvent} 事件驱动（**事件而不是直接调用**：提醒模块知道档案模块，
 *       档案模块不该知道提醒模块，ADR-0006）。</li>
 *   <li><b>展示</b>：{@code MessageService} 管消息中心的读、已读、删除与开关；
 *       列表采用**惰性补算**——用户来看时才补齐该发的提醒，而不是靠定时任务覆盖所有用户。</li>
 * </ul>
 *
 * <p>对外的能力只有 {@code api/MessageQueryApi}（给账号注销时清空消息）：
 * 本模块依赖 {@code ph-record} 的接口读宠物与记录，反向只通过事件。
 */
package com.pethealth.reminder;
