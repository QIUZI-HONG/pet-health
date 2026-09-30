package com.pethealth.provider.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 服务者（门店 / 机构），表 {@code provider}——交付文档 7.2 的 {@code merchant}（文档用词），
 * 按 CONTEXT.md 的术语纪律改名。
 *
 * <p>{@code phoneEnc} / {@code phoneHash} 是门店联系电话的密文与 HMAC 查找列（ADR-0013）：
 * 明文只在内存里出现，接口一律返回脱敏值。
 *
 * <p>{@code businessHours} 是 JSON 文本（一天一行，含开始与结束时间）。存 JSON 而不是拆表：
 * 读法只有「按服务者取整周」一种，没有按天范围查询的需求；将来预约时段校验要按天筛时再拆表。
 *
 * <p>{@code status} 四态（0 待审核 / 1 正常 / 2 驳回 / 3 冻结）——「驳回」发生在入驻阶段，
 * 「冻结」发生在经营阶段（清退 = 冻结，由超级管理员执行），两者的后续处置完全不同，
 * 合成一个状态会让「补交材料重提」与「被清退」在数据上长得一样。
 */
@TableName("provider")
public class Provider extends BaseEntity {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_APPROVED = 1;
    public static final int STATUS_REJECTED = 2;
    public static final int STATUS_FROZEN = 3;

    /**
     * 分类码 → 中文名（交付文档 5.2 的六大类）。
     *
     * <p><b>为什么放在领域而不是各处拼</b>：三端都要显示这个名字（C 端列表、服务者后台、
     * 运营后台），各写一份 switch 就会出现「一端叫洗护、一端叫洗护美容」。口径与
     * {@code ServiceCategoryView} 里那句分类清单同文：医院 / 洗护美容 / 训犬 / 寄养上门 /
     * 食品用品 / 间接服务。
     *
     * <p>认不出的码返回 {@code null} 而不是「其它」：码是库里来的，认不出说明数据或代码有一处
     * 旧了，静默显示成「其它」会让这种不一致永远没人发现。
     */
    public static String typeName(Integer type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case 1 -> "医院";
            case 2 -> "洗护美容";
            case 3 -> "训犬";
            case 4 -> "寄养上门";
            case 5 -> "食品用品";
            case 6 -> "间接服务";
            default -> null;
        };
    }

    private String name;
    private Integer type;
    private Integer category;
    private String logo;
    /**
     * 门店简介。空串表示清空，所以要允许 null 写进 UPDATE——否则「删掉简介」只会改到内存里。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String intro;
    private String address;
    /**
     * 经纬度。可为空（没有地理编码就不给），空值也要能写回——否则「清掉坐标」会静默失败。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal lng;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal lat;
    private String phoneEnc;
    private String phoneHash;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String businessHours;
    private Integer status;
    private Integer level;
    /**
     * AI 推荐优先级（1 最高 / 2 较高 / 3 普通），V45 起由月度考核按等级档位写回
     * （{@code assessment_level_rule.recommend_priority}，运营可改档位映射）。
     *
     * <p>为什么在 {@code provider} 上再存一份、而不是读考核分表：排序在浏览查询的 ORDER BY 里，
     * 每个请求 join 一张按月增长的宽表不划算；而这一列与 {@code level} / {@code monthlyScore}
     * 是同一批写回、同一套「不回退到更旧账期」的规则，多存一列的代价很小。
     *
     * <p>**非空、默认 3（普通）**：与 {@code level} 默认 1（基础档）同档。不用 NULL 表示
     * 「还没算过考核」——那一列的 NULL 在 MySQL 的升序里排最前，会把新店顶到最前面，
     * 而「没有数据」不该长得像「数据很好」。
     */
    private Integer recommendPriority;
    private String regionCode;
    private BigDecimal monthlyScore;
    private BigDecimal rating;
    private LocalDateTime approvedAt;

    public boolean isApproved() {
        return status != null && status == STATUS_APPROVED;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getType() {
        return type;
    }

    public void setType(Integer type) {
        this.type = type;
    }

    public Integer getCategory() {
        return category;
    }

    public void setCategory(Integer category) {
        this.category = category;
    }

    public String getLogo() {
        return logo;
    }

    public void setLogo(String logo) {
        this.logo = logo;
    }

    public String getIntro() {
        return intro;
    }

    public void setIntro(String intro) {
        this.intro = intro;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public BigDecimal getLng() {
        return lng;
    }

    public void setLng(BigDecimal lng) {
        this.lng = lng;
    }

    public BigDecimal getLat() {
        return lat;
    }

    public void setLat(BigDecimal lat) {
        this.lat = lat;
    }

    public String getPhoneEnc() {
        return phoneEnc;
    }

    public void setPhoneEnc(String phoneEnc) {
        this.phoneEnc = phoneEnc;
    }

    public String getPhoneHash() {
        return phoneHash;
    }

    public void setPhoneHash(String phoneHash) {
        this.phoneHash = phoneHash;
    }

    public String getBusinessHours() {
        return businessHours;
    }

    public void setBusinessHours(String businessHours) {
        this.businessHours = businessHours;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getLevel() {
        return level;
    }

    public void setLevel(Integer level) {
        this.level = level;
    }

    public Integer getRecommendPriority() {
        return recommendPriority;
    }

    public void setRecommendPriority(Integer recommendPriority) {
        this.recommendPriority = recommendPriority;
    }

    public String getRegionCode() {
        return regionCode;
    }

    public void setRegionCode(String regionCode) {
        this.regionCode = regionCode;
    }

    public BigDecimal getMonthlyScore() {
        return monthlyScore;
    }

    public void setMonthlyScore(BigDecimal monthlyScore) {
        this.monthlyScore = monthlyScore;
    }

    public BigDecimal getRating() {
        return rating;
    }

    public void setRating(BigDecimal rating) {
        this.rating = rating;
    }

    public LocalDateTime getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(LocalDateTime approvedAt) {
        this.approvedAt = approvedAt;
    }
}
