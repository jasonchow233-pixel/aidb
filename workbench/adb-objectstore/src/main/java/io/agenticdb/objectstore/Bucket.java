package io.agenticdb.objectstore;

import java.time.Instant;

/**
 * 对象存储 bucket（四家 provider 共用）。
 */
public record Bucket(String name, Instant creationDate) {
}
