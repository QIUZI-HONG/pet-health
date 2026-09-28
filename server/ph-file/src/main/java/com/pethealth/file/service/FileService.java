package com.pethealth.file.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pethealth.api.app.FilePresignRequest;
import com.pethealth.api.app.FilePresignView;
import com.pethealth.api.app.FileView;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.time.AppTime;
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
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

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
            file.setStorageKey("pending-" + java.util.UUID.randomUUID());
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

        storage.put(file.getStorageKey(), content);
        file.setMime(image.mime());
        file.setSizeBytes((long) content.length);
        file.setSha256(sha256(content));
        file.setWidth(image.width());
        file.setHeight(image.height());
        file.setStatus(FileObject.STATUS_STORED);
        file.setExpiresAt(null);
        fileMapper.updateById(file);

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
            child.setWidth(Math.min(source.getWidth(), THUMB_WIDTH));
            child.setHeight(scaleHeight(source));
            child.setStorageDriver(storage.driver());
            child.setStatus(FileObject.STATUS_STORED);
            child.setStorageKey("pending-" + java.util.UUID.randomUUID());
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
        return rows.stream().map(this::toView).toList();
    }

    public FileView get(long userId, long fileId) {
        return toView(requireOwned(userId, fileId));
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
     */
    @Override
    public List<String> readUrls(long userId, List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return List.of();
        }
        List<String> urls = new java.util.ArrayList<>(fileIds.size());
        for (Long fileId : fileIds) {
            FileObject file = fileMapper.selectById(fileId);
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

    private FileView toView(FileObject file) {
        String url = signedReadUrl(file.getId(), file.getOwnerUserId());
        String thumbUrl = thumbnailOf(file.getId())
                .map(thumb -> signedReadUrl(thumb.getId(), thumb.getOwnerUserId()))
                .orElse(url);
        return new FileView(file.getId(), file.getPetId(), file.getBizType(), file.getRole(),
                file.getMime(), file.getSizeBytes(), file.getWidth(), file.getHeight(), url, thumbUrl);
    }

    private String signedReadUrl(long fileId, long ownerUserId) {
        String token = tokens.issue(UploadTokens.PURPOSE_READ, fileId, ownerUserId,
                Instant.now().plus(properties.readTtl()));
        return "/api/v1/open/files/" + fileId + "?token=" + token;
    }

    private Optional<FileObject> thumbnailOf(long originalId) {
        return Optional.ofNullable(fileMapper.selectOne(Wrappers.<FileObject>lambdaQuery()
                .eq(FileObject::getOriginalId, originalId)
                .eq(FileObject::getRole, FileObject.ROLE_THUMB)));
    }

    private static BufferedImage scaleDown(BufferedImage source, int maxWidth) {
        if (source.getWidth() <= maxWidth) {
            return source;
        }
        int height = scaleHeight(source);
        BufferedImage target = new BufferedImage(maxWidth, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        // PNG 的透明区域在 JPEG 里会变黑，先铺白底
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, maxWidth, height);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source, 0, 0, maxWidth, height, null);
        g.dispose();
        return target;
    }

    private static int scaleHeight(BufferedImage source) {
        return Math.max(1, (int) Math.round(source.getHeight() * (double) Math.min(source.getWidth(), THUMB_WIDTH)
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
