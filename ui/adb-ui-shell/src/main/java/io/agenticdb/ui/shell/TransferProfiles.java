package io.agenticdb.ui.shell;

import io.agenticdb.transfer.ImportFormat;
import io.agenticdb.transfer.ImportMode;
import io.agenticdb.transfer.ImportOptions;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 导入向导的选项配置文件（Navicat 的「保存配置文件 / 载入配置文件」）。
 *
 * <p>⭐ 存的只是<b>读文件的规矩</b>（格式 / 编码 / 表头行 / 导入模式），
 * 不存目标表、不存文件路径 —— 那两样每次导入都不一样，存下来只会让下一次
 * 静默往错误的表里灌数据。
 *
 * <p>编码格式：一行一个配置，字段用 {@code '\t'} 分隔；名称里不许出现制表符与换行。
 * 选这种朴素格式而不是 JSON，是因为它必须能被 {@link SettingsStore} 一个字符串存下，
 * 且人在 registry 里也能读。
 */
final class TransferProfiles {

    /** 偏好的键。整张表是一个字符串（行 = 配置，列 = 制表符分隔）。 */
    static final String KEY = "transfer.import.profiles";

    private static final String SEP = "\t";

    /**
     * 一条配置。
     *
     * @param name      配置名（用户起）
     * @param format    文件格式
     * @param charset   文本编码名（{@code UTF-8} / {@code GBK} …）；xlsx 忽略
     * @param headerRow 表头在第几行（1 起）；{@code 0} 表示没有表头
     * @param mode      追加 / 覆盖
     */
    record ImportProfile(String name, ImportFormat format, String charset, int headerRow,
                         ImportMode mode) {

        ImportProfile {
            name = name == null ? "" : name.replace('\t', ' ').replace('\n', ' ').trim();
            format = format == null ? ImportFormat.CSV : format;
            charset = charset == null || charset.isBlank()
                    ? StandardCharsets.UTF_8.name() : charset.trim();
            headerRow = Math.max(0, headerRow);
            mode = mode == null ? ImportMode.APPEND : mode;
        }

        String encode() {
            return String.join(SEP, name, format.name(), charset,
                    Integer.toString(headerRow), mode.name());
        }

        /** 把这份配置盖到一组选项上（其余字段保持调用方的值）。 */
        ImportOptions applyTo(ImportOptions base) {
            return base.withFormat(format)
                    .withCharset(charsetOf(charset))
                    .withHeaderRow(headerRow)
                    .withMode(mode);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private final SettingsStore settings;

    TransferProfiles() {
        this(new SettingsStore());
    }

    TransferProfiles(SettingsStore settings) {
        this.settings = settings;
    }

    /** 已保存的配置（按保存顺序）。坏行直接跳过 —— 一份脏配置不该让整个列表打不开。 */
    List<ImportProfile> list() {
        String raw = settings.get(KEY, "");
        List<ImportProfile> profiles = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return profiles;
        }
        for (String line : raw.split("\n")) {
            ImportProfile profile = decode(line);
            if (profile != null) {
                profiles.add(profile);
            }
        }
        return profiles;
    }

    /** 保存（同名覆盖）。空名字直接忽略 —— 用空名字当键会让两条配置互相吃掉。 */
    void save(ImportProfile profile) {
        if (profile == null || profile.name().isBlank()) {
            return;
        }
        List<ImportProfile> profiles = list();
        profiles.removeIf(existing -> existing.name().equals(profile.name()));
        profiles.add(profile);
        write(profiles);
    }

    void remove(String name) {
        List<ImportProfile> profiles = list();
        if (profiles.removeIf(existing -> existing.name().equals(name))) {
            write(profiles);
        }
    }

    static ImportProfile decode(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String[] parts = line.split(SEP, -1);
        if (parts.length < 5 || parts[0].isBlank()) {
            return null;
        }
        try {
            ImportFormat format = ImportFormat.valueOf(parts[1]);
            int headerRow = Integer.parseInt(parts[3].trim());
            ImportMode mode = ImportMode.valueOf(parts[4]);
            return new ImportProfile(parts[0], format, parts[2], headerRow, mode);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static Charset charsetOf(String name) {
        try {
            return Charset.forName(name);
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }

    private void write(List<ImportProfile> profiles) {
        StringBuilder text = new StringBuilder();
        for (ImportProfile profile : profiles) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(profile.encode());
        }
        settings.set(KEY, text.toString());
    }
}
