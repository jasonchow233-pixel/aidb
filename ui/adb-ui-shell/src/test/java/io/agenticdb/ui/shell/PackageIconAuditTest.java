package io.agenticdb.ui.shell;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V-6 出包图标审计：`brand/app-icon.ico` 与 `brand/app-icon.icns` 必须是**结构合法**的
 * PNG 帧容器，且 {@code scripts/package.sh} 必须真的把 {@code --icon} 传给 jpackage。
 *
 * <p>为什么要守：jpackage 只按扩展名选解码器，**容器内容坏了不报错**、不给 --icon 也不报错 ——
 * 两种失败都会静默产出没有品牌图标（或坏图标）的安装包，装了才知道。这与第十八轮
 * 「失败被设计成静默降级」是同一个坑，这里按容器格式逐字段解码，而不是只看文件在不在。
 *
 * <p>资产由 {@code java tools/GenPackIcons.java} 从 GenLogo 同源几何生成。
 */
class PackageIconAuditTest {

    private static final byte[] PNG_SIGNATURE =
            {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    @Test
    @DisplayName("package.sh 每个包类型都必须带 --icon —— 不给就静默回退 Java 默认图标")
    void everyPackageTypePassesABrandIcon() throws IOException {
        String script = Files.readString(
                repositoryRoot().resolve("scripts/package.sh"), StandardCharsets.UTF_8);
        // 逐块切：case 的每个分支都要能拿到 --icon（按项目规矩先剥行注释再判）
        List<String> branches = new ArrayList<>();
        for (String chunk : script.split(";;")) {
            String stripped = chunk.replaceAll("(?m)#[^\\n]*", "");
            if (stripped.contains("--type")) {
                branches.add(stripped);
            }
        }
        assertThat(branches)
                .as("package.sh 至少要解析出一个 --type 分支（解析不到就是格式变了，门禁失去意义）")
                .isNotEmpty();
        // 公共 JPACKAGE_ARGS 段带 --icon 时按 uname 分支追加 —— 检查「平台段」都覆盖了 --icon
        String strippedScript = script.replaceAll("(?m)#.*", "");
        assertThat(strippedScript)
                .as("package.sh 必须把 brand 图标接进 JPACKAGE_ARGS —— Windows/macOS 各自的扩展名")
                .contains("--icon");
        assertThat(strippedScript)
                .as("Windows 分支必须用 .ico")
                .contains("app-icon.ico");
        assertThat(strippedScript)
                .as("macOS 分支必须用 .icns")
                .contains("app-icon.icns");
    }

    @Test
    @DisplayName(".ico：目录头、帧尺寸、每帧都是可解码的 PNG，且 256 帧按容器约定把边长写成 0")
    void windowsIcoIsAValidPngFrameContainer() throws Exception {
        byte[] ico = Files.readAllBytes(repositoryRoot().resolve("brand/app-icon.ico"));
        ByteBuffer buf = ByteBuffer.wrap(ico).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(buf.getShort()).as("reserved 必须是 0").isEqualTo((short) 0);
        assertThat(buf.getShort()).as("type 必须是 1（icon）").isEqualTo((short) 1);
        int count = buf.getShort() & 0xFFFF;
        assertThat(count).as("帧数至少要有 16/24/32/48/64/128/256 七档").isGreaterThanOrEqualTo(7);

        Set<Integer> seenEdges = new HashSet<>();
        for (int i = 0; i < count; i++) {
            int width = buf.get() & 0xFF;
            int height = buf.get() & 0xFF;
            buf.get();                    // 色数（PNG 帧恒 0）
            buf.get();                    // reserved
            assertThat(buf.getShort() & 0xFFFF).as("planes").isEqualTo(1);
            assertThat(buf.getShort() & 0xFFFF).as("bpp").isEqualTo(32);
            int size = buf.getInt();
            int offset = buf.getInt();
            assertThat(width).as("帧 " + i + " 的宽高必须一致").isEqualTo(height);
            assertThat(offset + size).as("帧 " + i + " 不能越出文件").isLessThanOrEqualTo(ico.length);
            int edge = width == 0 ? 256 : width;
            seenEdges.add(edge);
            PngInfo png = readPngInfo(ico, offset, size);
            assertThat(png.width)
                    .as("目录声明 " + edge + "px，PNG 头里必须也是 " + edge + "px")
                    .isEqualTo(edge);
            assertThat(png.valid)
                    .as("帧 " + edge + "px 必须是 CRC 完整的 PNG（IDAT 可解压）")
                    .isTrue();
        }
        assertThat(seenEdges).contains(16, 32, 48, 256);
        assertThat(seenEdges)
                .as("256 帧的边长按容器约定写成 0，解码回 256 后不能和别的帧撞车")
                .hasSize(count);
    }

    @Test
    @DisplayName(".icns：magic、大端总长与文件一致，每帧 4 字节 type + 自洽长度 + PNG 内容")
    void macIcnsIsValidPngFrameContainer() throws Exception {
        byte[] icns = Files.readAllBytes(repositoryRoot().resolve("brand/app-icon.icns"));
        assertThat(new String(icns, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("icns");
        int declaredTotal = ByteBuffer.wrap(icns, 4, 4).getInt(); // 大端
        assertThat(declaredTotal)
                .as("icns 头声明的总长（含 8 字节头）必须等于实际字节数")
                .isEqualTo(icns.length);

        int cursor = 8;
        Set<String> types = new HashSet<>();
        while (cursor < icns.length) {
            String type = new String(icns, cursor, 4, StandardCharsets.US_ASCII);
            assertThat(type.chars().allMatch(Character::isLetterOrDigit))
                    .as("type 必须是 4 个 ASCII 字母数字：" + type).isTrue();
            int length = ByteBuffer.wrap(icns, cursor + 4, 4).getInt();
            assertThat(length).as(type + " 的帧长至少要包住 8 字节头").isGreaterThan(8);
            assertThat(cursor + length).as(type + " 不能越出文件").isLessThanOrEqualTo(icns.length);
            byte[] png = new byte[length - 8];
            System.arraycopy(icns, cursor + 8, png, 0, png.length);
            PngInfo info = readPngInfo(png, 0, png.length);
            assertThat(info.width)
                    .as(type + " 槽位的 PNG 边长不该是 0 或 256（icns 用真实边长）")
                    .isGreaterThan(1);
            assertThat(info.valid).as(type + " 帧必须 CRC 完整").isTrue();
            types.add(type);
            cursor += length;
        }
        assertThat(cursor).as("帧必须严丝合缝铺到文件尾").isEqualTo(icns.length);
        assertThat(types).as("至少要覆盖 macOS 用的基本档位").contains("icn4", "icn5", "ic07", "ic08");
    }

    // ── PNG 解码（只到「结构合法」这一层：签名 + IHDR 尺寸 + 全 chunk CRC + IDAT 可解压） ──

    private record PngInfo(int width, boolean valid) {
    }

    private static PngInfo readPngInfo(byte[] data, int offset, int size) {
        byte[] frame = new byte[size];
        System.arraycopy(data, offset, frame, 0, size);
        return parsePng(frame);
    }

    private static PngInfo parsePng(byte[] frame) {
        if (frame.length < 8) return new PngInfo(-1, false);
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (frame[i] != PNG_SIGNATURE[i]) return new PngInfo(-1, false);
        }
        ByteBuffer buf = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        buf.position(8);
        int width = -1;
        boolean sawIend = false;
        while (buf.remaining() >= 8) {
            int length = buf.getInt();
            String chunkType = new String(frame, buf.position(), 4, StandardCharsets.US_ASCII);
            buf.position(buf.position() + 4);
            if (length < 0 || buf.remaining() < length + 4) return new PngInfo(width, false);
            int dataStart = buf.position();
            if ("IHDR".equals(chunkType)) {
                if (length < 8) return new PngInfo(-1, false);
                width = ByteBuffer.wrap(frame, dataStart, 4).getInt();
            } else if ("IDAT".equals(chunkType)) {
                if (!inflateOk(frame, dataStart, length)) return new PngInfo(width, false);
            } else if ("IEND".equals(chunkType)) {
                sawIend = true;
            }
            // CRC 覆盖 type + data（PNG 的规矩：长度不进 CRC）
            CRC32 crc = new CRC32();
            crc.update(frame, dataStart - 4, length + 4);
            int declared = ByteBuffer.wrap(frame, dataStart + length, 4).getInt();
            if ((int) crc.getValue() != declared) return new PngInfo(width, false);
            buf.position(dataStart + length + 4);
        }
        return new PngInfo(width, sawIend && width > 0);
    }

    /** IDAT 拼起来是 zlib 流；解压不出东西的帧在 jpackage 里会静默变成空白图标。 */
    private static boolean inflateOk(byte[] frame, int offset, int length) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(frame, offset, length);
            byte[] sink = new byte[4096];
            int produced = 0;
            while (!inflater.finished()) {
                int n = inflater.inflate(sink);
                if (n == 0 && inflater.finished()) break;
                produced += n;
                if (produced > 32 * 1024 * 1024) return false; // 解压炸弹护栏
            }
            return produced > 0;
        } catch (DataFormatException e) {
            return false;
        } finally {
            inflater.end();
        }
    }

    private static Path repositoryRoot() {
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (cursor != null) {
            if (Files.isRegularFile(cursor.resolve("pom.xml"))
                    && Files.isDirectory(cursor.resolve("core"))
                    && Files.isDirectory(cursor.resolve("brand"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("从 " + System.getProperty("user.dir")
                + " 往上找不到仓库根（需要 pom.xml + core/ + brand/)");
    }
}
