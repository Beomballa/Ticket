package com.portfolio.fanevent.admin.application;

import com.portfolio.fanevent.payment.domain.RefundAttemptStatus;
import com.portfolio.fanevent.reservation.domain.ReservationStatus;
import java.util.UUID;

public record RefundReconcileResult(
        UUID refundAttemptId,
        RefundAttemptStatus refundStatus,
        ReservationStatus reservationStatus,
        boolean resolved
) {
}
