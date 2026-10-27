import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * 生成安装包用的平台图标（.ico / .icns），几何与 {@link GenLogo#draw} 同源 —— 不经过 PNG 文件，
 * 改 GenLogo 的几何后重跑本工具即可。
 *
 * <p>两个容器都用「PNG 帧 + 目录头」的现代形态：
 * <ul>
 *   <li><b>.ico</b>：Vista+ 支持内嵌 PNG（256px 帧必须用 PNG，老 BMP 格式表达不了）。</li>
 *   <li><b>.icns</b>：PNG 压缩的 icon type（ic05/ic07…），与 Apple iconutil 的产物同型。</li>
 * </ul>
 * jpackage 只认文件扩展名选解码器，不校验容器内容 —— 所以错了要由
 * {@code PackageIconAuditTest} 守，不能指望出包失败来报信。
 *
 * <pre>
 *   java tools/GenPackIcons.java            # 生成 brand/app-icon.ico + brand/app-icon.icns
 *   java tools/GenPackIcons.java out_dir    # 写到指定目录
 * </pre>
 */
public final class GenPackIcons {

    /** .ico 的帧尺寸。24/48 是 Windows 任务栏与资源管理器的常用档，256 是容器上限。 */
    private static final int[] ICO_SIZES = {16, 24, 32, 48, 64, 128, 256};

    /** .icns 的 (type, 边长, @2x)。@2x 的帧按 2 倍边长绘制（icn5 = 16@2x = 32px）。 */
    private static final Object[][] ICNS_ENTRIES = {
            {"icn4", 16, false},    // 16@1x
            {"icn5", 32, false},    // 16@2x
            {"icn6", 32, false},    // 32@1x
            {"ic07", 128, false},   // 128@1x
            {"ic08", 256, false},   // 128@2x
            {"ic09", 512, false},   // 256@1x
            {"ic14", 16, true},     // 32@1x（16@2x 槽位）
            {"ic13", 32, true},     // 32@2x
            {"is12", 120, false},  // 图标集网格 120（macOS 11+ 大文件夹/通知用）
            {"is13", 16, true},     // 16@3x
            {"is14", 32, true},     // 32@3x
            {"is15", 512, true},    // 1024（256@4x）
    };

    public static void main(String[] args) throws Exception {
        File outDir = new File(args.length > 0 ? args[0] : "brand");
        if (!outDir.isDirectory() && !outDir.mkdirs()) {
            throw new IllegalStateException("建不出目录：" + outDir);
        }
        File ico = new File(outDir, "app-icon.ico");
        File icns = new File(outDir, "app-icon.icns");
        writeIco(ico);
        writeIcns(icns);
        System.out.println("写入 " + ico + " (" + ico.length() + " B, " + ICO_SIZES.length + " 帧)");
        System.out.println("写入 " + icns + " (" + icns.length() + " B, " + ICNS_ENTRIES.length + " 帧)");
    }

    // ── .ico ────────────────────────────────────────────────────────────

    private static void writeIco(File target) throws IOException {
        byte[][] frames = new byte[ICO_SIZES.length][];
        for (int i = 0; i < ICO_SIZES.length; i++) {
            frames[i] = png(GenLogo.draw(ICO_SIZES[i]));
        }
        try (RandomAccessFile f = new RandomAccessFile(target, "rw")) {
            f.setLength(0);
            writeU16(f, 0);                 // reserved
            writeU16(f, 1);                 // type: icon
            writeU16(f, frames.length);     // count
            int offset = 6 + 16 * frames.length;
            for (int i = 0; i < frames.length; i++) {
                int size = ICO_SIZES[i];
                // 容器约定：边长一个字节，256 写 0；色板 0（PNG 无 DIB 调色板）
                f.writeByte(size >= 256 ? 0 : size);
                f.writeByte(size >= 256 ? 0 : size);
                f.writeByte(0);             // 色数
                f.writeByte(0);             // reserved
                writeU16(f, 1);             // planes
                writeU16(f, 32);            // bpp
                writeI32(f, frames[i].length);
                writeI32(f, offset);
                offset += frames[i].length;
            }
            for (byte[] frame : frames) f.write(frame);
        }
    }

    // ── .icns ───────────────────────────────────────────────────────────

    private static void writeIcns(File target) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Object[] entry : ICNS_ENTRIES) {
            String type = (String) entry[0];
            int edge = (Integer) entry[1];
            boolean retina = (Boolean) entry[2];
            byte[] png = png(GenLogo.draw(retina ? edge * 2 : edge));
            byte[] tag = type.getBytes(StandardCharsets.US_ASCII);
            if (tag.length != 4) throw new IllegalStateException("icns type 必须是 4 字节：" + type);
            body.write(tag);
            // icns 的长度头：大端、含头自身的 8 字节
            writeBigI32(body, png.length + 8);
            body.write(png);
        }
        try (RandomAccessFile f = new RandomAccessFile(target, "rw")) {
            f.setLength(0);
            f.write("icns".getBytes(StandardCharsets.US_ASCII));
            f.writeInt(body.size() + 8); // RandomAccessFile.writeInt 就是大端，icns 头要的正是它
            f.write(body.toByteArray());
        }
    }

    // ── 原语 ────────────────────────────────────────────────────────────

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) {
            throw new IOException("没有 PNG writer");
        }
        return out.toByteArray();
    }

    private static void writeU16(RandomAccessFile f, int value) throws IOException {
        f.write(value & 0xFF);
        f.write((value >>> 8) & 0xFF);
    }

    private static void writeI32(RandomAccessFile f, int value) throws IOException {
        for (int shift = 0; shift < 32; shift += 8) f.write((value >>> shift) & 0xFF);
    }

    private static void writeBigI32(java.io.OutputStream out, int value) throws IOException {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }
}
