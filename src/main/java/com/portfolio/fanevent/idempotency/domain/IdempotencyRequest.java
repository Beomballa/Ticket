package com.portfolio.fanevent.idempotency.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.portfolio.fanevent.member.domain.Member;
import com.portfolio.fanevent.support.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "idempotency_requests")
public class IdempotencyRequest extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(name = "request_scope", nullable = false, length = 80)
    private String requestScope;

    @Column(name = "idempotency_key", nullable = false, length = 120)
    private String idempotencyKey;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_fingerprint", nullable = false, length = 64, columnDefinition = "char(64)")
    private String requestFingerprint;

    @Column(name = "response_status")
    private Integer responseStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body", columnDefinition = "jsonb")
    private JsonNode responseBody;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected IdempotencyRequest() {
    }

    public boolean hasSameFingerprint(String fingerprint) {
        return requestFingerprint.equals(fingerprint);
    }

    public boolean isCompleted() {
        return responseStatus != null && responseBody != null;
    }

    public void complete(int status, JsonNode body) {
        this.responseStatus = status;
        this.responseBody = body;
    }

    public JsonNode getResponseBody() {
        return responseBody;
    }
}
