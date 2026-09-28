package com.pethealth.reminder.api;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 消息模块对外暴露的只读查询（切片 #74 的数据导出）。
 *
 * <p>只开「导出」这一个口子：消息中心自己的读写逻辑留在模块内，别的模块（如账号注销）
 * 需要的是「把这位用户的消息完整交出来」，不是通用查询能力（ADR-0006：接口要窄）。
 */
public interface MessageQueryApi {

    /** 一条消息（导出用；不含 dedup_key 这类内部字段）。 */
    record MessageExport(String kind, String type, String title, String content, int riskLevel,
                         LocalDateTime remindAt, boolean read, LocalDateTime createdAt) {
    }

    List<MessageExport> exportOf(long userId);
}
