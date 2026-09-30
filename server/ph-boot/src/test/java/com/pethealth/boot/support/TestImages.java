package com.pethealth.boot.support;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * 造真图的字节，供需要走上传链路的用例使用。
 *
 * <p><b>为什么不用假头字节</b>：文件域落盘时按**魔数**判定类型、并用 {@code ImageIO} 真解码一次
 * （{@code ImageSniffer}），假字节会被拒；而「上传一张真图再读回来比对」这件事本身就是要验的
 * 那条链路。所以宁可在这里花十行生成一张纯色图。
 *
 * <p>原先这几段在 {@code FileStorageTest} 里是私有的，资质材料用例也需要同样的东西——
 * 复制一份的话，两份会各自漂移（例如哪天要造带透明通道的 PNG），所以抽到这里共用。
 */
public final class TestImages {

    private TestImages() {
    }

    /** 纯色 PNG 的字节。 */
    public static byte[] png(int width, int height) {
        return encode(image(width, height), "png");
    }

    /** 纯色 JPEG 的字节（缩略图与「非 PNG」的用例要它）。 */
    public static byte[] jpeg(int width, int height) {
        return encode(image(width, height), "jpg");
    }

    private static BufferedImage image(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.decode("#0ea382"));
        g.fillRect(0, 0, width, height);
        g.dispose();
        return image;
    }

    private static byte[] encode(BufferedImage image, String format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, format, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
