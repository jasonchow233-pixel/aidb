package io.agenticdb.declarations;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.*;

/** 逐条验证 15 条语义规则 —— 每条规则都有专用测试，便于定位回归。 */
class DeclarationValidationTest {

    private static DeclaredModel model;

    @BeforeAll
    static void loadRealDeclarations() {
        model = DeclarationLoader.load(declarationsRoot());
    }

    /** 每个测试都从空脚手架开始，避免上一个用例的 mysql2.yaml 污染下一个。 */
    @BeforeEach
    void cleanScaffold() throws IOException {
        Path root = Path.of("target", "test", "scaffold");
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    @Nested
    class BaselineMustPass {

        @Test
        void 声明层合法() {
            assertThat(catchThrowable(() -> DeclarationLoader.load(scaffoldQuiet()))).isNull();
        }

        private Path scaffoldQuiet() throws IOException {
            Path root = Path.of("target", "test", "scaffold", "declarations");
            Files.createDirectories(root);
            String yaml = """
                schemaVersion: 1
                order: 20
                id: mysql
                rustVariant: MySQL
                label: MySQL
                dialect: MySQL
                runtimeMode: native
                mcpMode: builtin
                defaultPort: 3306
                urlTemplate: "mysql://{host}/{database}"
                traits:
                  schemaAware: false
                  identityKind: autoIncrement
                  sequenceObjects: false
                  transactionalDdl: false
                  explainFormats: [json, text]
                  maxParams: 0
                  identifierQuote: "`"
                  paramStyle: positional
                capabilities:
                  queryExecution: true
                  metadataBrowse: true
                  objectBrowser: true
                  objectSource: true
                  schemaSearch: true
                  diagram: true
                  tableDataEdit: true
                  tableStructureEdit: true
                  tableImport: true
                  dataTransfer: true
                  sqlFileExecution: true
                  databaseCreate: true
                  fieldLineage: true
                  sqlExplain: true
                  userAdmin: true
                  specializedSurface: false
                compareModes: [schema]
                transferSupportLevel: native
                transferObjectFamily: mysql
                supportLevel: full
                """;
            Files.createDirectories(root.resolve("connections"));
            Files.writeString(root.resolve("connections/mysql.yaml"), yaml);
            Files.createDirectories(root.resolve("dialects"));
            Files.writeString(root.resolve("dialects/MySQL.yaml"), """
                schemaVersion: 1
                id: MySQL
                label: MySQL
                identifierQuote: "`"
                paramStyle: positional
                keywords: [SELECT, INSERT]
                functions: [count]
                types: [int, varchar]
                """);
            Files.createDirectories(root.resolve("transfer-families"));
            Files.writeString(root.resolve("transfer-families/mysql.yaml"), """
                schemaVersion: 1
                id: mysql
                label: MySQL 家族
                members: [mysql]
                supportsTransactionalDdl: false
                identityKeyword: AUTO_INCREMENT
                quoteIdent: '`'
                typeMapping:
                  int:
                    lossy: false
                    to: { postgres: INTEGER }
                """);
            Files.createDirectories(root.resolve("schema"));
            Files.writeString(root.resolve("schema/connection.schema.json"), "{\"type\":\"object\"}");
            return root;
        }
    }

    @Nested
    class SchemaVersion {

        @Test
        void schemaVersion必须是1() throws IOException {
            Path root = scaffold(VALID_MYSQL.replace("schemaVersion: 1", "schemaVersion: 2"));
            assertFailsWith(root, Issue.Rule.SCHEMA_VERSION);
        }
    }

    @Nested
    class DuplicateFields {

        @Test
        void id不能重复() throws IOException {
            Path root = scaffold(VALID_MYSQL);
            write(root.resolve("connections/mysql2.yaml"), VALID_MYSQL.replace("order: 20", "order: 21"));
            assertFailsWith(root, Issue.Rule.DUPLICATE_FIELD);
        }

        @Test
        void order不能重复() throws IOException {
            Path root = scaffold(VALID_MYSQL);
            write(root.resolve("connections/mysql2.yaml"),
                    VALID_MYSQL.replace("id: mysql", "id: mariadb")
                            .replace("rustVariant: MySQL", "rustVariant: MariaDB"));
            assertFailsWith(root, Issue.Rule.DUPLICATE_FIELD);
        }
    }

    @Nested
    class Dialect {

        @Test
        void dialect必须存在() throws IOException {
            Path root = scaffold(VALID_MYSQL.replace("dialect: MySQL", "dialect: ClickHouse"));
            assertFailsWith(root, Issue.Rule.UNKNOWN_DIALECT);
        }
    }

    @Nested
    class Family {

        @Test
        void 家族必须存在() throws IOException {
            Path root = scaffold(VALID_MYSQL.replace("transferObjectFamily: mysql",
                    "transferObjectFamily: nonexistent"));
            assertFailsWith(root, Issue.Rule.UNKNOWN_FAMILY);
        }

        @Test
        void 连接必须在家族内() throws IOException {
            Path root = scaffold(VALID_MYSQL);
            write(root.resolve("transfer-families/mysql.yaml"),
                    FAMILY_MYSQL.replace("members: [mysql]", "members: [mariadb]"));
            assertFailsWith(root, Issue.Rule.CONNECTION_IN_NO_FAMILY);
        }
    }

    @Nested
    class Capabilities {

        private static final String[] CAPS = {
                "queryExecution", "metadataBrowse", "objectBrowser", "objectSource",
                "schemaSearch", "diagram", "tableDataEdit", "tableStructureEdit",
                "tableImport", "dataTransfer", "sqlFileExecution", "databaseCreate",
                "fieldLineage", "sqlExplain", "userAdmin"};

        @Test
        void capabilities必须全写() throws IOException {
            Path root = scaffold(VALID_MYSQL.replace("  userAdmin: true\n", ""));
            assertFailsWith(root, Issue.Rule.CAPABILITY_MISSING);
        }

        @Test
        void 缺哪项要说明白() throws IOException {
            String yaml = VALID_MYSQL.replace("  userAdmin: true\n", "");
            assertThatThrownBy(() -> DeclarationLoader.load(scaffold(yaml)))
                    .as("load 抛出异常")
                    .isInstanceOf(DeclarationException.class)
                    .satisfies(ex -> assertThat(((DeclarationException) ex).issues())
                            .as("报出的问题包含 userAdmin 缺失")
                            .anyMatch(i -> i.rule() == Issue.Rule.CAPABILITY_MISSING
                                    && i.message().contains("userAdmin")));
        }

        @Test
        void capabilities全false要报错() throws IOException {
            Path root = scaffold(replaceCapabilities(allFalse(false)));
            assertFailsWith(root, Issue.Rule.CAPABILITIES_ALL_FALSE);
        }

        @Test
        void 专用界面可以全false() throws IOException {
            Path root = scaffold(replaceCapabilities(allFalse(true)));
            assertThat(catchThrowable(() -> DeclarationLoader.load(root))).isNull();
        }

        /** 生成运行时缩进的 capabilities 段；specialized 表示 specializedSurface 的值。 */
        private static String allFalse(boolean specializedIsTrue) {
            StringBuilder caps = new StringBuilder("capabilities:\n");
            for (String cap : CAPS) {
                caps.append("  ").append(cap).append(": false\n");
            }
            caps.append("  specializedSurface: ").append(specializedIsTrue).append('\n');
            return caps.toString();
        }

        private static String replaceCapabilities(String block) {
            int start = VALID_MYSQL.indexOf("capabilities:");
            int end = VALID_MYSQL.indexOf("compareModes:", start);
            return VALID_MYSQL.substring(0, start) + block + VALID_MYSQL.substring(end);
        }
    }

    @Nested
    class RuntimeMode {

        @Test
        void jdbc必须有驱动类() throws IOException {
            Path root = scaffold(VALID_MYSQL.replace("runtimeMode: native", "runtimeMode: jdbc"));
            assertFailsWith(root, Issue.Rule.JDBC_WITHOUT_DRIVER);
        }

        @Test
        void bridge必须是jdbc() throws IOException {
            Path root = scaffold(VALID_MYSQL.replace("mcpMode: builtin", "mcpMode: bridge"));
            assertFailsWith(root, Issue.Rule.MCP_BRIDGE_WITHOUT_JDBC);
        }
    }

    @Nested
    class Checksum {

        @Test
        void checksum不能是占位符() throws IOException {
            String yaml = VALID_MYSQL
                    + "\ndriverArtifact:\n  key: k\n  jar: j.jar\n  checksum: \"REPLACE_ME\"\n";
            Path root = scaffold(yaml);
            assertFailsWith(root, Issue.Rule.CHECKSUM_PLACEHOLDER);
        }
    }

    @Nested
    class TraitsRules {

        @Test
        void 自相矛盾要报错() throws IOException {
            Path root = scaffold(VALID_MYSQL.replace("identityKind: autoIncrement",
                    "identityKind: sequence"));
            assertFailsWith(root, Issue.Rule.IDENTITY_SEQUENCE_CONTRADICTION);
        }

        @Test
        void explainFormats不能为空() throws IOException {
            Path root = scaffold(VALID_MYSQL.replace("explainFormats: [json, text]",
                    "explainFormats: []"));
            assertFailsWith(root, Issue.Rule.EMPTY_EXPLAIN_FORMATS);
        }
    }

    @Nested
    class YamlSyntax {

        @Test
        void yaml语法错误() throws IOException {
            Path root = scaffold("schemaVersion: 1\n  bad indentation: [unclosed");
            assertFailsWith(root, Issue.Rule.YAML_SYNTAX);
        }

        @Test
        void 重复键要报错() throws IOException {
            Path root = scaffold(VALID_MYSQL + "\ndefaultPort: 3307\n");
            assertFailsWith(root, Issue.Rule.YAML_SYNTAX);
        }
    }

    // Helper methods -------------------------------------------------------

    private static final String VALID_MYSQL = """
        schemaVersion: 1
        order: 20
        id: mysql
        rustVariant: MySQL
        label: MySQL
        dialect: MySQL
        runtimeMode: native
        mcpMode: builtin
        defaultPort: 3306
        urlTemplate: "mysql://{host}/{database}"
        traits:
          schemaAware: false
          identityKind: autoIncrement
          sequenceObjects: false
          transactionalDdl: false
          explainFormats: [json, text]
          maxParams: 0
          identifierQuote: "`"
          paramStyle: positional
        capabilities:
          queryExecution: true
          metadataBrowse: true
          objectBrowser: true
          objectSource: true
          schemaSearch: true
          diagram: true
          tableDataEdit: true
          tableStructureEdit: true
          tableImport: true
          dataTransfer: true
          sqlFileExecution: true
          databaseCreate: true
          fieldLineage: true
          sqlExplain: true
          userAdmin: true
          specializedSurface: false
        compareModes: [schema]
        transferSupportLevel: native
        transferObjectFamily: mysql
        supportLevel: full
        """;

    private static final String DIALECT_MYSQL = """
        schemaVersion: 1
        id: MySQL
        label: MySQL
        identifierQuote: "`"
        paramStyle: positional
        keywords: [SELECT, INSERT]
        functions: [count]
        types: [int, varchar]
        """;

    private static final String FAMILY_MYSQL = """
        schemaVersion: 1
        id: mysql
        label: MySQL 家族
        members: [mysql]
        supportsTransactionalDdl: false
        identityKeyword: AUTO_INCREMENT
        quoteIdent: '`'
        typeMapping:
          int:
            lossy: false
            to: { postgres: INTEGER }
        """;

    private Path scaffold(String yaml) throws IOException {
        Path root = Path.of("target", "test", "scaffold", "declarations");
        Files.createDirectories(root.resolve("connections"));
        Files.writeString(root.resolve("connections/mysql.yaml"), yaml);
        Files.createDirectories(root.resolve("dialects"));
        Files.writeString(root.resolve("dialects/MySQL.yaml"), DIALECT_MYSQL);
        Files.createDirectories(root.resolve("transfer-families"));
        Files.writeString(root.resolve("transfer-families/mysql.yaml"), FAMILY_MYSQL);
        Files.createDirectories(root.resolve("schema"));
        Files.writeString(root.resolve("schema/connection.schema.json"), "{\"type\":\"object\"}");
        return root;
    }

    private static void write(Path p, String content) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private static void assertFailsWith(Path root, Issue.Rule expected) {
        assertThatThrownBy(() -> DeclarationLoader.load(root))
                .as("应抛出 DeclarationException")
                .isInstanceOf(DeclarationException.class)
                .satisfies(ex -> assertThat(((DeclarationException) ex).issues())
                        .as("应报出规则 %s，实际: %s", expected.id(),
                                ((DeclarationException) ex).issues())
                        .anyMatch(i -> i.rule() == expected));
    }

    /** Maven 以模块目录为工作目录；IDE 可能以仓库根目录运行。 */
    private static Path declarationsRoot() {
        Path moduleRelative = Path.of("..", "..", "declarations");
        return Files.isDirectory(moduleRelative) ? moduleRelative : Path.of("declarations");
    }
}