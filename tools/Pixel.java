import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** 临时像素探针：核对设计器帧里「选中行」到底有没有画出来。 */
public class Pixel {
    public static void main(String[] args) throws Exception {
        BufferedImage img = ImageIO.read(new File(args[0]));
        System.out.println("size " + img.getWidth() + "x" + img.getHeight());
        for (int i = 1; i < args.length; i += 2) {
            int x = Integer.parseInt(args[i]), y = Integer.parseInt(args[i + 1]);
            int rgb = img.getRGB(x, y);
            System.out.printf("(%4d,%4d) = #%02X%02X%02X%n", x, y,
                    (rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255);
        }
    }
}
