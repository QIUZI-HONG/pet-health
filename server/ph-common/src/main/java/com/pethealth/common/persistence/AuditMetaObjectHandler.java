package com.pethealth.common.persistence;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.pethealth.common.time.AppTime;
import com.pethealth.common.trace.TraceIds;
import org.apache.ibatis.reflection.MetaObject;

import java.time.LocalDateTime;

/**
 * 审计字段自动填充（ADR-0011）：写操作一律留 {@code operator_id} 与 {@code trace_id}，
 * 靠这一处统一完成，而不是每个 service 记得设一遍。
 *
 * <p>插入用 {@code strictInsertFill}（只在字段为空时填），更新用 {@code setFieldValByName}
 * （强制覆盖）——更新时实体往往是从库里查出来的，带着旧时间戳，不覆盖就会留下错的 {@code updated_at}。
 *
 * <p>Bean 由 {@link MybatisPlusConfig} 声明（不在这里打 {@code @Component}，否则同一个类会被注册两次）。
 */
public class AuditMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = AppTime.now();
        long operatorId = TraceIds.currentOperatorId();
        String traceId = TraceIds.currentTraceId();

        strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
        strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
        strictInsertFill(metaObject, "createdBy", Long.class, operatorId);
        strictInsertFill(metaObject, "updatedBy", Long.class, operatorId);
        strictInsertFill(metaObject, "traceId", String.class, traceId);
        strictInsertFill(metaObject, "isDeleted", Integer.class, 0);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        setFieldValByName("updatedAt", AppTime.now(), metaObject);
        setFieldValByName("updatedBy", TraceIds.currentOperatorId(), metaObject);
        setFieldValByName("traceId", TraceIds.currentTraceId(), metaObject);
    }
}
