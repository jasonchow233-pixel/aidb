package io.agenticdb.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lucide SVG → JavaFX {@code SVGPath} 路径转换器（运行时由 {@link Icons} 调用）。
 *
 * <p>为什么要转：JavaFX 的 {@code SVGPath} <b>不支持</b> SVG 的弧命令 {@code A/a}，
 * 也不认 {@code circle/rect/polygon} 这些元素；而 Lucide 官方图标大量使用两者。
 * 这里把它们全部展开成 {@code M L C Z} 绝对坐标：
 * <ul>
 *   <li>弧命令按 W3C F.6.5/F.6.6（端点参数化 → 中心参数化）拟合为 ≤90° 分段的三次贝塞尔；</li>
 *   <li>基本图形先展开为路径再走同一套归一化；</li>
 *   <li>24×24 视框等比缩放到 {@link Icons#VIEW_BOX}。</li>
 * </ul>
 *
 * <p>源文件是纯资源（{@code icons/lucide/*.svg}，ISC 许可，见同目录 ISC-LICENSE.txt），
 * 升级图标 = 替换 SVG 文件，无需重新生成任何代码。
 */
final class LucideConverter {

    private LucideConverter() {
    }

    /** SVG 文本 → 目标视框的绝对路径 d 字符串。 */
    static String toPath(String svg, double targetViewBox) {
        Matcher view = Pattern.compile(
                "viewBox\\s*=\\s*\"\\s*([0-9.\\-]+)\\s+([0-9.\\-]+)\\s+([0-9.\\-]+)\\s+([0-9.\\-]+)")
                .matcher(svg);
        if (!view.find()) {
            throw new IllegalStateException("没有 viewBox");
        }
        double width = Double.parseDouble(view.group(3));
        double height = Double.parseDouble(view.group(4));
        if (Math.abs(width - height) > 1e-6
                || Math.abs(Double.parseDouble(view.group(1))) > 1e-6
                || Math.abs(Double.parseDouble(view.group(2))) > 1e-6) {
            throw new IllegalStateException("viewBox 必须是从原点开始的正方形");
        }
        double scale = targetViewBox / width;

        List<String> segments = new ArrayList<>();
        Matcher element = Pattern.compile(
                "<(path|circle|ellipse|rect|line|polyline|polygon)\\b([^>]*?)/?>",
                Pattern.CASE_INSENSITIVE).matcher(svg);
        while (element.find()) {
            segments.add(elementToPath(element.group(1).toLowerCase(Locale.ROOT),
                    element.group(2), scale));
        }
        if (segments.isEmpty()) {
            throw new IllegalStateException("没有可转换的图形元素");
        }
        return String.join(" ", segments).replaceAll("\\s+", " ").trim();
    }

    private static String elementToPath(String tag, String attrs, double scale) {
        switch (tag) {
            case "path": {
                String d = attribute(attrs, "d");
                if (d == null) {
                    throw new IllegalStateException("path 没有 d");
                }
                return normalize(d, scale);
            }
            case "circle": {
                double cx = number(attrs, "cx"), cy = number(attrs, "cy"), r = number(attrs, "r");
                return ellipseToCubic(cx, cy, r, r, scale);
            }
            case "ellipse": {
                double cx = number(attrs, "cx"), cy = number(attrs, "cy");
                return ellipseToCubic(cx, cy, number(attrs, "rx"), number(attrs, "ry"), scale);
            }
            case "rect": {
                double x = number(attrs, "x"), y = number(attrs, "y");
                double w = number(attrs, "width"), h = number(attrs, "height");
                Double rxAttr = optionalNumber(attrs, "rx");
                Double ryAttr = optionalNumber(attrs, "ry");
                double rx = rxAttr != null ? rxAttr : (ryAttr != null ? ryAttr : 0);
                double ry = ryAttr != null ? ryAttr : rx;
                rx = Math.min(rx, w / 2);
                ry = Math.min(ry, h / 2);
                if (rx <= 1e-9 && ry <= 1e-9) {
                    return "M " + pair(x, y, scale) + " L " + pair(x + w, y, scale)
                            + " L " + pair(x + w, y + h, scale) + " L " + pair(x, y + h, scale) + " Z";
                }
                StringBuilder sb = new StringBuilder();
                sb.append("M ").append(pair(x + rx, y, scale));
                sb.append(" L ").append(pair(x + w - rx, y, scale));
                sb.append(' ').append(arcToCubic(x + w - rx, y, rx, ry, 0, false, true,
                        x + w, y + ry, scale));
                sb.append(" L ").append(pair(x + w, y + h - ry, scale));
                sb.append(' ').append(arcToCubic(x + w, y + h - ry, rx, ry, 0, false, true,
                        x + w - rx, y + h, scale));
                sb.append(" L ").append(pair(x + rx, y + h, scale));
                sb.append(' ').append(arcToCubic(x + rx, y + h, rx, ry, 0, false, true,
                        x, y + h - ry, scale));
                sb.append(" L ").append(pair(x, y + ry, scale));
                sb.append(' ').append(arcToCubic(x, y + ry, rx, ry, 0, false, true,
                        x + rx, y, scale));
                sb.append(" Z");
                return sb.toString();
            }
            case "line": {
                double x1 = number(attrs, "x1"), y1 = number(attrs, "y1");
                double x2 = number(attrs, "x2"), y2 = number(attrs, "y2");
                return "M " + pair(x1, y1, scale) + " L " + pair(x2, y2, scale);
            }
            case "polyline":
            case "polygon": {
                String points = attribute(attrs, "points");
                if (points == null) {
                    throw new IllegalStateException(tag + " 没有 points");
                }
                String[] tokens = points.trim().split("[\\s,]+");
                if (tokens.length < 4 || tokens.length % 2 != 0) {
                    throw new IllegalStateException(tag + " points 数量异常");
                }
                StringBuilder sb = new StringBuilder("M ");
                for (int i = 0; i < tokens.length; i += 2) {
                    sb.append(pair(Double.parseDouble(tokens[i]), Double.parseDouble(tokens[i + 1]), scale));
                    if (i + 2 < tokens.length) {
                        sb.append(" L ");
                    }
                }
                if (tag.equals("polygon")) {
                    sb.append(" Z");
                }
                return sb.toString();
            }
            default:
                throw new IllegalStateException("不支持的元素: " + tag);
        }
    }

    // ─────────────────────────── path d 归一化 ───────────────────────────

    private static final String COMMAND_LETTERS = "MmHhVvLlCcSsQqTtAaZz";

    /** 路径读取器：命令字母 + 按需取数；弧标志位按 SVG 语法只吃一位数字。 */
    private static final class Reader {
        private final String text;
        private int pos;

        Reader(String text) {
            this.text = text;
        }

        boolean hasCommand() {
            skipSeparators();
            return pos < text.length() && COMMAND_LETTERS.indexOf(text.charAt(pos)) >= 0;
        }

        /** 当前命令之后是否还有参数（隐式重复的判据：下一个非空白字符不是命令字母）。 */
        boolean hasMoreParameters() {
            skipSeparators();
            return pos < text.length() && COMMAND_LETTERS.indexOf(text.charAt(pos)) < 0;
        }

        char command() {
            skipSeparators();
            return text.charAt(pos++);
        }

        double number() {
            skipSeparators();
            int start = pos;
            if (pos < text.length() && (text.charAt(pos) == '-' || text.charAt(pos) == '+')) {
                pos++;
            }
            int digitsBeforeDot = 0;
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                pos++;
                digitsBeforeDot++;
            }
            int digitsAfterDot = 0;
            if (pos < text.length() && text.charAt(pos) == '.') {
                pos++;
                while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                    pos++;
                    digitsAfterDot++;
                }
            }
            if (digitsBeforeDot == 0 && digitsAfterDot == 0) {
                throw new IllegalStateException("不是数字（位置 " + start + "）");
            }
            if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                int save = pos;
                pos++;
                if (pos < text.length() && (text.charAt(pos) == '-' || text.charAt(pos) == '+')) {
                    pos++;
                }
                if (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                    while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                        pos++;
                    }
                } else {
                    pos = save;
                }
            }
            return Double.parseDouble(text.substring(start, pos));
        }

        /** 弧标志（large-arc / sweep）：SVG 语法里是单个 0/1，必须逐位拆。 */
        double flag() {
            skipSeparators();
            int start = pos;
            if (pos < text.length() && (text.charAt(pos) == '-' || text.charAt(pos) == '+')) {
                pos++;
            }
            if (pos >= text.length() || !Character.isDigit(text.charAt(pos))) {
                throw new IllegalStateException("缺少弧标志（位置 " + pos + "）");
            }
            pos++;
            return Double.parseDouble(text.substring(start, pos));
        }

        private void skipSeparators() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ',') {
                    pos++;
                } else {
                    break;
                }
            }
        }
    }

    /**
     * 解析 d（支持相对命令、弧命令、SVG 紧凑数字流与隐式重复），输出绝对 M L C Z。
     *
     * <p>数字流遵守 W3C SVG grammar：符号位只在「前一个数字已结束」时开启新数字，
     * 一个小数点终止当前数字 —— Lucide 的紧凑弧参数
     * （{@code 00-2.474}、{@code 005.5}、{@code 01-.472}）靠这条规则才能切开。
     */
    private static String normalize(String d, double scale) {
        Reader reader = new Reader(d);
        double cx = 0, cy = 0;
        double sx = 0, sy = 0;
        double lastC1x = Double.NaN, lastC1y = Double.NaN;
        double lastQ1x = Double.NaN, lastQ1y = Double.NaN;
        List<String> out = new ArrayList<>();
        while (reader.hasCommand()) {
            char letter = reader.command();
            char command = Character.toUpperCase(letter);
            boolean relative = Character.isLowerCase(letter);
            do {
                switch (command) {
                    case 'M': {
                        double x = reader.number() + (relative ? cx : 0);
                        double y = reader.number() + (relative ? cy : 0);
                        cx = x; cy = y; sx = x; sy = y;
                        out.add("M " + pair(x, y, scale));
                        break;
                    }
                    case 'L': {
                        double x = reader.number() + (relative ? cx : 0);
                        double y = reader.number() + (relative ? cy : 0);
                        cx = x; cy = y;
                        out.add("L " + pair(x, y, scale));
                        break;
                    }
                    case 'H': {
                        cx = reader.number() + (relative ? cx : 0);
                        out.add("L " + pair(cx, cy, scale));
                        break;
                    }
                    case 'V': {
                        cy = reader.number() + (relative ? cy : 0);
                        out.add("L " + pair(cx, cy, scale));
                        break;
                    }
                    case 'C': {
                        double x1 = reader.number() + (relative ? cx : 0);
                        double y1 = reader.number() + (relative ? cy : 0);
                        double x2 = reader.number() + (relative ? cx : 0);
                        double y2 = reader.number() + (relative ? cy : 0);
                        double x = reader.number() + (relative ? cx : 0);
                        double y = reader.number() + (relative ? cy : 0);
                        out.add("C " + pair(x1, y1, scale) + " " + pair(x2, y2, scale) + " " + pair(x, y, scale));
                        lastC1x = x2; lastC1y = y2;
                        lastQ1x = Double.NaN;
                        cx = x; cy = y;
                        break;
                    }
                    case 'S': {
                        double x2 = reader.number() + (relative ? cx : 0);
                        double y2 = reader.number() + (relative ? cy : 0);
                        double x = reader.number() + (relative ? cx : 0);
                        double y = reader.number() + (relative ? cy : 0);
                        double x1 = cx, y1 = cy;
                        if (!Double.isNaN(lastC1x)) {
                            x1 = 2 * cx - lastC1x;
                            y1 = 2 * cy - lastC1y;
                        }
                        out.add("C " + pair(x1, y1, scale) + " " + pair(x2, y2, scale) + " " + pair(x, y, scale));
                        lastC1x = x2; lastC1y = y2;
                        lastQ1x = Double.NaN;
                        cx = x; cy = y;
                        break;
                    }
                    case 'Q': {
                        double x1 = reader.number() + (relative ? cx : 0);
                        double y1 = reader.number() + (relative ? cy : 0);
                        double x = reader.number() + (relative ? cx : 0);
                        double y = reader.number() + (relative ? cy : 0);
                        double c1x = cx + 2.0 / 3.0 * (x1 - cx), c1y = cy + 2.0 / 3.0 * (y1 - cy);
                        double c2x = x + 2.0 / 3.0 * (x1 - x), c2y = y + 2.0 / 3.0 * (y1 - y);
                        out.add("C " + pair(c1x, c1y, scale) + " " + pair(c2x, c2y, scale) + " " + pair(x, y, scale));
                        lastQ1x = x1; lastQ1y = y1;
                        lastC1x = Double.NaN;
                        cx = x; cy = y;
                        break;
                    }
                    case 'T': {
                        double x = reader.number() + (relative ? cx : 0);
                        double y = reader.number() + (relative ? cy : 0);
                        double x1 = cx, y1 = cy;
                        if (!Double.isNaN(lastQ1x)) {
                            x1 = 2 * cx - lastQ1x;
                            y1 = 2 * cy - lastQ1y;
                        }
                        double c1x = cx + 2.0 / 3.0 * (x1 - cx), c1y = cy + 2.0 / 3.0 * (y1 - cy);
                        double c2x = x + 2.0 / 3.0 * (x1 - x), c2y = y + 2.0 / 3.0 * (y1 - y);
                        out.add("C " + pair(c1x, c1y, scale) + " " + pair(c2x, c2y, scale) + " " + pair(x, y, scale));
                        lastQ1x = x1; lastQ1y = y1;
                        lastC1x = Double.NaN;
                        cx = x; cy = y;
                        break;
                    }
                    case 'A': {
                        double rx = Math.abs(reader.number());
                        double ry = Math.abs(reader.number());
                        double rotation = Math.toRadians(reader.number());
                        boolean largeArc = reader.flag() != 0;
                        boolean sweep = reader.flag() != 0;
                        double x = reader.number() + (relative ? cx : 0);
                        double y = reader.number() + (relative ? cy : 0);
                        String cubics = arcToCubic(cx, cy, rx, ry, rotation, largeArc, sweep, x, y, scale);
                        if (!cubics.isEmpty()) {
                            out.add(cubics);
                        }
                        lastC1x = Double.NaN;
                        lastQ1x = Double.NaN;
                        cx = x; cy = y;
                        break;
                    }
                    case 'Z': {
                        out.add("Z");
                        cx = sx; cy = sy;
                        break;
                    }
                    default:
                        throw new IllegalStateException("不支持的命令: " + command);
                }
                // M 的隐式重复按 L 处理。
                if (command == 'M') {
                    command = 'L';
                }
            } while (command != 'Z' && reader.hasMoreParameters());
        }
        return String.join(" ", out);
    }

    // ─────────────────────────── 弧 → 三次贝塞尔 ───────────────────────────

    /** W3C F.6.5/F.6.6：端点参数化转中心参数化，按 ≤π/2 分段拟合。 */
    private static String arcToCubic(double x1, double y1, double rx, double ry, double phi,
                                     boolean largeArc, boolean sweep, double x2, double y2,
                                     double scale) {
        if (Math.abs(x1 - x2) < 1e-10 && Math.abs(y1 - y2) < 1e-10) {
            return "";
        }
        rx = Math.abs(rx);
        ry = Math.abs(ry);
        if (rx < 1e-10 || ry < 1e-10) {
            return "L " + pair(x2, y2, scale);
        }
        double dx2 = (x1 - x2) / 2.0, dy2 = (y1 - y2) / 2.0;
        double cosPhi = Math.cos(phi), sinPhi = Math.sin(phi);
        double x1p = cosPhi * dx2 + sinPhi * dy2;
        double y1p = -sinPhi * dx2 + cosPhi * dy2;
        double lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry);
        if (lambda > 1) {
            double sqrt = Math.sqrt(lambda);
            rx *= sqrt;
            ry *= sqrt;
        }
        double sign = (largeArc == sweep) ? -1 : 1;
        double radicand = (rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p)
                / (rx * rx * y1p * y1p + ry * ry * x1p * x1p);
        double factor = sign * Math.sqrt(Math.max(0, radicand));
        double cxp = factor * rx * y1p / ry;
        double cyp = -factor * ry * x1p / rx;
        double cx = cosPhi * cxp - sinPhi * cyp + (x1 + x2) / 2.0;
        double cy = sinPhi * cxp + cosPhi * cyp + (y1 + y2) / 2.0;
        double theta1 = angle(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry);
        double dTheta = angle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry);
        if (!sweep && dTheta > 0) {
            dTheta -= 2 * Math.PI;
        } else if (sweep && dTheta < 0) {
            dTheta += 2 * Math.PI;
        }
        int segments = Math.max(1, (int) Math.ceil(Math.abs(dTheta) / (Math.PI / 2)));
        double delta = dTheta / segments;
        double t = 4.0 / 3.0 * Math.tan(delta / 4.0);
        StringBuilder out = new StringBuilder();
        double theta = theta1;
        for (int s = 0; s < segments; s++) {
            double cosT1 = Math.cos(theta), sinT1 = Math.sin(theta);
            double cosT2 = Math.cos(theta + delta), sinT2 = Math.sin(theta + delta);
            double e1x = cosPhi * rx * cosT1 - sinPhi * ry * sinT1 + cx;
            double e1y = sinPhi * rx * cosT1 + cosPhi * ry * sinT1 + cy;
            double e2x = cosPhi * rx * cosT2 - sinPhi * ry * sinT2 + cx;
            double e2y = sinPhi * rx * cosT2 + cosPhi * ry * sinT2 + cy;
            double d1x = -(cosPhi * rx * sinT1 + sinPhi * ry * cosT1);
            double d1y = -(sinPhi * rx * sinT1 - cosPhi * ry * cosT1);
            double d2x = -(cosPhi * rx * sinT2 + sinPhi * ry * cosT2);
            double d2y = -(sinPhi * rx * sinT2 - cosPhi * ry * cosT2);
            double c1x = e1x + t * d1x, c1y = e1y + t * d1y;
            double c2x = e2x - t * d2x, c2y = e2y - t * d2y;
            out.append(s == 0 ? "C " : " C ")
                    .append(pair(c1x, c1y, scale)).append(' ')
                    .append(pair(c2x, c2y, scale)).append(' ')
                    .append(pair(e2x, e2y, scale));
            theta += delta;
        }
        return out.toString();
    }

    private static double angle(double ux, double uy, double vx, double vy) {
        double dot = ux * vx + uy * vy;
        double len = Math.sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy));
        double a = Math.acos(Math.max(-1, Math.min(1, dot / len)));
        if (ux * vy - uy * vx < 0) {
            a = -a;
        }
        return a;
    }

    /** 整圆/整椭圆 → 两段 180° 弧（各自展平为立方贝塞尔）。 */
    private static String ellipseToCubic(double cx, double cy, double rx, double ry, double scale) {
        String top = arcToCubic(cx - rx, cy, rx, ry, 0, false, true, cx + rx, cy, scale);
        String bottom = arcToCubic(cx + rx, cy, rx, ry, 0, false, true, cx - rx, cy, scale);
        return "M " + pair(cx - rx, cy, scale) + " " + top + " " + bottom + " Z";
    }

    private static String attribute(String attrs, String name) {
        Matcher m = Pattern.compile("\\b" + name + "\\s*=\\s*\"([^\"]*)\"").matcher(attrs);
        return m.find() ? m.group(1) : null;
    }

    private static double number(String attrs, String name) {
        Double v = optionalNumber(attrs, name);
        if (v == null) {
            throw new IllegalStateException("缺少属性 " + name);
        }
        return v;
    }

    private static Double optionalNumber(String attrs, String name) {
        String raw = attribute(attrs, name);
        return raw == null ? null : Double.parseDouble(raw);
    }

    private static String pair(double x, double y, double scale) {
        return num(x * scale) + " " + num(y * scale);
    }

    private static String num(double value) {
        double rounded = Math.round(value * 1000.0) / 1000.0;
        if (Math.abs(rounded - Math.rint(rounded)) < 1e-9) {
            return Long.toString(Math.round(rounded));
        }
        String s = String.format(Locale.ROOT, "%.3f", rounded);
        s = s.replaceAll("0+$", "");
        if (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.equals("-0")) {
            s = "0";
        }
        return s;
    }
}
