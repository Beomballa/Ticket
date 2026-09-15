package com.portfolio.fanevent.waitingroom;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portfolio.fanevent.support.observability.OperationalMetrics;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

class WaitingRoomServiceTest {

    @Test
    void redisFailureClosesAdmissionProtectedRequest() {
        WaitingRoomRedisStore store = mock(WaitingRoomRedisStore.class);
        WaitingRoomProperties properties = new WaitingRoomProperties(
                true, "test:", Duration.ofMinutes(2), 10, 20, 3,
                Duration.ofSeconds(1), Duration.ofSeconds(1));
        WaitingRoomPolicyRepository policyRepository = mock(WaitingRoomPolicyRepository.class);
        WaitingRoomPolicy policy = mock(WaitingRoomPolicy.class);
        when(policy.isEnabled()).thenReturn(true);
        when(policyRepository.findByEventId(7L)).thenReturn(Optional.of(policy));
        when(store.restoreMarkerIfMissing(7L))
                .thenThrow(new RedisConnectionFailureException("unavailable"));
        WaitingRoomService service = new WaitingRoomService(
                store,
                policyRepository,
                mock(WaitingRoomPolicyQueryRepository.class),
                mock(com.portfolio.fanevent.catalog.infrastructure.EventRepository.class),
                mock(AdmissionTokenService.class),
                properties,
                mock(OperationalMetrics.class),
                Clock.systemUTC());

        assertThatThrownBy(() -> service.join(7L, 10L))
                .isInstanceOf(WaitingRoomUnavailableException.class)
                .hasMessageContaining("대기열 상태");
    }
}
