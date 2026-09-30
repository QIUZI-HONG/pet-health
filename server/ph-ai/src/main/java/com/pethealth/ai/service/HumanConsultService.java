package com.pethealth.ai.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pethealth.ai.domain.AiConsult;
import com.pethealth.ai.domain.HumanConsultTicket;
import com.pethealth.ai.mapper.AiConsultMapper;
import com.pethealth.ai.mapper.HumanConsultTicketMapper;
import com.pethealth.api.admin.HumanConsultAdminView;
import com.pethealth.api.admin.HumanConsultStatusRequest;
import com.pethealth.api.app.HumanConsultView;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.time.AppTime;
import com.pethealth.common.util.Text;
import com.pethealth.api.reminder.BusinessMessageApi;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 转人工咨询：登记工单 + 通知用户（F006 的出口）。
 *
 * <p><b>口径（本项目裁决）</b>：转人工 = **登记一条工单交给平台运营跟进**，用户在消息中心收到
 * 「已受理」，运营回复后再收到一条（回复文案就是运营写的那句话）。
 * 它**不接支付**（钱在门店付、平台不经手资金，ADR-0036；交付文档的 39 元/次没有收款主体），
 * 也**不直接派给服务者**（派给谁需要匹配规则，运营先接住）。
 *
 * <p>两处幂等：受理靠 `consult_id` 唯一键（重复提交返回同一条），通知靠 `dedupKey`
 * （`BusinessMessageApi.notify` 自身幂等）——所以「连点两下」与「重试」都不会给用户发两条一模一样的消息。
 */
@Service
public class HumanConsultService {

    /** 消息类型与去重键前缀：用户维度看是「AI 咨询的人工回复」，归在系统通知那一类。 */
    private static final int MESSAGE_TYPE_SYSTEM = 10;
    private static final String ACCEPTED_DEDUP = "human-consult-accepted-";
    private static final String REPLIED_DEDUP = "human-consult-replied-";

    private final HumanConsultTicketMapper ticketMapper;
    private final AiConsultMapper consultMapper;
    private final BusinessMessageApi businessMessages;

    public HumanConsultService(HumanConsultTicketMapper ticketMapper, AiConsultMapper consultMapper,
                               BusinessMessageApi businessMessages) {
        this.ticketMapper = ticketMapper;
        this.consultMapper = consultMapper;
        this.businessMessages = businessMessages;
    }

    // ---------------------------------------------------------------- C 端

    /**
     * 把一次咨询转人工（幂等）。
     *
     * <p>归属校验：咨询必须属于这个用户与这只宠物（**不属于就 40400**，与「不存在」同码——
     * 用不同的码会泄露「这个 consultId 是存在的」）。
     */
    @Transactional
    public HumanConsultView transfer(long userId, long petId, long consultId) {
        AiConsult consult = consultMapper.selectById(consultId);
        if (consult == null || !consult.getUserId().equals(userId) || !consult.getPetId().equals(petId)) {
            throw BusinessException.notFound("咨询不存在");
        }

        HumanConsultTicket ticket = find(consultId);
        if (ticket == null) {
            ticket = new HumanConsultTicket();
            ticket.setUserId(userId);
            ticket.setPetId(petId);
            ticket.setConsultId(consultId);
            ticket.setRiskLevel(consult.getRiskLevel() == null ? 0 : consult.getRiskLevel());
            ticket.setStatus(HumanConsultTicket.STATUS_PENDING);
            ticket.setOperatorId(0L);
            try {
                ticketMapper.insert(ticket);
            } catch (DuplicateKeyException e) {
                // 并发提交：唯一键挡住了第二条，读回先落库的那一条（幂等，不是错误）
                ticket = find(consultId);
                if (ticket == null) {
                    throw e;
                }
            }
        }

        businessMessages.notify(new BusinessMessageApi.BusinessNotification(
                userId,
                MESSAGE_TYPE_SYSTEM,
                ACCEPTED_DEDUP + consultId,
                "已收到你的咨询",
                "我们已安排人工查看这次咨询，预计 2 小时内回复。回复会出现在消息中心，也会在这里更新状态。",
                "查看提醒设置",
                "/messages"));
        return toAppView(ticket);
    }

    // ---------------------------------------------------------------- 运营侧

    /** 待办队列：待处理在前，同状态按提交时间升序（先到先处理）。 */
    @Transactional(readOnly = true)
    public PageResult<HumanConsultAdminView> page(Integer status, long page, long pageSize) {
        Page<HumanConsultTicket> result = ticketMapper.selectPage(new Page<>(page, pageSize),
                Wrappers.<HumanConsultTicket>lambdaQuery()
                        .eq(status != null, HumanConsultTicket::getStatus, status)
                        .orderByAsc(HumanConsultTicket::getStatus)
                        .orderByAsc(HumanConsultTicket::getCreatedAt)
                        .orderByAsc(HumanConsultTicket::getId));
        return PageResult.from(result, HumanConsultService::toAdminView);
    }

    /**
     * 处置：回复（1）或关闭（2）。
     *
     * <p>两条规则：**回复必须带话**（那句话会原样发给用户，空回复等于让用户白等一次）、
     * **终态不可再改**（已回复/已关闭的单子再处置回 40900：处理结果是一次性的）。
     */
    @Transactional
    public HumanConsultAdminView handle(long operatorId, long ticketId, HumanConsultStatusRequest request) {
        HumanConsultTicket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null) {
            throw BusinessException.notFound("工单不存在：" + ticketId);
        }
        if (ticket.isHandled()) {
            throw BusinessException.conflict("这条工单已经处理过了");
        }
        int target = request.status() == null ? 0 : request.status();
        String reply = Text.trimToNull(request.replyNote());
        if (target == HumanConsultTicket.STATUS_REPLIED && reply == null) {
            throw BusinessException.paramInvalid("回复用户时请写下回复内容");
        }

        ticket.setStatus(target);
        ticket.setOperatorId(operatorId);
        ticket.setHandledAt(AppTime.now());
        ticketMapper.updateById(ticket);

        if (target == HumanConsultTicket.STATUS_REPLIED) {
            businessMessages.notify(new BusinessMessageApi.BusinessNotification(
                    ticket.getUserId(),
                    MESSAGE_TYPE_SYSTEM,
                    REPLIED_DEDUP + ticketId,
                    "人工回复已到",
                    reply,
                    "查看消息中心",
                    "/messages"));
        }
        return toAdminView(ticket);
    }

    // ---------------------------------------------------------------- 内部

    private HumanConsultTicket find(long consultId) {
        return ticketMapper.selectOne(Wrappers.<HumanConsultTicket>lambdaQuery()
                .eq(HumanConsultTicket::getConsultId, consultId));
    }

    private static HumanConsultView toAppView(HumanConsultTicket ticket) {
        return new HumanConsultView(ticket.getId(), ticket.getConsultId(), ticket.getRiskLevel(),
                ticket.getStatus(), ticket.getCreatedAt());
    }

    private static HumanConsultAdminView toAdminView(HumanConsultTicket ticket) {
        return new HumanConsultAdminView(ticket.getId(), ticket.getUserId(), ticket.getPetId(),
                ticket.getConsultId(), ticket.getRiskLevel(), ticket.getStatus(),
                ticket.getOperatorId(), ticket.getHandledAt(), ticket.getCreatedAt());
    }
}
