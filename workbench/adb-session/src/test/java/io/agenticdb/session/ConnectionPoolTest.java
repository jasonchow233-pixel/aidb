package io.agenticdb.session;

import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.JdbcSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.sql.Connection;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionPoolTest {

    @TempDir
    Path temp;

    @Test
    void opensSqliteDirectlyAndClosesWithoutLeakingFileHandle() throws Exception {
        Path file = temp.resolve("pool.db");
        DbSession session = ConnectionPool.open(ConnectionSpec.sqlite(file));
        session.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");
        session.execute("INSERT INTO t VALUES (1, 'Ada')");
        assertThat(session.query("SELECT name FROM t WHERE id = 1", 1).rows())
                .hasSize(1);
        session.close();

        // Windows 上文件未释放时会失败；此处能重建即说明连接已关闭。
        session = ConnectionPool.open(ConnectionSpec.sqlite(file));
        Object count = session.query("SELECT COUNT(*) AS c FROM t", 1).rows()
                .getFirst().get("c");
        assertThat(((Number) count).intValue()).isEqualTo(1);
        session.close();
    }

    @Test
    void closingTheSessionReturnsTheBorrowedConnectionToThePool() throws Exception {
        // ⭐ A12：以前关闭会话只关了 statement + 池子，**借来的那条连接一直挂在池外** ——
        //    池子关不掉、连接不释放（长时间使用会打满数据库的连接数）。
        //    这条门禁断言：关会话之后，池子里**没有在借的连接**了。
        Path file = temp.resolve("pooled.db");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + file);
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5_000);
        HikariDataSource dataSource = new HikariDataSource(config);
        try {
            Connection borrowed = dataSource.getConnection();
            assertThat(dataSource.getHikariPoolMXBean().getActiveConnections())
                    .as("借出之后池里应该有 1 条在用的连接")
                    .isEqualTo(1);

            DbSession session = new ConnectionPool.PooledSession(
                    JdbcSession.wrap(borrowed, "pooled"), dataSource);
            session.close();

            assertThat(dataSource.getHikariPoolMXBean().getActiveConnections())
                    .as("⭐ 关会话必须把连接还回池子（A12）")
                    .isZero();
        } finally {
            dataSource.close();
        }
    }
}
