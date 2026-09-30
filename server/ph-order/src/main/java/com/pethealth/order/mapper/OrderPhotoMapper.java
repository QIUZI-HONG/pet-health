package com.pethealth.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pethealth.order.domain.OrderPhoto;
import com.pethealth.order.domain.OrderPhotoSlot;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 三道照片墙的数据访问（槽位 + 照片两张表，迁移 V30）。
 *
 * <p>两个槽位方法值得单独说：
 *
 * <ul>
 *   <li>{@link #replacePhotos} 是「**整体替换**」在 SQL 层的落点：先按槽位删旧行、再插新行。
 *       之所以敢删，是因为照片行只承载「哪张图挂在哪一道上」这一个事实——
 *       图片本体在文件域，删的是关系不是证据（ADR-0020 的直传：字节不经过这里）；
 *   <li>{@link #upsertSlot} 用 {@code ON DUPLICATE KEY UPDATE} 而不是「先查再插」：
 *       同一订单的同一个槽位只有一个请求会赢，输的那个也不该报错（它只是把同一行再写一遍）。
 *       {@code photo_count} 由 service 传**真实条数**，不靠 {@code COUNT(*)} 累加——
 *       它是报工门禁的判据，必须与同一事务里写入的照片行数一致，见 {@code OrderPhotoWallService}。
 * </ul>
 */
@Mapper
public interface OrderPhotoMapper extends BaseMapper<OrderPhoto> {

    /** 槽位行（含未上传的：用 {@code upsertSlot} 建），按订单一次取回。 */
    @Select("""
            SELECT * FROM `order_photo_slot`
             WHERE `order_id` = #{orderId} AND `is_deleted` = 0
             ORDER BY `slot`
            """)
    List<OrderPhotoSlot> slotsOfOrder(@Param("orderId") long orderId);

    /**
     * 写一个槽位（没有就建、有就更新备注与计数）。
     *
     * <p>唯一键 {@code (order_id, slot)} 是并发下的收敛点：两个请求同时 PUT 同一槽位时，
     * 一个建、一个更新，最终留下的是**后提交的那一份**（这正是「整体替换」该有的语义）。
     */
    // @Insert 而不是 @Update：见 AppointmentSlotMapper 里那条注释（blockAttack 拦截器按注解
    // 判断语句类型，注解写错会让 INSERT 被当成 UPDATE 解析而报 50000）
    @Insert("""
            INSERT INTO `order_photo_slot`
                (`order_id`, `slot`, `photo_count`, `remark`, `created_by`, `updated_by`, `trace_id`)
            VALUES (#{orderId}, #{slot}, #{photoCount}, #{remark}, #{operatorId}, #{operatorId}, #{traceId})
            ON DUPLICATE KEY UPDATE
                `photo_count` = VALUES(`photo_count`),
                `remark` = VALUES(`remark`),
                `updated_at` = #{now},
                `updated_by` = #{operatorId},
                `trace_id` = #{traceId},
                `is_deleted` = 0
            """)
    int upsertSlot(@Param("orderId") long orderId, @Param("slot") int slot,
                   @Param("photoCount") int photoCount, @Param("remark") String remark,
                   @Param("operatorId") long operatorId, @Param("traceId") String traceId,
                   @Param("now") LocalDateTime now);

    /** 清掉一个槽位的照片行（整体替换的第一步）。 */
    @Delete("""
            DELETE FROM `order_photo`
             WHERE `slot_id` = #{slotId}
            """)
    int deletePhotosOfSlot(@Param("slotId") long slotId);

    /** 该订单已挂的照片 id（校验「这张图是不是已经在本单里」用）。 */
    @Select("""
            SELECT `file_id` FROM `order_photo`
             WHERE `order_id` = #{orderId} AND `is_deleted` = 0
            """)
    List<Long> fileIdsOfOrder(@Param("orderId") long orderId);

    /** 这张图挂在哪个订单上（为 null 表示还没挂过）；用于「已挂到别的订单 → 40400」。 */
    @Select("""
            SELECT `order_id` FROM `order_photo`
             WHERE `file_id` = #{fileId} AND `is_deleted` = 0
             LIMIT 1
            """)
    Long findOrderIdByFile(@Param("fileId") long fileId);
}
