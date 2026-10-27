import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** 临时探针：在帧里找某个颜色出现在哪些行（用于确认选中底到底画没画）。 */
public class Find {
    public static void main(String[] args) throws Exception {
        BufferedImage img = ImageIO.read(new File(args[0]));
        int target = Integer.parseInt(args[1], 16);
        int tr = (target >> 16) & 255, tg = (target >> 8) & 255, tb = target & 255;
        int prev = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            int hits = 0;
            for (int x = 0; x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y);
                if (Math.abs(((rgb >> 16) & 255) - tr) <= 3
                        && Math.abs(((rgb >> 8) & 255) - tg) <= 3
                        && Math.abs((rgb & 255) - tb) <= 3) {
                    hits++;
                }
            }
            if (hits > 20) {
                System.out.printf("y=%3d 命中 %d 像素%s%n", y, hits,
                        prev >= 0 && y != prev + 1 ? "  (与上一行不连续)" : "");
                prev = y;
            }
        }
    }
}
