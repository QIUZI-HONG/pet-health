package com.pethealth.provider.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.provider.domain.ProviderQualification;
import org.apache.ibatis.annotations.Mapper;

/**
 * 资质材料的数据访问。唯一性查重（同一营业执照 / 身份证只能挂一个有效服务者）走
 * {@code cert_no_hash} 的等值查询——密文没有保序性，只有 HMAC 列能查（ADR-0013）。
 */
@Mapper
public interface ProviderQualificationMapper extends BaseMapper<ProviderQualification> {
}
