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
import java.util.Optional;

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

    /**
     * ⭐ P5-1：改连接显示名。
     *
     * <p>只改元数据 —— id、会话、口令条目都不动。改名之后调用方要用返回的
     * 新档案替换内存里的旧对象（{@link #load()} 的顺序也按新名字重排）。
     *
     * @return 改好的档案；id 不存在时 {@link Optional#empty()}
     * @throws IllegalArgumentException 新名称为空
     */
    public Optional<ConnectionProfile> rename(String id, String newName) {
        if (newName == null || newName.isBlank()) {
            throw new IllegalArgumentException("连接名称不能为空");
        }
        return update(id, profile -> profile.withName(newName.trim()));
    }

    /**
     * ⭐ P5-1：把连接移进某个分组（空串 / null = 移出分组）。
     *
     * @return 改好的档案；id 不存在时 {@link Optional#empty()}
     */
    public Optional<ConnectionProfile> moveToGroup(String id, String group) {
        return update(id, profile -> profile.withGroup(group));
    }

    /** 现有分组名（去重、忽略空白、按名称排序）—— 连接页的「分组」下拉用它。 */
    public List<String> groups() {
        return load().stream()
                .map(ConnectionProfile::group)
                .filter(group -> !group.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }
    private Optional<ConnectionProfile> update(
            String id, java.util.function.UnaryOperator<ConnectionProfile> change) {
        if (id == null) {
            return Optional.empty();
        }
        List<ConnectionProfile> profiles = new ArrayList<>(load());
        for (int index = 0; index < profiles.size(); index++) {
            ConnectionProfile existing = profiles.get(index);
            if (!existing.id().equals(id)) {
                continue;
            }
            ConnectionProfile updated = change.apply(existing);
            profiles.set(index, updated);
            write(sort(profiles));
            return Optional.of(updated);
        }
        return Optional.empty();
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
            // ⭐ A14：落盘前剔除敏感键（口令类）。
            //    options 是自由 Map，用户可能塞了 password / token 之类，
            //    那样它们会明文躺在 connections.json 里 —— 即使文件是 0600。
            mapper.writeValue(temp.toFile(), profiles.stream()
                    .map(profile -> profile.withPasswordlessOptions(
                            ConnectionProfile.sanitizedOptions(profile.options())))
                    .toList());
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
        return profiles.stream().sorted(displayOrder()).toList();
    }

    /**
     * 连接列表的展示顺序：收藏 → 最近使用倒序 → 名称。
     *
     * <p>⭐ 抽成公开的，是因为<b>界面和落盘必须用同一个顺序</b> ——
     * 以前落盘按这个排、界面却把刚用过的连接追加到末尾，
     * 同一份数据「重启前后顺序不一样」。UI 那边（{@code AppShell}）
     * 改完列表要用这个比较器重排，不能自己拼顺序。
     */
    public static Comparator<ConnectionProfile> displayOrder() {
        return Comparator
                .comparing((ConnectionProfile profile) -> !profile.favorite())
                .thenComparing(ConnectionProfile::lastUsedAt, Comparator.reverseOrder())
                .thenComparing(ConnectionProfile::name, String.CASE_INSENSITIVE_ORDER);
    }
}
