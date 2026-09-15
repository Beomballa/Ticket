package com.portfolio.fanevent.payment.webhook;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payment/webhooks/mock")
@Tag(name = "Payment Webhook", description = "모의 PG 서명 웹훅 수신")
public class PaymentWebhookController {

    private final PaymentWebhookService service;

    public PaymentWebhookController(PaymentWebhookService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public WebhookReceiveResult receive(
            @RequestHeader("X-PG-Event-Id") String eventId,
            @RequestHeader("X-PG-Timestamp") String timestamp,
            @RequestHeader("X-PG-Signature") String signature,
            @RequestBody String rawBody
    ) {
        return service.receive(eventId, timestamp, signature, rawBody);
    }
}
