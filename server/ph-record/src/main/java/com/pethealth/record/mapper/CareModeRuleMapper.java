package com.pethealth.record.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.record.domain.CareModeRule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 专项照护的年龄阈值（V17）。
 *
 * <p>表很小（按物种一行），所以整体读出来在内存里挑——不需要为它做缓存，
 * 也不用担心每次评分都查一次库（一次 selectList 三行）。
 */
@Mapper
public interface CareModeRuleMapper extends BaseMapper<CareModeRule> {

    /** 当前生效的阈值行（未删除）。手写 SQL 必须自己带 {@code is_deleted = 0}（ADR-0011）。 */
    @Select("SELECT * FROM care_mode_rule WHERE is_deleted = 0 ORDER BY species")
    List<CareModeRule> selectEnabled();
}
