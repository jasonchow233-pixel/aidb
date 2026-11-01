package io.agenticdb.jdbc;

/**
 * ⭐ R42 N28-a：从「数据库存着的视图 DDL」里取出<b>查询体</b>。
 *
 * <p>视图设计器要预填的是 {@code SELECT …} 那一段，而六个方言交回来的形状各不相同：
 * <ul>
 *   <li>MySQL / MariaDB（{@code SHOW CREATE VIEW}）：
 *       {@code CREATE ALGORITHM=UNDEFINED DEFINER=`root`@`localhost` SQL SECURITY DEFINER VIEW \`v\` AS select …}</li>
 *   <li>PostgreSQL（{@code pg_get_viewdef}）：我方 {@link Dialect#readViewDdl} 已经
 *       包成 {@code CREATE OR REPLACE VIEW "v" AS SELECT …}</li>
 *   <li>SQLite（{@code sqlite_master.sql}）：就是用户当初写的 {@code CREATE VIEW "v" AS …}</li>
 *   <li>Oracle（{@code ALL_VIEWS.TEXT}）：12.2+ 给完整 DDL，更早的版本只给裸查询体</li>
 *   <li>SQL Server（{@code sys.sql_modules.definition}）：{@code CREATE VIEW dbo.v AS SELECT …}</li>
 * </ul>
 *
 * <p>切法只有一条规则：<b>头段里 {@code VIEW} 关键字之后的第一个 {@code AS}</b>。
 * 这个位置在六种写法里都是唯一正确的分界 ——
 * 从<b>后</b>往前找会切进查询体（列别名里全是 {@code AS}），
 * 不看 {@code VIEW} 直接找第一个 {@code AS} 会切在 {@code CREATE OR REPLACE VIEW} 之前。
 *
 * <p>取不出来时返回 {@code null}，让界面如实说明「读不到查询体」，
 * <b>不猜</b>：把整段 DDL 当查询体塞回 {@code CREATE VIEW … AS <整段 DDL>}
 * 会建出一个语法能过、内容全是垃圾的视图。
 */
public final class ViewDefinition {

    private ViewDefinition() {
    }

    /**
     * 取查询体。
     *
     * @param ddl 数据库交回的视图定义（完整 {@code CREATE …} 或裸查询体）
     * @return 查询体（已去首尾空白与尾分号）；无法解析时 {@code null}；
     *         输入本身就是裸查询体时原样收下
     */
    public static String selectBody(String ddl) {
        String text = ddl == null ? "" : ddl.trim();
        if (text.isEmpty()) {
            return null;
        }
        if (!looksLikeDdl(text)) {
            // 不是 DDL 就当裸查询体收下：Oracle 12.1 以前与部分驱动直接给 SELECT，
            // 而 {@code SELECT * FROM view} 这种正文里带「view」一词的裸查询
            // 也不该被下面的关键字定位绕晕。
            return trimTerminator(text);
        }
        int view = indexOfKeyword(text, "VIEW", 0);
        if (view < 0) {
            return null;
        }
        int separator = indexOfKeyword(text, "AS", view + "VIEW".length());
        if (separator < 0) {
            return null;
        }
        return trimTerminator(text.substring(separator + "AS".length()));
    }

    // 去掉尾分号，以及 MySQL「SHOW CREATE VIEW」尾巴上那个版本注释结束标记
    // （三个字符的感叹号-星号-斜杠加分号；写在块注释里会把注释本身关掉，所以这里用行注释）。
    private static String trimTerminator(String body) {
        String trimmed = body.trim();
        if (trimmed.endsWith("/*!*/;")) {
            trimmed = trimmed.substring(0, trimmed.length() - "/*!*/;".length()).trim();
        }
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 从 {@code from} 起找独立成词的关键字（大小写不敏感），返回起始下标；没有返回 -1。 */
    private static int indexOfKeyword(String text, String keyword, int from) {
        String upper = text.toUpperCase(java.util.Locale.ROOT);
        String target = keyword.toUpperCase(java.util.Locale.ROOT);
        int cursor = Math.max(0, from);
        while (true) {
            int hit = upper.indexOf(target, cursor);
            if (hit < 0) {
                return -1;
            }
            if (isWordBoundary(text, hit - 1) && isWordBoundary(text, hit + target.length())) {
                return hit;
            }
            cursor = hit + 1;
        }
    }

    /** 关键字两侧必须是「非单词字符」或边界 —— 否则 {@code my_view}、{@code ASC} 都会误命中。 */
    private static boolean isWordBoundary(String text, int index) {
        if (index < 0 || index >= text.length()) {
            return true;
        }
        char c = text.charAt(index);
        // 反引号 / 方括号 / 双引号都算分隔符：MySQL 的 `view` 与 SQL Server 的 [view]
        // 名字两侧就是这个字符，不能因为它不是「字母数字下划线」之外的字符就判成词内。
        return !Character.isLetterOrDigit(c) && c != '_';
    }

    /** 看起来像一条 DDL（而不是一段裸查询）—— 用于判断「认不出来该不该如实报错」。 */
    private static boolean looksLikeDdl(String text) {
        String upper = text.toUpperCase(java.util.Locale.ROOT);
        return upper.startsWith("CREATE") || upper.startsWith("ALTER")
                || upper.startsWith("DROP");
    }
}
