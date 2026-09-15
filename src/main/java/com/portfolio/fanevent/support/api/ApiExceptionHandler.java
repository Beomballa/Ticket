package com.portfolio.fanevent.support.api;

import com.portfolio.fanevent.admin.application.OutboxManualRetryRejectedException;
import com.portfolio.fanevent.catalog.domain.InsufficientStockException;
import com.portfolio.fanevent.idempotency.application.IdempotencyConflictException;
import com.portfolio.fanevent.idempotency.application.IdempotencyInProgressException;
import com.portfolio.fanevent.member.application.DuplicateEmailException;
import com.portfolio.fanevent.member.application.InvalidCredentialsException;
import com.portfolio.fanevent.payment.application.PaymentDeclinedException;
import com.portfolio.fanevent.payment.application.PaymentResultUnknownException;
import com.portfolio.fanevent.payment.application.RefundDeclinedException;
import com.portfolio.fanevent.payment.application.RefundResultUnknownException;
import com.portfolio.fanevent.payment.webhook.WebhookEventConflictException;
import com.portfolio.fanevent.payment.webhook.WebhookRejectedException;
import com.portfolio.fanevent.reservation.application.RateLimitExceededException;
import com.portfolio.fanevent.support.observability.OperationalMetrics;
import com.portfolio.fanevent.waitingroom.AdmissionTokenException;
import com.portfolio.fanevent.waitingroom.WaitingRoomUnavailableException;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private final OperationalMetrics metrics;

    public ApiExceptionHandler(OperationalMetrics metrics) {
        this.metrics = metrics;
    }

    @ExceptionHandler(WebhookRejectedException.class)
    ResponseEntity<ApiError> handleWebhookRejected(WebhookRejectedException exception) {
        metrics.paymentWebhook("request", "rejected");
        return error(HttpStatus.UNAUTHORIZED, exception.getCode(), exception.getMessage(), List.of());
    }

    @ExceptionHandler(WebhookEventConflictException.class)
    ResponseEntity<ApiError> handleWebhookConflict(WebhookEventConflictException exception) {
        metrics.paymentWebhook("request", "conflict");
        return error(HttpStatus.CONFLICT, "WEBHOOK_EVENT_CONFLICT", exception.getMessage(), List.of());
    }

    @ExceptionHandler(OutboxManualRetryRejectedException.class)
    ResponseEntity<ApiError> handleOutboxRetryRejected(OutboxManualRetryRejectedException exception) {
        return error(HttpStatus.CONFLICT, "OUTBOX_RETRY_REJECTED", exception.getMessage(), List.of());
    }

    @ExceptionHandler(RateLimitExceededException.class)
    ResponseEntity<ApiError> handleRateLimitExceeded(RateLimitExceededException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", Long.toString(exception.getRetryAfterSeconds()))
                .body(new ApiError(
                        "RESERVATION_RATE_LIMITED",
                        exception.getMessage(),
                        MDC.get("traceId"),
                        List.of()));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ApiError> handleIdempotencyConflict(IdempotencyConflictException exception) {
        return error(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", exception.getMessage(), List.of());
    }

    @ExceptionHandler(IdempotencyInProgressException.class)
    ResponseEntity<ApiError> handleIdempotencyInProgress(IdempotencyInProgressException exception) {
        return error(HttpStatus.CONFLICT, "IDEMPOTENCY_REQUEST_IN_PROGRESS", exception.getMessage(), List.of());
    }

    @ExceptionHandler(PaymentDeclinedException.class)
    ResponseEntity<ApiError> handlePaymentDeclined(PaymentDeclinedException exception) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "PAYMENT_DECLINED", exception.getMessage(), List.of());
    }

    @ExceptionHandler(PaymentResultUnknownException.class)
    ResponseEntity<ApiError> handlePaymentResultUnknown(PaymentResultUnknownException exception) {
        return error(
                HttpStatus.CONFLICT,
                "PAYMENT_RESULT_UNKNOWN",
                exception.getMessage(),
                List.of("paymentAttemptId: " + exception.getPaymentAttemptId()));
    }

    @ExceptionHandler(RefundDeclinedException.class)
    ResponseEntity<ApiError> handleRefundDeclined(RefundDeclinedException exception) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "REFUND_DECLINED", exception.getMessage(), List.of());
    }

    @ExceptionHandler(RefundResultUnknownException.class)
    ResponseEntity<ApiError> handleRefundResultUnknown(RefundResultUnknownException exception) {
        return error(
                HttpStatus.CONFLICT,
                "REFUND_RESULT_UNKNOWN",
                exception.getMessage(),
                List.of("refundAttemptId: " + exception.getRefundAttemptId()));
    }

    @ExceptionHandler(InsufficientStockException.class)
    ResponseEntity<ApiError> handleInsufficientStock(InsufficientStockException exception) {
        return error(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK", exception.getMessage(), List.of());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ApiError> handleOptimisticLock(ObjectOptimisticLockingFailureException exception) {
        metrics.inventoryConflict();
        return error(
                HttpStatus.CONFLICT,
                "INVENTORY_CONFLICT",
                "동시에 처리된 예약이 있습니다. 재고를 확인한 뒤 다시 시도해 주세요.",
                List.of());
    }

    @ExceptionHandler(DuplicateEmailException.class)
    ResponseEntity<ApiError> handleDuplicateEmail(DuplicateEmailException exception) {
        return error(HttpStatus.CONFLICT, "DUPLICATE_EMAIL", exception.getMessage(), List.of());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<ApiError> handleInvalidCredentials(InvalidCredentialsException exception) {
        return error(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", exception.getMessage(), List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception) {
        List<String> details = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .toList();
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "요청값이 올바르지 않습니다.", details);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage(), List.of());
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ApiError> handleIllegalState(IllegalStateException exception) {
        return error(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", exception.getMessage(), List.of());
    }

    @ExceptionHandler(EntityNotFoundException.class)
    ResponseEntity<ApiError> handleNotFound(EntityNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", exception.getMessage(), List.of());
    }

    @ExceptionHandler(AdmissionTokenException.class)
    ResponseEntity<ApiError> handleAdmissionToken(AdmissionTokenException exception) {
        HttpStatus status = "ADMISSION_TOKEN_REQUIRED".equals(exception.getCode())
                ? HttpStatus.PRECONDITION_REQUIRED : HttpStatus.FORBIDDEN;
        return error(status, exception.getCode(), exception.getMessage(), List.of());
    }

    @ExceptionHandler(WaitingRoomUnavailableException.class)
    ResponseEntity<ApiError> handleWaitingRoomUnavailable(WaitingRoomUnavailableException exception) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "WAITING_ROOM_UNAVAILABLE", exception.getMessage(), List.of());
    }

    private ResponseEntity<ApiError> error(
            HttpStatus status,
            String code,
            String message,
            List<String> details
    ) {
        return ResponseEntity.status(status)
                .body(new ApiError(code, message, MDC.get("traceId"), details));
    }
}
