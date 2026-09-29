package com.pethealth.file.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pethealth.api.app.FilePresignRequest;
import com.pethealth.api.app.FilePresignView;
import com.pethealth.api.app.FileView;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.time.AppTime;
import com.pethealth.common.trace.TraceIds;
import com.pethealth.file.api.FileUrlApi;
import com.pethealth.file.domain.FileObject;
import com.pethealth.file.mapper.FileObjectMapper;
import com.pethealth.file.storage.FileStorage;
import com.pethealth.file.storage.FileStorageProperties;
import com.pethealth.file.storage.ImageSniffer;
import com.pethealth.file.storage.UploadTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 文件能力（切片 #95，ADR-0020）。
 *
 * <p>生命周期三步，与对象存储的形态对齐：
 *
 * <ol>
 *   <li>{@link #presign} 建**待传行**并签发上传凭证；
 *   <li>{@link #store} 落定直传的字节（local 驱动直传即落定；cos 驱动的回调最终也走这个方法）；
 *   <li>{@link #list} / {@link #read} 按归属读回，读地址也是签名的。
 * </ol>
 *
 * <p>越权一律 40400（docs/conventions.md）：照片里是宠物与证件，不能用「这个 id 存不存在」回答探测者。
 */
@Service
public class FileService implements FileUrlApi {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    /** 允许的业务场景。收窄取值是为了让「照片到底挂在哪」可查，而不是让任何模块随手写个字符串进来。 */
    private static final Set<String> ALLOWED_BIZ_TYPES =
            Set.of("checkin", "epidemic", "profile", "ai_consult", "care");

    private static final Set<String> ALLOWED_ROLES =
            Set.of(FileObject.ROLE_ORIGINAL, FileObject.ROLE_CLOSEUP);

    /** 缩略图宽度：够列表与小图预览，且不会把「看不清细节」的锅甩给缩略图（要看细节点原图）。 */
    private static final int THUMB_WIDTH = 480;

    /** 交付文档 13.2：JPEG 压缩质量 0.8。 */
    private static final float JPEG_QUALITY = 0.8f;

    private static final DateTimeFormatter MONTH_PATH = DateTimeFormatter.ofPattern("yyyyMM", Locale.ROOT);

    private final FileObjectMapper fileMapper;
    private final FileStorage storage;
    private final FileStorageProperties properties;
    private final UploadTokens tokens;

    public FileService(FileObjectMapper fileMapper, FileStorage storage,
                       FileStorageProperties properties, UploadTokens tokens) {
        this.fileMapper = fileMapper;
        this.storage = storage;
        this.properties = properties;
        this.tokens = tokens;
    }

    // ---------------------------------------------------------------- 取凭证

    @Transactional
    public List<FilePresignView> presign(long userId, FilePresignRequest request) {
        String bizType = request.bizType().trim();
        if (!ALLOWED_BIZ_TYPES.contains(bizType)) {
            throw BusinessException.paramInvalid("不支持的文件用途：" + bizType);
        }
        List<FilePresignRequest.Item> items = request.items();
        if (items.size() > properties.maxCount()) {
            throw BusinessException.paramInvalid("一次最多 " + properties.maxCount() + " 张");
        }

        LocalDateTime expiresAt = AppTime.now().plus(properties.presignTtl());
        List<FilePresignView> views = new ArrayList<>(items.size());
        for (FilePresignRequest.Item item : items) {
            String role = (item.role() == null || item.role().isBlank())
                    ? FileObject.ROLE_ORIGINAL : item.role().trim();
            if (!ALLOWED_ROLES.contains(role)) {
                throw BusinessException.paramInvalid("不支持的文件用途标记：" + role);
            }
            // 声明的类型与体积只用于提前拦：真正生效的判定在 store（魔数 + 实际字节数）
            if (item.mime() != null && !item.mime().isBlank()
                    && !ImageSniffer.MIME_JPEG.equals(item.mime()) && !ImageSniffer.MIME_PNG.equals(item.mime())) {
                throw BusinessException.paramInvalid("只支持 JPEG 与 PNG");
            }
            if (item.sizeBytes() != null && item.sizeBytes() > properties.maxBytes()) {
                throw BusinessException.paramInvalid("单张图片不能超过 " + properties.maxBytes() / 1024 / 1024 + "MB");
            }

            FileObject file = new FileObject();
            file.setOwnerUserId(userId);
            file.setPetId(request.petId());
            file.setBizType(bizType);
            file.setRole(role);
            file.setMime(item.mime() == null || item.mime().isBlank() ? ImageSniffer.MIME_JPEG : item.mime());
            file.setSizeBytes(item.sizeBytes() == null ? 0L : item.sizeBytes());
            file.setStorageDriver(storage.driver());
            file.setStatus(FileObject.STATUS_PENDING);
            file.setExpiresAt(expiresAt);
            // 对象键要先有 id：先插入拿到自增 id，再回填 storageKey（同一事务内，外部看不到中间态）
            file.setStorageKey("pending-" + UUID.randomUUID());
            fileMapper.insert(file);
            file.setStorageKey(storageKeyOf(userId, file.getId(), ImageSniffer.extensionOf(file.getMime())));
            fileMapper.updateById(file);

            String token = tokens.issue(UploadTokens.PURPOSE_UPLOAD, file.getId(), userId,
                    expiresAt.atZone(AppTime.ZONE).toInstant());
            views.add(new FilePresignView(file.getId(), storage.uploadUrl(file.getId(), token),
                    expiresAt, properties.maxBytes(), role));
        }
        return views;
    }

    // ---------------------------------------------------------------- 落定

    /**
     * 落定一次直传。local 驱动由上传接口直接调用；cos 驱动将来由回调调用（协议形态一致）。
     *
     * <p>校验顺序刻意是「凭证 → 状态 → 类型 → 体积」：越早拒绝，越少给探测者反馈；类型判定放最后
     * 是因为它要解码，最贵。
     *
     * <p><b>状态跃迁必须是原子的条件更新</b>：这里原先读一遍 `status`、判一下、再写回去，
     * 并发落定同一个凭证时四个请求都能读到 `PENDING` 并全部通过——实测 4/4 返回 204，
     * 还各自插了一行缩略图（深测轮抓到的竞态，与打卡 D2、提醒 D9、评分 D19 是同一类问题）。
     * 所以真正把关的是下面那条 `UPDATE ... WHERE status = PENDING`；
     * 前面那次「读」只负责快速拒绝（免得为一必然失败的请求去解码图片），不是判据。
     */
    @Transactional
    public void store(long fileId, String token, byte[] content) {
        FileObject file = fileMapper.selectById(fileId);
        if (file == null) {
            throw BusinessException.notFound();
        }
        Instant now = Instant.now();
        boolean tokenOk = tokens.verify(token, UploadTokens.PURPOSE_UPLOAD, fileId, file.getOwnerUserId(), now);
        boolean expired = file.getExpiresAt() != null && file.getExpiresAt().isBefore(AppTime.now());
        if (!tokenOk || expired) {
            throw BusinessException.paramInvalid("上传地址已失效，请重新选择文件");
        }
        if (file.getStatus() != FileObject.STATUS_PENDING) {
            throw BusinessException.conflict("该文件已上传完成");
        }
        if (content == null || content.length == 0) {
            throw BusinessException.paramInvalid("上传内容为空");
        }
        if (content.length > properties.maxBytes()) {
            throw BusinessException.paramInvalid("单张图片不能超过 " + properties.maxBytes() / 1024 / 1024 + "MB");
        }

        Optional<ImageSniffer.Probe> probe = ImageSniffer.probe(content);
        if (probe.isEmpty()) {
            throw BusinessException.paramInvalid("只支持 JPEG 与 PNG，且文件需能正常打开");
        }
        ImageSniffer.Probe image = probe.get();

        // 审计三列必须**手工 set**：`update(null, wrapper)` 的 `et` 是 null，
        // MyBatis-Plus 会在解析 TableInfo 之前直接返回，AuditMetaObjectHandler.updateFill 根本不跑。
        // 原先这里是 updateById(file)（实体非 null，填充照常），改成条件更新时丢了这三列——
        // 于是同一张图：presign 那行的 trace_id 是建凭证的请求，落定这行仍是旧值，
        // 而三行之后插入的缩略图行（新 insert）却带着落定请求的 trace_id，同一个请求写出两套留痕。
        // 与 ProfileExportService.softDeleteAll 的写法一致（docs/conventions.md：写操作留 operator_id 与 trace_id）。
        long operatorId = TraceIds.currentOperatorId();
        String traceId = TraceIds.currentTraceId();
        int claimed = fileMapper.update(null, Wrappers.<FileObject>lambdaUpdate()
                .eq(FileObject::getId, fileId)
                .eq(FileObject::getStatus, FileObject.STATUS_PENDING)
                .set(FileObject::getMime, image.mime())
                .set(FileObject::getSizeBytes, (long) content.length)
                .set(FileObject::getSha256, sha256(content))
                .set(FileObject::getWidth, image.width())
                .set(FileObject::getHeight, image.height())
                .set(FileObject::getStatus, FileObject.STATUS_STORED)
                .set(FileObject::getExpiresAt, null)
                .set(FileObject::getUpdatedAt, AppTime.now())
                .set(FileObject::getUpdatedBy, operatorId)
                .set(FileObject::getTraceId, traceId));
        if (claimed != 1) {
            // 抢输的那一方：状态已被另一个请求改成「已传」。与顺序重复上传同一个答复（40900），
            // 客户端不需要区分这两种情况
            throw BusinessException.conflict("该文件已上传完成");
        }

        // 只有抢到的那个请求写字节与缩略图：否则同一个 fileId 会被重复写，缩略图还会多插几行
        storage.put(file.getStorageKey(), content);

        // 缩略图在上传时就生成好，而不是读的时候懒生成：读图路径要保持无写操作（列表一屏 9 张，
        // 懒生成会让第一次打开明显变慢，还要处理并发重复生成）。
        storeThumbnail(file, image.image());
    }

    private void storeThumbnail(FileObject original, BufferedImage source) {
        try {
            byte[] thumb = toJpeg(scaleDown(source, THUMB_WIDTH));
            FileObject child = new FileObject();
            child.setOwnerUserId(original.getOwnerUserId());
            child.setPetId(original.getPetId());
            child.setBizType(original.getBizType());
            child.setRole(FileObject.ROLE_THUMB);
            child.setOriginalId(original.getId());
            child.setMime(ImageSniffer.MIME_JPEG);
            child.setSizeBytes((long) thumb.length);
            child.setSha256(sha256(thumb));
            child.setWidth(scaledWidth(source, THUMB_WIDTH));
            child.setHeight(scaleHeight(source, THUMB_WIDTH));
            child.setStorageDriver(storage.driver());
            child.setStatus(FileObject.STATUS_STORED);
            child.setStorageKey("pending-" + UUID.randomUUID());
            fileMapper.insert(child);
            child.setStorageKey(storageKeyOf(original.getOwnerUserId(), child.getId(), "jpg"));
            fileMapper.updateById(child);
            storage.put(child.getStorageKey(), thumb);
        } catch (RuntimeException | IOException e) {
            // 缩略图失败不该让照片传不上去：原图已落定，列表回落到用原图渲染
            log.warn("生成缩略图失败，fileId={}", original.getId(), e);
        }
    }

    // ---------------------------------------------------------------- 读

    public List<FileView> list(long userId, Long petId, String bizType) {
        List<FileObject> rows = fileMapper.selectList(Wrappers.<FileObject>lambdaQuery()
                .eq(FileObject::getOwnerUserId, userId)
                .eq(petId != null, FileObject::getPetId, petId)
                .eq(bizType != null && !bizType.isBlank(), FileObject::getBizType, bizType)
                .eq(FileObject::getStatus, FileObject.STATUS_STORED)
                .ne(FileObject::getRole, FileObject.ROLE_THUMB)
                .orderByDesc(FileObject::getId));
        // 缩略图一次查回再按行取用：原先 toView 每行各查一次，列表越长查询次数越多
        Map<Long, FileObject> thumbnails = thumbnailsOf(rows.stream().map(FileObject::getId).toList());
        return rows.stream().map(row -> toView(row, thumbnails.get(row.getId()))).toList();
    }

    public FileView get(long userId, long fileId) {
        FileObject file = requireOwned(userId, fileId);
        return toView(file, thumbnailOf(fileId).orElse(null));
    }

    /** 签名读地址的内容：凭证通过就返回字节，不查登录身份——**签名即授权**（与对象存储的短时 URL 同理）。 */
    public StoredContent read(long fileId, String token) {
        FileObject file = fileMapper.selectById(fileId);
        if (file == null || file.getStatus() != FileObject.STATUS_STORED) {
            throw BusinessException.notFound();
        }
        if (!tokens.verify(token, UploadTokens.PURPOSE_READ, fileId, file.getOwnerUserId(), Instant.now())) {
            throw BusinessException.notFound();
        }
        return new StoredContent(file.getMime(), storage.read(file.getStorageKey()));
    }

    /** 一次签名读的结果。类型取自落定时嗅探的结果，而不是请求头。 */
    public record StoredContent(String mime, byte[] content) {
    }

    /**
     * 批量取签名读地址（{@link FileUrlApi}）。给 ph-ai 用：AI 服务要拿图，但它不该持有存储凭据。
     *
     * <p>跳过而不是报错：一张图不可用不该让整次咨询失败——那条路径由「有图但没分析」的降级话术兜住。
     *
     * <p>一次查回全部行再逐条判定：原先在循环里 {@code selectById}，一次咨询带 4 张图就是 4 次查询。
     * 遍历仍然按**调用方给的顺序**，所以结果顺序与「同一个 id 传两次就出现两次」的行为都没变。
     */
    @Override
    public List<String> readUrls(long userId, List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return List.of();
        }
        Map<Long, FileObject> byId = new HashMap<>();
        for (FileObject file : fileMapper.selectBatchIds(fileIds)) {
            byId.put(file.getId(), file);
        }
        List<String> urls = new ArrayList<>(fileIds.size());
        for (Long fileId : fileIds) {
            FileObject file = byId.get(fileId);
            if (file == null || file.getOwnerUserId() != userId
                    || file.getStatus() != FileObject.STATUS_STORED
                    || FileObject.ROLE_THUMB.equals(file.getRole())) {
                continue;
            }
            urls.add(signedReadUrl(file.getId(), file.getOwnerUserId()));
        }
        return urls;
    }

    // ---------------------------------------------------------------- 删

    @Transactional
    public void delete(long userId, long fileId) {
        FileObject file = requireOwned(userId, fileId);
        for (FileObject child : fileMapper.selectList(Wrappers.<FileObject>lambdaQuery()
                .eq(FileObject::getOriginalId, fileId))) {
            storage.remove(child.getStorageKey());
            fileMapper.deleteById(child.getId());
        }
        storage.remove(file.getStorageKey());
        fileMapper.deleteById(fileId);
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 按归属取文件。**别人的文件按不存在处理**（40400，docs/conventions.md）。
     *
     * <p>缩略图行不对外当「一个文件」：它是原图的派生物，前端拿到它的 id 当原图用会莫名其妙地糊。
     * 它只通过 {@link #read} 的签名地址暴露。
     */
    private FileObject requireOwned(long userId, long fileId) {
        FileObject file = fileMapper.selectById(fileId);
        if (file == null || file.getOwnerUserId() != userId
                || FileObject.ROLE_THUMB.equals(file.getRole())) {
            throw BusinessException.notFound();
        }
        return file;
    }

    private String storageKeyOf(long userId, long fileId, String extension) {
        return "f/" + userId + "/" + LocalDateTime.now(AppTime.ZONE).format(MONTH_PATH)
                + "/" + fileId + "." + extension;
    }

    /**
     * 行 → 对外视图。
     *
     * @param file      原图行（调用方已确认归属）
     * @param thumbnail 该原图的缩略图行；传 {@code null} 表示没有（生成失败，或还没轮到），
     *                  此时 {@code thumbUrl} 回落成原图地址——前端只认一个字段，不该自己判空
     */
    private FileView toView(FileObject file, FileObject thumbnail) {
        String url = signedReadUrl(file.getId(), file.getOwnerUserId());
        String thumbUrl = thumbnail == null
                ? url
                : signedReadUrl(thumbnail.getId(), thumbnail.getOwnerUserId());
        return new FileView(file.getId(), file.getPetId(), file.getBizType(), file.getRole(),
                file.getMime(), file.getSizeBytes(), file.getWidth(), file.getHeight(), url, thumbUrl);
    }

    private String signedReadUrl(long fileId, long ownerUserId) {
        String token = tokens.issue(UploadTokens.PURPOSE_READ, fileId, ownerUserId,
                Instant.now().plus(properties.readTtl()));
        return "/api/v1/open/files/" + fileId + "?token=" + token;
    }

    /** 取某张原图的缩略图行（单张路径用，见 {@link #get}）。 */
    private Optional<FileObject> thumbnailOf(long originalId) {
        return Optional.ofNullable(fileMapper.selectOne(Wrappers.<FileObject>lambdaQuery()
                .eq(FileObject::getOriginalId, originalId)
                .eq(FileObject::getRole, FileObject.ROLE_THUMB)));
    }

    /**
     * 批量取缩略图行，键是原图 id（列表路径用，见 {@link #list}）。
     *
     * <p>缩略图与业务行是 1:1 的派生关系，所以一张原图只应有一行；这里用 map 收敛，
     * 万一历史数据里有重复行也只是取到最后一行，而不是把整个列表接口打成 500。
     *
     * @param originalIds 原图 id 列表；空列表直接返回空 map，避免拼出 {@code IN ()}
     */
    private Map<Long, FileObject> thumbnailsOf(List<Long> originalIds) {
        if (originalIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, FileObject> thumbnails = new HashMap<>();
        for (FileObject row : fileMapper.selectList(Wrappers.<FileObject>lambdaQuery()
                .in(FileObject::getOriginalId, originalIds)
                .eq(FileObject::getRole, FileObject.ROLE_THUMB))) {
            thumbnails.put(row.getOriginalId(), row);
        }
        return thumbnails;
    }

    /** 缩放后的宽度：不超过 {@code maxWidth}，也不放大原图。 */
    private static int scaledWidth(BufferedImage source, int maxWidth) {
        return Math.min(source.getWidth(), maxWidth);
    }

    private static BufferedImage scaleDown(BufferedImage source, int maxWidth) {
        if (source.getWidth() <= maxWidth) {
            return source;
        }
        int width = scaledWidth(source, maxWidth);
        int height = scaleHeight(source, maxWidth);
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        // PNG 的透明区域在 JPEG 里会变黑，先铺白底
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source, 0, 0, width, height, null);
        g.dispose();
        return target;
    }

    /**
     * 等比缩放后的高度。
     *
     * <p>宽度上限由参数传入而不是读 {@link #THUMB_WIDTH}：原先两条缩放路径各算各的
     * （一条用参数、一条读常量），改动上限时必然有一处漏改，而算错高度的表现是图被拉扁。
     */
    private static int scaleHeight(BufferedImage source, int maxWidth) {
        return Math.max(1, (int) Math.round(source.getHeight() * (double) Math.min(source.getWidth(), maxWidth)
                / source.getWidth()));
    }

    private static byte[] toJpeg(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("当前 JDK 没有 JPEG 编码器");
        }
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(JPEG_QUALITY);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
    }
}
