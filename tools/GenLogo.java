import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 生成 AIDB 应用图标（PNG，多尺寸）。
 *
 * <p>与 {@code brand/aidb-mark.svg} 的几何完全一致 —— 改一个就要改两个。
 * 用 Java2D 而不是引入图片工具链：JDK 自带、无依赖、可重复生成。
 *
 * <pre>
 *   java tools/GenLogo.java              # 生成默认尺寸集
 *   java tools/GenLogo.java 512 256 128  # 只生成指定尺寸
 * </pre>
 */
public final class GenLogo {

    /** 画布边长。所有几何按 1024 规格设计，输出时整体缩放。 */
    private static final int BASE = 1024;

    public static void main(String[] args) throws Exception {
        File outDir = new File(args.length > 1 ? args[0] : "ui/adb-ui-shell/src/main/resources/io/agenticdb/ui");
        int[] sizes = args.length > 1
                ? java.util.Arrays.stream(args, 1, args.length).mapToInt(Integer::parseInt).toArray()
                : new int[]{1024, 512, 256, 128, 64, 32, 16};
        if (!outDir.isDirectory() && !outDir.mkdirs()) {
            throw new IllegalStateException("建不出目录：" + outDir);
        }
        for (int size : sizes) {
            File target = new File(outDir, size == 512 ? "app-icon.png" : "app-icon-" + size + ".png");
            ImageIO.write(draw(size), "png", target);
            System.out.println("写入 " + target + " (" + size + "x" + size + ")");
        }
    }

    /** 按给定边长绘制一枚圆角方图标（带透明边距，等比缩放 1024 规格）。 */
    static BufferedImage draw(int size) {
        double scale = (double) size / BASE;
        // 4× 超采样后再缩到目标尺寸，避免圆角与弧线出锯齿
        int ss = size * 4;
        double k = (double) ss / BASE;
        BufferedImage hi = new BufferedImage(ss, ss, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = hi.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            // 渐变（与 tokens.json 的 accent 同源）
            Paint tile = new GradientPaint(
                    new Point2D.Double(0, 64 * k), new Color(0x3B82F6),
                    new Point2D.Double(0, 960 * k), new Color(0x1D4ED8));

            // 底板
            g.setPaint(tile);
            g.fill(new RoundRectangle2D.Double(64 * k, 64 * k, 896 * k, 896 * k, 216 * k, 216 * k));

            // 数据库圆柱
            double cx = 470 * k;
            double rx = 230 * k;
            double ry = 86 * k;
            double top = 360 * k;
            double bottom = 660 * k;

            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Double(cx - rx, top - ry, rx * 2, ry * 2));

            // 盘身：矩形 + 底部半椭圆
            Path2D body = new Path2D.Double();
            body.moveTo(cx - rx, top);
            body.lineTo(cx - rx, bottom);
            body.append(new Arc2D.Double(cx - rx, bottom - ry, rx * 2, ry * 2, 180, 180, Arc2D.OPEN), true);
            body.lineTo(cx + rx, top);
            body.closePath();
            g.fill(body);

            // 分隔弧（切出三张盘）
            g.setPaint(tile);
            g.setStroke(new BasicStroke((float) (34 * k), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (double y : new double[]{480 * k, 570 * k}) {
                g.draw(new Arc2D.Double(cx - rx, y - ry, rx * 2, ry * 2, 180, 180, Arc2D.OPEN));
            }

            // AI 四角 sparkle：四条二次贝塞尔，控制点贴近中心 → 内凹收尖
            // cx=760, cy=296, t=126, 控制偏移 c=26
            g.setColor(Color.WHITE);
            double cx2 = 760 * k;
            double cy2 = 296 * k;
            double t = 126 * k;
            double c = 26 * k;
            Path2D spark = new Path2D.Double();
            spark.moveTo(cx2, cy2 - t);
            spark.quadTo(cx2 + c, cy2 - c, cx2 + t, cy2);
            spark.quadTo(cx2 + c, cy2 + c, cx2, cy2 + t);
            spark.quadTo(cx2 - c, cy2 + c, cx2 - t, cy2);
            spark.quadTo(cx2 - c, cy2 - c, cx2, cy2 - t);
            spark.closePath();
            g.fill(spark);
        } finally {
            g.dispose();
        }

        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = out.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.drawImage(hi, 0, 0, size, size, null);
        } finally {
            g2.dispose();
        }
        return out;
    }
}
