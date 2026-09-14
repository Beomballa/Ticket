package com.portfolio.fanevent.payment.application;

import java.util.UUID;

public record ReconciliationCandidate(UUID attemptId, int attempts) {
}
