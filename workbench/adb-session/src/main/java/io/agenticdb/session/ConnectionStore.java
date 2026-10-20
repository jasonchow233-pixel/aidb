package io.agenticdb.session;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agenticdb.jdbc.DialectKind;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 连接元数据持久化。JSON 只存非密信息；口令由 {@link CredentialStore} 单独保存。
 *
 * <p>写盘用「临时文件 + 原子替换」，权限尽量设为 600；文件损坏时返回空列表，
 * 不把上一个配置炸掉。
 */
public final class ConnectionStore {

    private final Path file;
    private final CredentialStore credentials;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.INDENT_OUTPUT, true);

    public ConnectionStore(Path file, CredentialStore credentials) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath();
        this.credentials = credentials == null ? CredentialStore.inMemory() : credentials;
    }

    public static ConnectionStore defaultStore() {
        String override = System.getProperty("adb.connections.file");
        Path path = override != null && !override.isBlank()
                ? Path.of(override)
                : Path.of(System.getProperty("user.home"), ".agenticdb", "connections.json");
        return new ConnectionStore(path, CredentialStore.platform());
    }

    public Path file() {
        return file;
    }

    public List<ConnectionProfile> load() {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            ConnectionProfile[] profiles = mapper.readValue(file.toFile(),
                    ConnectionProfile[].class);
            return sort(List.of(profiles));
        } catch (IOException e) {
            System.err.println("[adb] 连接配置损坏，已忽略: " + file + " · " + e.getMessage());
            return List.of();
        }
    }

    public void save(ConnectionProfile profile) {
        Objects.requireNonNull(profile, "profile");
        List<ConnectionProfile> profiles = new ArrayList<>(load());
        profiles.removeIf(existing -> existing.id().equals(profile.id()));
        profiles.add(profile);
        write(sort(profiles));
    }

    public void delete(ConnectionProfile profile) {
        if (profile == null) {
            return;
        }
        credentials.delete(profile.id());
        List<ConnectionProfile> profiles = new ArrayList<>(load());
        profiles.removeIf(existing -> existing.id().equals(profile.id()));
        write(sort(profiles));
    }

    public void touch(ConnectionProfile profile) {
        if (profile == null) {
            return;
        }
        save(profile.withLastUsed(System.currentTimeMillis()));
    }

    /** 首次启动或演示库被清理后补回 SQLite 演示连接。 */
    public void ensureDemo(Path demoPath) {
        List<ConnectionProfile> profiles = new ArrayList<>(load());
        String name = demoPath.getFileName() == null
                ? "agenticdb-demo.db" : demoPath.getFileName().toString();
        ConnectionProfile demo = ConnectionProfile.sqlite("demo", name,
                demoPath.toAbsolutePath().toString()).withFavorite(true);
        boolean found = profiles.stream().anyMatch(profile -> profile.id().equals("demo"));
        if (!found) {
            profiles.add(demo);
            write(sort(profiles));
        } else {
            profiles.replaceAll(profile -> profile.id().equals("demo")
                    ? profile.withName(name) : profile);
            write(sort(profiles));
        }
    }

    public CredentialStore credentials() {
        return credentials;
    }

    private void write(List<ConnectionProfile> profiles) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = Files.createTempFile(parent == null ? Path.of(".") : parent,
                    "connections-", ".json.tmp");
            mapper.writeValue(temp.toFile(), profiles);
            restrictToOwner(temp);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("无法保存连接配置 " + file + ": " + e.getMessage(), e);
        }
    }

    private void restrictToOwner(Path path) {
        try {
            Files.setPosixFilePermissions(path, java.util.Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows / 非 POSIX 文件系统由 ACL 控制。
        }
    }

    private static List<ConnectionProfile> sort(List<ConnectionProfile> profiles) {
        return profiles.stream()
                .sorted(Comparator
                        .comparing((ConnectionProfile profile) -> !profile.favorite())
                        .thenComparing(ConnectionProfile::lastUsedAt, Comparator.reverseOrder())
                        .thenComparing(ConnectionProfile::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }
}
