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

    public OperationalMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("fan.event.outbox.backlog", pendingOutbox, AtomicLong::get)
                .tag("status", "pending").register(registry);
        Gauge.builder("fan.event.outbox.backlog", failedOutbox, AtomicLong::get)
                .tag("status", "failed").register(registry);
        Gauge.builder("fan.event.outbox.backlog", processingOutbox, AtomicLong::get)
                .tag("status", "processing").register(registry);
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

    private void increment(String name, String tagName, String tagValue) {
        counters.computeIfAbsent(
                        name + ':' + tagValue,
                        ignored -> registry.counter(name, tagName, tagValue))
                .increment();
    }
}
