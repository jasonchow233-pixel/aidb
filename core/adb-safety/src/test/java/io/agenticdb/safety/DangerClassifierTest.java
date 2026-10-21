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
    @DisplayName("边界输入")
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
        @DisplayName("只有注释的输入返回只读")
        void 只有注释的输入返回只读() {
            assertThat(DangerClassifier.classify("-- 什么都没有")).isEqualTo(Danger.READ_ONLY);
            assertThat(DangerClassifier.classify("/* 什么都没有 */")).isEqualTo(Danger.READ_ONLY);
        }
    }

    @Nested
    @DisplayName("未知语句必须 fail-closed")
    class UnknownHead {

        @Test
        @DisplayName("未知语句头按「管理操作」处理，不得静默执行")
        void 未知语句failClosed() {
            assertThat(DangerClassifier.classify("SET search_path TO public"))
                    .as("SET 改的是会话状态，不是只读；旧版判成 READ_ONLY 等于不拦")
                    .isNotEqualTo(Danger.READ_ONLY);
            assertThat(DangerClassifier.classify("MERGE INTO t USING s ON (1=1) WHEN MATCHED THEN UPDATE SET a=1"))
                    .isEqualTo(Danger.MUTATING);
            assertThat(DangerClassifier.classify("UPSERT INTO t VALUES (1)"))
                    .isEqualTo(Danger.MUTATING);
            assertThat(DangerClassifier.classify("COPY t FROM '/tmp/x.csv'"))
                    .isEqualTo(Danger.MUTATING);
            assertThat(DangerClassifier.classify("LOCK TABLE t"))
                    .isEqualTo(Danger.MUTATING);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "CALL do_something()",
                "DO $$ BEGIN PERFORM 1; END $$",
                "CALL some_schema.some_proc(1)",
        })
        @DisplayName("能执行任意代码的语句按「管理操作」处理")
        void 可执行任意代码的语句按管理操作处理(String sql) {
            assertThat(DangerClassifier.classify(sql))
                    .as("CALL / DO 能跑任意代码，必须走最重的确认")
                    .isEqualTo(Danger.ADMIN);
        }

        @Test
        @DisplayName("完全认不出来的语句头也是管理操作，不能是只读")
        void 完全认不出来的语句头也是管理操作() {
            assertThat(DangerClassifier.classify("FLUSHALL"))
                    .isEqualTo(Danger.ADMIN);
            assertThat(DangerClassifier.classify("WHATEVER SOMETHING"))
                    .isEqualTo(Danger.ADMIN);
        }

        @Test
        @DisplayName("EXPLAIN ANALYZE 会真的执行语句，必须按内层语句分级")
        void explainAnalyze按内层语句分级() {
            assertThat(DangerClassifier.classify("EXPLAIN ANALYZE SELECT 1"))
                    .isEqualTo(Danger.READ_ONLY);
            assertThat(DangerClassifier.classify("EXPLAIN ANALYZE DELETE FROM t"))
                    .as("PostgreSQL 的 EXPLAIN ANALYZE 真的执行 DELETE")
                    .isEqualTo(Danger.DESTRUCTIVE);
            assertThat(DangerClassifier.classify("EXPLAIN ANALYZE DELETE FROM t WHERE id=1"))
                    .isEqualTo(Danger.MUTATING);
        }

        @Test
        @DisplayName("普通 EXPLAIN 只解释不执行，是只读")
        void 普通explain是只读() {
            assertThat(DangerClassifier.classify("EXPLAIN SELECT * FROM users"))
                    .isEqualTo(Danger.READ_ONLY);
            assertThat(DangerClassifier.classify("EXPLAIN QUERY PLAN SELECT * FROM users"))
                    .isEqualTo(Danger.READ_ONLY);
        }
    }

    @Nested
    @DisplayName("WITH 体内可能藏着写语句")
    class WithBody {

        @Test
        @DisplayName("WITH 后面跟 DELETE 不能判只读")
        void with后面跟delete不能判只读() {
            assertThat(DangerClassifier.classify("WITH cte AS (SELECT 1) DELETE FROM t"))
                    .as("语句头是 WITH，但它删数据 —— 旧版漏判成只读，直接执行")
                    .isEqualTo(Danger.DESTRUCTIVE);
            assertThat(DangerClassifier.classify("WITH cte AS (SELECT 1) DELETE FROM t WHERE id=1"))
                    .isEqualTo(Danger.MUTATING);
        }

        @Test
        @DisplayName("WITH 后面跟 UPDATE 也不能判只读")
        void with后面跟update不能判只读() {
            assertThat(DangerClassifier.classify("WITH cte AS (SELECT 1) UPDATE t SET a=1"))
                    .isEqualTo(Danger.MUTATING);
        }

        @Test
        @DisplayName("CTE 自身带写语句同样要拦")
        void cte自身带写语句同样要拦() {
            assertThat(DangerClassifier.classify(
                    "WITH moved AS (DELETE FROM old_t RETURNING *) SELECT * FROM moved"))
                    .isEqualTo(Danger.DESTRUCTIVE);
        }

        @Test
        @DisplayName("纯读的 WITH 仍然是只读")
        void 纯读的with仍然是只读() {
            assertThat(DangerClassifier.classify("WITH x AS (SELECT 1) SELECT * FROM x"))
                    .isEqualTo(Danger.READ_ONLY);
            assertThat(DangerClassifier.classify(
                    "WITH RECURSIVE c(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM c WHERE n<10) SELECT * FROM c"))
                    .isEqualTo(Danger.READ_ONLY);
        }

        @Test
        @DisplayName("字符串里的写语句关键字不算数")
        void 字符串里的写语句关键字不算数() {
            assertThat(DangerClassifier.classify(
                    "WITH x AS (SELECT 1) SELECT 'delete from t' AS note"))
                    .as("字面量里的 delete 不是语句")
                    .isEqualTo(Danger.READ_ONLY);
        }
    }

    @Nested
    @DisplayName("切分必须用词法内核")
    class SafeSplit {

        @Test
        @DisplayName("字符串字面量里的分号不切碎语句")
        void 字符串字面量里的分号不切碎语句() {
            assertThat(DangerClassifier.classify("INSERT INTO t VALUES ('a;b')"))
                    .as("裸 split(\";\") 会把后半截 b') 当成未知语句")
                    .isEqualTo(Danger.MUTATING);
        }

        @Test
        @DisplayName("注释里的分号不切碎语句")
        void 注释里的分号不切碎语句() {
            assertThat(DangerClassifier.classify("SELECT 1 -- ; DROP TABLE t"))
                    .isEqualTo(Danger.READ_ONLY);
            assertThat(DangerClassifier.classify("/* ; DROP TABLE t */ SELECT 1"))
                    .isEqualTo(Danger.READ_ONLY);
        }

        @Test
        @DisplayName("分号后仍是危险语句时要取最危险")
        void 分号后仍是危险语句时要取最危险() {
            assertThat(DangerClassifier.classify("INSERT INTO t VALUES ('a;b'); DROP TABLE t"))
                    .isEqualTo(Danger.DESTRUCTIVE);
        }
    }

    @Nested
    @DisplayName("PRAGMA 读写要分开")
    class Pragma {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "PRAGMA table_info(users)",
                "PRAGMA foreign_key_list(t)",
                "PRAGMA database_list",
        })
        @DisplayName("读型 PRAGMA 是只读")
        void 读型pragma是只读(String sql) {
            assertThat(DangerClassifier.classify(sql)).isEqualTo(Danger.READ_ONLY);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "PRAGMA journal_mode = WAL",
                "PRAGMA foreign_keys = OFF",
                "PRAGMA user_version = 3",
        })
        @DisplayName("赋值型 PRAGMA 会改库，不能当只读")
        void 赋值型pragma会改库(String sql) {
            assertThat(DangerClassifier.classify(sql)).isEqualTo(Danger.MUTATING);
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