package io.agenticdb.session;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Windows WinCred 后端门禁。
 *
 * <p>非 Windows 平台 {@code assumeTrue} 跳过，<b>不计通过</b>；纯格式函数在所有平台都跑。
 * 每条真机用例使用独立 target，并在 finally 里删除，避免污染用户凭据库。
 */
class CredentialStoreTest {

    private static String uniqueProfile() {
        return "adb-test-" + UUID.randomUUID();
    }

    @Test
    void targetNameIsNamespacedLikeOtherPlatforms() {
        assertThat(WindowsCredentialStore.targetName("abc"))
                .isEqualTo("io.agenticdb.connection/abc")
                .startsWith(WindowsCredentialStore.TARGET_PREFIX);
    }

    @Test
    void passwordBlobIsUtf16LittleEndian() {
        byte[] bytes = WindowsCredentialStore.encode("pä密");
        assertThat(bytes).hasSize(6);
        assertThat(new String(bytes, StandardCharsets.UTF_16LE)).isEqualTo("pä密");
    }

    @Test
    void tooLongPasswordFailsBeforeItReachesWindows() {
        // 2560 字节 = UTF-16 下 1280 个 BMP 字符，正好是 CRED_MAX_CREDENTIAL_BLOB_SIZE。
        assertThatCode(() -> WindowsCredentialStore.encode("x".repeat(1280)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> WindowsCredentialStore.encode("x".repeat(1281)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("口令过长");
    }

    @Test
    void platformUsesWinCredOnWindows() {
        assumeTrue(WindowsCredentialStore.supported(), "WinCred 仅在 Windows 上验证");
        try (CredentialStore store = CredentialStore.platform()) {
            assertThat(store).isInstanceOf(WindowsCredentialStore.class);
        }
    }

    @Test
    void roundTripsNonAsciiPasswordAndOverwrites() {
        assumeTrue(WindowsCredentialStore.supported(), "WinCred 仅在 Windows 上验证");
        String profile = uniqueProfile();
        CredentialStore store = new WindowsCredentialStore();
        try {
            assertThat(store.password(profile)).isEmpty();

            store.save(profile, "p@ss 中文");
            assertThat(store.password(profile)).contains("p@ss 中文");

            store.save(profile, " next ");
            assertThat(store.password(profile)).contains(" next ");
        } finally {
            store.delete(profile);
        }
        assertThat(store.password(profile)).isEmpty();
    }

    @Test
    void credentialSurvivesANewStoreInstance() {
        assumeTrue(WindowsCredentialStore.supported(), "WinCred 仅在 Windows 上验证");
        String profile = uniqueProfile();
        CredentialStore first = new WindowsCredentialStore();
        try {
            first.save(profile, "restart-me");
            CredentialStore second = new WindowsCredentialStore();
            assertThat(second.password(profile)).contains("restart-me");
        } finally {
            first.delete(profile);
        }
    }

    @Test
    void blankPasswordDeletesTheEntry() {
        assumeTrue(WindowsCredentialStore.supported(), "WinCred 仅在 Windows 上验证");
        String profile = uniqueProfile();
        CredentialStore store = new WindowsCredentialStore();
        try {
            store.save(profile, "to-be-removed");
            store.save(profile, "   ");
            assertThat(store.password(profile)).isEqualTo(Optional.empty());
        } finally {
            store.delete(profile);
        }
    }

    @Test
    void deletingAMissingCredentialIsSilent() {
        assumeTrue(WindowsCredentialStore.supported(), "WinCred 仅在 Windows 上验证");
        CredentialStore store = new WindowsCredentialStore();
        String profile = uniqueProfile();
        assertThatCode(() -> {
            store.delete(profile);
            store.delete(profile);
        }).doesNotThrowAnyException();
    }
}
