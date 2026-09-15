package com.portfolio.fanevent.payment.webhook;

public class WebhookRejectedException extends RuntimeException {

    private final String code;

    public WebhookRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
