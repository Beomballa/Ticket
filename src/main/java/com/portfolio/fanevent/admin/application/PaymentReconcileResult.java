package com.portfolio.fanevent.admin.application;

import com.portfolio.fanevent.payment.domain.PaymentAttemptStatus;
import com.portfolio.fanevent.reservation.domain.ReservationStatus;
import java.util.UUID;

public record PaymentReconcileResult(
        UUID paymentAttemptId,
        PaymentAttemptStatus paymentStatus,
        ReservationStatus reservationStatus,
        boolean resolved
) {
}
