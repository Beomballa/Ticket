package com.portfolio.fanevent.reservation.application;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import com.portfolio.fanevent.support.observability.OperationalMetrics;

@Component
public class ReservationRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(ReservationRateLimiter.class);
    private static final DefaultRedisScript<List> FIXED_WINDOW_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            local ttl = redis.call('PTTL', KEYS[1])
            return {current, ttl}
            """, List.class);

    private final StringRedisTemplate redisTemplate;
    private final ReservationRateLimitProperties properties;
    private final OperationalMetrics metrics;

    public ReservationRateLimiter(
            StringRedisTemplate redisTemplate,
            ReservationRateLimitProperties properties,
            OperationalMetrics metrics
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.metrics = metrics;
    }

    public void check(Long memberId) {
        if (!properties.enabled()) {
            return;
        }
        try {
            List<?> result = redisTemplate.execute(
                    FIXED_WINDOW_SCRIPT,
                    List.of(properties.keyPrefix() + memberId),
                    Long.toString(properties.window().toMillis()));
            if (result == null || result.size() < 2) {
                return;
            }
            long current = ((Number) result.get(0)).longValue();
            long ttlMillis = ((Number) result.get(1)).longValue();
            if (current > properties.limit()) {
                metrics.rateLimit("rejected");
                throw new RateLimitExceededException((ttlMillis + 999) / 1000);
            }
            metrics.rateLimit("allowed");
        } catch (DataAccessException exception) {
            metrics.rateLimit("fail_open");
            log.warn("Redis 속도 제한 확인에 실패해 예약 요청을 허용합니다. memberId={}", memberId);
        }
    }
}
