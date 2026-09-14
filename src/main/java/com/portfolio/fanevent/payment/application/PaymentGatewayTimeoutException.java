package com.portfolio.fanevent.payment.application;

public class PaymentGatewayTimeoutException extends RuntimeException {

    public PaymentGatewayTimeoutException() {
        super("결제 게이트웨이 응답 시간이 초과되었습니다.");
    }
}
