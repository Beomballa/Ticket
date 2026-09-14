package com.portfolio.fanevent.admin.application;

import com.portfolio.fanevent.admin.infrastructure.AdminRefundQueryRepository;
import com.portfolio.fanevent.payment.application.PaymentGateway;
import com.portfolio.fanevent.payment.application.RefundGatewayResult;
import com.portfolio.fanevent.payment.application.RefundResult;
import com.portfolio.fanevent.payment.domain.RefundAttempt;
import com.portfolio.fanevent.payment.domain.RefundAttemptStatus;
import com.portfolio.fanevent.payment.infrastructure.RefundAttemptRepository;
import com.portfolio.fanevent.reservation.application.ReservationCancellationFinalizer;
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
public class AdminRefundService {

    private static final Logger log = LoggerFactory.getLogger(AdminRefundService.class);

    private final AdminRefundQueryRepository queryRepository;
    private final RefundAttemptRepository attemptRepository;
    private final ReservationRepository reservationRepository;
    private final PaymentGateway paymentGateway;
    private final ReservationCancellationFinalizer cancellationFinalizer;
    private final OperationalMetrics metrics;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public AdminRefundService(
            AdminRefundQueryRepository queryRepository,
            RefundAttemptRepository attemptRepository,
            ReservationRepository reservationRepository,
            PaymentGateway paymentGateway,
            ReservationCancellationFinalizer cancellationFinalizer,
            OperationalMetrics metrics,
            JdbcTemplate jdbcTemplate,
            Clock clock
    ) {
        this.queryRepository = queryRepository;
        this.attemptRepository = attemptRepository;
        this.reservationRepository = reservationRepository;
        this.paymentGateway = paymentGateway;
        this.cancellationFinalizer = cancellationFinalizer;
        this.metrics = metrics;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<AdminRefundAttemptSummary> searchUnknown(Pageable pageable) {
        return queryRepository.searchUnknown(pageable);
    }

    @Transactional
    public RefundReconcileResult reconcile(UUID attemptId, String adminSubject) {
        RefundAttempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "환불 시도를 찾을 수 없습니다: " + attemptId));
        Reservation reservation = reservationRepository.findWithItemsById(attempt.getReservationId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "예약을 찾을 수 없습니다: " + attempt.getReservationId()));

        if (attempt.getStatus() != RefundAttemptStatus.UNKNOWN) {
            completeSucceededReservation(attempt, reservation, clock.instant());
            return result(attempt, reservation, true);
        }

        RefundResult gatewayResult = paymentGateway.getRefundResult(
                attempt.getGatewayIdempotencyKey());
        if (gatewayResult.result() == RefundGatewayResult.UNKNOWN) {
            metrics.refundReconciliation("unknown");
            return result(attempt, reservation, false);
        }

        Instant now = clock.instant();
        if (gatewayResult.result() == RefundGatewayResult.DECLINED) {
            attempt.decline("대사 결과 환불 요청이 거절되었습니다.", now);
            recordAudit(attempt, adminSubject, "DECLINED");
            metrics.refundReconciliation("declined");
            log.info("refund reconciliation declined: refundAttemptId={}, reservationId={}",
                    attemptId, reservation.getId());
            return result(attempt, reservation, true);
        }

        attempt.succeed(gatewayResult.gatewayRefundReference(), now);
        cancellationFinalizer.complete(reservation, now);
        recordAudit(attempt, adminSubject, "SUCCEEDED");
        attemptRepository.flush();
        reservationRepository.flush();
        metrics.refundReconciliation("succeeded");
        log.info("refund reconciliation succeeded: refundAttemptId={}, reservationId={}",
                attemptId, reservation.getId());
        return result(attempt, reservation, true);
    }

    private void completeSucceededReservation(
            RefundAttempt attempt,
            Reservation reservation,
            Instant now
    ) {
        if (attempt.getStatus() == RefundAttemptStatus.SUCCEEDED && reservation.isConfirmed()) {
            cancellationFinalizer.complete(reservation, now);
            reservationRepository.flush();
        }
    }

    private RefundReconcileResult result(
            RefundAttempt attempt,
            Reservation reservation,
            boolean resolved
    ) {
        return new RefundReconcileResult(
                attempt.getId(), attempt.getStatus(), reservation.getStatus(), resolved);
    }

    private void recordAudit(
            RefundAttempt attempt,
            String adminSubject,
            String result
    ) {
        jdbcTemplate.update("""
                INSERT INTO audit_logs (
                    actor_member_id, action, target_type, target_id, details)
                VALUES (?, 'REFUND_RECONCILED', 'REFUND_ATTEMPT', ?,
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
