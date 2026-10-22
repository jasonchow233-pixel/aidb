package io.agenticdb.introspect;

import io.agenticdb.fixtures.DemoDatabase;
import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.JdbcSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ O2 门禁：函数 / 存储过程的**定义体**读得回来。
 *
 * <p>以前对象树里只有**名字** —— 点开看不到参数、返回类型、函数体，
 * 用户只能去数据库命令行里查。现在三种方言都能读回原文（来源见
 * {@code Dialect.readRoutineDefinition}）。
 *
 * <p>⚠️ <b>SQLite 没有 SQL 层函数</b>（用户自定义函数只能走 C API）——
 * 所以 SQLite 上「读不到」是**正确结果**，本门禁守住「读不到要优雅」
 * （返回空串，不抛异常、不让页面打不开）。
 * 真正的函数定义体由 {@code MySqlIntegrationTest} 在**真库**上验证。
 */
class RoutineDefinitionTest {

    @TempDir
    Path temp;

    private DbSession session;
    private SchemaService schema;

    @BeforeEach
    void open() throws Exception {
        Path file = DemoDatabase.create(temp.resolve("demo.db"));
        session = JdbcSession.open(ConnectionSpec.sqlite(file));
        schema = new SchemaService();
    }

    @AfterEach
    void close() throws Exception {
        session.close();
    }

    @Test
    void sqliteHasNoSqlLevelFunctionsAndFailsGracefully() {
        assertThat(schema.routineDefinition(session, "total_of", false))
                .as("SQLite 没有 SQL 层函数 —— 读不到是**正确结果**，但必须优雅")
                .isEmpty();
    }

    @Test
    void unknownRoutineYieldsAnEmptyDefinitionInsteadOfFailing() {
        assertThat(schema.routineDefinition(session, "no_such_function", false))
                .as("读不到就返回空串 —— 页面显示「读不到」，不该整个打不开")
                .isEmpty();
    }

    @Test
    void sqliteHasNoScheduledEvents() {
        // ⭐ O1：只有 MySQL 有 EVENT —— 其它方言的 eventsListSql() 是 null，
        //    于是对象树里**不会出现**「事件」分组（不假装有）。
        assertThat(session.dialect().eventsListSql()).isNull();
        assertThat(schema.routineDefinition(session, "anything", false)).isEmpty();
    }

    @Test
    void blankNameYieldsAnEmptyDefinition() {
        assertThat(schema.routineDefinition(session, "  ", false)).isEmpty();
    }
}
