package com.portfolio.fanevent.payment.application;

public class RefundDeclinedException extends RuntimeException {

    public RefundDeclinedException() {
        this("환불 요청이 거절되었습니다.");
    }

    public RefundDeclinedException(String message) {
        super(message);
    }
}
