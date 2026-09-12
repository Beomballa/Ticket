package com.portfolio.fanevent.idempotency.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.fanevent.idempotency.domain.IdempotencyRequest;
import com.portfolio.fanevent.idempotency.infrastructure.IdempotencyRequestRepository;
import java.time.Clock;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdempotencyService {

    private final IdempotencyRequestRepository repository;
    private final IdempotencyProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotencyService(
            IdempotencyRequestRepository repository,
            IdempotencyProperties properties,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.repository = repository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public <T> T execute(
            Long memberId,
            String requestScope,
            String idempotencyKey,
            String fingerprint,
            int successStatus,
            Class<T> responseType,
            Supplier<T> operation
    ) {
        String normalizedKey = normalizeKey(idempotencyKey);
        int acquired = repository.tryAcquire(
                memberId,
                requestScope,
                normalizedKey,
                fingerprint,
                clock.instant().plus(properties.ttl()));
        IdempotencyRequest request = repository
                .findByMemberIdAndRequestScopeAndIdempotencyKey(memberId, requestScope, normalizedKey)
                .orElseThrow(() -> new IllegalStateException("멱등키 처리 정보를 찾을 수 없습니다."));

        if (acquired == 0) {
            if (!request.hasSameFingerprint(fingerprint)) {
                throw new IdempotencyConflictException();
            }
            if (!request.isCompleted()) {
                throw new IdempotencyInProgressException();
            }
            return readResponse(request, responseType);
        }

        T response = operation.get();
        request.complete(successStatus, objectMapper.valueToTree(response));
        repository.flush();
        return response;
    }

    private <T> T readResponse(IdempotencyRequest request, Class<T> responseType) {
        try {
            return objectMapper.treeToValue(request.getResponseBody(), responseType);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("저장된 멱등 응답을 읽을 수 없습니다.", exception);
        }
    }

    private String normalizeKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 120) {
            throw new IllegalArgumentException("Idempotency-Key는 1자 이상 120자 이하여야 합니다.");
        }
        return idempotencyKey.trim();
    }
}
