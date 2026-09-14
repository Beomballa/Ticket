package com.portfolio.fanevent.payment.application;

import java.util.UUID;

public class PaymentResultUnknownException extends RuntimeException {

    private final UUID paymentAttemptId;

    public PaymentResultUnknownException(UUID paymentAttemptId) {
        super("결제 승인 결과를 확인 중입니다. 관리자가 대사한 뒤 다시 확인해 주세요.");
        this.paymentAttemptId = paymentAttemptId;
    }

    public UUID getPaymentAttemptId() {
        return paymentAttemptId;
    }
}
