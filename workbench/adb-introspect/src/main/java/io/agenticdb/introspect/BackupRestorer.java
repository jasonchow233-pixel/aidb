package io.agenticdb.introspect;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * ⭐ P2 Backup（纯 JDBC 恢复）：把 {@link BackupDumper} 落盘的 {@code .sql} 文件重放到目标库。
 *
 * <p>两件事职责分离：{@link BackupDumper} 只负责「把库当前状态落盘」，本类只负责
 * 「把落盘的状态重放回去」。这样恢复路径可以脱离真实写库被单测覆盖，而落盘侧也只测生成。
 *
 * <p>重放是<b>语句级</b>的：先把文件按 {@code ;} 切成一条条语句（切分尊重字符串字面量
 * 与注释，避免把值里的 {@code ;} 误断），再逐条 {@code Statement.execute}。它能重放
 * {@code BackupDumper} 生成的文件，也能重放其它工具导出的、同样以 {@code ;} 结尾的脚本。
 *
 * <p>不自动 DROP 既有表：清空 / 重建由调用方决定（例如先 {@link #tableNames} 取出表名、
 * 再按自己的策略删旧表）。这样恢复既可用于「灌进空库」，也可用于「覆盖已有表」（只要库里
 * 没有同名表，或调用方已先清掉）。
 */
public final class BackupRestorer {

    private BackupRestorer() {
    }

    /**
     * 把 dump 文件重放到连接对应的库。
     *
     * @return 实际执行的语句条数（空语句不计）
     * @throws SQLException 任意一条语句执行失败 —— 已执行的语句不会回滚（调用方应自行控制事务）
     */
    public static int restore(Connection connection, Reader in) throws SQLException, IOException {
        String script = readAll(in);
        List<String> statements = splitStatements(script);
        int executed = 0;
        try (Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                try {
                    statement.execute(sql);
                } catch (SQLException e) {
                    throw new SQLException("restore failed at statement #" + (executed + 1)
                            + ": " + e.getMessage(), e);
                }
                executed++;
            }
        }
        return executed;
    }

    /**
     * 取出 dump 里 {@code -- table: NAME} 标记列出的表名（用于「先删旧表再恢复」）。
     * {@code -- view:} 是另一种标记，不会被这里误当成表。
     */
    public static List<String> tableNames(Reader in) throws IOException {
        return markedNames(in, "-- table: ");
    }

    /**
     * 取出 dump 里 {@code -- view: NAME} 标记列出的视图名。
     *
     * <p>视图与表分开列，是为了让「恢复前先清理」的调用方不会把视图当表 DROP ——
     * 两种对象在不同方言里要用不同的删除语句。
     */
    public static List<String> viewNames(Reader in) throws IOException {
        return markedNames(in, "-- view: ");
    }

    private static List<String> markedNames(Reader in, String marker) throws IOException {
        List<String> names = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(in)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith(marker)) {
                    names.add(trimmed.substring(marker.length()).trim());
                }
            }
        }
        return names;
    }

    /** 把整份脚本读成字符串。 */
    private static String readAll(Reader in) throws IOException {
        StringBuilder builder = new StringBuilder();
        char[] buffer = new char[4096];
        int read;
        while ((read = in.read(buffer)) != -1) {
            builder.append(buffer, 0, read);
        }
        return builder.toString();
    }

    /**
     * 切分 SQL 为语句列表：以顶层 {@code ;} 为界，忽略字符串字面量与注释里的 {@code ;}。
     *
     * <p>支持的转义：{@code --} 行注释、{@code /* *\/} 块注释、{@code '...'} 字符串
     * （含 {@code ''} 转义）。与 {@link BackupDumper} 生成的格式一一对应，因此重放文件
     * 里值里的 {@code ;} 不会被误断。
     */
    static List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int i = 0;
        int len = sql.length();
        while (i < len) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                int newline = sql.indexOf('\n', i);
                i = newline == -1 ? len : newline + 1;
                continue;
            }
            if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end == -1 ? len : end + 2;
                continue;
            }
            if (c == '\'') {
                current.append(c);
                i++;
                while (i < len) {
                    char d = sql.charAt(i);
                    current.append(d);
                    if (d == '\'') {
                        if (i + 1 < len && sql.charAt(i + 1) == '\'') {
                            current.append(sql.charAt(i + 1));
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (c == ';') {
                String trimmed = current.toString().trim();
                if (!trimmed.isEmpty()) {
                    statements.add(trimmed);
                }
                current.setLength(0);
                i++;
                continue;
            }
            current.append(c);
            i++;
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            statements.add(tail);
        }
        return statements;
    }
}
