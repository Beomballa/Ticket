package com.portfolio.fanevent.payment.infrastructure;

import com.portfolio.fanevent.payment.application.PaymentDeclinedException;
import com.portfolio.fanevent.payment.application.PaymentGateway;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public class MockPaymentGateway implements PaymentGateway {

    public static final String APPROVED_TOKEN = "mock-approved";

    @Override
    public void authorize(Long reservationId, BigDecimal amount, String paymentToken) {
        if (!APPROVED_TOKEN.equals(paymentToken)) {
            throw new PaymentDeclinedException();
        }
    }

    @Override
    public void refund(Long reservationId, BigDecimal amount) {
        // MVP 모의 어댑터는 승인된 결제를 항상 정상 환불한다.
    }
}
