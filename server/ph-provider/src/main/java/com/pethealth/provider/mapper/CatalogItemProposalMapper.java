package com.pethealth.provider.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.provider.domain.CatalogItemProposal;
import org.apache.ibatis.annotations.Mapper;

/**
 * 目录外服务提案的数据访问。审核队列按 {@code (status, submitted_at)} 走索引。
 */
@Mapper
public interface CatalogItemProposalMapper extends BaseMapper<CatalogItemProposal> {
}
