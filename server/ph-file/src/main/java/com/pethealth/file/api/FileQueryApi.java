package com.pethealth.file.api;

import com.pethealth.api.app.FileView;

import java.util.Collection;
import java.util.Map;

/**
 * 文件模块对外暴露的**按 id 取元数据**接口（ADR-0006：别的模块不碰 {@code file_object} 表）。
 *
 * <p>与 {@link FileUrlApi} 的区别在**归属口径**：
 *
 * <ul>
 *   <li>{@link FileUrlApi} 是「**我自己的**文件」——调用方传的是用户 id，它按归属过滤，
 *       给的是这个用户能看的地址（ph-ai 用它取用户上传的图）；
 *   <li>本接口按 **id 集合**取，不按上传者过滤。它服务的是**业务归属在别处**的场景：
 *       订单的三道照片墙（ADR-0040 第四节）由服务者在履约中上传，而照片要同时呈现给
 *       门店（服务者后台）与宠物主人（C 端）——两个身份都不是上传者。此时
 *       「这张照片该不该给这个人看」已经由业务侧判过（ph-order 先校验订单归属与订单状态），
 *       文件模块只负责把元数据与签名读地址给出来。
 * </ul>
 *
 * <p><b>这道信任边界要说清楚</b>：调用方**必须先做完自己的归属校验**再调这里。
 * 因此本接口只给元数据与短时读地址，**不做任何批量删除、状态变更**——能写的能力一概没有。
 * 上传者归属仍然只由 {@link FileUrlApi} / C 端的 {@code /files/{id}} 守。
 *
 * <p>实现类在 {@code com.pethealth.file.service}，由 Spring 注入。
 */
public interface FileQueryApi {

    /**
     * 按 id 批量取文件元数据（含签名读地址与缩略图地址）。
     *
     * <p>只返回**已落定、未删除的原图**：没上传完的（{@code status=PENDING}）、已删的、
     * 以及缩略图行（它是原图的派生物，由 {@code thumb_url} 承载）都不在返回里。
     * 查不到的 id 不会出现在 Map 里——调用方按「这个 id 不可用」处理，
     * 不要假设每个入参都有值。
     *
     * @param fileIds 文件 id 集合；空集合不查库
     * @return id → 文件元数据
     */
    Map<Long, FileView> files(Collection<Long> fileIds);

    /**
     * 校验这批 id **都是该用户上传、用途为 {@code bizType}、且已落定**的原图；有一条不满足就 40001。
     *
     * <p>它服务的是「表单里带上来的 file_id 得是提交人自己的、且用途对得上」这类校验
     * （ADR-0053 第二节：归属校验的责任在调用方，但**判定归属所需的事实**由文件域给，
     * 调用方不该去推断）。典型场景是资质材料：图只能来自提交人自己那次
     * {@code biz_type=qualification} 的上传——否则一个 ID 就能把别人的证件照挂到自己的材料上，
     * 而审核员看到的是「一张与材料无关的图」。
     *
     * <p>报错**不区分**「不存在」「是别人的」「用途不对」「还没传完」：四种对调用方都是同一件事
     * （这个 id 不能用），分开说等于告诉提交人「这个 id 存在，只是不是你的」。
     *
     * @param fileIds 待校验的 id；重复值只算一次，{@code null} 会被跳过
     * @throws com.pethealth.common.error.BusinessException 40001
     */
    void requireOwned(long userId, Collection<Long> fileIds, String bizType);
}
