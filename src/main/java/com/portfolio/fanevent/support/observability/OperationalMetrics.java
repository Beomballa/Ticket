package com.portfolio.fanevent.support.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class OperationalMetrics {

    private final MeterRegistry registry;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final AtomicLong pendingOutbox = new AtomicLong();
    private final AtomicLong failedOutbox = new AtomicLong();
    private final AtomicLong processingOutbox = new AtomicLong();
    private final AtomicLong paymentReconciliationBacklog = new AtomicLong();
    private final AtomicLong paymentReconciliationOldestAgeSeconds = new AtomicLong();
    private final AtomicLong refundReconciliationBacklog = new AtomicLong();
    private final AtomicLong refundReconciliationOldestAgeSeconds = new AtomicLong();
    private final AtomicLong lateApprovalCompensationBacklog = new AtomicLong();
    private final AtomicLong lateApprovalCompensationOldestAgeSeconds = new AtomicLong();

    public OperationalMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("fan.event.outbox.backlog", pendingOutbox, AtomicLong::get)
                .tag("status", "pending").register(registry);
        Gauge.builder("fan.event.outbox.backlog", failedOutbox, AtomicLong::get)
                .tag("status", "failed").register(registry);
        Gauge.builder("fan.event.outbox.backlog", processingOutbox, AtomicLong::get)
                .tag("status", "processing").register(registry);
        Gauge.builder("fan.event.payment.reconciliation.backlog",
                        paymentReconciliationBacklog, AtomicLong::get)
                .register(registry);
        Gauge.builder("fan.event.payment.reconciliation.oldest.age.seconds",
                        paymentReconciliationOldestAgeSeconds, AtomicLong::get)
                .register(registry);
        Gauge.builder("fan.event.refund.reconciliation.backlog",
                        refundReconciliationBacklog, AtomicLong::get)
                .register(registry);
        Gauge.builder("fan.event.refund.reconciliation.oldest.age.seconds",
                        refundReconciliationOldestAgeSeconds, AtomicLong::get)
                .register(registry);
        Gauge.builder("fan.event.payment.compensation.backlog",
                        lateApprovalCompensationBacklog, AtomicLong::get)
                .register(registry);
        Gauge.builder("fan.event.payment.compensation.oldest.age.seconds",
                        lateApprovalCompensationOldestAgeSeconds, AtomicLong::get)
                .register(registry);
    }

    public void cache(String result) {
        increment("fan.event.cache.requests", "result", result);
    }

    public void rateLimit(String result) {
        increment("fan.event.reservation.rate.limit", "result", result);
    }

    public void outbox(String result) {
        increment("fan.event.outbox.delivery", "result", result);
    }

    public void outboxManualRetry(String result) {
        increment("fan.event.outbox.manual.retry", "result", result);
    }

    public void paymentReconciliation(String result) {
        increment("fan.event.payment.reconciliation", "result", result);
    }

    public void refundReconciliation(String result) {
        increment("fan.event.refund.reconciliation", "result", result);
    }

    public void automaticReconciliation(String type, String result) {
        increment("fan.event.reconciliation.automatic", "type", type, "result", result);
    }

    public void paymentWebhook(String type, String result) {
        increment("fan.event.payment.webhook", "type", type, "result", result);
    }

    public void lateApprovalCompensation(String result) {
        increment("fan.event.payment.compensation", "result", result);
    }

    public void expired(int count) {
        registry.counter("fan.event.reservation.expired").increment(count);
    }

    public void inventoryConflict() {
        registry.counter("fan.event.inventory.conflicts").increment();
    }

    public void updateOutboxBacklog(long pending, long failed, long processing) {
        pendingOutbox.set(pending);
        failedOutbox.set(failed);
        processingOutbox.set(processing);
    }

    public void updateReconciliationBacklog(
            long paymentCount,
            long paymentOldestAgeSeconds,
            long refundCount,
            long refundOldestAgeSeconds
    ) {
        paymentReconciliationBacklog.set(paymentCount);
        this.paymentReconciliationOldestAgeSeconds.set(paymentOldestAgeSeconds);
        refundReconciliationBacklog.set(refundCount);
        this.refundReconciliationOldestAgeSeconds.set(refundOldestAgeSeconds);
    }

    public void updateCompensationBacklog(long count, long oldestAgeSeconds) {
        lateApprovalCompensationBacklog.set(count);
        lateApprovalCompensationOldestAgeSeconds.set(oldestAgeSeconds);
    }

    private void increment(String name, String tagName, String tagValue) {
        counters.computeIfAbsent(
                        name + ':' + tagValue,
                        ignored -> registry.counter(name, tagName, tagValue))
                .increment();
    }

    private void increment(
            String name,
            String firstTagName,
            String firstTagValue,
            String secondTagName,
            String secondTagValue
    ) {
        String key = name + ':' + firstTagValue + ':' + secondTagValue;
        counters.computeIfAbsent(
                        key,
                        ignored -> registry.counter(
                                name,
                                firstTagName,
                                firstTagValue,
                                secondTagName,
                                secondTagValue))
                .increment();
    }
}
