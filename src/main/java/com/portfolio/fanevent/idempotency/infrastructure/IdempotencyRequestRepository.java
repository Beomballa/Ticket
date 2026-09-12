package com.portfolio.fanevent.idempotency.infrastructure;

import com.portfolio.fanevent.idempotency.domain.IdempotencyRequest;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRequestRepository extends JpaRepository<IdempotencyRequest, Long> {

    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO idempotency_requests (
                member_id, request_scope, idempotency_key, request_fingerprint, expires_at
            ) VALUES (
                :memberId, :requestScope, :idempotencyKey, :fingerprint, :expiresAt
            )
            ON CONFLICT (member_id, request_scope, idempotency_key)
            DO UPDATE SET
                request_fingerprint = EXCLUDED.request_fingerprint,
                response_status = NULL,
                response_body = NULL,
                expires_at = EXCLUDED.expires_at,
                updated_at = CURRENT_TIMESTAMP
            WHERE idempotency_requests.expires_at <= CURRENT_TIMESTAMP
            """, nativeQuery = true)
    int tryAcquire(
            @Param("memberId") Long memberId,
            @Param("requestScope") String requestScope,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("fingerprint") String fingerprint,
            @Param("expiresAt") Instant expiresAt
    );

    Optional<IdempotencyRequest> findByMemberIdAndRequestScopeAndIdempotencyKey(
            Long memberId,
            String requestScope,
            String idempotencyKey
    );
}
