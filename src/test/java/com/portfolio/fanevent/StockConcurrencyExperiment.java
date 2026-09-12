package com.portfolio.fanevent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

final class StockConcurrencyExperiment {

    static final int INITIAL_STOCK = 100;
    static final int REQUEST_COUNT = 1_000;
    static final int CONCURRENCY = 32;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    StockConcurrencyExperiment(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    Result run(Strategy strategy, Long inventoryId) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Attempt>> futures = new ArrayList<>(REQUEST_COUNT);

        try {
            for (int request = 0; request < REQUEST_COUNT; request++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    long startedAt = System.nanoTime();
                    Outcome outcome = transactionTemplate.execute(
                            status -> execute(strategy, inventoryId));
                    return new Attempt(outcome, System.nanoTime() - startedAt);
                }));
            }

            long experimentStartedAt = System.nanoTime();
            start.countDown();
            List<Attempt> attempts = new ArrayList<>(REQUEST_COUNT);
            for (Future<Attempt> future : futures) {
                attempts.add(future.get(60, TimeUnit.SECONDS));
            }
            long durationNanos = System.nanoTime() - experimentStartedAt;
            return summarize(strategy, inventoryId, attempts, durationNanos);
        } finally {
            executor.shutdownNow();
        }
    }

    private Outcome execute(Strategy strategy, Long inventoryId) {
        return switch (strategy) {
            case OPTIMISTIC -> optimistic(inventoryId);
            case PESSIMISTIC -> pessimistic(inventoryId);
            case CONDITIONAL_UPDATE -> conditionalUpdate(inventoryId);
        };
    }

    private Outcome optimistic(Long inventoryId) {
        Map<String, Object> snapshot = jdbcTemplate.queryForMap(
                "SELECT available_quantity, version FROM sellable_inventory WHERE id = ?",
                inventoryId);
        int available = ((Number) snapshot.get("available_quantity")).intValue();
        long version = ((Number) snapshot.get("version")).longValue();
        if (available == 0) {
            return Outcome.SOLD_OUT;
        }

        int updated = jdbcTemplate.update("""
                UPDATE sellable_inventory
                SET available_quantity = available_quantity - 1,
                    version = version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND version = ?
                  AND available_quantity >= 1
                """, inventoryId, version);
        return updated == 1 ? Outcome.SUCCESS : Outcome.CONFLICT;
    }

    private Outcome pessimistic(Long inventoryId) {
        Integer available = jdbcTemplate.queryForObject("""
                SELECT available_quantity
                FROM sellable_inventory
                WHERE id = ?
                FOR UPDATE
                """, Integer.class, inventoryId);
        if (available == null || available == 0) {
            return Outcome.SOLD_OUT;
        }

        jdbcTemplate.update("""
                UPDATE sellable_inventory
                SET available_quantity = available_quantity - 1,
                    version = version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, inventoryId);
        return Outcome.SUCCESS;
    }

    private Outcome conditionalUpdate(Long inventoryId) {
        int updated = jdbcTemplate.update("""
                UPDATE sellable_inventory
                SET available_quantity = available_quantity - 1,
                    version = version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND available_quantity >= 1
                """, inventoryId);
        return updated == 1 ? Outcome.SUCCESS : Outcome.SOLD_OUT;
    }

    private Result summarize(
            Strategy strategy,
            Long inventoryId,
            List<Attempt> attempts,
            long durationNanos
    ) {
        long successes = count(attempts, Outcome.SUCCESS);
        long conflicts = count(attempts, Outcome.CONFLICT);
        long soldOut = count(attempts, Outcome.SOLD_OUT);
        List<Long> latencies = attempts.stream()
                .map(Attempt::latencyNanos)
                .sorted(Comparator.naturalOrder())
                .toList();
        int p95Index = (int) Math.ceil(latencies.size() * 0.95) - 1;
        double p95Millis = latencies.get(p95Index) / 1_000_000.0;
        double durationSeconds = durationNanos / 1_000_000_000.0;
        double tps = attempts.size() / durationSeconds;
        int finalStock = jdbcTemplate.queryForObject(
                "SELECT available_quantity FROM sellable_inventory WHERE id = ?",
                Integer.class,
                inventoryId);
        return new Result(
                strategy,
                attempts.size(),
                successes,
                conflicts,
                soldOut,
                finalStock,
                tps,
                p95Millis);
    }

    private long count(List<Attempt> attempts, Outcome outcome) {
        return attempts.stream().filter(attempt -> attempt.outcome() == outcome).count();
    }

    enum Strategy {
        OPTIMISTIC,
        PESSIMISTIC,
        CONDITIONAL_UPDATE
    }

    private enum Outcome {
        SUCCESS,
        CONFLICT,
        SOLD_OUT
    }

    private record Attempt(Outcome outcome, long latencyNanos) {
    }

    record Result(
            Strategy strategy,
            int requests,
            long successes,
            long conflicts,
            long soldOut,
            int finalStock,
            double tps,
            double p95Millis
    ) {
        double conflictRate() {
            return conflicts * 100.0 / requests;
        }
    }
}
