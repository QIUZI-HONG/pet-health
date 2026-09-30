package com.pethealth.file.storage;

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
import java.util.Iterator;

/**
 * 图片几何与编码：缩略图的尺寸计算、缩放与 JPEG 编码。
 *
 * <p>从 {@code FileService} 里分出来，是因为这两件事的**变化原因不同**：
 * FileService 管的是文件的生命周期（凭证 → 落定 → 读 → 删，见 ADR-0020），
 * 而这里是纯计算（缩放算法、压缩质量、透明底处理），没有数据库、没有存储、没有事务。
 * 原先它们混在一起时，改一个缩略图宽度要在 400 多行的服务里翻找，且两处缩放计算
 * 各写各的（一处读常量、一处用参数），改动时必然有一处漏改。
 *
 * <p>顺带一提尺寸口径：三个方法都收 {@code maxWidth} 参数而不是各读
 * {@link #WIDTH}——上限只有一个来源（{@link FileService} 调用时传入），
 * 免得同一次缩放里宽与高用了两个不同的上限，表现是图被拉扁。
 */
public final class Thumbnails {

    /** 缩略图宽度：够列表与小图预览，且不会把「看不清细节」的锅甩给缩略图（要看细节点原图）。 */
    public static final int WIDTH = 480;

    /** 交付文档 13.2：JPEG 压缩质量 0.8。 */
    private static final float JPEG_QUALITY = 0.8f;

    private Thumbnails() {
    }

    /** 缩放后的宽度：不超过 {@code maxWidth}，也不放大原图。 */
    public static int scaledWidth(BufferedImage source, int maxWidth) {
        return Math.min(source.getWidth(), maxWidth);
    }

    /**
     * 等比缩放后的高度。
     *
     * <p>按 {@code maxWidth} 上限算而不是按原图宽算，保证与 {@link #scaledWidth} 同源。
     */
    public static int scaledHeight(BufferedImage source, int maxWidth) {
        return Math.max(1, (int) Math.round(source.getHeight() * (double) scaledWidth(source, maxWidth)
                / source.getWidth()));
    }

    /** 缩到宽度上限内；原图本来就窄时**原样返回**（不放大，也就不会变糊）。 */
    public static BufferedImage scaleDown(BufferedImage source, int maxWidth) {
        if (source.getWidth() <= maxWidth) {
            return source;
        }
        int width = scaledWidth(source, maxWidth);
        int height = scaledHeight(source, maxWidth);
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
     * 编码成 JPEG（质量 {@link #JPEG_QUALITY}）。
     *
     * <p>用 {@link MemoryCacheImageOutputStream} 而不是文件流：结果是**字节**，
     * 落盘与写对象存储走的是同一个 {@code FileStorage} 接口。
     *
     * @throws IOException JDK 没有 JPEG 编码器，或写入过程中出错
     */
    public static byte[] toJpeg(BufferedImage image) throws IOException {
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
}
