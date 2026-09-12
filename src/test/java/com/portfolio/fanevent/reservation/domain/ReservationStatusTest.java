package com.portfolio.fanevent.reservation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReservationStatusTest {

    @Test
    void allowsOnlyDefinedReservationTransitions() {
        assertThat(ReservationStatus.PENDING.canTransitionTo(ReservationStatus.CONFIRMED)).isTrue();
        assertThat(ReservationStatus.PENDING.canTransitionTo(ReservationStatus.EXPIRED)).isTrue();
        assertThat(ReservationStatus.PENDING.canTransitionTo(ReservationStatus.CANCELLED)).isTrue();
        assertThat(ReservationStatus.PENDING.canTransitionTo(ReservationStatus.FAILED)).isTrue();
        assertThat(ReservationStatus.CONFIRMED.canTransitionTo(ReservationStatus.CANCELLED)).isTrue();
        assertThat(ReservationStatus.CANCELLED.canTransitionTo(ReservationStatus.CANCELLED)).isTrue();

        assertThat(ReservationStatus.CONFIRMED.canTransitionTo(ReservationStatus.PENDING)).isFalse();
        assertThat(ReservationStatus.EXPIRED.canTransitionTo(ReservationStatus.CONFIRMED)).isFalse();
        assertThat(ReservationStatus.CANCELLED.canTransitionTo(ReservationStatus.CONFIRMED)).isFalse();
        assertThat(ReservationStatus.FAILED.canTransitionTo(ReservationStatus.PENDING)).isFalse();
    }
}
