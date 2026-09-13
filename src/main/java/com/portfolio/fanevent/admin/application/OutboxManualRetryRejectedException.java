package com.portfolio.fanevent.admin.application;

import java.util.UUID;

public class OutboxManualRetryRejectedException extends RuntimeException {

    public OutboxManualRetryRejectedException(UUID eventId) {
        super("최대 시도 횟수에 도달한 실패 이벤트만 수동 재처리할 수 있습니다: " + eventId);
    }
}
