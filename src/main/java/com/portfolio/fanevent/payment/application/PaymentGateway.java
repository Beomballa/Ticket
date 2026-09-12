package com.portfolio.fanevent.payment.application;

import java.math.BigDecimal;

public interface PaymentGateway {

    void authorize(Long reservationId, BigDecimal amount, String paymentToken);

    void refund(Long reservationId, BigDecimal amount);
}
