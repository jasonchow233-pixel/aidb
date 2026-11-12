package io.agenticdb.redis;

import java.util.List;

/** {@code zset} 值：带分值的成员，按分值升序。 */
public record RedisZSet(String type, List<ScoredMember> members) implements RedisValue {
}
