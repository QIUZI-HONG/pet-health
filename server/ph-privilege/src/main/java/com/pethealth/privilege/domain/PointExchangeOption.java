package com.pethealth.privilege.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

/**
 * 积分兑换档位，表 {@code point_exchange_option}——**只兑平台补贴券**。
 *
 * <p>为什么限定平台补贴券（ADR-0038 第四节）：兑换消耗的是平台的钱（成本归平台），
 * 不消耗服务者的贡献额度。如果允许兑服务者出的券，等于把兑换成本转嫁给服务者，
 * 直接抵消它的出券意愿——而那正是券池赖以成立的东西。
 *
 * <p>校验落在服务层（创建 / 修改档位时检查模板的 {@code costBearer = 2} 且启用），
 * 数据库不写跨表约束：本项目的引用一律是逻辑引用，不建物理外键。
 */
@TableName("point_exchange_option")
public class PointExchangeOption extends BaseEntity {

    public static final int STATUS_ENABLED = 1;
    public static final int STATUS_DISABLED = 0;

    private String name;
    private Integer pointsCost;
    private Long couponTemplateId;
    private Integer sortOrder;
    private Integer status;

    public boolean isEnabled() {
        return status != null && status == STATUS_ENABLED;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getPointsCost() {
        return pointsCost;
    }

    public void setPointsCost(Integer pointsCost) {
        this.pointsCost = pointsCost;
    }

    public Long getCouponTemplateId() {
        return couponTemplateId;
    }

    public void setCouponTemplateId(Long couponTemplateId) {
        this.couponTemplateId = couponTemplateId;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
