package com.portfolio.fanevent.waitingroom;

import com.portfolio.fanevent.support.observability.OperationalMetrics;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class WaitingRoomService {

    private final WaitingRoomRedisStore store;
    private final AdmissionTokenService tokenService;
    private final WaitingRoomProperties properties;
    private final OperationalMetrics metrics;
    private final Clock clock;

    public WaitingRoomService(WaitingRoomRedisStore store, AdmissionTokenService tokenService,
            WaitingRoomProperties properties, OperationalMetrics metrics, Clock clock) {
        this.store = store;
        this.tokenService = tokenService;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    public WaitingRoomEntry join(Long eventId, Long memberId) {
        if (!properties.enabled()) return WaitingRoomEntry.disabled(eventId);
        try {
            WaitingRoomEntry entry = response(eventId, memberId, store.join(eventId, memberId, clock.instant()));
            metrics.waitingRoom("join", entry.status().name().toLowerCase());
            return entry;
        } catch (DataAccessException exception) {
            metrics.waitingRoom("join", "redis_error");
            throw new WaitingRoomUnavailableException();
        }
    }

    public WaitingRoomEntry status(Long eventId, Long memberId) {
        if (!properties.enabled()) return WaitingRoomEntry.disabled(eventId);
        try {
            return response(eventId, memberId, store.status(eventId, memberId, clock.instant()));
        } catch (DataAccessException exception) {
            metrics.waitingRoom("status", "redis_error");
            throw new WaitingRoomUnavailableException();
        }
    }

    public boolean isOpen(Long eventId) {
        if (!properties.enabled()) return false;
        try {
            return store.isOpen(eventId);
        } catch (DataAccessException exception) {
            throw new WaitingRoomUnavailableException();
        }
    }

    public AdmissionTokenService.AdmissionClaims claim(String token, Long memberId, Long eventId,
            String idempotencyKey) {
        AdmissionTokenService.AdmissionClaims claims = tokenService.verify(token, memberId, eventId);
        try {
            if (!store.claim(eventId, memberId, claims.generation(), clock.instant(), idempotencyKey)) {
                throw new AdmissionTokenException("ADMISSION_TOKEN_REUSED", "이미 다른 예약 요청에 사용된 입장 토큰입니다.");
            }
            metrics.waitingRoom("token", "claimed");
            return claims;
        } catch (DataAccessException exception) {
            metrics.waitingRoom("token", "redis_error");
            throw new WaitingRoomUnavailableException();
        }
    }

    public void complete(AdmissionTokenService.AdmissionClaims claims, String idempotencyKey) {
        if (claims == null) return;
        try {
            store.complete(claims.eventId(), claims.memberId(), claims.generation(), idempotencyKey);
            metrics.waitingRoom("token", "consumed");
        } catch (DataAccessException exception) {
            metrics.waitingRoom("token", "completion_deferred_to_ttl");
        }
    }

    public int admit(Long eventId) {
        try {
            Instant now = clock.instant();
            List<WaitingRoomRedisStore.AdmittedMember> admitted = store.admit(eventId, now);
            store.recordAdmissions(eventId, admitted, now);
            if (!admitted.isEmpty()) metrics.waitingRoomAdmissions(admitted.size());
            return admitted.size();
        } catch (DataAccessException exception) {
            metrics.waitingRoom("admit", "redis_error");
            throw new WaitingRoomUnavailableException();
        }
    }

    public void open(Long eventId) {
        redisOperation(() -> store.open(eventId));
    }

    public void close(Long eventId) {
        redisOperation(() -> store.close(eventId));
    }

    public WaitingRoomSummary summary(Long eventId) {
        try {
            return store.summary(eventId);
        } catch (DataAccessException exception) {
            throw new WaitingRoomUnavailableException();
        }
    }

    public List<WaitingRoomSummary> summaries() {
        try {
            return store.openEventIds().stream().map(Long::valueOf).sorted(Comparator.naturalOrder())
                    .map(store::summary).toList();
        } catch (DataAccessException exception) {
            throw new WaitingRoomUnavailableException();
        }
    }

    private void redisOperation(Runnable operation) {
        try {
            operation.run();
        } catch (DataAccessException exception) {
            throw new WaitingRoomUnavailableException();
        }
    }

    private WaitingRoomEntry response(Long eventId, Long memberId, WaitingRoomRedisStore.EntryState state) {
        if (state.code() == 0) return WaitingRoomEntry.disabled(eventId);
        if (state.code() == 1) {
            long wait = Math.max(0, (state.position() - 1) / properties.batchSize()
                    * properties.estimatedServiceSeconds());
            return new WaitingRoomEntry(eventId, WaitingRoomStatus.WAITING, state.position(), wait, null, null);
        }
        if (state.code() == 2) {
            Instant expiresAt = Instant.ofEpochMilli(state.activeUntilMillis() == 0
                    ? clock.instant().plus(properties.admissionTtl()).toEpochMilli()
                    : state.activeUntilMillis());
            return new WaitingRoomEntry(eventId, WaitingRoomStatus.ADMITTED, null, 0L,
                    tokenService.issue(eventId, memberId, state.generation(), expiresAt), expiresAt);
        }
        return new WaitingRoomEntry(eventId, WaitingRoomStatus.WAITING, null, null, null, null);
    }
}
