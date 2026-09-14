package com.portfolio.fanevent.payment.infrastructure;

import com.portfolio.fanevent.payment.application.PaymentDeclinedException;
import com.portfolio.fanevent.payment.application.PaymentAuthorization;
import com.portfolio.fanevent.payment.application.PaymentGateway;
import com.portfolio.fanevent.payment.application.PaymentGatewayResult;
import com.portfolio.fanevent.payment.application.PaymentGatewayTimeoutException;
import com.portfolio.fanevent.payment.application.PaymentReconciliationResult;
import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class MockPaymentGateway implements PaymentGateway {

    public static final String APPROVED_TOKEN = "mock-approved";
    public static final String TIMEOUT_APPROVED_TOKEN = "mock-timeout-approved";

    private final Map<String, PaymentGatewayResult> results = new ConcurrentHashMap<>();

    @Override
    public PaymentAuthorization authorize(
            Long reservationId,
            BigDecimal amount,
            String paymentToken,
            String gatewayIdempotencyKey
    ) {
        PaymentGatewayResult existing = results.get(gatewayIdempotencyKey);
        if (existing != null) {
            return existingAuthorization(existing, reservationId);
        }
        if (TIMEOUT_APPROVED_TOKEN.equals(paymentToken)) {
            results.put(gatewayIdempotencyKey, PaymentGatewayResult.APPROVED);
            throw new PaymentGatewayTimeoutException();
        }
        if (!APPROVED_TOKEN.equals(paymentToken)) {
            results.put(gatewayIdempotencyKey, PaymentGatewayResult.DECLINED);
            throw new PaymentDeclinedException();
        }
        results.put(gatewayIdempotencyKey, PaymentGatewayResult.APPROVED);
        return authorization(reservationId);
    }

    @Override
    public PaymentReconciliationResult getAuthorizationResult(String gatewayIdempotencyKey) {
        PaymentGatewayResult result = results.getOrDefault(
                gatewayIdempotencyKey, PaymentGatewayResult.UNKNOWN);
        String reference = result == PaymentGatewayResult.APPROVED
                ? "mock-reconciled-" + gatewayIdempotencyKey
                : null;
        return new PaymentReconciliationResult(result, reference);
    }

    @Override
    public void refund(Long reservationId, BigDecimal amount) {
        // MVP 모의 어댑터는 승인된 결제를 항상 정상 환불한다.
    }

    private PaymentAuthorization existingAuthorization(
            PaymentGatewayResult result,
            Long reservationId
    ) {
        if (result == PaymentGatewayResult.APPROVED) {
            return authorization(reservationId);
        }
        if (result == PaymentGatewayResult.DECLINED) {
            throw new PaymentDeclinedException();
        }
        throw new PaymentGatewayTimeoutException();
    }

    private PaymentAuthorization authorization(Long reservationId) {
        return new PaymentAuthorization("mock-payment-" + reservationId);
    }
}
