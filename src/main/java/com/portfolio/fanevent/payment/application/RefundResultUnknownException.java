package com.portfolio.fanevent.payment.application;

import java.util.UUID;

public class RefundResultUnknownException extends RuntimeException {

    private final UUID refundAttemptId;

    public RefundResultUnknownException(UUID refundAttemptId) {
        super("환불 결과를 확인 중입니다. 관리자가 대사한 뒤 다시 확인해 주세요.");
        this.refundAttemptId = refundAttemptId;
    }

    public UUID getRefundAttemptId() {
        return refundAttemptId;
    }
}
