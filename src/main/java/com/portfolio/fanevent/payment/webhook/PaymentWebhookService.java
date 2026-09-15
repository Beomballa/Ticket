package com.portfolio.fanevent.payment.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.fanevent.admin.application.AdminPaymentService;
import com.portfolio.fanevent.admin.application.AdminRefundService;
import com.portfolio.fanevent.payment.application.PaymentGatewayResult;
import com.portfolio.fanevent.payment.application.RefundGatewayResult;
import com.portfolio.fanevent.payment.domain.PaymentAttempt;
import com.portfolio.fanevent.payment.domain.RefundAttempt;
import com.portfolio.fanevent.payment.infrastructure.PaymentAttemptRepository;
import com.portfolio.fanevent.payment.infrastructure.RefundAttemptRepository;
import com.portfolio.fanevent.support.observability.OperationalMetrics;
import jakarta.persistence.EntityNotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PaymentWebhookService {

    public static final String SYSTEM_ACTOR = "system:pg-webhook";

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookService.class);

    private final PaymentWebhookVerifier verifier;
    private final PaymentWebhookInboxStore store;
    private final PaymentWebhookResultRecorder recorder;
    private final PaymentAttemptRepository paymentRepository;
    private final RefundAttemptRepository refundRepository;
    private final AdminPaymentService paymentService;
    private final AdminRefundService refundService;
    private final OperationalMetrics metrics;
    private final ObjectMapper objectMapper;

    public PaymentWebhookService(
            PaymentWebhookVerifier verifier,
            PaymentWebhookInboxStore store,
            PaymentWebhookResultRecorder recorder,
            PaymentAttemptRepository paymentRepository,
            RefundAttemptRepository refundRepository,
            AdminPaymentService paymentService,
            AdminRefundService refundService,
            OperationalMetrics metrics,
            ObjectMapper objectMapper
    ) {
        this.verifier = verifier;
        this.store = store;
        this.recorder = recorder;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.paymentService = paymentService;
        this.refundService = refundService;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    public WebhookReceiveResult receive(
            String providerEventId,
            String timestamp,
            String signature,
            String rawBody
    ) {
        validateEventId(providerEventId);
        verifier.verify(timestamp, signature, rawBody);
        WebhookPayload payload = parse(rawBody);
        validateTerminalResult(payload);
        WebhookAcceptance accepted = store.accept(
                providerEventId, sha256(rawBody), rawBody, payload);
        metrics.paymentWebhook(payload.eventType().name().toLowerCase(),
                accepted.duplicate() ? "duplicate" : "received");
        return process(accepted.event(), accepted.duplicate());
    }

    public WebhookReceiveResult retry(UUID eventId) {
        return process(store.find(eventId), false);
    }

    private WebhookReceiveResult process(PaymentWebhookInbox event, boolean duplicate) {
        if (event.getStatus() == WebhookInboxStatus.PROCESSED) {
            return result(event, true);
        }
        int claimedAttempt = store.claim(event.getId());
        if (claimedAttempt == 0) {
            return result(store.find(event.getId()), true);
        }
        try {
            if (event.getEventType() == WebhookEventType.PAYMENT_AUTHORIZATION_RESULT) {
                processPayment(event);
            } else {
                processRefund(event);
            }
            store.markProcessed(event.getId(), claimedAttempt);
            metrics.paymentWebhook(event.getEventType().name().toLowerCase(), "processed");
            log.info("payment webhook processed: eventId={}, type={}",
                    event.getProviderEventId(), event.getEventType());
        } catch (RuntimeException exception) {
            store.markFailed(event.getId(), claimedAttempt, exception.getMessage());
            metrics.paymentWebhook(event.getEventType().name().toLowerCase(), "failed");
            log.warn("payment webhook processing failed: eventId={}, type={}",
                    event.getProviderEventId(), event.getEventType(), exception);
        }
        return result(store.find(event.getId()), duplicate);
    }

    private void processPayment(PaymentWebhookInbox event) {
        PaymentGatewayResult gatewayResult = PaymentGatewayResult.valueOf(event.getResult());
        recorder.recordPayment(
                event.getGatewayIdempotencyKey(), gatewayResult, event.getGatewayReference());
        PaymentAttempt attempt = paymentRepository
                .findByGatewayIdempotencyKey(event.getGatewayIdempotencyKey())
                .orElseThrow(() -> new EntityNotFoundException("대상 결제 시도를 찾을 수 없습니다."));
        paymentService.reconcile(attempt.getId(), SYSTEM_ACTOR);
    }

    private void processRefund(PaymentWebhookInbox event) {
        RefundGatewayResult gatewayResult = RefundGatewayResult.valueOf(event.getResult());
        recorder.recordRefund(
                event.getGatewayIdempotencyKey(), gatewayResult, event.getGatewayReference());
        RefundAttempt attempt = refundRepository
                .findByGatewayIdempotencyKey(event.getGatewayIdempotencyKey())
                .orElseThrow(() -> new EntityNotFoundException("대상 환불 시도를 찾을 수 없습니다."));
        refundService.reconcile(attempt.getId(), SYSTEM_ACTOR);
    }

    private WebhookPayload parse(String rawBody) {
        try {
            return objectMapper.readValue(rawBody, WebhookPayload.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("PG 웹훅 payload가 올바르지 않습니다.");
        }
    }

    private void validateTerminalResult(WebhookPayload payload) {
        boolean valid = switch (payload.eventType()) {
            case PAYMENT_AUTHORIZATION_RESULT -> payload.result().equals("APPROVED")
                    || payload.result().equals("DECLINED");
            case REFUND_RESULT -> payload.result().equals("SUCCEEDED")
                    || payload.result().equals("DECLINED");
        };
        if (!valid) {
            throw new IllegalArgumentException("PG 웹훅은 확정된 결과만 전달할 수 있습니다.");
        }
    }

    private void validateEventId(String providerEventId) {
        if (providerEventId == null || providerEventId.isBlank()
                || providerEventId.length() > 120) {
            throw new IllegalArgumentException("PG event ID가 올바르지 않습니다.");
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("PG 웹훅 payload hash를 생성할 수 없습니다.", exception);
        }
    }

    private WebhookReceiveResult result(PaymentWebhookInbox event, boolean duplicate) {
        return new WebhookReceiveResult(
                event.getProviderEventId(), event.getStatus(), duplicate);
    }
}
