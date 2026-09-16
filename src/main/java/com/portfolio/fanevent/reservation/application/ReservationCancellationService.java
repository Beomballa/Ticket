package com.portfolio.fanevent.reservation.application;

import com.portfolio.fanevent.payment.application.PaymentGateway;
import com.portfolio.fanevent.payment.application.RefundAttemptService;
import com.portfolio.fanevent.payment.application.RefundDeclinedException;
import com.portfolio.fanevent.payment.application.RefundGatewayTimeoutException;
import com.portfolio.fanevent.payment.application.RefundResult;
import com.portfolio.fanevent.payment.application.RefundResultUnknownException;
import com.portfolio.fanevent.payment.domain.RefundAttempt;
import com.portfolio.fanevent.payment.domain.RefundAttemptStatus;
import org.springframework.stereotype.Service;

@Service
public class ReservationCancellationService {

    private final ReservationCancellationTransaction cancellationTransaction;
    private final RefundAttemptService refundAttemptService;
    private final PaymentGateway paymentGateway;

    public ReservationCancellationService(
            ReservationCancellationTransaction cancellationTransaction,
            RefundAttemptService refundAttemptService,
            PaymentGateway paymentGateway
    ) {
        this.cancellationTransaction = cancellationTransaction;
        this.refundAttemptService = refundAttemptService;
        this.paymentGateway = paymentGateway;
    }

    public ReservationResult cancel(Long memberId, Long reservationId) {
        ReservationCancellationTransaction.CancellationSnapshot snapshot =
                cancellationTransaction.prepare(memberId, reservationId);
        if (snapshot.confirmed() && !snapshot.alreadyCancelled()) {
            RefundAttempt attempt = refundAttemptService.begin(
                    snapshot.reservationId(), snapshot.amount());
            refund(snapshot, attempt);
        }
        return cancellationTransaction.complete(memberId, reservationId);
    }

    private void refund(
            ReservationCancellationTransaction.CancellationSnapshot snapshot,
            RefundAttempt attempt
    ) {
        if (attempt.getStatus() == RefundAttemptStatus.SUCCEEDED) {
            return;
        }
        if (attempt.getStatus() == RefundAttemptStatus.DECLINED) {
            throw new RefundDeclinedException();
        }
        if (attempt.getStatus() == RefundAttemptStatus.UNKNOWN) {
            throw new RefundResultUnknownException(attempt.getId());
        }

        try {
            RefundResult result = paymentGateway.refund(
                    snapshot.reservationId(),
                    snapshot.amount(),
                    attempt.getGatewayPaymentReference(),
                    attempt.getGatewayIdempotencyKey());
            refundAttemptService.succeed(
                    attempt.getId(), result.gatewayRefundReference());
        } catch (RefundDeclinedException exception) {
            refundAttemptService.decline(attempt.getId(), exception.getMessage());
            throw exception;
        } catch (RefundGatewayTimeoutException exception) {
            refundAttemptService.markUnknown(attempt.getId(), exception.getMessage());
            throw new RefundResultUnknownException(attempt.getId());
        }
    }
}
