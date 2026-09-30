package com.pethealth.provider.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.provider.domain.OnboardingApplication;
import org.apache.ibatis.annotations.Mapper;

/**
 * 入驻申请单的数据访问。审核队列按 {@code (status, submitted_at)} 走索引（先到先审）。
 */
@Mapper
public interface OnboardingApplicationMapper extends BaseMapper<OnboardingApplication> {
}
