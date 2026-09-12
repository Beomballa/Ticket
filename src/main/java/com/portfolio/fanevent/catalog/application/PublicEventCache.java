package com.portfolio.fanevent.catalog.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class PublicEventCache {

    private static final Logger log = LoggerFactory.getLogger(PublicEventCache.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final PublicEventCacheProperties properties;

    public PublicEventCache(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            PublicEventCacheProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public Optional<EventDetail> get(Long eventId) {
        if (!properties.enabled()) {
            return Optional.empty();
        }
        try {
            String cached = redisTemplate.opsForValue().get(key(eventId));
            return cached == null
                    ? Optional.empty()
                    : Optional.of(objectMapper.readValue(cached, EventDetail.class));
        } catch (DataAccessException | JsonProcessingException exception) {
            log.warn("공개 이벤트 캐시 조회에 실패해 DB 조회로 전환합니다. eventId={}", eventId);
            return Optional.empty();
        }
    }

    public void put(Long eventId, EventDetail detail) {
        if (!properties.enabled()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(
                    key(eventId),
                    objectMapper.writeValueAsString(detail),
                    properties.ttl());
        } catch (DataAccessException | JsonProcessingException exception) {
            log.warn("공개 이벤트 캐시 저장에 실패했지만 원본 응답은 유지합니다. eventId={}", eventId);
        }
    }

    public void evict(Long eventId) {
        if (!properties.enabled()) {
            return;
        }
        try {
            redisTemplate.delete(key(eventId));
        } catch (DataAccessException exception) {
            log.warn("공개 이벤트 캐시 무효화에 실패했습니다. eventId={}", eventId);
        }
    }

    String key(Long eventId) {
        return properties.keyPrefix() + eventId;
    }
}
