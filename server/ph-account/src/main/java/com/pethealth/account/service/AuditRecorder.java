package com.pethealth.account.service;

import com.pethealth.account.domain.AuditLog;
import com.pethealth.account.mapper.AuditLogMapper;
import com.pethealth.account.metrics.SecurityMetrics;
import com.pethealth.common.web.ClientIp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 写一条审计记录（ADR-0028）。
 *
 * <p>这个类只有两件事，但有几处刻意安排，改动前请先读 ADR-0028：
 *
 * <ol>
 *   <li><b>写入永远在独立事务里</b>（{@code REQUIRES_NEW}）：审计行不该跟着业务事务一起消失。
 *   <li><b>事务边界在 try 之外</b>：用 {@link TransactionTemplate} 而不是在方法上打
 *       {@code @Transactional} + 方法内 try/catch。后者是无效的——插入失败时事务已经被标记成
 *       rollback-only，方法内接住异常也救不回来，提交时照样炸（这个坑本仓已经踩过，见
 *       {@code HealthScoreService} 与 {@code CheckInService} 的注释）。
 *   <li><b>「事实」与「尝试」分两个方法</b>，见下面两个方法的说明。混成一个会让审计表里出现
 *       「说发生了、其实回滚了」的记录。
 * </ol>
 *
 * <p>写入失败**只记 ERROR、不打断业务**：审计是观测手段，让用户登不上不是它的正确代价。
 */
@Service
public class AuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(AuditRecorder.class);

    private static final int DETAIL_MAX = 512;

    private final AuditLogMapper mapper;
    private final TransactionTemplate requiresNew;
    private final SecurityMetrics metrics;

    public AuditRecorder(AuditLogMapper mapper, PlatformTransactionManager transactionManager,
                         SecurityMetrics metrics) {
        this.mapper = mapper;
        this.metrics = metrics;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 记一件**已经发生的事实**（注册成功、登录成功、导出、注销）。
     *
     * <p>这类记录只在业务事务**提交之后**才写：审计若抢在提交前落库，而事务随后回滚
     * （Redis 抖动让「签发令牌」失败就会这样），审计表里就会留下一条「说注册了、其实没注册」的假记录。
     * 审计的全部价值在于可信，**假记录比缺记录更糟**。
     *
     * <p>当前没有活跃事务时（例如导出走的是只读查询路径）直接写，不需要挂提交钩子。
     */
    public void recordOutcome(String action, Long operatorId, Long targetId, String subjectRef, String detail) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            write(action, operatorId, targetId, subjectRef, detail);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                write(action, operatorId, targetId, subjectRef, detail);
            }
            // 回滚时**什么都不做**：这一条本来就不该留下
        });
    }

    /**
     * 记**一次被拒绝的尝试**（登录失败）。
     *
     * <p>这类记录必须**立即写、且不随业务事务回滚**：登录失败会抛异常、业务事务注定回滚，
     * 而「有人用错口令撞我的号」正是最需要留证据的那一次。挂 {@code afterCommit} 就等于永远不记。
     *
     * @param operatorId 传 {@code null} 表示确实不知道是谁（未登录的尝试）
     */
    public void recordAttempt(String action, Long operatorId, Long targetId, String subjectRef, String detail) {
        write(action, operatorId, targetId, subjectRef, detail);
    }

    /**
     * 真正落库。
     *
     * @param subjectRef **手机号的 HMAC**（{@code FieldCipher#lookupHash} 的结果，不可还原），
     *                   用于「同一个号被撞了几次」；禁止传明文手机号
     * @param detail     补充说明；禁止放 PII 明文，超长会截断
     */
    private void write(String action, Long operatorId, Long targetId, String subjectRef, String detail) {
        // try 包住**整段**而不是只包住 insert：审计自己出任何意外（将来加字段时的 NPE、
        // 上下文取不到、Redis/MySQL 抖一下）都不该把这次业务一起带走——ADR-0028 定了
        // 「审计失败只记 ERROR 不阻断业务」。事务边界仍在 try 之内，
        // TransactionTemplate 会先把事务回滚干净再把异常抛出来，所以 catch 里没有悬着的
        // rollback-only 事务（与「在 @Transactional 方法里 catch」那种无效写法不同）
        try {
            AuditLog row = new AuditLog();
            row.setAction(action);
            row.setTargetType(AuditLog.TARGET_USER);
            row.setTargetId(targetId);
            row.setSubjectRef(subjectRef == null ? "" : subjectRef);
            row.setIp(ClientIp.current());
            row.setDetail(trim(detail));
            if (operatorId != null) {
                // 先设好，strictInsertFill 只在字段为空时填，所以这里的显式值会赢过 MDC 里的 0。
                // 显式传入而不依赖 MDC：注册与登录发生在拿到令牌之前，MDC 里没有 operatorId
                row.setCreatedBy(operatorId);
                row.setUpdatedBy(operatorId);
            }
            // 没显式给操作者时由 AuditMetaObjectHandler 从 MDC 填；
            // trace_id 一律由它填——审计行必须能追回那一次请求
            requiresNew.executeWithoutResult(status -> mapper.insert(row));
        } catch (RuntimeException e) {
            // 审计失败不阻断业务，所以它不会有面向用户的症状——只有这个指标能告诉你这一层没在记了
            metrics.auditWriteFailed();
            log.error("审计写入失败（不影响业务） action={} targetId={}", action, targetId, e);
        }
    }

    private static String trim(String detail) {
        if (detail == null) {
            return "";
        }
        return detail.length() <= DETAIL_MAX ? detail : detail.substring(0, DETAIL_MAX);
    }
}
