import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/** 裁剪并放大某个区域（本机没有 PIL，肉眼在 1280 宽上看不出 1px 的重叠）。 */
public class Crop {
    public static void main(String[] args) throws Exception {
        String in = args[0];
        int x = Integer.parseInt(args[1]);
        int y = Integer.parseInt(args[2]);
        int w = Integer.parseInt(args[3]);
        int h = Integer.parseInt(args[4]);
        int zoom = args.length > 5 ? Integer.parseInt(args[5]) : 4;
        BufferedImage src = ImageIO.read(new File(in));
        BufferedImage out = new BufferedImage(w * zoom, h * zoom, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(src.getSubimage(x, y, Math.min(w, src.getWidth() - x),
                Math.min(h, src.getHeight() - y)), 0, 0, w * zoom, h * zoom, null);
        g.dispose();
        String dst = in.replaceAll("\\.png$", "") + "-crop-" + x + "-" + y + "-" + w + "x" + h + ".png";
        ImageIO.write(out, "png", new File(dst));
        System.out.println(src.getWidth() + "x" + src.getHeight() + " -> " + dst);
    }
}
