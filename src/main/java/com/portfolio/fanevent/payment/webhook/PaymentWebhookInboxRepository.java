package com.portfolio.fanevent.payment.webhook;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentWebhookInboxRepository extends JpaRepository<PaymentWebhookInbox, UUID> {

    Optional<PaymentWebhookInbox> findByProviderEventId(String providerEventId);
}
