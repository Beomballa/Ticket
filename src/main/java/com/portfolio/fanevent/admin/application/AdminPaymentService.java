package com.portfolio.fanevent.admin.application;

import com.portfolio.fanevent.admin.infrastructure.AdminPaymentQueryRepository;
import com.portfolio.fanevent.outbox.application.OutboxEventWriter;
import com.portfolio.fanevent.payment.application.PaymentGateway;
import com.portfolio.fanevent.payment.application.PaymentGatewayResult;
import com.portfolio.fanevent.payment.application.PaymentReconciliationResult;
import com.portfolio.fanevent.payment.domain.PaymentAttempt;
import com.portfolio.fanevent.payment.domain.PaymentAttemptStatus;
import com.portfolio.fanevent.payment.domain.RefundAttempt;
import com.portfolio.fanevent.payment.domain.RefundPurpose;
import com.portfolio.fanevent.payment.infrastructure.PaymentAttemptRepository;
import com.portfolio.fanevent.payment.infrastructure.RefundAttemptRepository;
import com.portfolio.fanevent.reservation.domain.Reservation;
import com.portfolio.fanevent.reservation.infrastructure.ReservationRepository;
import com.portfolio.fanevent.support.observability.OperationalMetrics;
import jakarta.persistence.EntityNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminPaymentService {

    private static final Logger log = LoggerFactory.getLogger(AdminPaymentService.class);

    private final AdminPaymentQueryRepository queryRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final ReservationRepository reservationRepository;
    private final RefundAttemptRepository refundRepository;
    private final PaymentGateway paymentGateway;
    private final OutboxEventWriter outboxEventWriter;
    private final OperationalMetrics metrics;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public AdminPaymentService(
            AdminPaymentQueryRepository queryRepository,
            PaymentAttemptRepository attemptRepository,
            ReservationRepository reservationRepository,
            RefundAttemptRepository refundRepository,
            PaymentGateway paymentGateway,
            OutboxEventWriter outboxEventWriter,
            OperationalMetrics metrics,
            JdbcTemplate jdbcTemplate,
            Clock clock
    ) {
        this.queryRepository = queryRepository;
        this.attemptRepository = attemptRepository;
        this.reservationRepository = reservationRepository;
        this.refundRepository = refundRepository;
        this.paymentGateway = paymentGateway;
        this.outboxEventWriter = outboxEventWriter;
        this.metrics = metrics;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<AdminPaymentAttemptSummary> searchUnknown(Pageable pageable) {
        return queryRepository.searchUnknown(pageable);
    }

    @Transactional
    public PaymentReconcileResult reconcile(UUID attemptId, String adminSubject) {
        PaymentAttempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "결제 시도를 찾을 수 없습니다: " + attemptId));
        Reservation reservation = reservationRepository.findWithItemsById(attempt.getReservationId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "예약을 찾을 수 없습니다: " + attempt.getReservationId()));

        if (attempt.getStatus() != PaymentAttemptStatus.UNKNOWN) {
            return result(attempt, reservation, true);
        }

        PaymentReconciliationResult gatewayResult = paymentGateway.getAuthorizationResult(
                attempt.getGatewayIdempotencyKey());
        if (gatewayResult.result() == PaymentGatewayResult.UNKNOWN) {
            metrics.paymentReconciliation("unknown");
            return result(attempt, reservation, false);
        }

        Instant now = clock.instant();
        if (gatewayResult.result() == PaymentGatewayResult.DECLINED) {
            attempt.decline("대사 결과 결제 승인이 거절되었습니다.", now);
            recordAudit(attempt, adminSubject, "DECLINED");
            metrics.paymentReconciliation("declined");
            log.info("payment reconciliation declined: paymentAttemptId={}, reservationId={}",
                    attemptId, reservation.getId());
            return result(attempt, reservation, true);
        }

        attempt.approve(gatewayResult.gatewayReference(), now);
        if (reservation.isExpired()) {
            createLateApprovalCompensation(attempt, now);
            recordAudit(attempt, adminSubject, "APPROVED_COMPENSATION_PENDING");
            attemptRepository.flush();
            metrics.paymentReconciliation("approved_compensation_pending");
            metrics.lateApprovalCompensation("created");
            log.warn("late payment approval compensation created: paymentAttemptId={}, reservationId={}",
                    attemptId, reservation.getId());
            return result(attempt, reservation, true);
        }
        reservation.requireConfirmable(now);
        if (reservation.confirm(now)) {
            outboxEventWriter.appendReservationEvent(
                    reservation, "RESERVATION_CONFIRMED", now);
        }
        recordAudit(attempt, adminSubject, "APPROVED");
        attemptRepository.flush();
        reservationRepository.flush();
        metrics.paymentReconciliation("approved");
        log.info("payment reconciliation approved: paymentAttemptId={}, reservationId={}",
                attemptId, reservation.getId());
        return result(attempt, reservation, true);
    }

    private void createLateApprovalCompensation(PaymentAttempt payment, Instant now) {
        RefundAttempt existing = refundRepository.findByReservationId(payment.getReservationId())
                .orElse(null);
        if (existing != null) {
            if (existing.getPurpose() != RefundPurpose.LATE_PAYMENT_COMPENSATION) {
                throw new IllegalStateException("예약에 다른 목적의 환불 시도가 이미 존재합니다.");
            }
            return;
        }
        refundRepository.save(RefundAttempt.latePaymentCompensation(
                payment.getReservationId(),
                payment.getId(),
                "late-payment-compensation-" + payment.getReservationId(),
                payment.getGatewayReference(),
                payment.getAmount(),
                now));
    }

    private PaymentReconcileResult result(
            PaymentAttempt attempt,
            Reservation reservation,
            boolean resolved
    ) {
        return new PaymentReconcileResult(
                attempt.getId(), attempt.getStatus(), reservation.getStatus(), resolved);
    }

    private void recordAudit(
            PaymentAttempt attempt,
            String adminSubject,
            String result
    ) {
        jdbcTemplate.update("""
                INSERT INTO audit_logs (
                    actor_member_id, action, target_type, target_id, details)
                VALUES (?, 'PAYMENT_RECONCILED', 'PAYMENT_ATTEMPT', ?,
                        jsonb_build_object(
                            'reservationId', CAST(? AS bigint),
                            'result', CAST(? AS text),
                            'adminSubject', CAST(? AS text)))
                """,
                numericSubject(adminSubject),
                attempt.getId().toString(),
                attempt.getReservationId(),
                result,
                adminSubject);
    }

    private Long numericSubject(String subject) {
        try {
            return Long.valueOf(subject);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
