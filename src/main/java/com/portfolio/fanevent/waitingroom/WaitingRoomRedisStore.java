package com.portfolio.fanevent.waitingroom;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class WaitingRoomRedisStore {

    private static final DefaultRedisScript<List> JOIN = script("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return {0, 0, 0} end
            local member = ARGV[1]
            local activeUntil = redis.call('ZSCORE', KEYS[4], member)
            if activeUntil and tonumber(activeUntil) > tonumber(ARGV[2]) then
                return {2, 0, tonumber(redis.call('HGET', KEYS[3], member)), tonumber(activeUntil)}
            end
            if activeUntil then
                redis.call('ZREM', KEYS[4], member)
                redis.call('HDEL', KEYS[3], member)
                redis.call('HDEL', KEYS[5], member)
            end
            local score = redis.call('ZSCORE', KEYS[2], member)
            if not score then
                score = redis.call('INCR', KEYS[6])
                redis.call('ZADD', KEYS[2], score, member)
            end
            return {1, redis.call('ZRANK', KEYS[2], member) + 1, tonumber(score)}
            """);
    private static final DefaultRedisScript<List> STATUS = script("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return {0, 0, 0, 0} end
            local activeUntil = redis.call('ZSCORE', KEYS[4], ARGV[1])
            if activeUntil and tonumber(activeUntil) > tonumber(ARGV[2]) then
                return {2, 0, tonumber(redis.call('HGET', KEYS[3], ARGV[1])), tonumber(activeUntil)}
            end
            if activeUntil then
                redis.call('ZREM', KEYS[4], ARGV[1])
                redis.call('HDEL', KEYS[3], ARGV[1])
                redis.call('HDEL', KEYS[5], ARGV[1])
            end
            local rank = redis.call('ZRANK', KEYS[2], ARGV[1])
            if rank then return {1, rank + 1, 0, 0} end
            return {3, 0, 0, 0}
            """);
    private static final DefaultRedisScript<List> ADMIT = script("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return {} end
            local expired = redis.call('ZRANGEBYSCORE', KEYS[4], '-inf', ARGV[1])
            for _, member in ipairs(expired) do
                redis.call('HDEL', KEYS[3], member)
                redis.call('HDEL', KEYS[5], member)
            end
            if #expired > 0 then redis.call('ZREM', KEYS[4], unpack(expired)) end
            local capacity = tonumber(ARGV[3]) - redis.call('ZCARD', KEYS[4])
            local take = math.min(tonumber(ARGV[2]), capacity, redis.call('ZCARD', KEYS[2]))
            if take <= 0 then return {} end
            local popped = redis.call('ZPOPMIN', KEYS[2], take)
            local result = {}
            for i = 1, #popped, 2 do
                local member = popped[i]
                local generation = redis.call('INCR', KEYS[6])
                redis.call('HSET', KEYS[3], member, generation)
                redis.call('ZADD', KEYS[4], ARGV[4], member)
                table.insert(result, member)
                table.insert(result, tostring(generation))
            end
            return result
            """);
    private static final DefaultRedisScript<Long> CLAIM = new DefaultRedisScript<>("""
            local activeUntil = redis.call('ZSCORE', KEYS[1], ARGV[1])
            if not activeUntil or tonumber(activeUntil) <= tonumber(ARGV[3]) then return -1 end
            if redis.call('HGET', KEYS[2], ARGV[1]) ~= ARGV[2] then return -1 end
            local claim = redis.call('HGET', KEYS[3], ARGV[1])
            local requested = ARGV[2] .. '|' .. ARGV[4]
            if not claim then redis.call('HSET', KEYS[3], ARGV[1], requested); return 1 end
            if claim == requested then return 1 end
            return 0
            """, Long.class);
    private static final DefaultRedisScript<Long> COMPLETE = new DefaultRedisScript<>("""
            local expected = ARGV[2] .. '|' .. ARGV[3]
            if redis.call('HGET', KEYS[3], ARGV[1]) ~= expected then return 0 end
            redis.call('ZREM', KEYS[1], ARGV[1])
            redis.call('HDEL', KEYS[2], ARGV[1])
            redis.call('HDEL', KEYS[3], ARGV[1])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final WaitingRoomProperties properties;

    public WaitingRoomRedisStore(StringRedisTemplate redis, WaitingRoomProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public EntryState join(Long eventId, Long memberId, Instant now) {
        return state(redis.execute(JOIN, keys(eventId), memberId.toString(), millis(now)));
    }

    public EntryState status(Long eventId, Long memberId, Instant now) {
        return state(redis.execute(STATUS, keys(eventId), memberId.toString(), millis(now)));
    }

    public List<AdmittedMember> admit(
            Long eventId,
            Instant now,
            int batchSize,
            int activeCapacity,
            java.time.Duration admissionTtl
    ) {
        Instant expiresAt = now.plus(admissionTtl);
        List<?> raw = redis.execute(ADMIT, keys(eventId), millis(now),
                Integer.toString(batchSize), Integer.toString(activeCapacity),
                millis(expiresAt));
        List<AdmittedMember> admitted = new ArrayList<>();
        if (raw != null) {
            for (int i = 0; i + 1 < raw.size(); i += 2) {
                admitted.add(new AdmittedMember(Long.valueOf(raw.get(i).toString()),
                        Long.parseLong(raw.get(i + 1).toString()), expiresAt));
            }
        }
        return admitted;
    }

    public boolean claim(Long eventId, Long memberId, long generation, Instant now, String idempotencyKey) {
        Long result = redis.execute(CLAIM, List.of(active(eventId), admitted(eventId), claimed(eventId)),
                memberId.toString(), Long.toString(generation), millis(now), idempotencyKey);
        if (result == null || result < 0) {
            throw new AdmissionTokenException("ADMISSION_TOKEN_EXPIRED", "입장 허용 시간이 만료되었습니다.");
        }
        return result == 1;
    }

    public void complete(Long eventId, Long memberId, long generation, String idempotencyKey) {
        redis.execute(COMPLETE, List.of(active(eventId), admitted(eventId), claimed(eventId)),
                memberId.toString(), Long.toString(generation), idempotencyKey);
    }

    public void open(Long eventId) {
        redis.opsForValue().set(enabled(eventId), "1");
        redis.opsForSet().add(events(), eventId.toString());
    }

    public boolean restoreMarkerIfMissing(Long eventId) {
        Boolean restored = redis.opsForValue().setIfAbsent(enabled(eventId), "1");
        if (Boolean.TRUE.equals(restored)) {
            redis.opsForSet().add(events(), eventId.toString());
            return true;
        }
        return false;
    }

    public void close(Long eventId) {
        redis.delete(List.of(enabled(eventId), queue(eventId), admitted(eventId), active(eventId),
                claimed(eventId), sequence(eventId), admissionSequence(eventId)));
        redis.opsForSet().remove(events(), eventId.toString());
    }

    public boolean isOpen(Long eventId) {
        return Boolean.TRUE.equals(redis.hasKey(enabled(eventId)));
    }

    public WaitingRoomRedisStats stats(Long eventId) {
        boolean markerPresent = isOpen(eventId);
        Long waiting = redis.opsForZSet().zCard(queue(eventId));
        Long active = redis.opsForZSet().zCard(active(eventId));
        Double cutoff = (double) (System.currentTimeMillis() - 60_000);
        Long throughput = redis.opsForZSet().count(throughput(eventId), cutoff, Double.POSITIVE_INFINITY);
        return new WaitingRoomRedisStats(
                markerPresent, value(waiting), value(active), value(throughput));
    }

    public void recordAdmissions(Long eventId, List<AdmittedMember> admitted, Instant now) {
        if (admitted.isEmpty()) return;
        admitted.forEach(member -> redis.opsForZSet().add(
                throughput(eventId), member.memberId() + ":" + member.generation(), now.toEpochMilli()));
        redis.opsForZSet().removeRangeByScore(throughput(eventId), 0, now.minusSeconds(3600).toEpochMilli());
    }

    private EntryState state(List<?> raw) {
        if (raw == null || raw.isEmpty()) throw new WaitingRoomUnavailableException();
        return new EntryState(number(raw, 0), number(raw, 1), number(raw, 2), number(raw, 3));
    }

    private long number(List<?> values, int index) {
        return index >= values.size() ? 0 : Long.parseLong(values.get(index).toString());
    }

    private long value(Long value) { return value == null ? 0 : value; }
    private String millis(Instant instant) { return Long.toString(instant.toEpochMilli()); }
    private List<String> keys(Long eventId) {
        return List.of(enabled(eventId), queue(eventId), admitted(eventId), active(eventId),
                claimed(eventId), sequence(eventId), admissionSequence(eventId));
    }
    private String enabled(Long id) { return key(id, "enabled"); }
    private String queue(Long id) { return key(id, "queue"); }
    private String admitted(Long id) { return key(id, "admitted"); }
    private String active(Long id) { return key(id, "active"); }
    private String claimed(Long id) { return key(id, "claimed"); }
    private String sequence(Long id) { return key(id, "sequence"); }
    private String admissionSequence(Long id) { return key(id, "admission-sequence"); }
    private String throughput(Long id) { return key(id, "throughput"); }
    private String events() { return properties.keyPrefix() + "events"; }
    private String key(Long id, String suffix) { return properties.keyPrefix() + id + ':' + suffix; }
    private static DefaultRedisScript<List> script(String source) { return new DefaultRedisScript<>(source, List.class); }

    public record EntryState(long code, long position, long generation, long activeUntilMillis) {}
    public record AdmittedMember(Long memberId, long generation, Instant expiresAt) {}
}
