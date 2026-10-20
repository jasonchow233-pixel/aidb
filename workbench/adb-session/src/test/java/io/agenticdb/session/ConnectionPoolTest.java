package io.agenticdb.session;

import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
}
