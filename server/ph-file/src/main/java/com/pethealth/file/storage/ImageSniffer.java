package com.pethealth.file.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Optional;

/**
 * 图片识别：**按魔数判类型**，不认请求头里的 {@code Content-Type}。
 *
 * <p>为什么必须这样（交付文档 6.3 的文件上传安全）：请求头由客户端自己填，把 .exe 改名成 .jpg 再声明
 * {@code image/jpeg} 就能通过基于头的白名单。魔数是文件内容的头几个字节，改不了。
 *
 * <p>顺带用 JDK 自带的 ImageIO 解一次：既拿到宽高（前端排版要用），也顺便挡掉「魔数对但内容已损坏」的文件。
 * 这一步不做，缩略图生成时才会炸，而那时文件已经落盘了。
 */
public final class ImageSniffer {

    private static final Logger log = LoggerFactory.getLogger(ImageSniffer.class);

    public static final String MIME_JPEG = "image/jpeg";
    public static final String MIME_PNG = "image/png";

    private ImageSniffer() {
    }

    /** 一张可用的图片：类型、宽、高、以及解码结果（生成缩略图时复用，避免二次解码）。 */
    public record Probe(String mime, int width, int height, BufferedImage image) {
    }

    /**
     * 嗅探并解码。**不支持或无法解码时返回空**——调用方据此回 40001，并把「不支持的类型」与
     * 「文件损坏」当成同一件事（对用户都是「换一张图」）。
     */
    public static Optional<Probe> probe(byte[] content) {
        String mime = sniffMime(content);
        if (mime == null) {
            return Optional.empty();
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(content));
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                return Optional.empty();
            }
            return Optional.of(new Probe(mime, image.getWidth(), image.getHeight(), image));
        } catch (IOException e) {
            // 返回空是对的（对用户就是「换一张图」），但**不能无声**：魔数对而内容损坏的文件
            // 是客户端 bug 或上传被截断的现场，留一条日志才有线索（docs/conventions.md 禁止吞异常）
            log.warn("图片解码失败，按不可用处理：mime={} 字节数={}", mime, content.length, e);
            return Optional.empty();
        }
    }

    /** 只判类型不解码；{@code null} 表示不在白名单内。 */
    public static String sniffMime(byte[] content) {
        if (content == null || content.length < 8) {
            return null;
        }
        if (isJpeg(content)) {
            return MIME_JPEG;
        }
        if (isPng(content)) {
            return MIME_PNG;
        }
        return null;
    }

    /** JPEG：{@code FF D8 FF}（SOI + 第一个段的标记前缀）。 */
    private static boolean isJpeg(byte[] c) {
        return (c[0] & 0xFF) == 0xFF && (c[1] & 0xFF) == 0xD8 && (c[2] & 0xFF) == 0xFF;
    }

    /** PNG：8 字节固定签名 {@code 89 50 4E 47 0D 0A 1A 0A}。 */
    private static boolean isPng(byte[] c) {
        return (c[0] & 0xFF) == 0x89 && c[1] == 'P' && c[2] == 'N' && c[3] == 'G'
                && (c[4] & 0xFF) == 0x0D && (c[5] & 0xFF) == 0x0A
                && (c[6] & 0xFF) == 0x1A && (c[7] & 0xFF) == 0x0A;
    }

    /** 由类型给出存储键的扩展名。 */
    public static String extensionOf(String mime) {
        return MIME_PNG.equals(mime) ? "png" : "jpg";
    }
}
