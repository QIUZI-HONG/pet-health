package com.pethealth.order.service;

import com.pethealth.api.app.FileView;
import com.pethealth.api.order.OrderPhotoSlotRequest;
import com.pethealth.api.order.OrderPhotoSlotView;
import com.pethealth.api.order.OrderPhotoWallView;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.time.AppTime;
import com.pethealth.common.trace.TraceIds;
import com.pethealth.file.api.FileQueryApi;
import com.pethealth.order.domain.OrderPhoto;
import com.pethealth.order.domain.OrderPhotoSlot;
import com.pethealth.order.mapper.OrderPhotoMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 三道照片墙（ADR-0040 第四节 / 切片 #107）：**接宠检查 / 服务防护 / 取宠对比，三者缺一不可**。
 *
 * <p>本类只做「存」与「读」两件事，**状态与归属判定在 {@code OrderService}**：
 * 照片墙能不能写（订单是不是「履约中」）、这张订单是不是本店的，都是订单侧的事；
 * 放在这里会让「谁能改照片」出现第二个判据。
 *
 * <p>三条它自己负责的不变量：
 *
 * <ol>
 *   <li><b>整体替换</b>：PUT 的是槽位的**全量集合**（传空数组 = 清空），存储上就是
 *       「删该槽位的行 → 按序插入新行」。照片行只承载「哪张图挂在哪一道上」，
 *       删的是关系不是证据——图片本体在文件域（ADR-0020 的直传）；
 *   <li><b>计数是服务端记的</b>：{@code order_photo_slot.photo_count} 与同一事务里写入的行数
 *       永远一致。报工门禁读的是这个计数，不是前端传来的数组长度；
 *   <li><b>一张照片只属于一个订单、一个槽位</b>：表上的 {@code uk_file_id} 让重复挂载
 *       在数据库层面不可能发生；应用侧再判一次是为了给出**明确的答复**（40400 / 40001），
 *       而不是一个 50000。
 * </ol>
 *
 * <p>照片的可见性由订单归属决定（ADR-0040）：字节经 {@code /api/v1/open/files/**} 的签名地址读，
 * 元数据由 {@link FileQueryApi} 给——文件模块不按上传者过滤，因为看照片的人（门店与宠物主人）
 * 都不是上传者，而「该不该给这个人看」已经在订单侧判过。
 */
@Service
public class OrderPhotoWallService {

    /** care 是文件域登记的唯一服务留痕用途（{@code FileService.ALLOWED_BIZ_TYPES}）。 */
    private static final String BIZ_TYPE_CARE = "care";

    private final OrderPhotoMapper photoMapper;
    private final FileQueryApi fileQueryApi;

    public OrderPhotoWallService(OrderPhotoMapper photoMapper, FileQueryApi fileQueryApi) {
        this.photoMapper = photoMapper;
        this.fileQueryApi = fileQueryApi;
    }

    // ---------------------------------------------------------------- 读

    /**
     * 一面完整的照片墙（固定三个槽位，未上传的也在、{@code satisfied=false}）。
     *
     * <p>{@code reportable} 与 {@code missingSlots} 由这里算出来，**前端不要自己判**：
     * 「三个槽位各至少一张才允许报工」是服务端的硬约束（ADR-0040），
     * 前端把按钮禁掉不算数——判据只能有一处，否则「按钮可点、接口拒绝」或反过来的缝迟早出现。
     */
    public OrderPhotoWallView viewOf(long orderId) {
        List<OrderPhotoSlot> rows = photoMapper.slotsOfOrder(orderId);
        Map<Integer, OrderPhotoSlot> bySlot = new HashMap<>();
        for (OrderPhotoSlot row : rows) {
            bySlot.put(row.getSlot(), row);
        }
        Map<Integer, List<Long>> fileIdsBySlot = fileIdsBySlot(orderId);
        Map<Long, FileView> files = fileQueryApi.files(allFileIds(fileIdsBySlot));

        List<OrderPhotoSlotView> slots = new ArrayList<>(OrderPhotoSlot.allSlots().length);
        List<Integer> missing = new ArrayList<>();
        for (int slot : OrderPhotoSlot.allSlots()) {
            OrderPhotoSlot row = bySlot.get(slot);
            boolean satisfied = row != null && row.isSatisfied();
            if (!satisfied) {
                missing.add(slot);
            }
            slots.add(new OrderPhotoSlotView(
                    slot,
                    OrderPhotoSlot.slotName(slot),
                    photos(fileIdsBySlot.get(slot), files),
                    row == null ? null : row.getRemark(),
                    satisfied,
                    row == null ? null : row.getUpdatedAt()));
        }
        return new OrderPhotoWallView(slots, missing.isEmpty(), missing);
    }

    /**
     * 还缺哪几道——报工被拒时要**点名**（契约：「不能只说报工失败」）。
     *
     * <p>查库而不是复用 {@link #viewOf} 的结果：报工是写路径，不该为了判一下就多拉一批
     * 文件元数据与签名地址（每张图一次签名）。
     */
    public List<Integer> missingSlots(long orderId) {
        Map<Integer, OrderPhotoSlot> bySlot = new HashMap<>();
        for (OrderPhotoSlot row : photoMapper.slotsOfOrder(orderId)) {
            bySlot.put(row.getSlot(), row);
        }
        List<Integer> missing = new ArrayList<>(3);
        for (int slot : OrderPhotoSlot.allSlots()) {
            OrderPhotoSlot row = bySlot.get(slot);
            if (row == null || !row.isSatisfied()) {
                missing.add(slot);
            }
        }
        return missing;
    }

    // ---------------------------------------------------------------- 写

    /**
     * 保存一个槽位（**整体替换**）：{@code fileIds} 是这一槽位的全量集合。
     *
     * <p><b>顺序是刻意的：先清掉本槽位原来的照片行，再判这批新 id 能不能挂。</b>
     * 反过来（先判后清）会把「把这一槽位原来的照片重新提交一遍」判成
     * 「这张图已经挂在本单的另一个槽位」——用户只是点了一次保存，却得到一个看不懂的冲突。
     * 清出去的行与后面的校验在**同一个事务**里：校验没过就一起回滚，旧照片不会被误删。
     *
     * @throws BusinessException
     *         <ul>
     *           <li><b>40001</b>：超过 9 张、同一个 id 传了两次、或这张图已经挂在本单的另一个槽位上；
     *           <li><b>40400</b>：{@code file_id} 不存在 / 没落定 / 不是 care 用途 /
     *               已挂在**别的订单**上 / 是别的宠物的照片。
     *         </ul>
     */
    @Transactional
    public void replace(long orderId, long orderPetId, int slot, OrderPhotoSlotRequest request) {
        List<Long> fileIds = request.fileIds() == null ? List.of() : request.fileIds().stream()
                .filter(java.util.Objects::nonNull).toList();
        if (fileIds.size() > OrderPhotoSlot.MAX_PHOTOS_PER_SLOT) {
            throw BusinessException.paramInvalid("单个槽位最多 " + OrderPhotoSlot.MAX_PHOTOS_PER_SLOT + " 张照片");
        }
        if (new HashSet<>(fileIds).size() != fileIds.size()) {
            throw BusinessException.paramInvalid("同一个照片不能在一个槽位里传两次");
        }

        long operatorId = TraceIds.currentOperatorId();
        String traceId = TraceIds.currentTraceId();
        long slotId = photoMapper.slotsOfOrder(orderId).stream()
                .filter(row -> row.getSlot() == slot)
                .map(OrderPhotoSlot::getId)
                .findFirst()
                .orElse(0L);
        if (slotId != 0L) {
            photoMapper.deletePhotosOfSlot(slotId);
        }
        requireUsablePhotos(orderId, orderPetId, fileIds);

        photoMapper.upsertSlot(orderId, slot, fileIds.size(), request.remark(), operatorId, traceId, AppTime.now());
        if (fileIds.isEmpty()) {
            return;
        }
        // 槽位行的 id 要拿回来挂照片：upsert 之后按 (order_id, slot) 查一下（唯一键保证只有一行）
        long persistedSlotId = photoMapper.slotsOfOrder(orderId).stream()
                .filter(row -> row.getSlot() == slot)
                .map(OrderPhotoSlot::getId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("槽位行刚写完就查不到：" + orderId + "/" + slot));
        for (int index = 0; index < fileIds.size(); index++) {
            OrderPhoto photo = new OrderPhoto();
            photo.setSlotId(persistedSlotId);
            photo.setOrderId(orderId);
            photo.setFileId(fileIds.get(index));
            photo.setSortOrder(index);
            photoMapper.insert(photo);
        }
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 每个 id 都必须是一张**能挂到这一单上**的 care 照片。
     *
     * <p>五条判据，前四条来自契约（「file_id 不存在、不属于本店与本订单 → 40400」），
     * 第五条是本实现补的（见 ADR-0048）：
     *
     * <ol>
     *   <li>文件存在且已落定（{@link FileQueryApi} 只返回落定的原图）；
     *   <li>用途是 {@code care}——别的用途（打卡 / 证件 / 咨询）是用户的私有照片，
     *       挂进服务留痕会把用户没打算给门店看的东西暴露出去；
     *   <li>没挂在别的订单上（同一个 file_id 出现在两张单上，谁也说不清它证明了什么）；
     *   <li>没挂在本单的**另一个**槽位（它是「一张图一道证据」，不是可以复用的素材）。
     *       本槽位自己的旧照片已经被 {@code replace} 先清掉了，所以「重新提交同一组照片」不会撞到这里；
     *   <li><b>是这只宠物的照片</b>：{@code file_object.pet_id} 与订单的宠物不一致时拒绝——
     *       「不属于本订单」最实在的一种，拍错猫狗的照片不是这一单的证据。
     *       照片没登记宠物（pet_id 为空）时放行：那是账号级的图，本模块判不了，
     *       不该把「判不了」当成「不合法」。
     * </ol>
     */
    private void requireUsablePhotos(long orderId, long orderPetId, List<Long> fileIds) {
        if (fileIds.isEmpty()) {
            return;
        }
        Map<Long, FileView> files = fileQueryApi.files(fileIds);
        for (Long fileId : fileIds) {
            FileView file = files.get(fileId);
            if (file == null) {
                throw BusinessException.notFound("照片不存在或还没上传完成：" + fileId);
            }
            if (!BIZ_TYPE_CARE.equals(file.bizType())) {
                throw BusinessException.notFound("照片用途不对，服务留痕只认 care：" + fileId);
            }
            Long owner = photoMapper.findOrderIdByFile(fileId);
            if (owner == null) {
                // 没挂过：再判宠物是否对得上
                if (file.petId() != null && orderPetId != 0L && file.petId() != orderPetId) {
                    throw BusinessException.notFound("照片不是这只宠物的：" + fileId);
                }
                continue;
            }
            if (owner == orderId) {
                throw BusinessException.paramInvalid("这张照片已经挂在本订单的另一个槽位上：" + fileId);
            }
            throw BusinessException.notFound("照片已经挂在别的订单上：" + fileId);
        }
    }

    /** 订单下每个槽位的照片 id（按 sort_order 升序）。 */
    private Map<Integer, List<Long>> fileIdsBySlot(long orderId) {
        Map<Long, Integer> slotNoById = new HashMap<>();
        for (OrderPhotoSlot row : photoMapper.slotsOfOrder(orderId)) {
            slotNoById.put(row.getId(), row.getSlot());
        }
        List<OrderPhoto> photos = photoMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OrderPhoto>()
                        .eq(OrderPhoto::getOrderId, orderId)
                        .orderByAsc(OrderPhoto::getSortOrder)
                        .orderByAsc(OrderPhoto::getId));
        Map<Integer, List<Long>> bySlot = new LinkedHashMap<>();
        for (OrderPhoto photo : photos) {
            Integer slot = slotNoById.get(photo.getSlotId());
            if (slot == null) {
                continue;
            }
            bySlot.computeIfAbsent(slot, key -> new ArrayList<>()).add(photo.getFileId());
        }
        return bySlot;
    }

    private Set<Long> allFileIds(Map<Integer, List<Long>> bySlot) {
        Set<Long> ids = new HashSet<>();
        bySlot.values().forEach(ids::addAll);
        return ids;
    }

    /** id 列表 → 元数据列表（顺序按 id 列表；查不到的跳过——不能因为一张图挂了就让整墙失败）。 */
    private List<FileView> photos(List<Long> fileIds, Map<Long, FileView> files) {
        if (fileIds == null || fileIds.isEmpty()) {
            return List.of();
        }
        List<FileView> views = new ArrayList<>(fileIds.size());
        for (Long fileId : fileIds) {
            FileView file = files.get(fileId);
            if (file != null) {
                views.add(file);
            }
        }
        return views;
    }
}
