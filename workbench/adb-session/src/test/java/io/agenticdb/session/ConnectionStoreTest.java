package io.agenticdb.session;

import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DialectKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionStoreTest {

    @TempDir
    Path temp;

    @Test
    void persistsMetadataWithoutPasswordAndKeepsDemoFavorite() throws Exception {
        Path file = temp.resolve("connections.json");
        CredentialStore credentials = CredentialStore.inMemory();
        ConnectionStore store = new ConnectionStore(file, credentials);

        store.ensureDemo(Path.of("demo.db"));
        ConnectionProfile saved = ConnectionProfile.newProfile("Local PG",
                DialectKind.POSTGRESQL, "localhost", 5432, "shop", "postgres");
        credentials.save(saved.id(), "s3cret");
        store.save(saved);

        List<ConnectionProfile> loaded = store.load();
        assertThat(loaded).extracting(ConnectionProfile::id)
                .contains("demo", saved.id());
        assertThat(loaded.getFirst().id()).isEqualTo("demo");
        assertThat(loaded.getFirst().favorite()).isTrue();
        assertThat(credentials.password(saved.id())).contains("s3cret");
        assertThat(Files.readString(file, StandardCharsets.UTF_8))
                .doesNotContain("s3cret")
                .contains("Local PG");
    }

    /**
     * A14：{@code options} 是自由 Map，用户可能塞进 {@code password} / {@code token}。
     * 它们<b>不允许</b>明文落盘。
     */
    @Test
    void sensitiveOptionsNeverReachTheDisk() throws Exception {
        Path file = temp.resolve("connections.json");
        CredentialStore credentials = CredentialStore.inMemory();
        ConnectionStore store = new ConnectionStore(file, credentials);

        ConnectionProfile base = ConnectionProfile.newProfile("Leaky", DialectKind.POSTGRESQL,
                "localhost", 5432, "shop", "postgres");
        ConnectionProfile leaky = base.withPasswordlessOptions(java.util.Map.of(
                "ssl", "true",
                "password", "should-never-be-written",
                "apiKey", "also-secret"));
        store.save(leaky);

        String json = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(json)
                .as("敏感键不得落盘（A14）")
                .doesNotContain("should-never-be-written")
                .doesNotContain("also-secret")
                .contains("ssl");
    }

    /**
     * R4：{@code options} 不得覆盖钥匙串里的口令。
     *
     * <p>以前 {@code JdbcSession.open} 先设 password 再 apply options，
     * 于是 options 里的 password 反而赢了。
     */
    @Test
    void optionsCannotOverrideTheKeychainPassword() {
        ConnectionProfile base = ConnectionProfile.newProfile("Override", DialectKind.POSTGRESQL,
                "localhost", 5432, "shop", "postgres");
        ConnectionProfile leaky = base.withPasswordlessOptions(
                java.util.Map.of("password", "from-options"));

        ConnectionSpec spec = leaky.spec("from-keychain");

        assertThat(spec.options())
                .as("敏感键在建立连接前就要被剔除")
                .doesNotContainKey("password");
        assertThat(spec.password()).isEqualTo("from-keychain");
    }

    @Test
    void sensitiveOptionDetectionIsCaseAndSeparatorInsensitive() {
        assertThat(ConnectionProfile.isSensitiveOption("password")).isTrue();
        assertThat(ConnectionProfile.isSensitiveOption("Password")).isTrue();
        assertThat(ConnectionProfile.isSensitiveOption("sslPassword")).isTrue();
        assertThat(ConnectionProfile.isSensitiveOption("api-key")).isTrue();
        assertThat(ConnectionProfile.isSensitiveOption("sslmode")).isFalse();
        assertThat(ConnectionProfile.isSensitiveOption("ApplicationName")).isFalse();
    }

    @Test
    void deleteRemovesMetadataAndCredential() {        ConnectionStore store = new ConnectionStore(temp.resolve("connections.json"),
                CredentialStore.inMemory());
        ConnectionProfile profile = ConnectionProfile.newProfile("MySQL", DialectKind.MYSQL,
                "127.0.0.1", 3307, "demo", "root");
        store.credentials().save(profile.id(), "pw");
        store.save(profile);

        store.delete(profile);

        assertThat(store.load()).isEmpty();
        assertThat(store.credentials().password(profile.id())).isEmpty();
    }

    @Test
    void duplicateKeepsConnectionDetailsButNewIdAndNoLastUsed() {
        ConnectionProfile profile = ConnectionProfile.newProfile("Old", DialectKind.POSTGRESQL,
                "db.local", 5432, "app", "app").withFavorite(true).withLastUsed(123456L);
        ConnectionProfile copy = profile.duplicate();

        assertThat(copy.id()).isNotEqualTo(profile.id());
        assertThat(copy.name()).isEqualTo("Old 副本");
        assertThat(copy.favorite()).isFalse();
        assertThat(copy.lastUsedAt()).isZero();
        assertThat(copy.displayTarget()).isEqualTo("db.local:5432/app");
    }

    /**
     * ⭐ 界面顺序必须与落盘顺序一致 —— 这条守的是「同一份数据不能有两种顺序」。
     *
     * <p>以前 UI（{@code AppShell}）把刚连过的连接追加到列表末尾，
     * 而这里是「收藏 → 最近使用倒序」，重启之后顺序就变了。
     * 现在两边共用 {@link ConnectionStore#displayOrder()}。
     */
    @Test
    void displayOrderIsFavoriteThenMostRecentlyUsedThenName() {
        ConnectionProfile old = ConnectionProfile.newProfile("Old", DialectKind.POSTGRESQL,
                "db.local", 5432, "app", "app").withLastUsed(1000L);
        ConnectionProfile fresh = ConnectionProfile.newProfile("Fresh", DialectKind.POSTGRESQL,
                "db.local", 5432, "app", "app").withLastUsed(9000L);
        ConnectionProfile star = ConnectionProfile.newProfile("Star", DialectKind.POSTGRESQL,
                "db.local", 5432, "app", "app").withLastUsed(1L).withFavorite(true);
        ConnectionProfile alpha = ConnectionProfile.newProfile("alpha", DialectKind.POSTGRESQL,
                "db.local", 5432, "app", "app").withLastUsed(9000L);

        List<ConnectionProfile> ordered = java.util.List.of(old, alpha, fresh, star).stream()
                .sorted(ConnectionStore.displayOrder()).toList();

        assertThat(ordered).extracting(ConnectionProfile::name)
                .containsExactly("Star", "alpha", "Fresh", "Old");
        // 最近使用的排前面 —— 这是「刚连过的连接跑到末尾」那条 bug 的正面断言。
        assertThat(ordered.indexOf(fresh)).isLessThan(ordered.indexOf(old));
    }

    @Test
    void profileToStringNeverContainsPassword() {
        ConnectionProfile profile = ConnectionProfile.newProfile("PG", DialectKind.POSTGRESQL,
                "db.local", 5432, "app", "app");
        ConnectionSpec spec = profile.spec("very-secret");

        assertThat(spec.toString()).doesNotContain("very-secret");
        assertThat(profile.toString()).doesNotContain("very-secret");
        assertThat(spec.jdbcUrl()).doesNotContain("very-secret");
    }

    // ─────────────────────── P5-1：重命名 / 分组 ───────────────────────

    /**
     * ⭐ P5-1：重命名只改显示名。
     *
     * <p>id / 口令条目 / 连接参数都不许跟着动 —— 改个名字把口令弄丢，
     * 是这类功能最典型的事故。
     */
    @Test
    void renameChangesOnlyTheDisplayName() {
        ConnectionStore store = new ConnectionStore(temp.resolve("connections.json"),
                CredentialStore.inMemory());
        ConnectionProfile profile = ConnectionProfile.newProfile("Old", DialectKind.POSTGRESQL,
                "db.local", 5432, "app", "app");
        store.credentials().save(profile.id(), "pw");
        store.save(profile);

        Optional<ConnectionProfile> renamed = store.rename(profile.id(), "  新名字  ");

        assertThat(renamed).isPresent();
        assertThat(renamed.get().id()).as("id 不变 —— 会话与口令都挂在它上面")
                .isEqualTo(profile.id());
        assertThat(renamed.get().name()).as("首尾空白要去掉").isEqualTo("新名字");
        assertThat(store.load()).singleElement()
                .satisfies(loaded -> assertThat(loaded.name()).isEqualTo("新名字"));
        assertThat(store.credentials().password(profile.id())).contains("pw");
        assertThat(store.rename("missing", "x")).isEmpty();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> store.rename(profile.id(), "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * ⭐ P5-1：分组落盘、可清空，且不影响连接参数。
     *
     * <p>空串是「未分组」的规范形态（不是 {@code null}）—— 旧配置文件没有这个字段时
     * 反序列化出来就是 null，必须归一成空串，否则界面会出现「null」字样。
     */
    @Test
    void groupIsPersistedAndCanBeCleared() {
        ConnectionStore store = new ConnectionStore(temp.resolve("connections.json"),
                CredentialStore.inMemory());
        ConnectionProfile profile = ConnectionProfile.newProfile("PG", DialectKind.POSTGRESQL,
                "db.local", 5432, "app", "app");
        store.save(profile);

        store.moveToGroup(profile.id(), " 生产 ");
        assertThat(store.load()).singleElement()
                .satisfies(loaded -> assertThat(loaded.group()).isEqualTo("生产"));
        assertThat(store.groups()).containsExactly("生产");
        assertThat(store.load().getFirst().spec("pw").host()).isEqualTo("db.local");

        store.moveToGroup(profile.id(), "");
        assertThat(store.load()).singleElement()
                .satisfies(loaded -> assertThat(loaded.group()).isEmpty());
        assertThat(store.groups()).isEmpty();
    }
}
