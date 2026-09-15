package com.portfolio.fanevent.waitingroom;

import com.portfolio.fanevent.catalog.domain.Event;
import com.portfolio.fanevent.catalog.infrastructure.EventRepository;
import com.portfolio.fanevent.support.observability.OperationalMetrics;
import jakarta.persistence.EntityNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WaitingRoomService {

    private final WaitingRoomRedisStore store;
    private final WaitingRoomPolicyRepository policyRepository;
    private final WaitingRoomPolicyQueryRepository policyQueryRepository;
    private final EventRepository eventRepository;
    private final AdmissionTokenService tokenService;
    private final WaitingRoomProperties properties;
    private final OperationalMetrics metrics;
    private final Clock clock;

    public WaitingRoomService(
            WaitingRoomRedisStore store,
            WaitingRoomPolicyRepository policyRepository,
            WaitingRoomPolicyQueryRepository policyQueryRepository,
            EventRepository eventRepository,
            AdmissionTokenService tokenService,
            WaitingRoomProperties properties,
            OperationalMetrics metrics,
            Clock clock
    ) {
        this.store = store;
        this.policyRepository = policyRepository;
        this.policyQueryRepository = policyQueryRepository;
        this.eventRepository = eventRepository;
        this.tokenService = tokenService;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public WaitingRoomEntry join(Long eventId, Long memberId) {
        WaitingRoomPolicy policy = enabledPolicy(eventId);
        if (policy == null) return WaitingRoomEntry.disabled(eventId);
        try {
            ensureMarker(eventId);
            WaitingRoomEntry entry = response(
                    eventId, memberId, policy, store.join(eventId, memberId, clock.instant()));
            metrics.waitingRoom("join", entry.status().name().toLowerCase());
            return entry;
        } catch (DataAccessException exception) {
            metrics.waitingRoom("join", "redis_error");
            throw new WaitingRoomUnavailableException();
        }
    }

    @Transactional(readOnly = true)
    public WaitingRoomEntry status(Long eventId, Long memberId) {
        WaitingRoomPolicy policy = enabledPolicy(eventId);
        if (policy == null) return WaitingRoomEntry.disabled(eventId);
        try {
            ensureMarker(eventId);
            return response(eventId, memberId, policy,
                    store.status(eventId, memberId, clock.instant()));
        } catch (DataAccessException exception) {
            metrics.waitingRoom("status", "redis_error");
            throw new WaitingRoomUnavailableException();
        }
    }

    @Transactional(readOnly = true)
    public boolean isOpen(Long eventId) {
        return properties.enabled() && policyRepository.findByEventId(eventId)
                .map(WaitingRoomPolicy::isEnabled).orElse(false);
    }

    public AdmissionTokenService.AdmissionClaims claim(
            String token,
            Long memberId,
            Long eventId,
            String idempotencyKey
    ) {
        AdmissionTokenService.AdmissionClaims claims = tokenService.verify(token, memberId, eventId);
        try {
            if (!store.claim(eventId, memberId, claims.generation(), clock.instant(), idempotencyKey)) {
                throw new AdmissionTokenException(
                        "ADMISSION_TOKEN_REUSED", "이미 다른 예약 요청에 사용된 입장 토큰입니다.");
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

    @Transactional(readOnly = true)
    public int admit(Long eventId) {
        WaitingRoomPolicy policy = enabledPolicy(eventId);
        if (policy == null) return 0;
        try {
            ensureMarker(eventId);
            Instant now = clock.instant();
            List<WaitingRoomRedisStore.AdmittedMember> admitted = store.admit(
                    eventId, now, policy.getBatchSize(), policy.getActiveCapacity(), policy.getAdmissionTtl());
            store.recordAdmissions(eventId, admitted, now);
            if (!admitted.isEmpty()) metrics.waitingRoomAdmissions(admitted.size());
            return admitted.size();
        } catch (DataAccessException exception) {
            metrics.waitingRoom("admit", "redis_error");
            throw new WaitingRoomUnavailableException();
        }
    }

    @Transactional
    public void open(Long eventId) {
        configure(eventId, new WaitingRoomPolicyCommand(
                true, properties.batchSize(), properties.activeCapacity(), properties.admissionTtl()));
    }

    @Transactional
    public void configure(Long eventId, WaitingRoomPolicyCommand command) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new EntityNotFoundException("이벤트를 찾을 수 없습니다: " + eventId));
        WaitingRoomPolicy policy = policyRepository.findByEventId(eventId)
                .orElseGet(() -> WaitingRoomPolicy.enabled(
                        event, command.batchSize(), command.activeCapacity(), command.admissionTtl()));
        policy.update(command.enabled(), command.batchSize(), command.activeCapacity(), command.admissionTtl());
        policyRepository.saveAndFlush(policy);
        synchronizeRuntime(policy);
    }

    @Transactional
    public void close(Long eventId) {
        WaitingRoomPolicy policy = policyRepository.findByEventId(eventId)
                .orElseThrow(() -> new EntityNotFoundException("대기열 정책을 찾을 수 없습니다: " + eventId));
        policy.disable();
        policyRepository.flush();
        try {
            store.close(eventId);
        } catch (DataAccessException exception) {
            metrics.waitingRoom("policy_sync", "redis_error");
        }
    }

    @Transactional(readOnly = true)
    public List<WaitingRoomSummary> summaries() {
        return policyQueryRepository.findAll().stream().map(this::summary).toList();
    }

    @Transactional(readOnly = true)
    public int reconcileRuntime() {
        int recovered = 0;
        for (WaitingRoomPolicy policy : policyRepository.findAllByEnabledTrueOrderByIdAsc()) {
            try {
                if (store.restoreMarkerIfMissing(policy.getEventId())) {
                    recovered++;
                    metrics.waitingRoom("policy_sync", "recovered");
                }
            } catch (DataAccessException exception) {
                metrics.waitingRoom("policy_sync", "redis_error");
                throw new WaitingRoomUnavailableException();
            }
        }
        return recovered;
    }

    private WaitingRoomPolicy enabledPolicy(Long eventId) {
        if (!properties.enabled()) return null;
        return policyRepository.findByEventId(eventId)
                .filter(WaitingRoomPolicy::isEnabled)
                .orElse(null);
    }

    private void synchronizeRuntime(WaitingRoomPolicy policy) {
        try {
            if (policy.isEnabled()) store.open(policy.getEventId());
            else store.close(policy.getEventId());
            metrics.waitingRoom("policy_sync", "synchronized");
        } catch (DataAccessException exception) {
            metrics.waitingRoom("policy_sync", "redis_error");
        }
    }

    private void ensureMarker(Long eventId) {
        if (store.restoreMarkerIfMissing(eventId)) {
            metrics.waitingRoom("policy_sync", "recovered_on_request");
        }
    }

    private WaitingRoomSummary summary(WaitingRoomPolicyView policy) {
        try {
            WaitingRoomRedisStats stats = store.stats(policy.eventId());
            String redisStatus = policy.enabled()
                    ? (stats.markerPresent() ? "SYNCHRONIZED" : "MISSING")
                    : (stats.markerPresent() ? "STALE" : "DISABLED");
            return new WaitingRoomSummary(
                    policy.eventId(), policy.eventTitle(), policy.enabled(),
                    stats.waitingCount(), stats.admittedCount(), policy.batchSize(),
                    policy.activeCapacity(), policy.admissionTtlSeconds(),
                    stats.admittedLastMinute(), redisStatus);
        } catch (DataAccessException exception) {
            return new WaitingRoomSummary(
                    policy.eventId(), policy.eventTitle(), policy.enabled(),
                    0, 0, policy.batchSize(), policy.activeCapacity(),
                    policy.admissionTtlSeconds(), 0, "UNAVAILABLE");
        }
    }

    private WaitingRoomEntry response(
            Long eventId,
            Long memberId,
            WaitingRoomPolicy policy,
            WaitingRoomRedisStore.EntryState state
    ) {
        if (state.code() == 0) throw new WaitingRoomUnavailableException();
        if (state.code() == 1) {
            long wait = Math.max(0, (state.position() - 1) / policy.getBatchSize()
                    * properties.estimatedServiceSeconds());
            return new WaitingRoomEntry(
                    eventId, WaitingRoomStatus.WAITING, state.position(), wait, null, null);
        }
        if (state.code() == 2) {
            Instant expiresAt = Instant.ofEpochMilli(state.activeUntilMillis() == 0
                    ? clock.instant().plus(policy.getAdmissionTtl()).toEpochMilli()
                    : state.activeUntilMillis());
            return new WaitingRoomEntry(
                    eventId, WaitingRoomStatus.ADMITTED, null, 0L,
                    tokenService.issue(eventId, memberId, state.generation(), expiresAt), expiresAt);
        }
        return new WaitingRoomEntry(
                eventId, WaitingRoomStatus.WAITING, null, null, null, null);
    }
}
