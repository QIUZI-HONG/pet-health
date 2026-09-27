package com.pethealth.record.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 宠物档案（切片 #94：建档 / 多宠 / 软删除与恢复）。
 *
 * <p>字段沿用交付文档 7.2 的语义：{@code species} 1 犬 2 猫、{@code gender} 0 未知 1 公 2 母。
 * {@code deletedAt} 是软删除时间，用于「30 天内可恢复」这个窗口——软删标记本身在 {@link BaseEntity#getIsDeleted()}。
 */
@TableName("pet")
public class Pet extends BaseEntity {

    public static final int SPECIES_DOG = 1;
    public static final int SPECIES_CAT = 2;

    private Long userId;
    private String name;
    private Integer species;

    /**
     * 品种。传空串表示清空，所以要允许 null 写进 UPDATE——否则「清空品种」只会改到内存里，
     * 响应说 null、库里还是旧值，下一次读取又变回来。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String breed;
    private Integer gender;
    private LocalDate birthday;
    private BigDecimal weight;

    /** 头像 URL，同样是「空串 = 清空」。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String avatar;
    private Integer isSterilized;
    private Integer isChronic;
    /**
     * 慢病描述。**必须显式声明「null 也要写进 UPDATE」**：MyBatis-Plus 默认忽略 null 字段，
     * 于是「取消慢病标记时把描述清掉」这条业务规则会静默失效——库里留着一条没人认领的病史。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String chronicDesc;
    private LocalDateTime deletedAt;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getSpecies() {
        return species;
    }

    public void setSpecies(Integer species) {
        this.species = species;
    }

    public String getBreed() {
        return breed;
    }

    public void setBreed(String breed) {
        this.breed = breed;
    }

    public Integer getGender() {
        return gender;
    }

    public void setGender(Integer gender) {
        this.gender = gender;
    }

    public LocalDate getBirthday() {
        return birthday;
    }

    public void setBirthday(LocalDate birthday) {
        this.birthday = birthday;
    }

    public BigDecimal getWeight() {
        return weight;
    }

    public void setWeight(BigDecimal weight) {
        this.weight = weight;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public Integer getIsSterilized() {
        return isSterilized;
    }

    public void setIsSterilized(Integer isSterilized) {
        this.isSterilized = isSterilized;
    }

    public Integer getIsChronic() {
        return isChronic;
    }

    public void setIsChronic(Integer isChronic) {
        this.isChronic = isChronic;
    }

    public String getChronicDesc() {
        return chronicDesc;
    }

    public void setChronicDesc(String chronicDesc) {
        this.chronicDesc = chronicDesc;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    public boolean isSterilizedFlag() {
        return isSterilized != null && isSterilized == 1;
    }

    public boolean isChronicFlag() {
        return isChronic != null && isChronic == 1;
    }
}
