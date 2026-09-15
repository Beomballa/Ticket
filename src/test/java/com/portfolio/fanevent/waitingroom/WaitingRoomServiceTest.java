package com.portfolio.fanevent.waitingroom;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portfolio.fanevent.support.observability.OperationalMetrics;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

class WaitingRoomServiceTest {

    @Test
    void redisFailureClosesAdmissionProtectedRequest() {
        WaitingRoomRedisStore store = mock(WaitingRoomRedisStore.class);
        WaitingRoomProperties properties = new WaitingRoomProperties(
                true, "test:", Duration.ofMinutes(2), 10, 20, 3,
                Duration.ofSeconds(1), Duration.ofSeconds(1));
        when(store.isOpen(7L)).thenThrow(new RedisConnectionFailureException("unavailable"));
        WaitingRoomService service = new WaitingRoomService(
                store,
                mock(AdmissionTokenService.class),
                properties,
                mock(OperationalMetrics.class),
                Clock.systemUTC());

        assertThatThrownBy(() -> service.isOpen(7L))
                .isInstanceOf(WaitingRoomUnavailableException.class)
                .hasMessageContaining("대기열 상태");
    }
}
