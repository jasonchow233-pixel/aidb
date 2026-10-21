package io.agenticdb.declarations;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * ⭐ 声明层加载器 —— 全项目唯一的真相源入口。
 *
 * <p>移植自 Rust 侧 {@code adb-types/build.rs}（638 行）。
 * <b>输出必须与 Rust 侧逐字段一致</b>，因为 UI、传输规划、连接向导
 * 全部依赖它，且这些行为已经被 83 条 API 契约断言覆盖过。
 *
 * <h2>加载 vs 校验的分工</h2>
 * <ul>
 *   <li>本类：加载 + 校验 + 映射成 record。<b>一次性</b>，启动时跑</li>
 *   <li>Maven 校验：在 {@code process-sources} 阶段跑同一套校验，
 *       让「改错 YAML 编译不过」这条防线不丢</li>
 * </ul>
 *
 * <h2>两个数据源</h2>
 * <ol>
 *   <li>{@code file:.../declarations} —— 开发时从源码目录读，改 YAML 立即生效</li>
 *   <li>{@code jar:/declarations} —— 生产时从 jar 内读，随应用分发</li>
 * </ol>
 * 两者的解析结果必须完全相同，这本身要有一条测试守着
 * （{@code DeclarationLoaderTest.classpathAndPathAgree}）。
 */
public final class DeclarationLoader {

    private DeclarationLoader() {
    }

    /**
     * 加载并校验全部声明。
     *
     * @param root {@code declarations/} 的路径，或 jar 内的虚拟路径
     * @throws DeclarationException 任何一条校验不通过
     */
    public static DeclaredModel load(Path root) throws DeclarationException {
        List<Issue> issues = new ArrayList<>();

        List<Map<String, Object>> connections = readAll(root, "connections", issues);
        List<Map<String, Object>> families = readAll(root, "transfer-families", issues);
        List<Map<String, Object>> dialects = readAll(root, "dialects", issues);

        if (!issues.isEmpty()) {
            // 语法都过不了就别做语义校验了，报出来的都是噪音
            throw new DeclarationException(issues);
        }

        validate(connections, families, dialects, issues);
        validateAgainstJsonSchema(connections, root, issues);

        if (!issues.isEmpty()) {
            throw new DeclarationException(issues);
        }

        return mapToModel(connections, families, dialects);
    }

    /** 从 classpath（jar 内）加载。生产路径。 */
    public static DeclaredModel loadFromClasspath() throws DeclarationException {
        List<Issue> issues = new ArrayList<>();
        List<Map<String, Object>> connections = readAllFromJar("connections", issues);
        List<Map<String, Object>> families = readAllFromJar("transfer-families", issues);
        List<Map<String, Object>> dialects = readAllFromJar("dialects", issues);

        if (!issues.isEmpty()) {
            throw new DeclarationException(issues);
        }
        validate(connections, families, dialects, issues);
        validateAgainstJsonSchema(connections, null, issues);
        if (!issues.isEmpty()) {
            throw new DeclarationException(issues);
        }
        return mapToModel(connections, families, dialects);
    }

    // ──────────────────────────── 读取 ────────────────────────────

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> readAll(Path root, String sub, List<Issue> issues) {
        List<Map<String, Object>> out = new ArrayList<>();
        Path dir = root.resolve(sub);
        if (!Files.isDirectory(dir)) {
            issues.add(new Issue(Issue.Rule.YAML_SYNTAX, dir.toString(), "目录不存在"));
            return out;
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException e) {
            issues.add(new Issue(Issue.Rule.YAML_SYNTAX, dir.toString(), "读取失败: " + e.getMessage()));
            return out;
        }
        for (Path f : files) {
            Map<String, Object> m = parseYaml(f, issues);
            if (m != null) {
                m.put("__file", f.getFileName().toString());
                out.add(m);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> readAllFromJar(String sub, List<Issue> issues) {
        List<Map<String, Object>> out = new ArrayList<>();
        // jar 内是构建期复制的快照，目录名保持一致
        java.net.URL url = DeclarationLoader.class.getResource("/declarations/" + sub);
        if (url == null) {
            issues.add(new Issue(Issue.Rule.YAML_SYNTAX, "jar:/declarations/" + sub, "资源不存在"));
            return out;
        }
        try {
            if ("jar".equals(url.getProtocol())) {
                readFromJar(url, sub, issues, out);
            } else {
                // 爆炸 classpath：url 指向一个**目录**，要枚举目录里的文件。
                // ⭐ 旧实现把目录当成「换行分隔的文件名索引」去读 —— 那永远读不到东西：
                //    目录在 classpath 下要么抛 IOException，要么读出空字符串，
                //    结果是**静默返回空模型**（调用方以为加载了 4 个连接，实际是 0 个）。
                readFromDirectory(Path.of(url.toURI()), sub, issues, out);
            }
        } catch (IOException | java.net.URISyntaxException e) {
            issues.add(new Issue(Issue.Rule.YAML_SYNTAX, "jar:/declarations/" + sub,
                    "读取失败: " + e.getMessage()));
        }
        return out;
    }

    /** 爆炸 classpath：枚举目录里的 YAML。 */
    @SuppressWarnings("unchecked")
    private static void readFromDirectory(Path dir, String sub, List<Issue> issues,
                                          List<Map<String, Object>> out) throws IOException {
        if (!Files.isDirectory(dir)) {
            issues.add(new Issue(Issue.Rule.YAML_SYNTAX, dir.toString(), "不是目录"));
            return;
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
        for (Path f : files) {
            Map<String, Object> m = YamlReader.parse(Files.readAllBytes(f));
            if (m != null) {
                m.put("__file", f.getFileName().toString());
                out.add(m);
            }
        }
    }

    /** 打包 jar：枚举 jar 条目。 */
    @SuppressWarnings("unchecked")
    private static void readFromJar(java.net.URL url, String sub, List<Issue> issues,
                                    List<Map<String, Object>> out) throws IOException,
            java.net.URISyntaxException {
        String external = url.toExternalForm();
        int separator = external.indexOf("!/");
        if (separator < 0) {
            issues.add(new Issue(Issue.Rule.YAML_SYNTAX, external, "不是合法的 jar URL"));
            return;
        }
        // "jar:file:/path/to.jar!/declarations/connections" → "file:/path/to.jar"
        String jarUrl = external.substring(0, separator);
        if (jarUrl.startsWith("jar:")) {
            jarUrl = jarUrl.substring("jar:".length());
        }
        String prefix = "declarations/" + sub + "/";
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(
                Path.of(new java.net.URI(jarUrl)).toFile())) {
            List<String> names = jar.stream()
                    .map(java.util.jar.JarEntry::getName)
                    .filter(name -> name.startsWith(prefix))
                    .filter(name -> name.endsWith(".yaml") || name.endsWith(".yml"))
                    .sorted()
                    .toList();
            for (String name : names) {
                java.util.jar.JarEntry entry = jar.getJarEntry(name);
                if (entry == null) {
                    continue;
                }
                try (InputStream is = jar.getInputStream(entry)) {
                    Map<String, Object> m = YamlReader.parse(is.readAllBytes());
                    if (m != null) {
                        m.put("__file", name.substring(prefix.length()));
                        out.add(m);
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseYaml(Path file, List<Issue> issues) {
        try {
            Map<String, Object> m = YamlReader.parse(Files.readAllBytes(file));
            if (m == null) {
                issues.add(new Issue(Issue.Rule.YAML_SYNTAX, file.getFileName().toString(), "不是对象"));
            }
            return m;
        } catch (IOException e) {
            issues.add(new Issue(Issue.Rule.YAML_SYNTAX, file.getFileName().toString(),
                    "读取失败: " + e.getMessage()));
            return null;
        } catch (RuntimeException e) {
            issues.add(new Issue(Issue.Rule.YAML_SYNTAX, file.getFileName().toString(),
                    "YAML 解析失败: " + e.getMessage()));
            return null;
        }
    }

    // ──────────────────────────── 15 条校验 ────────────────────────────

    @SuppressWarnings("unchecked")
    private static void validate(
            List<Map<String, Object>> connections,
            List<Map<String, Object>> families,
            List<Map<String, Object>> dialects,
            List<Issue> issues) {

        // ④ 先建 dialect id 索引
        Set<String> dialectIds = new TreeSet<>();
        for (Map<String, Object> d : dialects) {
            dialectIds.add(str(d, "id"));
        }
        Set<String> familyIds = new TreeSet<>();
        Map<String, List<String>> familyMembers = new LinkedHashMap<>();
        for (Map<String, Object> f : families) {
            String fid = str(f, "id");
            familyIds.add(fid);
            Object m = f.get("members");
            familyMembers.put(fid,
                    m instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of());
            require(issues, f, Issue.Rule.SCHEMA_VERSION, "schemaVersion", eq(intOf(f, "schemaVersion"), 1));
        }

        Set<String> seenId = new TreeSet<>();
        Set<String> seenVariant = new TreeSet<>();
        Set<Integer> seenOrder = new TreeSet<>();

        for (Map<String, Object> c : connections) {
            String file = str(c, "__file");
            String id = str(c, "id");

            // ① schemaVersion
            require(issues, c, Issue.Rule.SCHEMA_VERSION, "schemaVersion", eq(intOf(c, "schemaVersion"), 1));

            // ②③ 重复
            if (!seenId.add(id)) {
                add(issues, Issue.Rule.DUPLICATE_FIELD, file, "id 重复: " + id);
            }
            String variant = str(c, "rustVariant");
            if (!seenVariant.add(variant)) {
                add(issues, Issue.Rule.DUPLICATE_FIELD, file, "rustVariant 重复: " + variant);
            }
            Integer order = intOf(c, "order");
            if (order != null && !seenOrder.add(order)) {
                add(issues, Issue.Rule.DUPLICATE_FIELD, file, "order 重复: " + order);
            }

            // ④ dialect 必须声明
            String dialect = str(c, "dialect");
            if (dialect == null || dialect.isBlank()) {
                add(issues, Issue.Rule.UNKNOWN_DIALECT, file, "缺 dialect");
            } else if (!dialectIds.contains(dialect)) {
                add(issues, Issue.Rule.UNKNOWN_DIALECT, file,
                        "dialect 「%s」 在 dialects/ 里没有对应文件".formatted(dialect));
            }

            // ⑤ transferObjectFamily 必须声明
            String fam = str(c, "transferObjectFamily");
            if (fam == null || fam.isBlank()) {
                add(issues, Issue.Rule.UNKNOWN_FAMILY, file, "缺 transferObjectFamily");
            } else if (!familyIds.contains(fam)) {
                add(issues, Issue.Rule.UNKNOWN_FAMILY, file,
                        "transferObjectFamily 「%s」 不存在".formatted(fam));
            } else if (!familyMembers.get(fam).contains(id)) {
                // ⑩ 连接不属于任何家族 —— 传输规划会直接断掉
                add(issues, Issue.Rule.CONNECTION_IN_NO_FAMILY, file,
                        "id 「%s」 不在家族 %s 的 members 里".formatted(id, fam));
            }

            // ⑥⑮ capabilities
            Object capsObj = c.get("capabilities");
            if (!(capsObj instanceof Map)) {
                add(issues, Issue.Rule.CAPABILITIES_ALL_FALSE, file, "缺 capabilities 段");
            } else {
                Map<String, Object> caps = (Map<String, Object>) capsObj;
                Set<String> missing = new TreeSet<>(ConnectionDescriptor.Capabilities.ALL_KEYS);
                missing.removeAll(caps.keySet());
                if (!missing.isEmpty()) {
                    add(issues, Issue.Rule.CAPABILITY_MISSING, file,
                            "以下能力未显式声明: " + missing);
                }
                boolean allFalse = caps.values().stream()
                        .allMatch(v -> Boolean.FALSE.equals(v));
                boolean hasSurface = Boolean.TRUE.equals(caps.get("specializedSurface"));
                if (allFalse && !hasSurface) {
                    add(issues, Issue.Rule.CAPABILITIES_ALL_FALSE, file,
                            "capabilities 全为 false 且 specializedSurface 也不是 true —— "
                                    + "说明这段是漏填的，不是有意的");
                }
            }

            // ⑦⑪ runtimeMode 与 mcpMode
            String runtimeMode = str(c, "runtimeMode");
            String mcpMode = str(c, "mcpMode");
            boolean isJdbc = "jdbc".equals(runtimeMode);
            if (isJdbc) {
                String dc = str(c, "driverClass");
                if (dc == null || dc.isBlank()) {
                    add(issues, Issue.Rule.JDBC_WITHOUT_DRIVER, file,
                            "runtimeMode=jdbc 必须有 driverClass");
                }
            }
            if ("bridge".equals(mcpMode) && !isJdbc) {
                add(issues, Issue.Rule.MCP_BRIDGE_WITHOUT_JDBC, file,
                        "mcpMode=bridge 要求 runtimeMode=jdbc，当前是 %s".formatted(runtimeMode));
            }

            // ⑧ checksum 占位符
            Object art = c.get("driverArtifact");
            if (art instanceof Map<?, ?> am) {
                Object checksum = am.get("checksum");
                if (checksum != null) {
                    String s = String.valueOf(checksum).toUpperCase(java.util.Locale.ROOT);
                    if (s.contains("REPLACE_ME") || s.contains("TODO") || s.contains("XXX")) {
                        add(issues, Issue.Rule.CHECKSUM_PLACEHOLDER, file,
                                "driverArtifact.checksum 还是占位符: " + checksum);
                    }
                }
            }

            // ⑨⑫ traits
            Object traitsObj = c.get("traits");
            if (!(traitsObj instanceof Map)) {
                add(issues, Issue.Rule.SCHEMA_VIOLATION, file, "缺 traits 段");
            } else {
                Map<String, Object> tr = (Map<String, Object>) traitsObj;
                String identityKind = String.valueOf(tr.getOrDefault("identityKind", ""));
                boolean sequenceObjects = Boolean.TRUE.equals(tr.get("sequenceObjects"));
                if ("sequence".equals(identityKind) && !sequenceObjects) {
                    add(issues, Issue.Rule.IDENTITY_SEQUENCE_CONTRADICTION, file,
                            "identityKind=sequence 但 sequenceObjects=false，自相矛盾");
                }
                Object ef = tr.get("explainFormats");
                if (!(ef instanceof List<?> l) || l.isEmpty()) {
                    add(issues, Issue.Rule.EMPTY_EXPLAIN_FORMATS, file,
                            "explainFormats 不能为空");
                }
            }
        }
    }

    /**
     * ⑭ 按 {@code schema/connection.schema.json} 真校验。
     *
     * <p>⭐ 这是<b>升级而非等价移植</b>：Rust 侧把这个 324 行的 JSON Schema
     * 当文档放着，{@code build.rs} 从未跑过它。Java 侧第一次真正执行。
     */
    @SuppressWarnings("unchecked")
    private static void validateAgainstJsonSchema(
            List<Map<String, Object>> connections, Path root, List<Issue> issues) {

        String schemaJson = null;
        try {
            if (root != null) {
                Path p = root.resolve("schema/connection.schema.json");
                if (Files.exists(p)) {
                    schemaJson = Files.readString(p);
                }
            } else {
                try (InputStream is = DeclarationLoader.class
                        .getResourceAsStream("/declarations/schema/connection.schema.json")) {
                    if (is != null) {
                        schemaJson = new String(is.readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8);
                    }
                }
            }
        } catch (IOException ignored) {
            // schema 文件缺失不算致命 —— 语义校验已经跑了
            return;
        }
        if (schemaJson == null) {
            return;
        }

        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            // ⠿ 2.0.8 的 API 是 SchemaRegistry（不是 1.x 的 JsonSchemaFactory）。
            // 网络上还大量文章停留在 1.x 的 API，照抄会编译不过。
            var registry = com.networknt.schema.SchemaRegistry.withDefaultDialect(
                    com.networknt.schema.SpecificationVersion.DRAFT_7);
            var schema = registry.getSchema(schemaJson);
            for (Map<String, Object> c : connections) {
                Object file = c.get("__file");
                var errors = schema.validate(mapper.valueToTree(withoutMeta(c)));
                if (!errors.isEmpty()) {
                    add(issues, Issue.Rule.SCHEMA_VIOLATION, String.valueOf(file),
                            errors.size() + " 处不合法，首条: " + errors.get(0).getMessage());
                }
            }
        } catch (RuntimeException e) {
            add(issues, Issue.Rule.SCHEMA_VIOLATION, "schema/connection.schema.json",
                    "校验器执行失败: " + e.getMessage());
        }
    }

    // ──────────────────────────── 映射 ────────────────────────────

    @SuppressWarnings("unchecked")
    private static DeclaredModel mapToModel(
            List<Map<String, Object>> connections,
            List<Map<String, Object>> families,
            List<Map<String, Object>> dialects) {

        List<ConnectionDescriptor> conns = connections.stream()
                .map(DeclarationLoader::toDescriptor).toList();
        List<TransferFamily> fams = families.stream()
                .map(DeclarationLoader::toFamily).sorted(
                        Comparator.comparing(TransferFamily::id)).toList();
        List<Dialect> dias = dialects.stream()
                .map(DeclarationLoader::toDialect).sorted(
                        Comparator.comparing(Dialect::id)).toList();
        return new DeclaredModel(conns, fams, dias, List.of());
    }

    @SuppressWarnings("unchecked")
    private static ConnectionDescriptor toDescriptor(Map<String, Object> m) {
        ConnectionDescriptor.Traits t = new ConnectionDescriptor.Traits(
                bool(m, "traits", "schemaAware"), bool(m, "traits", "singleDatabase"),
                bool(m, "traits", "clearableQuerySchema"), str(m, "traits", "identityKind"),
                bool(m, "traits", "sequenceObjects"), bool(m, "traits", "transactionalDdl"),
                bool(m, "traits", "onlineDdlConcurrent"), bool(m, "traits", "multipleResultsets"),
                bool(m, "traits", "savepoints"), strList(m, "traits", "explainFormats"),
                intOr(m, "traits", "maxParams", 0), str(m, "traits", "identifierQuote"),
                str(m, "traits", "paramStyle"));

        ConnectionDescriptor.Capabilities c = new ConnectionDescriptor.Capabilities(
                bool(m, "capabilities", "queryExecution"), bool(m, "capabilities", "metadataBrowse"),
                bool(m, "capabilities", "objectBrowser"), bool(m, "capabilities", "objectSource"),
                bool(m, "capabilities", "schemaSearch"), bool(m, "capabilities", "diagram"),
                bool(m, "capabilities", "tableDataEdit"), bool(m, "capabilities", "tableStructureEdit"),
                bool(m, "capabilities", "tableImport"), bool(m, "capabilities", "dataTransfer"),
                bool(m, "capabilities", "sqlFileExecution"), bool(m, "capabilities", "databaseCreate"),
                bool(m, "capabilities", "fieldLineage"), bool(m, "capabilities", "sqlExplain"),
                bool(m, "capabilities", "userAdmin"), bool(m, "capabilities", "specializedSurface"));

        ConnectionDescriptor.DriverArtifact art = null;
        Object ao = m.get("driverArtifact");
        if (ao instanceof Map<?, ?> rawArt) {
            @SuppressWarnings("unchecked")
            Map<String, Object> am = (Map<String, Object>) rawArt;
            art = new ConnectionDescriptor.DriverArtifact(
                    String.valueOf(am.getOrDefault("key", "")),
                    String.valueOf(am.getOrDefault("jar", "")),
                    String.valueOf(am.getOrDefault("checksum", "")),
                    String.valueOf(am.getOrDefault("license", "")));
        }

        return new ConnectionDescriptor(
                intOf(m, "schemaVersion"), intOf(m, "order"),
                str(m, "id"), str(m, "rustVariant"), str(m, "label"), str(m, "icon"),
                str(m, "dialect"), str(m, "runtimeMode"), str(m, "mcpMode"),
                intOf(m, "defaultPort"), str(m, "urlTemplate"), str(m, "driverClass"),
                art, t, c, strList(m, "compareModes"),
                str(m, "transferSupportLevel"), str(m, "transferObjectFamily"),
                str(m, "supportLevel"));
    }

    @SuppressWarnings("unchecked")
    private static TransferFamily toFamily(Map<String, Object> m) {
        Map<String, TransferFamily.TypeRule> mapping = new TreeMap<>();
        Object tm = m.get("typeMapping");
        if (tm instanceof Map<?, ?> src) {
            for (Map.Entry<?, ?> e : src.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> rule) {
                    Map<String, String> to = new TreeMap<>();
                    Object t = rule.get("to");
                    if (t instanceof Map<?, ?> tm2) {
                        tm2.forEach((k, v) -> to.put(String.valueOf(k), String.valueOf(v)));
                    }
                    mapping.put(String.valueOf(e.getKey()), new TransferFamily.TypeRule(
                            Boolean.TRUE.equals(rule.get("lossy")), to));
                }
            }
        }
        return new TransferFamily(
                intOf(m, "schemaVersion"), str(m, "id"), str(m, "label"),
                strList(m, "members"), bool(m, "supportsTransactionalDdl"),
                str(m, "identityKeyword"), str(m, "quoteIdent"), mapping);
    }

    private static Dialect toDialect(Map<String, Object> m) {
        return new Dialect(
                intOf(m, "schemaVersion"), str(m, "id"), str(m, "label"),
                str(m, "identifierQuote"), str(m, "paramStyle"),
                strList(m, "keywords"), strList(m, "functions"), strList(m, "types"));
    }

    // ──────────────────────────── 小工具 ────────────────────────────

    private static Map<String, Object> withoutMeta(Map<String, Object> m) {
        Map<String, Object> copy = new LinkedHashMap<>(m);
        copy.remove("__file");
        return copy;
    }

    private static boolean eq(Object a, Object b) {
        return java.util.Objects.equals(a, b);
    }

    private static void require(List<Issue> issues, Map<String, Object> m,
                                Issue.Rule rule, String field, boolean ok) {
        if (!ok) {
            add(issues, rule, str(m, "__file"), field + " 不合法");
        }
    }

    private static void add(List<Issue> issues, Issue.Rule rule, String file, String msg) {
        issues.add(new Issue(rule, file == null ? "?" : file, msg));
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private static String str(Map<String, Object> m, String section, String k) {
        Object v;
        if (section == null) {
            v = m.get(k);
        } else {
            Object s = m.get(section);
            v = (s instanceof Map<?, ?> map) ? map.get(k) : null;
        }
        return v == null ? null : String.valueOf(v);
    }

    private static int intOf(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Number n ? n.intValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private static int intOr(Map<String, Object> m, String section, String k, int fallback) {
        Object s = m.get(section);
        Object v = (s instanceof Map<?, ?> map) ? map.get(k) : null;
        return v instanceof Number n ? n.intValue() : fallback;
    }

    @SuppressWarnings("unchecked")
    private static boolean bool(Map<String, Object> m, String k) {
        return Boolean.TRUE.equals(m.get(k));
    }

    @SuppressWarnings("unchecked")
    private static boolean bool(Map<String, Object> m, String section, String k) {
        Object s = m.get(section);
        Object v = (s instanceof Map<?, ?> map) ? map.get(k) : null;
        return Boolean.TRUE.equals(v);
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Map<String, Object> m, String section, String k) {
        Object s = m.get(section);
        Object v = (s instanceof Map<?, ?> map) ? map.get(k) : null;
        return v instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
    }
}