package com.portfolio.fanevent.payment.application;

public record ReconciliationBacklogSnapshot(
        long paymentCount,
        long paymentOldestAgeSeconds,
        long refundCount,
        long refundOldestAgeSeconds
) {
}
