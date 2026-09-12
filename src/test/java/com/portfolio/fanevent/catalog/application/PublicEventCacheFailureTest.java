package com.portfolio.fanevent.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.portfolio.fanevent.catalog.domain.EventStatus;
import com.portfolio.fanevent.catalog.domain.EventType;
import com.portfolio.fanevent.catalog.infrastructure.EventQueryRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@SuppressWarnings("unchecked")
class PublicEventCacheFailureTest {

    @Test
    void redisFailureFallsBackToDatabaseAndKeepsOriginalResponse() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString()))
                .thenThrow(new RedisConnectionFailureException("redis unavailable"));

        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        PublicEventCache cache = new PublicEventCache(
                redisTemplate,
                objectMapper,
                new PublicEventCacheProperties(true, Duration.ofMinutes(5), "test:event:"));
        EventQueryRepository repository = mock(EventQueryRepository.class);
        EventDetail expected = new EventDetail(
                1L,
                "DB 원본 응답",
                "Redis 장애 테스트",
                EventType.CONCERT,
                EventStatus.PUBLISHED,
                2L,
                "아티스트",
                Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-10-01T00:00:00Z"),
                List.of());
        when(repository.findPublicDetail(1L)).thenReturn(Optional.of(expected));

        EventQueryService service = new EventQueryService(repository, cache);

        assertThat(service.getPublicDetail(1L)).isEqualTo(expected);
        assertThatCode(() -> cache.put(1L, expected)).doesNotThrowAnyException();
        assertThatCode(() -> cache.evict(1L)).doesNotThrowAnyException();
    }
}
