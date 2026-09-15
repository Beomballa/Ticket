package com.portfolio.fanevent.waitingroom;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.fanevent.catalog.domain.Event;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class WaitingRoomPolicyTest {

    @Test
    void validatesCapacityAndTokenTtl() {
        Event event = org.mockito.Mockito.mock(Event.class);

        assertThatThrownBy(() -> WaitingRoomPolicy.enabled(event, 11, 10, Duration.ofMinutes(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("활성 입장 정원");
        assertThatThrownBy(() -> WaitingRoomPolicy.enabled(event, 1, 10, Duration.ofSeconds(9)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("10초~1시간");
    }
}
