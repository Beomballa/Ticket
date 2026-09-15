package com.portfolio.fanevent.reservation.application;

import com.portfolio.fanevent.idempotency.application.RequestFingerprint;
import com.portfolio.fanevent.payment.application.PaymentAttemptService;
import com.portfolio.fanevent.payment.application.PaymentAuthorization;
import com.portfolio.fanevent.payment.application.PaymentDeclinedException;
import com.portfolio.fanevent.payment.application.PaymentGateway;
import com.portfolio.fanevent.payment.application.PaymentGatewayTimeoutException;
import com.portfolio.fanevent.payment.application.PaymentResultUnknownException;
import com.portfolio.fanevent.payment.domain.PaymentAttempt;
import com.portfolio.fanevent.payment.domain.PaymentAttemptStatus;
import org.springframework.stereotype.Service;

@Service
public class ReservationConfirmationService {

    private final ReservationConfirmationTransaction confirmationTransaction;
    private final PaymentGateway paymentGateway;
    private final PaymentAttemptService paymentAttemptService;
    private final RequestFingerprint requestFingerprint;

    public ReservationConfirmationService(
            ReservationConfirmationTransaction confirmationTransaction,
            PaymentGateway paymentGateway,
            PaymentAttemptService paymentAttemptService,
            RequestFingerprint requestFingerprint
    ) {
        this.confirmationTransaction = confirmationTransaction;
        this.paymentGateway = paymentGateway;
        this.paymentAttemptService = paymentAttemptService;
        this.requestFingerprint = requestFingerprint;
    }

    public ReservationResult confirm(
            Long memberId,
            Long reservationId,
            String paymentToken,
            String idempotencyKey
    ) {
        String fingerprint = requestFingerprint.sha256(
                reservationId + "|paymentToken=" + paymentToken);
        ReservationConfirmationTransaction.ConfirmationSnapshot snapshot =
                confirmationTransaction.prepare(memberId, reservationId);
        if (!snapshot.alreadyConfirmed()) {
            authorize(snapshot, paymentToken, idempotencyKey);
        }
        return confirmationTransaction.complete(
                memberId,
                reservationId,
                idempotencyKey,
                fingerprint,
                snapshot.requestedAt());
    }

    private void authorize(
            ReservationConfirmationTransaction.ConfirmationSnapshot snapshot,
            String paymentToken,
            String idempotencyKey
    ) {
        String paymentTokenFingerprint = requestFingerprint.sha256(paymentToken);
        String gatewayIdempotencyKey = "reservation-" + snapshot.reservationId() + '-'
                + requestFingerprint.sha256(idempotencyKey);
        PaymentAttempt attempt = paymentAttemptService.begin(
                snapshot.reservationId(),
                gatewayIdempotencyKey,
                paymentTokenFingerprint,
                snapshot.amount());
        authorizePayment(snapshot, paymentToken, attempt);
    }

    private void authorizePayment(
            ReservationConfirmationTransaction.ConfirmationSnapshot snapshot,
            String paymentToken,
            PaymentAttempt attempt
    ) {
        if (attempt.getStatus() == PaymentAttemptStatus.APPROVED) {
            return;
        }
        if (attempt.getStatus() == PaymentAttemptStatus.DECLINED) {
            throw new PaymentDeclinedException();
        }
        if (attempt.getStatus() == PaymentAttemptStatus.UNKNOWN) {
            throw new PaymentResultUnknownException(attempt.getId());
        }

        try {
            PaymentAuthorization authorization = paymentGateway.authorize(
                    snapshot.reservationId(),
                    snapshot.amount(),
                    paymentToken,
                    attempt.getGatewayIdempotencyKey());
            paymentAttemptService.approve(attempt.getId(), authorization.gatewayReference());
        } catch (PaymentDeclinedException exception) {
            paymentAttemptService.decline(attempt.getId(), exception.getMessage());
            throw exception;
        } catch (PaymentGatewayTimeoutException exception) {
            paymentAttemptService.markUnknown(attempt.getId(), exception.getMessage());
            throw new PaymentResultUnknownException(attempt.getId());
        }
    }
}
