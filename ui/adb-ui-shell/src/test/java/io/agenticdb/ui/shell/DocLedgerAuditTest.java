package io.agenticdb.ui.shell;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ T3「文档棘轮」门禁：公开文档不许把<b>已交付的能力</b>写成「未完成 / 未实现 / 如实说明页」，
 * 也不许把用户已裁定<b>不做</b>的 ER 图写成「评估 / 落地 / 待启动」这类仍在办陈述。
 *
 * <p>这是纯文件扫描（同 {@code RibbonCapabilityAuditTest} 的源码审计路子）：不启 JavaFX toolkit、
 * 不连数据库、不碰场景图。它钉的是「文档 ⇔ 代码」这两份事实对得上：
 * ① 每个已交付能力都能在仓库里找到<b>真实存在的代码证据</b>（证据被删时门禁也红，避免棘轮空转）；
 * ② README / ROADMAP / STATUS 三份公开文档里，不得再出现该能力「还没做」的否定句式。
 *
 * <p><b>为什么统一小写 + 去 markdown 记号</b>：旧句写的是
 * {@code SSH tunnelling is **not implemented**}、{@code 没有 `danger` 参数} ——
 * 星号与反引号会把字面量匹配漏掉，所以逐行先 {@link #normalize(String)} 再比较
 * （小写一律 {@link Locale#ROOT}，不受运行环境语言影响）。
 *
 * <h2>变异验证（把一句话改回去，重跑本类必须红）</h2>
 * <ol>
 *   <li>把 {@code README.md}「Known limits」里 SSH 那句改回
 *       {@code SSH tunnelling is **not implemented** — ...}，重跑
 *       {@link #publicDocsNeverDescribeDeliveredCapabilitiesAsUnfinished()} 必须红；</li>
 *   <li>把 {@code docs/STATUS.md} §3 改回「危险分级第三层（驱动侧复查）不存在」、
 *       或 §4 改回「数据脱敏…尚未拍板」、或 ROADMAP backlog 改回
 *       「ER 图（只读结构图）—— 评估并落地…」，同一条断言必须红；</li>
 *   <li>删掉任一代码证据文件（例如 {@code DataMasker.java}），重跑
 *       {@link #deliveredCapabilitiesHaveCodeEvidence()} 必须红 —— 防止门禁空转；</li>
 *   <li>{@link #theRatchetActuallyCatchesTheKnownStaleSentences()} 反向自检：
 *       上面这些历史原句必须至少被一条否定句式命中，正则写错导致整份清单失灵时它会红。</li>
 * </ol>
 *
 * <p>⚠️ 本类不引入任何 {@code adb-*} styleClass、不调用 {@code I18n.t(...)}、不新增资源键 ——
 * 不惊动整仓扫描的 {@code I18nAuditTest} / {@code StyleClassCoverageTest}。
 */
class DocLedgerAuditTest {

    /** 三份公开文档，相对仓库根。少一份就是静默假绿，下面逐份验存在与长度。 */
    private static final List<String> DOCS = List.of(
            "README.md", "docs/ROADMAP.md", "docs/STATUS.md");

    /**
     * 能力名 → 代码证据（相对仓库根的路径 + 必须出现的记号）→ 文档里禁止出现的否定句式。
     * 否定句式是大小写无关的正则，逐行匹配 {@link #normalize(String)} 之后的文本。
     * {@code evidencePath == null} 表示「用户裁定不做」的纯文案门禁（ER 图），没有代码证据。
     */
    private record Capability(String name,
                              String evidencePath,
                              String evidenceToken,
                              List<String> forbidden) {}

    private static final List<Capability> CAPABILITIES = List.of(
            new Capability("SSH 隧道",
                    "ui/adb-ui-shell/src/main/java/io/agenticdb/ui/shell/SshHostKeysPane.java",
                    "known_hosts",
                    List.of("ssh tunnelling is not implemented",
                            "ssh 未实现",
                            "ssh 隧道未实现")),
            new Capability("危险分级第三层",
                    "workbench/adb-jdbc/src/main/java/io/agenticdb/jdbc/DbSession.java",
                    "requireDeclared",
                    List.of("driver-side second opinion 未完成",
                            "危险分级第三层未完成",
                            "not finished: a driver-side second opinion",
                            "takes no danger",
                            "没有 danger 参数",
                            "第三层（驱动侧复查）不存在")),
            new Capability("数据脱敏",
                    "workbench/adb-transfer/src/main/java/io/agenticdb/transfer/DataMasker.java",
                    "class DataMasker",
                    List.of("脱敏.*尚未拍板",
                            "脱敏.*如实说明页",
                            "脱敏.*仍是.*说明页")),
            new Capability("Filter OR 组",
                    "workbench/adb-introspect/src/main/java/io/agenticdb/introspect/FilterGroup.java",
                    "record FilterGroup",
                    List.of("只有平铺 and",
                            "or 组.*说明页")),
            /**
             * ⭐ 2026-11-15 第三轮：分页能力刚落地时，三份公开文档里同时留着
             * 「一次列完整层」「不暴露分页 token」「no progressive pagination」等旧口径。
             * 逐处改完还不够 —— 把它们收进棘轮，下次谁再把已交付写成未完成就红。
             */
            new Capability("对象存储渐进分页",
                    "workbench/adb-objectstore/src/main/java/io/agenticdb/objectstore/ObjectPage.java",
                    "record ObjectPage",
                    List.of("无渐进翻页",
                            "不暴露分页 token",
                            "无渐进加载",
                            "无法渐进加载",
                            "分页 token 缺口",
                            "no progressive pagination",
                            "lists a whole delimiter level in one call")),
            /**
             * ⭐ 2026-11-15 第四轮：Redis over SSH 刚接入（P1-1 闭环）。公开文档此前同时留着
             * "Redis over SSH is not wired up" / "Redis over SSH 未接入" / "SSH 段不作用于 Redis"
             * 等旧口径，逐处改完还不够 —— 把它们收进棘轮，下次谁再把已交付写成未完成就红。
             */
            new Capability("Redis over SSH",
                    "workbench/adb-redis/src/main/java/io/agenticdb/redis/RedisSessions.java",
                    "SshTunnel.open",
                    List.of("redis over ssh is not wired up",
                            "redis over ssh 未接入",
                            "redis connections currently go direct",
                            "ssh 段目前不作用于 redis")),
            new Capability("ER 图（用户裁定不做）",
                    null, null,
                    List.of("只读 er 图.*评估",
                            "只读 er 图.*落地",
                            "只读 er 图.*待启动",
                            "只读 er 图.*未承诺",
                            "评估并落地只读实体关系图",
                            "er 图.*评估并落地",
                            "er 图.*待启动"))
    );

    /**
     * 正向半边：每个已交付能力的代码证据必须真的在、且不是空壳。
     * 证据文件被删时这条门禁也要红 —— 否则剩下的文案扫描会退化成「没人守的空清单」。
     */
    @Test
    void deliveredCapabilitiesHaveCodeEvidence() throws IOException {
        Path root = repositoryRoot();
        for (Capability capability : CAPABILITIES) {
            if (capability.evidencePath() == null) {
                continue; // 裁定不做的纯文案门禁，没有代码证据
            }
            Path evidence = root.resolve(capability.evidencePath());
            assertThat(Files.isRegularFile(evidence))
                    .as("%s：代码证据文件 %s 必须存在（删了它，这条门禁就得红，防止棘轮空转）",
                            capability.name(), capability.evidencePath())
                    .isTrue();
            assertThat(read(evidence))
                    .as("%s：证据文件 %s 必须真的含 %s（不是空壳）",
                            capability.name(), capability.evidencePath(), capability.evidenceToken())
                    .contains(capability.evidenceToken());
        }
    }

    /**
     * 核心棘轮：三份公开文档里，命中任一「已交付能力被写成未完成」的否定句式就红。
     * 命中明细带文件名与行号，方便直接定位到那一句。
     */
    @Test
    void publicDocsNeverDescribeDeliveredCapabilitiesAsUnfinished() throws IOException {
        int totalPatterns = CAPABILITIES.stream().mapToInt(c -> c.forbidden().size()).sum();
        assertThat(totalPatterns)
                .as("否定句式清单不能是空的 —— 空清单会让棘轮假绿")
                .isGreaterThanOrEqualTo(20);

        Path root = repositoryRoot();
        List<String> violations = new ArrayList<>();
        for (String doc : DOCS) {
            Path path = root.resolve(doc);
            assertThat(Files.isRegularFile(path))
                    .as("公开文档 %s 必须存在（少了它，扫描就是假绿）", doc)
                    .isTrue();
            String text = read(path);
            assertThat(text.length())
                    .as("公开文档 %s 不能是空的（空文件会让棘轮假绿）", doc)
                    .isGreaterThan(200);
            String[] lines = text.split("\\R");
            assertThat(lines.length)
                    .as("公开文档 %s 的行数不足以构成一次真扫描（假绿）", doc)
                    .isGreaterThan(10);

            for (Capability capability : CAPABILITIES) {
                for (String forbidden : capability.forbidden()) {
                    Pattern pattern = Pattern.compile(forbidden);
                    for (int i = 0; i < lines.length; i++) {
                        Matcher matcher = pattern.matcher(normalize(lines[i]));
                        if (matcher.find()) {
                            violations.add(String.format(
                                    "%s:%d 能力[%s] 命中否定句式 /%s/ → %s",
                                    doc, i + 1, capability.name(), forbidden, lines[i].trim()));
                        }
                    }
                }
            }
        }

        boolean found = !violations.isEmpty();
        assertThat(found)
                .as("公开文档不得把已交付能力写成未完成 / 未实现 / 说明页；命中明细（文件 · 行号 · 句式）：\n  %s",
                        String.join("\n  ", violations))
                .isFalse();
    }

    /**
     * 反向自检：这些「改回去的旧句」必须至少被一条否定句式命中。
     * 只验文档不命中还不够 —— 正则写错、清单形同虚设时同样会全绿，这条把它钉住。
     */
    @Test
    void theRatchetActuallyCatchesTheKnownStaleSentences() {
        List<String> knownStale = List.of(
                "SSH tunnelling is **not implemented** — the connection wizard says so",
                "3. **Not finished: a driver-side second opinion.** `DbSession.execute(sql)` takes no danger argument",
                "危险分级**第三层（驱动侧复查）不存在**：`DbSession.execute(sql)` 没有 `danger` 参数",
                "**数据脱敏**做不做**尚未拍板**（不在 P1 那三面里",
                "3. **数据脱敏导出** —— 此前为「如实说明页」，现转为真实能力",
                "底层 `DataQuery` 只有平铺 AND —— `and` 固定，`[Ω]`（OR 组）给如实说明页",
                "4. **ER 图（只读结构图）** —— 评估并落地只读实体关系图",
                "⚠️ 口径分清：**只读 ER 图**仍在评估、未承诺",
                "Redis over SSH is not wired up yet — the connection wizard says so",
                "⚠️ Redis over SSH 未接入（还在说明页）");

        List<Pattern> all = CAPABILITIES.stream()
                .flatMap(c -> c.forbidden().stream())
                .map(Pattern::compile)
                .toList();
        List<String> missed = new ArrayList<>();
        for (String stale : knownStale) {
            String normalized = normalize(stale);
            if (all.stream().noneMatch(p -> p.matcher(normalized).find())) {
                missed.add(stale);
            }
        }
        assertThat(missed)
                .as("历史陈旧句必须至少被一条否定句式命中（否则棘轮是假绿）：\n  %s",
                        String.join("\n  ", missed))
                .isEmpty();
    }

    // ─────────────────────────── 工具 ───────────────────────────

    /**
     * 测试进程 cwd 通常是 {@code ui/adb-ui-shell}：从这里逐级向上找同时含
     * {@code pom.xml} 与 {@code README.md} 的目录（最多 6 级），不硬编码绝对路径。
     */
    private static Path repositoryRoot() {
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i <= 6 && cursor != null; i++, cursor = cursor.getParent()) {
            if (Files.isRegularFile(cursor.resolve("pom.xml"))
                    && Files.isRegularFile(cursor.resolve("README.md"))) {
                return cursor;
            }
        }
        throw new IllegalStateException("找不到仓库根目录（含 pom.xml 与 README.md 的目录）");
    }

    /** 统一小写（Locale.ROOT）并去掉 markdown 的 * 与行内代码反引号，免得加粗/代码把否定句漏掉。 */
    private static String normalize(String line) {
        return line.replace("*", "").replace("`", "").toLowerCase(Locale.ROOT);
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
