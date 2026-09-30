package com.pethealth.order.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

/**
 * 照片墙里的一张照片（表 {@code order_photo}，迁移 V30）。
 *
 * <p>{@code fileId} 指向文件域的 {@code file_object}（{@code biz_type=care}，ADR-0020 的直传）：
 * 字节不经业务接口，这里只挂关系。表上的唯一键 {@code uk_file_id} 让
 * 「同一张照片挂到两个订单上」在数据库层面不可能发生——服务留痕照片是**这一单**的证据，
 * 复用到别处就说不清它到底证明了什么。
 *
 * <p>照片**不进用户的档案照片墙**（ADR-0040 第四节 / ADR-0030）：它归属订单，
 * 归属判据是订单，不是上传者。
 */
@TableName("order_photo")
public class OrderPhoto extends BaseEntity {

    private Long slotId;
    private Long orderId;
    private Long fileId;
    private Integer sortOrder;

    public Long getSlotId() {
        return slotId;
    }

    public void setSlotId(Long slotId) {
        this.slotId = slotId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Long getFileId() {
        return fileId;
    }

    public void setFileId(Long fileId) {
        this.fileId = fileId;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }
}
