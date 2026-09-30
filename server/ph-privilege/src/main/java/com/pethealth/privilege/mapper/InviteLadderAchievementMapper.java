package com.pethealth.privilege.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.privilege.domain.InviteLadderAchievement;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 邀请阶梯达成记录的数据访问。 */
@Mapper
public interface InviteLadderAchievementMapper extends BaseMapper<InviteLadderAchievement> {

    /** 某一档的达成人数（运营总览的阶梯统计）。 */
    @Select("""
            SELECT COUNT(*)
              FROM invite_ladder_achievement
             WHERE is_deleted = 0
               AND threshold = #{threshold}
            """)
    int countByThreshold(@Param("threshold") int threshold);
}
