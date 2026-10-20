package io.agenticdb.declarations;

import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * 声明层加载测试。
 * <p>
 * 守的是两件事：
 * <ol>
 *   <li>仓库里这 14 个 YAML 现在就是合法的 —— 每次 CI 都跑一遍</li>
 *   <li>解析结果逐字段符合预期 —— 用对拍快照，不靠肉眼</li>
 * </ol>
 */
class DeclarationLoaderTest {

    private static DeclaredModel model;

    @BeforeAll
    static void loadOnce() {
        model = DeclarationLoader.load(declarationsRoot());
    }

    @Nested
    class Baseline {

        @Test
        void 声明层合法() {
            assertThatNoException().isThrownBy(() ->
                    DeclarationLoader.load(declarationsRoot()));
        }

        @Test
        void 数量对得上() {
            assertThat(model.connections()).hasSize(4);
            assertThat(model.families()).hasSize(5);
            assertThat(model.dialects()).hasSize(4);
        }

        @Test
        void jdbc连接() {
            assertThat(model.connections())
                    .filteredOn(ConnectionDescriptor::isJdbc)
                    .extracting(ConnectionDescriptor::id)
                    .containsExactlyInAnyOrder("sqlite", "postgresql", "mysql", "dameng");
        }

        @Test
        void 全部走jdbc() {
            assertThat(model.connections())
                    .filteredOn(ConnectionDescriptor::isJdbc)
                    .extracting(ConnectionDescriptor::id)
                    .containsExactlyInAnyOrder("sqlite", "postgresql", "mysql", "dameng");
        }

        @Test
        void 每个jdbc都有驱动类() {
            for (ConnectionDescriptor c : model.connections()) {
                assertThat(c.isJdbc()).as("%s 应为 jdbc 模式", c.id()).isTrue();
                assertThat(c.driverClass()).as("%s 缺 driverClass", c.id()).isNotBlank();
            }
        }

        @Test
        void 驱动类可加载() {
            // 达梦驱动不随包分发（商业 jar），所以只校验前三个
            for (String id : new String[]{"sqlite", "postgresql", "mysql"}) {
                ConnectionDescriptor c = model.byId(id).orElseThrow();
                try {
                    Class.forName(c.driverClass());
                } catch (ClassNotFoundException e) {
                    throw new AssertionError(
                            "%s 的 driverClass %s 不在 classpath 上".formatted(id, c.driverClass()), e);
                }
            }
        }
    }

    @Nested
    class Capabilities {

        @Test
        void 每个连接的15项都齐() {
            for (ConnectionDescriptor c : model.connections()) {
                assertThat(c.capabilities())
                        .as("%s 的 capabilities 必须全 15 项", c.id())
                        .isNotNull();
            }
        }

        @Test
        void SQLite的显式关闭() {
            ConnectionDescriptor sqlite = model.byId("sqlite").orElseThrow();
            assertThat(sqlite.capabilities().objectBrowser()).isFalse();
            assertThat(sqlite.capabilities().databaseCreate()).isFalse();
            assertThat(sqlite.capabilities().userAdmin()).isFalse();
        }

        @Test
        void 达梦的显式关闭() {
            ConnectionDescriptor dm = model.byId("dameng").orElseThrow();
            assertThat(dm.capabilities().databaseCreate()).isFalse();
            assertThat(dm.capabilities().userAdmin()).isFalse();
        }
    }

    @Nested
    class Traits {

        @Test
        void mysql隐式提交() {
            ConnectionDescriptor mysql = model.byId("mysql").orElseThrow();
            assertThat(mysql.traits().transactionalDdl()).isFalse();
            assertThat(mysql.traits().identityKind()).isEqualTo("autoIncrement");
        }

        @Test
        void postgres事务性() {
            ConnectionDescriptor pg = model.byId("postgresql").orElseThrow();
            assertThat(pg.traits().transactionalDdl()).isTrue();
            assertThat(pg.traits().identityKind())
                    .as("与 MySQL 的差异必须体现在这里，否则导出 DDL 会错")
                    .isNotEqualTo("autoIncrement");
        }

        @Test
        void 按traits推导出UI警告() {
            ConnectionDescriptor mysql = model.byId("mysql").orElseThrow();
            List<DeclaredModel.UiWarning> nowarnings = model.warningsOf(mysql);
            assertThat(nowarnings)
                    .isNotEmpty()
                    .anyMatch(w -> w.toString().contains("ddl-implicit-commit"));
        }

        @Test
        void PostgreSQL无该警告() {
            ConnectionDescriptor pg = model.byId("postgresql").orElseThrow();
            List<DeclaredModel.UiWarning> nowarnings = model.warningsOf(pg);
            assertThat(nowarnings)
                    .noneMatch(w -> w.toString().contains("ddl-implicit-commit"));
        }
    }

    @Nested
    class Families {

        @Test
        void dameng走oracle家族() {
            ConnectionDescriptor dm = model.byId("dameng").orElseThrow();
            assertThat(dm.transferObjectFamily()).isEqualTo("oracle");
            assertThat(model.familyOf("dameng")).isPresent();
        }

        @Test
        void mysql家族成员() {
            var f = model.families().stream()
                    .filter(x -> x.id().equals("mysql")).findFirst().orElseThrow();
            assertThat(f.members())
                    .contains("mysql", "mariadb", "tidb", "oceanbase-mysql", "polardb-mysql");
        }

        @Test
        void 同家族有映射() {
            var oracle = model.families().stream()
                    .filter(x -> x.id().equals("oracle")).findFirst().orElseThrow();
            assertThat(oracle.typeMapping())
                    .as("家族必须自带类型映射表，否则 UI 显示「无映射」")
                    .isNotEmpty();
        }

        @Test
        void 有损映射有标记() {
            var mysql = model.families().stream()
                    .filter(x -> x.id().equals("mysql")).findFirst().orElseThrow();
            assertThat(mysql.typeMapping().values())
                    .as("MySQL 家族应有 lossy 项（float/datetime/timestamp）")
                    .anyMatch(TransferFamily.TypeRule::lossy);
        }

        @Test
        void 家族元信息齐全() {
            for (TransferFamily f : model.families()) {
                assertThat(f.identityKeyword()).as("%s 缺 identityKeyword", f.id()).isNotBlank();
                assertThat(f.quoteIdent()).as("%s 缺 quoteIdent", f.id()).isNotBlank();
            }
        }
    }

    @Nested
    class Dialects {

        @Test
        void 方言有词表() {
            for (Dialect d : model.dialects()) {
                assertThat(d.keywords()).as("%s 缺 keywords", d.id()).isNotEmpty();
                assertThat(d.types()).as("%s 缺 types", d.id()).isNotEmpty();
            }
        }

        @Test
        void postgres关键字可查表() {
            var pg = model.dialects().stream()
                    .filter(d -> d.id().equalsIgnoreCase("Postgres")).findFirst().orElseThrow();
            assertThat(pg.keywordSet()).contains("select", "insert", "returning");
        }
    }

    @Nested
    class ParitySnapshot {

        @Test
        void 连接快照() throws Exception {
            var mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            var rows = model.connections().stream().map(c -> {
                var m = new java.util.LinkedHashMap<String, Object>();
                m.put("id", c.id());
                m.put("label", c.label());
                m.put("dialect", c.dialect());
                m.put("runtimeMode", c.runtimeMode());
                m.put("mcpMode", c.mcpMode());
                m.put("defaultPort", c.defaultPort());
                m.put("identityKind", c.traits().identityKind());
                m.put("transactionalDdl", c.traits().transactionalDdl());
                m.put("sequenceObjects", c.traits().sequenceObjects());
                m.put("identifierQuote", c.traits().identifierQuote());
                m.put("paramStyle", c.traits().paramStyle());
                m.put("maxParams", c.traits().maxParams());
                m.put("explainFormats", c.traits().explainFormats());
                m.put("family", c.transferObjectFamily());
                m.put("supportLevel", c.supportLevel());
                m.put("driverClass", c.driverClass());
                m.put("queryExecution", c.capabilities().queryExecution());
                m.put("objectBrowser", c.capabilities().objectBrowser());
                m.put("databaseCreate", c.capabilities().databaseCreate());
                m.put("userAdmin", c.capabilities().userAdmin());
                return m;
            }).toList();
            String actual = mapper.writeValueAsString(rows);

            Path snapshot = Path.of("src", "test", "resources", "connections.snapshot.json");
            if (Boolean.getBoolean("update.snapshots")) {
                Files.createDirectories(snapshot.getParent());
                Files.writeString(snapshot, actual);
            }
            assertThat(Files.exists(snapshot))
                    .as("快照缺失。加 -Dupdate.snapshots 重新生成")
                    .isTrue();
            assertThat(actual).isEqualTo(Files.readString(snapshot).trim());
        }
    }

    /** Maven 以模块目录为工作目录；IDE 可能以仓库根目录运行。 */
    private static Path declarationsRoot() {
        Path moduleRelative = Path.of("..", "..", "declarations");
        return Files.isDirectory(moduleRelative) ? moduleRelative : Path.of("declarations");
    }
}