package io.agenticdb.session;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.PointerByReference;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Windows 凭据管理器（WinCred / {@code advapi32.dll}）。
 *
 * <p>口令存在系统凭据库里：不进 {@code connections.json}、不进日志、不进命令行参数。
 * 条目名固定为 {@code io.agenticdb.connection/<profileId>}，与 macOS / Linux 的
 * service + account 口径一致。
 */
public final class WindowsCredentialStore implements CredentialStore {

    static final String TARGET_PREFIX = "io.agenticdb.connection/";
    /** wincred.h：CRED_TYPE_GENERIC。 */
    static final int CRED_TYPE_GENERIC = 1;
    /** wincred.h：凭据随本机保存，重启后仍在。 */
    static final int CRED_PERSIST_LOCAL_MACHINE = 2;
    /** wincred.h：CRED_MAX_CREDENTIAL_BLOB_SIZE = 5 * 512 字节。 */
    static final int MAX_BLOB_BYTES = 5 * 512;
    /** ERROR_NOT_FOUND：读 / 删一个不存在的凭据，不是错误。 */
    private static final int ERROR_NOT_FOUND = 1168;

    private final WinCredApi api;

    public WindowsCredentialStore() {
        this(Native.load("Advapi32", WinCredApi.class));
    }

    WindowsCredentialStore(WinCredApi api) {
        this.api = api;
    }

    /** 只做系统判定，不加载 DLL —— 非 Windows 平台上调用它必须是安全的。 */
    static boolean supported() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }

    static String targetName(String profileId) {
        return TARGET_PREFIX + profileId;
    }

    static byte[] encode(String password) {
        byte[] bytes = password.getBytes(StandardCharsets.UTF_16LE);
        if (bytes.length > MAX_BLOB_BYTES) {
            throw new IllegalArgumentException("口令过长：WinCred 上限 " + MAX_BLOB_BYTES
                    + " 字节（UTF-16 约 " + (MAX_BLOB_BYTES / 2) + " 个字符）");
        }
        return bytes;
    }

    @Override
    public Optional<String> password(String profileId) {
        PointerByReference reference = new PointerByReference();
        if (!api.CredReadW(new WString(targetName(profileId)), CRED_TYPE_GENERIC, 0, reference)) {
            int error = Native.getLastError();
            if (error == ERROR_NOT_FOUND) {
                return Optional.empty();
            }
            throw new IllegalStateException("WinCred 读取失败（错误码 " + error + "）");
        }
        Pointer pointer = reference.getValue();
        if (pointer == null) {
            return Optional.empty();
        }
        try {
            Credential credential = new Credential(pointer);
            if (credential.CredentialBlob == null || credential.CredentialBlobSize <= 0) {
                return Optional.empty();
            }
            byte[] bytes = credential.CredentialBlob.getByteArray(0, credential.CredentialBlobSize);
            return Optional.of(new String(bytes, StandardCharsets.UTF_16LE));
        } finally {
            api.CredFree(pointer);
        }
    }

    @Override
    public void save(String profileId, String password) {
        if (password == null || password.isBlank()) {
            delete(profileId);
            return;
        }
        byte[] bytes = encode(password);
        Memory blob = new Memory(bytes.length);
        blob.write(0, bytes, 0, bytes.length);
        try {
            Credential credential = new Credential();
            credential.TargetName = new WString(targetName(profileId));
            credential.Type = CRED_TYPE_GENERIC;
            credential.CredentialBlobSize = bytes.length;
            credential.CredentialBlob = blob;
            credential.Persist = CRED_PERSIST_LOCAL_MACHINE;
            credential.UserName = new WString(profileId);
            credential.Comment = new WString("AIDB 连接口令");
            credential.write();
            if (!api.CredWriteW(credential, 0)) {
                int error = Native.getLastError();
                throw new IllegalStateException("WinCred 写入失败（错误码 " + error + "）");
            }
        } finally {
            // 明文只在内存里活到这次调用结束。
            blob.clear();
        }
    }

    @Override
    public void delete(String profileId) {
        if (api.CredDeleteW(new WString(targetName(profileId)), CRED_TYPE_GENERIC, 0)) {
            return;
        }
        int error = Native.getLastError();
        if (error != ERROR_NOT_FOUND) {
            throw new IllegalStateException("WinCred 删除失败（错误码 " + error + "）");
        }
    }

    interface WinCredApi extends Library {
        boolean CredWriteW(Credential credential, int flags);

        boolean CredReadW(WString targetName, int type, int flags, PointerByReference credential);

        boolean CredDeleteW(WString targetName, int type, int flags);

        void CredFree(Pointer credential);
    }

    /** wincred.h 的 {@code CREDENTIALW}；字段顺序不能改，JNA 按它算偏移。 */
    public static class Credential extends Structure {
        public int Flags;
        public int Type;
        public WString TargetName;
        public WString Comment;
        public FileTime LastWritten;
        public int CredentialBlobSize;
        public Pointer CredentialBlob;
        public int Persist;
        public int AttributeCount;
        public Pointer Attributes;
        public WString TargetAlias;
        public WString UserName;

        public Credential() {
            super();
        }

        Credential(Pointer pointer) {
            super(pointer);
            read();
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("Flags", "Type", "TargetName", "Comment", "LastWritten",
                    "CredentialBlobSize", "CredentialBlob", "Persist", "AttributeCount",
                    "Attributes", "TargetAlias", "UserName");
        }
    }

    /** wincred.h 的 {@code FILETIME}（两个 DWORD，8 字节）。 */
    public static class FileTime extends Structure {
        public int dwLowDateTime;
        public int dwHighDateTime;

        @Override
        protected List<String> getFieldOrder() {
            return List.of("dwLowDateTime", "dwHighDateTime");
        }
    }
}
