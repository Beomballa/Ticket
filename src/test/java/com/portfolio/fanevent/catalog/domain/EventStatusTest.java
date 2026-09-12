package com.portfolio.fanevent.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EventStatusTest {

    @Test
    void allowsOnlyForwardLifecycleTransitionsAndCancellation() {
        assertThat(EventStatus.DRAFT.canTransitionTo(EventStatus.PUBLISHED)).isTrue();
        assertThat(EventStatus.PUBLISHED.canTransitionTo(EventStatus.ON_SALE)).isTrue();
        assertThat(EventStatus.ON_SALE.canTransitionTo(EventStatus.CLOSED)).isTrue();
        assertThat(EventStatus.ON_SALE.canTransitionTo(EventStatus.CANCELLED)).isTrue();
        assertThat(EventStatus.ON_SALE.canTransitionTo(EventStatus.ON_SALE)).isTrue();

        assertThat(EventStatus.ON_SALE.canTransitionTo(EventStatus.DRAFT)).isFalse();
        assertThat(EventStatus.CLOSED.canTransitionTo(EventStatus.ON_SALE)).isFalse();
        assertThat(EventStatus.CANCELLED.canTransitionTo(EventStatus.PUBLISHED)).isFalse();
    }
}
