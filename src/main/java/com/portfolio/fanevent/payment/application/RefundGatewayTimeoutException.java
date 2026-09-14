package com.portfolio.fanevent.payment.application;

public class RefundGatewayTimeoutException extends RuntimeException {

    public RefundGatewayTimeoutException() {
        super("환불 처리 응답 시간이 초과되었습니다.");
    }
}
