package io.agenticdb.fixtures;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * 演示库：固定种子的 SQLite 文件，用来验证「连接 → 对象树 → 数据页 → 分页」整条竖线。
 *
 * <p>约束：
 * <ul>
 *   <li>同一路径只生成一次；已有 {@code users} 表就复用，不覆盖用户数据。</li>
 *   <li>数据完全确定：200 users / 500 orders / 80 products，测试可以断言精确数字。</li>
 *   <li>文件损坏或只建了一半时整库重建，避免「树能打开但查不了」。</li>
 * </ul>
 */
public final class DemoDatabase {

    public static final String DEFAULT_FILE_NAME = "agenticdb-demo.db";
    public static final int USER_COUNT = 200;
    public static final int ORDER_COUNT = 500;
    public static final int PRODUCT_COUNT = 80;

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 1, 1, 8, 0);

    private DemoDatabase() {
    }

    /**
     * 演示库默认位置。测试与多实例可以用 {@code -Dadb.demo.db=...} 覆盖，
     * 环境变量 {@code ADB_DEMO_DB} 作为第二选择。
     */
    public static Path defaultPath() {
        String override = System.getProperty("adb.demo.db");
        if (override == null || override.isBlank()) {
            override = System.getenv("ADB_DEMO_DB");
        }
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath();
        }
        return Path.of(System.getProperty("user.home"), ".agenticdb", DEFAULT_FILE_NAME);
    }

    /** 不存在或缺少核心表时创建；已存在有效演示库时原样返回。 */
    public static Path ensure(Path file) {
        Objects.requireNonNull(file, "file");
        try {
            Path absolute = file.toAbsolutePath();
            Path parent = absolute.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (isUsable(absolute)) {
                return absolute;
            }
            return create(absolute);
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("无法创建演示库 " + file + ": " + e.getMessage(), e);
        }
    }

    /** 强制重建。测试与「重置演示库」动作使用。 */
    public static Path create(Path file) {
        Objects.requireNonNull(file, "file");
        try {
            Path absolute = file.toAbsolutePath();
            Path parent = absolute.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.deleteIfExists(absolute);
            build(absolute);
            return absolute;
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("无法重建演示库 " + file + ": " + e.getMessage(), e);
        }
    }

    private static boolean isUsable(Path file) throws SQLException {
        if (!Files.isRegularFile(file) || file.toFile().length() == 0) {
            return false;
        }
        try (Connection connection = open(file);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='users'")) {
            return rs.next() && rs.getInt(1) == 1;
        }
    }

    private static void build(Path file) throws SQLException {
        try (Connection connection = open(file)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE users (
                            id INTEGER PRIMARY KEY,
                            name TEXT NOT NULL,
                            email TEXT NOT NULL UNIQUE,
                            active BOOLEAN NOT NULL DEFAULT 1,
                            score REAL NOT NULL DEFAULT 0,
                            bio TEXT,
                            created_at TIMESTAMP NOT NULL
                        )""");
                statement.execute("""
                        CREATE TABLE orders (
                            id INTEGER PRIMARY KEY,
                            user_id INTEGER NOT NULL REFERENCES users(id),
                            status TEXT NOT NULL,
                            amount REAL NOT NULL,
                            created_at TIMESTAMP NOT NULL
                        )""");
                statement.execute("""
                        CREATE TABLE products (
                            id INTEGER PRIMARY KEY,
                            sku TEXT NOT NULL UNIQUE,
                            name TEXT NOT NULL,
                            price REAL NOT NULL,
                            stock INTEGER NOT NULL,
                            metadata TEXT
                        )""");
                // 有意留空：数据页的空态必须能在演示库里点到，而不是只存在于代码里。
                statement.execute("""
                        CREATE TABLE notes (
                            id INTEGER PRIMARY KEY,
                            body TEXT NOT NULL,
                            created_at TIMESTAMP NOT NULL
                        )""");
                statement.execute("""
                        CREATE VIEW v_active_users AS
                        SELECT id, name, email FROM users WHERE active = 1""");
                statement.execute("CREATE INDEX idx_users_email ON users(email)");
                statement.execute("CREATE INDEX idx_orders_user ON orders(user_id)");
                statement.execute("CREATE INDEX idx_products_sku ON products(sku)");
            }

            insertUsers(connection);
            insertOrders(connection);
            insertProducts(connection);
            connection.commit();
        }
    }

    private static Connection open(Path file) throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
        return connection;
    }

    private static void insertUsers(Connection connection) throws SQLException {
        String sql = """
                INSERT INTO users(id, name, email, active, score, bio, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 1; i <= USER_COUNT; i++) {
                ps.setInt(1, i);
                ps.setString(2, "User " + String.format("%03d", i));
                ps.setString(3, "user" + String.format("%03d", i) + "@example.com");
                ps.setInt(4, i % 10 == 0 ? 0 : 1);
                ps.setDouble(5, (i * 7 % 100) + 0.5);
                if (i % 5 == 0) {
                    ps.setNull(6, java.sql.Types.VARCHAR);
                } else {
                    ps.setString(6, "Bio for user " + i);
                }
                ps.setString(7, TIMESTAMP.format(BASE_TIME.plusHours(i)));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void insertOrders(Connection connection) throws SQLException {
        String[] statuses = {"PAID", "PENDING", "CANCELLED", "REFUNDED"};
        String sql = """
                INSERT INTO orders(id, user_id, status, amount, created_at)
                VALUES (?, ?, ?, ?, ?)""";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 1; i <= ORDER_COUNT; i++) {
                ps.setInt(1, i);
                ps.setInt(2, (i - 1) % USER_COUNT + 1);
                ps.setString(3, statuses[(i - 1) % statuses.length]);
                ps.setDouble(4, (i * 13 % 500) + 9.99);
                ps.setString(5, TIMESTAMP.format(BASE_TIME.plusMinutes(i * 17L)));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void insertProducts(Connection connection) throws SQLException {
        String sql = """
                INSERT INTO products(id, sku, name, price, stock, metadata)
                VALUES (?, ?, ?, ?, ?, ?)""";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 1; i <= PRODUCT_COUNT; i++) {
                ps.setInt(1, i);
                ps.setString(2, "SKU-" + String.format("%03d", i));
                ps.setString(3, "Product " + String.format("%03d", i));
                ps.setDouble(4, (i * 3 % 200) + 1.99);
                ps.setInt(5, (i * 17) % 500);
                ps.setString(6, "{\"color\":\"red\",\"weight\":" + i + "}");
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}
