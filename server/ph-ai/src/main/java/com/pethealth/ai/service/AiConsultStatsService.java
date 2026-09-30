package com.pethealth.ai.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pethealth.api.ai.AiConsultStatsApi;
import com.pethealth.ai.domain.AiConsult;
import com.pethealth.ai.mapper.AiConsultMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * {@link AiConsultStatsApi} 的实现：只做「数了多少条、其中几条红」。
 *
 * <p>用 MyBatis-Plus 的 count 而不是自定义聚合 SQL：条件只有三个（宠物、时间窗、风险等级），
 * 而 {@code ai_consult} 上已有的 {@code idx_pet_time} 正好覆盖前两个——写聚合 SQL 反而要把
 * 那条索引的形状抄进代码里。
 */
@Service
public class AiConsultStatsService implements AiConsultStatsApi {

    private final AiConsultMapper consultMapper;

    public AiConsultStatsService(AiConsultMapper consultMapper) {
        this.consultMapper = consultMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public ConsultStats summaryOf(long petId, LocalDate from, LocalDate to) {
        int total = count(petId, from, to, null);
        int red = count(petId, from, to, 3);
        return new ConsultStats(total, red);
    }

    /** {@code riskLevel} 为 null 表示不限等级。窗口按业务日取整天（含首尾两天）。 */
    private int count(long petId, LocalDate from, LocalDate to, Integer riskLevel) {
        var query = Wrappers.<AiConsult>lambdaQuery()
                .eq(AiConsult::getPetId, petId)
                .ge(AiConsult::getCreatedAt, from.atStartOfDay())
                .lt(AiConsult::getCreatedAt, to.plusDays(1).atStartOfDay());
        if (riskLevel != null) {
            query.eq(AiConsult::getRiskLevel, riskLevel);
        }
        Long count = consultMapper.selectCount(query);
        return count == null ? 0 : count.intValue();
    }
}
