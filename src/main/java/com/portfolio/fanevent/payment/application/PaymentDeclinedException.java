package com.portfolio.fanevent.payment.application;

public class PaymentDeclinedException extends RuntimeException {

    public PaymentDeclinedException() {
        super("모의 결제가 승인되지 않았습니다.");
    }
}
