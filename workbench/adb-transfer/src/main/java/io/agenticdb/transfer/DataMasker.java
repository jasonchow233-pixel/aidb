package io.agenticdb.transfer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ⭐ P-3：导出行在<b>进编码器之前</b>的脱敏。
 *
 * <p>放在这一层而不是每种格式各写一份，是因为 CSV / TSV / JSON / XML / HTML /
 * SQL / xlsx 七条写出路径只要漏一条，用户就会在「以为已经脱敏」的文件里
 * 拿到明文 —— 这是安全功能，不能靠调用方自觉。
 *
 * <p>只改<b>值</b>不改列名：表头不是数据，把列名打码反而让导出文件不可用。
 * NULL 保持 NULL（不凭空造一个字符串），空串保持空串（不凭空造六个星号）。
 * 数字 / 日期 / 布尔等非文本值先转字符串再打码 —— 掩码结果本来就是文本，
 * SQL 编码器会因此给它加引号，这对被保护的列是正确行为。
 *
 * <p>⭐ HASH 加固：给导出选项带一个盐值后，摘要输入变成 {@code salt + ":" + value}。
 * 盐值不写进导出文件，所以只拿到文件的人无法用手机号 / 身份证这类低熵值构造彩虹表；
 * 空盐值保持旧行为（老脚本与老测试不必改），并且 HASH 始终只是「可比对但不可逆」的
 * 轻量脱敏 —— 需要强保护时还是选 FULL。
 */
public final class DataMasker {

    /** 固定长度，故意不按原值长度打码 —— 长度本身就是信息。 */
    private static final String FULL_MASK = "******";
    private static final int HASH_HEX_LENGTH = 16;

    private DataMasker() {
    }

    /** 单值掩码（不加盐，兼容 P-3 首版调用点）。{@code null} 值 / 空规则原样返回。 */
    public static Object mask(Object value, MaskRule rule) {
        return mask(value, rule, "");
    }

    /** 单值掩码，可带导出级盐值。 */
    public static Object mask(Object value, MaskRule rule, String salt) {
        if (value == null || rule == null) {
            return value;
        }
        String text = String.valueOf(value);
        if (text.isEmpty()) {
            return text;
        }
        return switch (rule.mode()) {
            case FULL -> FULL_MASK;
            case EDGES -> keepEdges(text);
            case HASH -> hashPrefix(text, salt);
        };
    }

    /**
     * 整行掩码：按列名找规则，列顺序与值个数都不变。
     *
     * @param names 列名（与 values 一一对应）；不足的部分按「无规则」处理
     */
    public static List<Object> maskRow(List<Object> values, List<String> names,
                                       List<MaskRule> rules) {
        return maskRow(values, names, rules, "");
    }

    /** 整行掩码，可带导出级盐值（由 {@link ExportOptions#maskSalt()} 一路带到所有格式）。 */
    public static List<Object> maskRow(List<Object> values, List<String> names,
                                       List<MaskRule> rules, String salt) {
        if (values == null || rules == null || rules.isEmpty()) {
            return values;
        }
        Map<String, MaskRule> byColumn = new HashMap<>();
        for (MaskRule rule : rules) {
            if (rule != null) {
                byColumn.put(rule.column(), rule);
            }
        }
        List<Object> masked = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            String name = names != null && i < names.size() ? names.get(i) : null;
            masked.add(mask(values.get(i), name == null ? null : byColumn.get(name), salt));
        }
        return masked;
    }

    private static String keepEdges(String text) {
        int length = text.length();
        if (length <= 2) {
            return "*".repeat(length);
        }
        // 中间也封顶 6 个星号：长文本不该在导出文件里膨胀出一大串掩码。
        return text.charAt(0) + "*".repeat(Math.min(length - 2, 6))
                + text.charAt(length - 1);
    }

    private static String hashPrefix(String text, String salt) {
        String input = salt == null || salt.isEmpty() ? text : salt + ":" + text;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return "sha256:" + hex.substring(0, HASH_HEX_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            // ⚠️ core/workbench 层的字符串字面量不许带中文（I18nAuditTest 的 CJK 棘轮）：
            //    领域层异常消息统一英文，要给人看的那份由 UI 层本地化。
            throw new IllegalStateException("SHA-256 is unavailable in this JRE", e);
        }
    }
}
