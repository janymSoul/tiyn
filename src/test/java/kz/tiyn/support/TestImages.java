package kz.tiyn.support;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

public final class TestImages {

    private TestImages() {}

    public static byte[] png(int width, int height) {
        return encode(width, height, BufferedImage.TYPE_INT_ARGB, "png");
    }

    public static byte[] jpeg(int width, int height) {
        return encode(width, height, BufferedImage.TYPE_INT_RGB, "jpeg");
    }

    private static byte[] encode(int width, int height, int type, String format) {
        BufferedImage image = new BufferedImage(width, height, type);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setRGB(x, y, (x * 7 + y * 13) | 0xFF000000);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, format, out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }
}
