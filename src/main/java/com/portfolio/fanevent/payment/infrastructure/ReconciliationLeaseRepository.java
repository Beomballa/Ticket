package com.portfolio.fanevent.payment.infrastructure;

import com.portfolio.fanevent.payment.application.ReconciliationBacklogSnapshot;
import com.portfolio.fanevent.payment.application.ReconciliationCandidate;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ReconciliationLeaseRepository {

    private static final String CLAIM_PAYMENT = """
            WITH candidates AS (
                SELECT id
                FROM payment_attempts
                WHERE status = 'UNKNOWN'
                  AND next_reconciliation_at <= ?
                  AND (reconciliation_lease_until IS NULL OR reconciliation_lease_until <= ?)
                ORDER BY next_reconciliation_at, id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            )
            UPDATE payment_attempts attempt
            SET reconciliation_attempts = attempt.reconciliation_attempts + 1,
                last_reconciliation_at = ?,
                reconciliation_lease_until = ?
            FROM candidates
            WHERE attempt.id = candidates.id
            RETURNING attempt.id, attempt.reconciliation_attempts
            """;

    private static final String CLAIM_REFUND = """
            WITH candidates AS (
                SELECT id
                FROM refund_attempts
                WHERE status = 'UNKNOWN'
                  AND next_reconciliation_at <= ?
                  AND (reconciliation_lease_until IS NULL OR reconciliation_lease_until <= ?)
                ORDER BY next_reconciliation_at, id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            )
            UPDATE refund_attempts attempt
            SET reconciliation_attempts = attempt.reconciliation_attempts + 1,
                last_reconciliation_at = ?,
                reconciliation_lease_until = ?
            FROM candidates
            WHERE attempt.id = candidates.id
            RETURNING attempt.id, attempt.reconciliation_attempts
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public ReconciliationLeaseRepository(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<ReconciliationCandidate> claimPayments(int batchSize, Duration leaseDuration) {
        return claim(CLAIM_PAYMENT, batchSize, leaseDuration);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<ReconciliationCandidate> claimRefunds(int batchSize, Duration leaseDuration) {
        return claim(CLAIM_REFUND, batchSize, leaseDuration);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completePayment(ReconciliationCandidate candidate, Duration retryDelay) {
        complete("payment_attempts", candidate, retryDelay);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeRefund(ReconciliationCandidate candidate, Duration retryDelay) {
        complete("refund_attempts", candidate, retryDelay);
    }

    @Transactional(readOnly = true)
    public ReconciliationBacklogSnapshot snapshot() {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT
                    (SELECT count(*) FROM payment_attempts WHERE status = 'UNKNOWN') payment_count,
                    (SELECT min(requested_at) FROM payment_attempts WHERE status = 'UNKNOWN') payment_oldest,
                    (SELECT count(*) FROM refund_attempts WHERE status = 'UNKNOWN') refund_count,
                    (SELECT min(requested_at) FROM refund_attempts WHERE status = 'UNKNOWN') refund_oldest
                """);
        Instant now = clock.instant();
        return new ReconciliationBacklogSnapshot(
                ((Number) row.get("payment_count")).longValue(),
                ageSeconds(row.get("payment_oldest"), now),
                ((Number) row.get("refund_count")).longValue(),
                ageSeconds(row.get("refund_oldest"), now));
    }

    private List<ReconciliationCandidate> claim(
            String sql,
            int batchSize,
            Duration leaseDuration
    ) {
        Instant now = clock.instant();
        Timestamp timestamp = Timestamp.from(now);
        return jdbcTemplate.query(
                sql,
                (resultSet, rowNumber) -> new ReconciliationCandidate(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getInt("reconciliation_attempts")),
                timestamp,
                timestamp,
                batchSize,
                timestamp,
                Timestamp.from(now.plus(leaseDuration)));
    }

    private void complete(
            String table,
            ReconciliationCandidate candidate,
            Duration retryDelay
    ) {
        if (!table.equals("payment_attempts") && !table.equals("refund_attempts")) {
            throw new IllegalArgumentException("지원하지 않는 대사 원장입니다.");
        }
        jdbcTemplate.update("""
                UPDATE %s
                SET reconciliation_lease_until = NULL,
                    next_reconciliation_at = CASE
                        WHEN status = 'UNKNOWN' THEN CAST(? AS TIMESTAMPTZ)
                        ELSE NULL
                    END
                WHERE id = ?
                  AND reconciliation_attempts = ?
                """.formatted(table),
                Timestamp.from(clock.instant().plus(retryDelay)),
                candidate.attemptId(),
                candidate.attempts());
    }

    private long ageSeconds(Object value, Instant now) {
        if (value == null) {
            return 0;
        }
        Instant requestedAt = ((Timestamp) value).toInstant();
        return Math.max(Duration.between(requestedAt, now).toSeconds(), 0);
    }
}
