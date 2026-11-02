package io.agenticdb.introspect;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ D-22 的服务侧门禁：占位符有几个，表名就得绑几次。
 *
 * <p>Oracle 的目录视图把「加引号建的对象」原样存、把「没加引号建的」折成大写，
 * 所以方言写 {@code TABLE_NAME IN (?, UPPER(?))} —— 同一个名字在 SQL 里出现两次。
 * 服务侧要是还按老习惯只 {@code setString(1, …)}，服务器直接报 ORA-01008
 * 「已绑定的变量没全都绑上」，而 {@code SchemaService} 每段读取都自带 catch，
 * 于是这个错<b>不会</b>出现在界面上：结构页只是安静地少了一块注释、少了一块约束。
 *
 * <p>为什么测这个私有方法而不从 {@code structure()} 往下跑：
 * 那条路要把 {@code DatabaseMetaData} 的整条链（列、主键、索引、外键）都做成替身，
 * 成本远大于这条不变量本身；而且真库矩阵里已经有 Oracle 那行在跑端到端。
 * 这里只锁「绑定次数 = 占位符个数」这一件事，用反射直取。
 */
class PlaceholderBindingTest {

    /** 记下每次 setString 的位置，并告诉调用方「这条 SQL 有 n 个占位符」。 */
    private static final class Recorder implements InvocationHandler {
        final List<Integer> bound = new ArrayList<>();
        boolean metadataBroken;
        private final int placeholders;

        Recorder(int placeholders) {
            this.placeholders = placeholders;
        }

        PreparedStatement statement() {
            return (PreparedStatement) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] {PreparedStatement.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "setString" -> bound.add((Integer) args[0]);
                case "getParameterMetaData" -> {
                    if (metadataBroken) {
                        throwSql();
                    }
                    return Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[] {java.sql.ParameterMetaData.class},
                            (p, m, a) -> "getParameterCount".equals(m.getName())
                                    ? placeholders
                                    : defaultValue(m.getReturnType()));
                }
                case "toString" -> {
                    return "recording-statement";
                }
                default -> {
                    return defaultValue(method.getReturnType());
                }
            }
            return null;
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) {
                return null;
            }
            if (type == boolean.class) {
                return false;
            }
            if (type == void.class) {
                return null;
            }
            return 0;
        }
    }

    private static void bind(PreparedStatement statement, String name) throws Exception {
        Method helper = SchemaService.class.getDeclaredMethod(
                "bindTableNames", PreparedStatement.class, String.class);
        helper.setAccessible(true);
        helper.invoke(null, statement, name);
    }

    @Test
    void everyPlaceholderGetsBoundNotJustTheFirst() throws Exception {
        Recorder recorder = new Recorder(2);
        bind(recorder.statement(), "orders");

        assertThat(recorder.bound)
                .as("Oracle 那条 IN (?, UPPER(?)) 有两个占位符，只绑第 1 个就是 ORA-01008")
                .containsExactly(1, 2);
    }

    @Test
    void singlePlaceholderDialectsStillBindOnce() throws Exception {
        Recorder recorder = new Recorder(1);
        bind(recorder.statement(), "orders");

        assertThat(recorder.bound).containsExactly(1);
    }

    @Test
    void metadataThatRefusesToAnswerFallsBackToOneBinding() throws Exception {
        // 少数驱动没实现 getParameterMetaData —— 退化成改造前的「只绑第一个」，
        // 不能因为元数据本身抛错就把结构页打挂。
        Recorder recorder = new Recorder(2);
        recorder.metadataBroken = true;

        bind(recorder.statement(), "orders");

        assertThat(recorder.bound)
                .as("问不到占位符个数时宁可少绑，也不许抛出去把整页带走")
                .containsExactly(1);
    }

    private static Object throwSql() throws Exception {
        throw new java.sql.SQLException("这个驱动没实现 getParameterMetaData");
    }
}
