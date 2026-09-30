package com.pethealth.provider.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pethealth.api.provider.ProviderQualificationRequest;
import com.pethealth.common.crypto.FieldCipher;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.util.Text;
import com.pethealth.file.api.FileQueryApi;
import com.pethealth.provider.domain.Provider;
import com.pethealth.provider.domain.ProviderQualification;
import com.pethealth.provider.mapper.ProviderMapper;
import com.pethealth.provider.mapper.ProviderQualificationMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 资质材料的公共校验：**同一营业执照 / 身份证只允许一个「有效状态」的服务者**
 * （2026-09-29 项目所有者拍板）。
 *
 * <p>两条边界是刻意的：
 *
 * <ul>
 *   <li><b>被驳回或已清退的记录不占名额</b>：否则一个人被误拒一次就再也开不了店——
 *       而纠正一次错误审核本来就该是可能的；
 *   <li><b>只认营业执照与身份证</b>：执业许可证、健康证是按人或按门店发的，
 *       同一张证出现在两家机构是正常的（多点执业），拿它做唯一性会误伤。
 * </ul>
 *
 * <p>比对走 {@code cert_no_hash}：密文没有保序性，等值比较只能用 HMAC 列（ADR-0013）。
 * 数据库上不建唯一索引——「部分唯一」（只对有效状态生效）MySQL 没有，
 * 硬建全列唯一会把「被驳回的那份也不能再提交」变成事实，与上面的边界冲突。
 */
@Component
public class QualificationGuard {

    /** 资质材料图的用途标记（与 {@code ProviderFileController} 的入口白名单、文件域的取值表同名同值）。 */
    private static final String BIZ_TYPE_QUALIFICATION = "qualification";

    private final ProviderQualificationMapper qualificationMapper;
    private final ProviderMapper providerMapper;
    private final FieldCipher cipher;
    private final FileQueryApi fileQueryApi;

    public QualificationGuard(ProviderQualificationMapper qualificationMapper, ProviderMapper providerMapper,
                              FieldCipher cipher, FileQueryApi fileQueryApi) {
        this.qualificationMapper = qualificationMapper;
        this.providerMapper = providerMapper;
        this.cipher = cipher;
        this.fileQueryApi = fileQueryApi;
    }

    /**
     * @param excludingProviderId 自己（补交材料时不该与自己冲突），没有则为 {@code null}
     */
    public void requireUniqueCertificates(List<ProviderQualificationRequest> requests, Long excludingProviderId) {
        for (ProviderQualificationRequest request : requests) {
            int type = request.type() == null ? 0 : request.type();
            if (type != ProviderQualification.TYPE_BUSINESS_LICENSE
                    && type != ProviderQualification.TYPE_ID_CARD) {
                continue;
            }
            String certNo = Text.trimToNull(request.certNo());
            if (certNo == null) {
                // 证件号没给就没法查重。企业执照是必填的话，审核时会被驳回——
                // 「哪些材料必须带证件号」的清单待甲方确认（ADR-0035 待澄清）
                continue;
            }
            String hash = cipher.lookupHash(certNo);
            for (ProviderQualification conflict : qualificationMapper.selectList(
                    Wrappers.<ProviderQualification>lambdaQuery()
                            .eq(ProviderQualification::getCertNoHash, hash))) {
                if (excludingProviderId != null && excludingProviderId.equals(conflict.getProviderId())) {
                    continue;
                }
                if (!conflict.blocksUniqueness()) {
                    continue;
                }
                Provider owner = providerMapper.selectById(conflict.getProviderId());
                if (owner == null || !blocksUniqueness(owner)) {
                    continue;
                }
                throw BusinessException.conflict(
                        (type == ProviderQualification.TYPE_BUSINESS_LICENSE ? "该营业执照" : "该身份证")
                                + "已用于另一个服务者的入驻，请勿重复提交");
            }
        }
    }

    /** 服务者是否「占用」同证件的名额：只有待审核与正常占，被驳回或已冻结的不占。 */
    private static boolean blocksUniqueness(Provider provider) {
        int status = provider.getStatus() == null ? -1 : provider.getStatus();
        return status == Provider.STATUS_PENDING || status == Provider.STATUS_APPROVED;
    }

    /**
     * 每一份材料都必须带图（ADR-0053），而且**图得是提交人自己为这个用途传的**。
     *
     * <p>接口层已经用 {@code @NotNull} 拦了一道，这里是**领域里的最后一道**——
     * 与订单评分的处理同一个道理：方法是不是只被 Controller 调用，从签名上看不出来，
     * 领域约束就不该建立在「调用方一定传对」之上。
     *
     * <p>归属与用途由文件域判定（{@link FileQueryApi#requireOwned}，ADR-0053 第二节）：
     * 只校「非空」时，一个 file_id 就能把**别人的**证件照挂到自己的材料上，
     * 而审核员看到的是「一张与材料无关的图」——他会当成材料不合格驳回，服务者则完全不知道为什么。
     *
     * <p>报错文案带上材料名（有名称用名称，没有就用类型名），否则一次提交十份材料时，
     * 服务者只知道「有一份没传图」，得自己一份份找。
     */
    public void requireImages(long userId, List<ProviderQualificationRequest> requests) {
        for (ProviderQualificationRequest request : requests) {
            if (request.fileId() == null) {
                throw BusinessException.paramInvalid("请先上传「" + describe(request) + "」的材料图片");
            }
        }
        fileQueryApi.requireOwned(userId,
                requests.stream().map(ProviderQualificationRequest::fileId).toList(),
                BIZ_TYPE_QUALIFICATION);
    }

    private static String describe(ProviderQualificationRequest request) {
        String name = Text.trimToNull(request.name());
        if (name != null) {
            return name;
        }
        String typeName = ProviderQualification.typeName(request.type());
        return typeName == null ? "资质材料" : typeName;
    }
}
