package io.agenticdb.objectstore;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Date;

/** SDK 适配器共用的小转换（时间、流、空值），避免四家各写一遍。 */
final class SdkSupport {

    private SdkSupport() {
    }

    static Instant instant(Date date) {
        return date == null ? Instant.EPOCH : date.toInstant();
    }

    static String safe(String value) {
        return value == null ? "" : value;
    }

    static byte[] readAll(InputStream in) {
        try (InputStream stream = in) {
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read object content", e);
        }
    }

    /** SDK 的受检异常统一包成运行时异常，让 {@link ObjectStoreSession} 接口保持干净。 */
    static RuntimeException wrap(String operation, Exception e) {
        return new IllegalStateException(operation + " failed: " + e.getMessage(), e);
    }
}
