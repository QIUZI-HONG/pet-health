package com.pethealth.provider.domain;

/**
 * 按联盟维度分组的门店数（不是表）。
 *
 * <p>给运营看「停用这一档会影响多少家」用。口径是**未软删的全部门店**，
 * 不按状态过滤：冻结的门店也仍然归属在这一档上，停用它同样会影响那家店的展示。
 */
public class AllianceCategoryStat {

    private Integer categoryId;
    private Long providerCount;

    public Integer getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Integer categoryId) {
        this.categoryId = categoryId;
    }

    public Long getProviderCount() {
        return providerCount;
    }

    public void setProviderCount(Long providerCount) {
        this.providerCount = providerCount;
    }
}
