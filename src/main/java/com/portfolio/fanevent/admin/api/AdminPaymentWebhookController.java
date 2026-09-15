package com.portfolio.fanevent.admin.api;

import com.portfolio.fanevent.admin.application.AdminPaymentWebhookService;
import com.portfolio.fanevent.admin.application.AdminWebhookInboxSummary;
import com.portfolio.fanevent.payment.webhook.WebhookReceiveResult;
import com.portfolio.fanevent.support.api.OpenApiConfiguration;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/payment-webhooks")
@Tag(name = "Admin Payment Webhooks", description = "PG 웹훅 Inbox 조회와 재처리")
@SecurityRequirement(name = OpenApiConfiguration.BEARER_AUTH)
public class AdminPaymentWebhookController {

    private final AdminPaymentWebhookService service;

    public AdminPaymentWebhookController(AdminPaymentWebhookService service) {
        this.service = service;
    }

    @GetMapping
    public AdminQueryController.PageResponse<AdminWebhookInboxSummary> search(
            @PageableDefault(size = 20) Pageable pageable
    ) {
        Page<AdminWebhookInboxSummary> page = service.search(pageable);
        return AdminQueryController.PageResponse.from(page);
    }

    @PostMapping("/{eventId}/retry")
    public WebhookReceiveResult retry(@PathVariable UUID eventId) {
        return service.retry(eventId);
    }
}
