package com.portfolio.fanevent.payment.webhook;

public class WebhookEventConflictException extends RuntimeException {

    public WebhookEventConflictException() {
        super("같은 PG event ID에 다른 payload가 전달되었습니다.");
    }
}
