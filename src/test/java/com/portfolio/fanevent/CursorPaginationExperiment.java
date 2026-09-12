package com.portfolio.fanevent;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

final class CursorPaginationExperiment {

    private static final int WARM_UP_RUNS = 10;
    private static final int MEASURED_RUNS = 100;

    private final JdbcTemplate jdbcTemplate;

    CursorPaginationExperiment(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    Comparison run(Long memberId) {
        Map<String, Object> boundary = jdbcTemplate.queryForMap("""
                SELECT created_at, id
                FROM reservations
                WHERE member_id = ? AND status = 'CONFIRMED'
                ORDER BY created_at DESC, id DESC
                OFFSET 49000 LIMIT 1
                """, memberId);
        Instant cursorCreatedAt = ((Timestamp) boundary.get("created_at")).toInstant();
        long cursorId = ((Number) boundary.get("id")).longValue();

        for (int run = 0; run < WARM_UP_RUNS; run++) {
            executeOffset(memberId);
            executeCursor(memberId, cursorCreatedAt, cursorId);
        }

        List<Long> offsetLatencies = new ArrayList<>(MEASURED_RUNS);
        List<Long> cursorLatencies = new ArrayList<>(MEASURED_RUNS);
        for (int run = 0; run < MEASURED_RUNS; run++) {
            offsetLatencies.add(measure(() -> executeOffset(memberId)));
            cursorLatencies.add(measure(() -> executeCursor(memberId, cursorCreatedAt, cursorId)));
        }
        return new Comparison(summarize(offsetLatencies), summarize(cursorLatencies));
    }

    private void executeOffset(Long memberId) {
        jdbcTemplate.queryForList("""
                SELECT id, created_at
                FROM reservations
                WHERE member_id = ? AND status = 'CONFIRMED'
                ORDER BY created_at DESC, id DESC
                OFFSET 49000 LIMIT 20
                """, memberId);
    }

    private void executeCursor(Long memberId, Instant createdAt, long reservationId) {
        jdbcTemplate.queryForList("""
                SELECT id, created_at
                FROM reservations
                WHERE member_id = ?
                  AND status = 'CONFIRMED'
                  AND (created_at, id) < (?, ?)
                ORDER BY created_at DESC, id DESC
                LIMIT 20
                """, memberId, Timestamp.from(createdAt), reservationId);
    }

    private long measure(Runnable query) {
        long startedAt = System.nanoTime();
        query.run();
        return System.nanoTime() - startedAt;
    }

    private Distribution summarize(List<Long> latencies) {
        List<Long> sorted = latencies.stream().sorted(Comparator.naturalOrder()).toList();
        return new Distribution(
                percentile(sorted, 0.50),
                percentile(sorted, 0.95),
                percentile(sorted, 0.99));
    }

    private double percentile(List<Long> sorted, double percentile) {
        int index = Math.max(0, (int) Math.ceil(sorted.size() * percentile) - 1);
        return sorted.get(index) / 1_000_000.0;
    }

    record Comparison(Distribution offset, Distribution cursor) {
    }

    record Distribution(double p50Millis, double p95Millis, double p99Millis) {
    }
}
