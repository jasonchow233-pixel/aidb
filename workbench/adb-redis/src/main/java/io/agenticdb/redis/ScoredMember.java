package io.agenticdb.redis;

/** zset 的一个成员 + 分值。 */
public record ScoredMember(String member, double score) {
}
