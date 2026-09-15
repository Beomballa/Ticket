package com.portfolio.fanevent.admin.application;

import com.portfolio.fanevent.admin.infrastructure.AdminWebhookQueryRepository;
import com.portfolio.fanevent.payment.webhook.PaymentWebhookInboxRepository;
import com.portfolio.fanevent.payment.webhook.PaymentWebhookService;
import com.portfolio.fanevent.payment.webhook.WebhookReceiveResult;
import jakarta.persistence.EntityNotFoundException;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminPaymentWebhookService {

    private final PaymentWebhookInboxRepository repository;
    private final AdminWebhookQueryRepository queryRepository;
    private final PaymentWebhookService webhookService;

    public AdminPaymentWebhookService(
            PaymentWebhookInboxRepository repository,
            AdminWebhookQueryRepository queryRepository,
            PaymentWebhookService webhookService
    ) {
        this.repository = repository;
        this.queryRepository = queryRepository;
        this.webhookService = webhookService;
    }

    @Transactional(readOnly = true)
    public Page<AdminWebhookInboxSummary> search(Pageable pageable) {
        return queryRepository.search(pageable);
    }

    public WebhookReceiveResult retry(UUID eventId) {
        if (!repository.existsById(eventId)) {
            throw new EntityNotFoundException("PG 웹훅 Inbox 이벤트를 찾을 수 없습니다.");
        }
        return webhookService.retry(eventId);
    }
}
