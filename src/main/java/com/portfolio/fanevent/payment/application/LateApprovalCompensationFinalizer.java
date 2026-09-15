package com.portfolio.fanevent.payment.application;

import com.portfolio.fanevent.payment.domain.RefundAttempt;
import com.portfolio.fanevent.payment.domain.RefundAttemptStatus;
import com.portfolio.fanevent.payment.domain.RefundPurpose;
import com.portfolio.fanevent.payment.infrastructure.RefundAttemptRepository;
import jakarta.persistence.EntityNotFoundException;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LateApprovalCompensationFinalizer {

    private final RefundAttemptRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public LateApprovalCompensationFinalizer(
            RefundAttemptRepository repository,
            JdbcTemplate jdbcTemplate,
            Clock clock
    ) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeed(UUID attemptId, String gatewayRefundReference, String actor) {
        RefundAttempt attempt = findCompensation(attemptId);
        if (attempt.getStatus() == RefundAttemptStatus.SUCCEEDED) {
            return;
        }
        attempt.succeed(gatewayRefundReference, clock.instant());
        recordAudit(attempt, actor, "SUCCEEDED");
        repository.flush();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void decline(UUID attemptId, String message, String actor) {
        RefundAttempt attempt = findCompensation(attemptId);
        if (attempt.getStatus() == RefundAttemptStatus.DECLINED) {
            return;
        }
        attempt.decline(message, clock.instant());
        recordAudit(attempt, actor, "DECLINED");
        repository.flush();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void retry(UUID attemptId, String actor) {
        RefundAttempt attempt = findCompensation(attemptId);
        if (attempt.getStatus() == RefundAttemptStatus.SUCCEEDED) {
            return;
        }
        attempt.retryCompensation(clock.instant());
        recordAudit(attempt, actor, "RETRY_REQUESTED");
        repository.flush();
    }

    private RefundAttempt findCompensation(UUID attemptId) {
        RefundAttempt attempt = repository.findById(attemptId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "보상 환불 시도를 찾을 수 없습니다: " + attemptId));
        if (attempt.getPurpose() != RefundPurpose.LATE_PAYMENT_COMPENSATION) {
            throw new IllegalStateException("늦은 승인 보상 환불이 아닙니다.");
        }
        return attempt;
    }

    private void recordAudit(RefundAttempt attempt, String actor, String result) {
        jdbcTemplate.update("""
                INSERT INTO audit_logs (
                    actor_member_id, action, target_type, target_id, details)
                VALUES (?, 'LATE_PAYMENT_COMPENSATION', 'REFUND_ATTEMPT', ?,
                        jsonb_build_object(
                            'reservationId', CAST(? AS bigint),
                            'result', CAST(? AS text),
                            'actor', CAST(? AS text)))
                """, numericSubject(actor), attempt.getId().toString(),
                attempt.getReservationId(), result, actor);
    }

    private Long numericSubject(String subject) {
        try {
            return Long.valueOf(subject);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
