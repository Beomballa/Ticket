package com.portfolio.fanevent.payment.infrastructure;

import com.portfolio.fanevent.payment.application.PaymentDeclinedException;
import com.portfolio.fanevent.payment.application.PaymentAuthorization;
import com.portfolio.fanevent.payment.application.PaymentGateway;
import com.portfolio.fanevent.payment.application.PaymentGatewayResult;
import com.portfolio.fanevent.payment.application.PaymentGatewayTimeoutException;
import com.portfolio.fanevent.payment.application.PaymentReconciliationResult;
import com.portfolio.fanevent.payment.application.RefundDeclinedException;
import com.portfolio.fanevent.payment.application.RefundGatewayResult;
import com.portfolio.fanevent.payment.application.RefundGatewayTimeoutException;
import com.portfolio.fanevent.payment.application.RefundResult;
import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class MockPaymentGateway implements PaymentGateway {

    public static final String APPROVED_TOKEN = "mock-approved";
    public static final String TIMEOUT_APPROVED_TOKEN = "mock-timeout-approved";
    public static final String TIMEOUT_DECLINED_TOKEN = "mock-timeout-declined";
    public static final String REFUND_DECLINED_TOKEN = "mock-refund-declined";
    public static final String REFUND_TIMEOUT_SUCCEEDED_TOKEN = "mock-refund-timeout-succeeded";
    public static final String REFUND_TIMEOUT_DECLINED_TOKEN = "mock-refund-timeout-declined";

    private final Map<String, PaymentGatewayResult> results = new ConcurrentHashMap<>();
    private final Map<Long, RefundScenario> refundScenarios = new ConcurrentHashMap<>();
    private final Map<String, RefundGatewayResult> refundResults = new ConcurrentHashMap<>();

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
        if (TIMEOUT_DECLINED_TOKEN.equals(paymentToken)) {
            results.put(gatewayIdempotencyKey, PaymentGatewayResult.DECLINED);
            throw new PaymentGatewayTimeoutException();
        }
        RefundScenario refundScenario = refundScenario(paymentToken);
        if (!APPROVED_TOKEN.equals(paymentToken) && refundScenario == null) {
            results.put(gatewayIdempotencyKey, PaymentGatewayResult.DECLINED);
            throw new PaymentDeclinedException();
        }
        if (refundScenario != null) {
            refundScenarios.put(reservationId, refundScenario);
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
    public RefundResult refund(
            Long reservationId,
            BigDecimal amount,
            String gatewayPaymentReference,
            String gatewayIdempotencyKey
    ) {
        RefundGatewayResult existing = refundResults.get(gatewayIdempotencyKey);
        if (existing != null) {
            return existingRefund(existing, gatewayIdempotencyKey);
        }

        RefundScenario scenario = refundScenarios.getOrDefault(
                reservationId, RefundScenario.SUCCEEDED);
        if (scenario == RefundScenario.TIMEOUT_SUCCEEDED) {
            refundResults.put(gatewayIdempotencyKey, RefundGatewayResult.SUCCEEDED);
            throw new RefundGatewayTimeoutException();
        }
        if (scenario == RefundScenario.TIMEOUT_DECLINED) {
            refundResults.put(gatewayIdempotencyKey, RefundGatewayResult.DECLINED);
            throw new RefundGatewayTimeoutException();
        }
        if (scenario == RefundScenario.DECLINED) {
            refundResults.put(gatewayIdempotencyKey, RefundGatewayResult.DECLINED);
            throw new RefundDeclinedException();
        }
        refundResults.put(gatewayIdempotencyKey, RefundGatewayResult.SUCCEEDED);
        return successfulRefund(gatewayIdempotencyKey);
    }

    @Override
    public RefundResult getRefundResult(String gatewayIdempotencyKey) {
        RefundGatewayResult result = refundResults.getOrDefault(
                gatewayIdempotencyKey, RefundGatewayResult.UNKNOWN);
        String reference = result == RefundGatewayResult.SUCCEEDED
                ? "mock-refund-reconciled-" + gatewayIdempotencyKey
                : null;
        return new RefundResult(result, reference);
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

    private RefundScenario refundScenario(String paymentToken) {
        return switch (paymentToken) {
            case REFUND_DECLINED_TOKEN -> RefundScenario.DECLINED;
            case REFUND_TIMEOUT_SUCCEEDED_TOKEN -> RefundScenario.TIMEOUT_SUCCEEDED;
            case REFUND_TIMEOUT_DECLINED_TOKEN -> RefundScenario.TIMEOUT_DECLINED;
            default -> null;
        };
    }

    private RefundResult existingRefund(
            RefundGatewayResult result,
            String gatewayIdempotencyKey
    ) {
        if (result == RefundGatewayResult.SUCCEEDED) {
            return successfulRefund(gatewayIdempotencyKey);
        }
        if (result == RefundGatewayResult.DECLINED) {
            throw new RefundDeclinedException();
        }
        throw new RefundGatewayTimeoutException();
    }

    private RefundResult successfulRefund(String gatewayIdempotencyKey) {
        return new RefundResult(
                RefundGatewayResult.SUCCEEDED,
                "mock-refund-" + gatewayIdempotencyKey);
    }

    private enum RefundScenario {
        SUCCEEDED,
        DECLINED,
        TIMEOUT_SUCCEEDED,
        TIMEOUT_DECLINED
    }
}
