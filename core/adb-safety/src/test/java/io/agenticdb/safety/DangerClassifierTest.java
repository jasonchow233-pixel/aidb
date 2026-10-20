package io.agenticdb.safety;

import io.agenticdb.model.Danger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Danger 分级测试。
 * <p>移植自 Rust 侧 {@code danger_classification()} 并扩充。
 * 其中 {@link MultiStatement} 整个类是为了锁死一条 L2 安全漏洞的回归。
 */
class DangerClassifierTest {

    @Nested
    @DisplayName("只读语句")
    class ReadOnly {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "SELECT * FROM users",
                "select 1",
                "  SELECT 1",
                "PRAGMA table_info(users)",
                "EXPLAIN SELECT * FROM users",
                "WITH x AS (SELECT 1) SELECT * FROM x",
                "VALUES (1), (2)",
                "SHOW TABLES",
                "DESCRIBE users",
                "-- 注释在前面\nSELECT 1",
                "/* 块注释 */ SELECT 1",
        })
        void 危险分级(String sql) {
            assertThat(DangerClassifier.classify(sql)).isEqualTo(Danger.READ_ONLY);
        }
    }

    @Nested
    @DisplayName("写数据语句")
    class Mutating {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "INSERT INTO t VALUES (1)",
                "UPDATE t SET a=1 WHERE id=2",
                "REPLACE INTO t VALUES (1)",
                "DELETE FROM t WHERE id=1",
                "CREATE TEMP TABLE tmp AS SELECT 1",
        })
        void 写数据语句(String sql) {
            assertThat(DangerClassifier.classify(sql)).isEqualTo(Danger.MUTATING);
        }
    }

    @Nested
    @DisplayName("破坏性与管理操作")
    class Destructive {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "DELETE FROM t",
                "DROP TABLE t",
                "CREATE TABLE t (a int)",
                "ALTER TABLE t ADD b int",
                "TRUNCATE TABLE t",
                "VACUUM",
                "REINDEX",
        })
        void 危险分级(String sql) {
            assertThat(DangerClassifier.classify(sql)).isEqualTo(Danger.DESTRUCTIVE);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
                "GRANT ALL ON t TO u|ADMIN",
                "REVOKE ALL ON t FROM u|ADMIN",
                "ATTACH 'x.db' AS y|ADMIN",
        })
        void 管理操作(String sql, Danger expected) {
            assertThat(DangerClassifier.classify(sql)).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("多语句必须取最危险的那条")
    class MultiStatement {

        @ParameterizedTest(name = "{0}|{1}")
        @CsvSource(delimiter = '|', value = {
                "SELECT 1; INSERT INTO t VALUES (1)   | MUTATING",
                "SELECT 1; UPDATE t SET a=1          | MUTATING",
                "SELECT 1; TRUNCATE TABLE t          | DESTRUCTIVE",
                "SELECT 1; GRANT ALL ON t TO u        | ADMIN",
                "INSERT INTO a VALUES(1); DELETE FROM t | DESTRUCTIVE",
                "SELECT 1; SELECT 2; SELECT 3         | READ_ONLY",
        })
        void 多语句取最危险(String sql, Danger expected) {
            assertThat(DangerClassifier.classify(sql.strip()))
                    .as("整条语句的等级必须是最危险的那条，否则 DROP 不弹确认框直接执行")
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("尾随分号不是多语句")
        void 尾随分号不是多语句() {
            assertThat(DangerClassifier.classify("SELECT * FROM users;"))
                    .isEqualTo(Danger.READ_ONLY);
        }

        @Test
        @DisplayName("连续分号不改变结论")
        void 连续分号不改变结论() {
            assertThat(DangerClassifier.classify("SELECT 1;;DROP TABLE t;"))
                    .isEqualTo(Danger.DESTRUCTIVE);
        }
    }

    @Nested
    @DisplayName("边界与降级")
    class EdgeCases {

        @Test
        @DisplayName("空输入返回只读")
        void 空输入返回只读() {
            assertThat(DangerClassifier.classify(null)).isEqualTo(Danger.READ_ONLY);
            assertThat(DangerClassifier.classify("")).isEqualTo(Danger.READ_ONLY);
            assertThat(DangerClassifier.classify("   ")).isEqualTo(Danger.READ_ONLY);
            assertThat(DangerClassifier.classify(";")).isEqualTo(Danger.READ_ONLY);
        }

        @Test
        @DisplayName("未知语句降级为只读")
        void 未知语句failOpen() {
            assertThat(DangerClassifier.classify("EXPLAIN ANALYZE SELECT 1"))
                    .isEqualTo(Danger.READ_ONLY);
            assertThat(DangerClassifier.classify("SET search_path TO public"))
                    .isEqualTo(Danger.READ_ONLY);
        }
    }

    @Nested
    @DisplayName("确认与审批的级别区分")
    class GateLevels {

        @Test
        @DisplayName("只有只读不需要确认")
        void 只有只读不需要确认() {
            assertThat(Danger.READ_ONLY.requiresConfirm()).isFalse();
            assertThat(Danger.READ_ONLY.requiresApproval()).isFalse();
            assertThat(Danger.MUTATING.requiresConfirm()).isTrue();
            assertThat(Danger.MUTATING.requiresApproval()).isFalse();
            assertThat(Danger.DESTRUCTIVE.requiresApproval()).isTrue();
            assertThat(Danger.ADMIN.requiresApproval()).isTrue();
        }

        @Test
        @DisplayName("中文标签齐全")
        void 中文标签齐全() {
            assertThat(Danger.values())
                    .extracting(Danger::label)
                    .containsExactly("只读", "写数据", "改结构", "管理操作");
        }
    }
}