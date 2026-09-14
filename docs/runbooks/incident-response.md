# 장애 복구 런북

## Git 비밀정보 탐지

GitHub Actions의 `Secret scan`이 실패하면 탐지된 값을 이슈·채팅·로그에 복사하지 않는다.

1. 탐지 종류와 영향받은 저장소·커밋·파일 경로만 안전한 채널에 기록한다.
2. 실제 자격증명이거나 가능성을 배제할 수 없으면 해당 제공자에서 즉시 폐기하고 새 값으로 교체한다.
3. 애플리케이션·CI·배포 환경이 새 자격증명을 사용하도록 갱신하고 이전 값이 거부되는지 확인한다.
4. 현재 브랜치에서 값을 제거한다. 이미 원격 이력에 포함됐다면 영향받는 협업자와 배포 경로를 확인한
   뒤 이력 재작성 범위를 합의한다. 값 삭제만으로는 노출 대응이 끝나지 않는다.
5. 전체 이력 Gitleaks 검사와 기존 백엔드·프론트 품질 게이트를 다시 통과시킨다.

테스트 fixture나 명백한 공개 식별자가 오탐인 경우에도 광범위한 정규식·커밋 allowlist는 만들지 않는다.
최소 경로 또는 정확한 fingerprint만 근거와 만료 조건을 문서화해 예외 처리한다.

## 공통 확인

1. Grafana `Fan Event Platform Overview`에서 p95, 5xx, cache 결과, rate limit, Outbox 적체를 확인한다.
2. 응답의 `X-Request-Id` 또는 오류 본문의 `traceId`로 애플리케이션 로그를 검색한다.
3. 장애 시작 시각, 영향 API, 오류율, 최근 배포와 설정 변경을 기록한다.
4. 데이터 수정 전에 조회 결과와 실행 명령을 사건 기록에 남긴다.

## Redis 장애

### 징후

- `fan_event_cache_requests_total{result="error"}` 또는
  `fan_event_reservation_rate_limit_total{result="fail_open"}` 증가
- 상세 API DB 부하와 p95 상승

### 즉시 대응

1. Redis health와 연결 수, 메모리, eviction, 네트워크를 확인한다.
2. 공개 상세은 DB fallback, 예약 제한은 fail-open인지 샘플 요청으로 확인한다.
3. DB p95나 커넥션 풀이 위험하면 인입 계층에서 임시 요청 제한을 적용한다.
4. Redis 복구 후 cache hit가 회복되고 fail_open 증가가 멈췄는지 확인한다.

### 복구 판정

- 10분 동안 Redis 오류 증가 없음
- 상세 p95와 DB 커넥션 사용량이 정상 범위
- 예약 생성 성공률과 429 비율이 예상 범위

## Outbox 적체·소비 실패

### 징후

- `fan_event_outbox_backlog{status="pending|failed|processing"}` 지속 증가
- `fan_event_outbox_delivery_total{result="failed"}` 증가

### 즉시 대응

1. PENDING, FAILED, 임대가 만료된 PROCESSING 건수와 가장 오래된 `available_at`을 조회한다.
2. 로그에서 event ID와 event type, attempt, last_error를 확인한다.
3. 소비자 의존 시스템과 DB 상태를 복구한 뒤 폴러 한 인스턴스의 처리율을 관찰한다.
4. 최대 시도에 도달한 이벤트는 payload와 대상 상태를 검증하기 전 임의 재처리하지 않는다.
5. 원인을 해소한 뒤 관리자 API로 격리 이벤트를 확인하고 단건 재처리한다.

```http
GET /api/admin/outbox-events/exhausted?page=0&size=20
Authorization: Bearer <admin-token>

POST /api/admin/outbox-events/{eventId}/retry
Authorization: Bearer <admin-token>
```

성공 응답은 `202 Accepted`이며 이벤트를 기존 ID 그대로 `PENDING`에 되돌린다. `409 OUTBOX_RETRY_REJECTED`는 다른 운영자가 이미 재처리했거나 자동 재시도 대상인 이벤트라는 뜻이다. DB에서 status를 직접 수정하거나 실패 이벤트를 새 UUID로 복제하지 않는다.

### 복구 판정

- 적체가 감소하고 새 이벤트의 처리 지연이 정상화
- 같은 event ID의 감사 로그가 1건으로 유지
- `OUTBOX_MANUAL_RETRY` 감사 로그와 `fan_event_outbox_manual_retry_total`에 재처리 결과가 남음
- FAILED 원인과 재처리 여부가 사건 기록에 남음

## DB 지연·재고 충돌 급증

### 징후

- HTTP p95·5xx 및 Hikari active/pending 증가
- `fan_event_inventory_conflicts_total` 증가
- PostgreSQL lock wait, slow query 또는 CPU 상승

### 즉시 대응

1. 예약 생성, 만료, 관리자 조회 중 어느 경로가 지연을 주도하는지 URI별 p95로 분리한다.
2. `pg_stat_activity`, lock wait와 느린 SQL의 `EXPLAIN (ANALYZE, BUFFERS)`를 확보한다.
3. 예약 생성 과부하면 인입 제한을 강화하고 비핵심 관리자 조회를 일시 줄인다.
4. 교착 또는 장기 트랜잭션을 종료하기 전 대상 세션과 롤백 영향을 확인한다.

### 복구 판정

- Hikari pending 0과 DB lock wait 정상화
- 재고 수량이 0 이상이고 성공 예약 수와 차감량 일치
- p95·오류율이 정상 범위에서 10분 유지

## 결제 결과 불명

### 징후

- API가 `409 PAYMENT_RESULT_UNKNOWN`과 `paymentAttemptId`를 반환
- `fan_event_payment_reconciliation_total{result="unknown"}` 증가
- 관리자 결과 불명 목록에 오래된 시도가 누적

### 즉시 대응

1. 오류 응답의 `traceId`와 `paymentAttemptId`로 승인 요청 로그를 확인한다.
2. 관리자 API에서 결제 시도의 예약 ID, 금액, 요청 시각을 확인한다. 저장된 fingerprint를 결제 토큰처럼 사용하지 않는다.
3. PG 장애와 조회 API 상태를 확인한 뒤 단건 대사를 실행한다.

```http
GET /api/admin/payment-attempts/unknown?page=0&size=20
Authorization: Bearer <admin-token>

POST /api/admin/payment-attempts/{paymentAttemptId}/reconcile
Authorization: Bearer <admin-token>
```

4. `resolved=false`이면 PG 결과가 아직 없다는 뜻이므로 `UNKNOWN`을 유지한다. 승인 API를 새 키로 반복 호출하지 않는다.
5. 승인으로 확인됐지만 예약이 이미 만료·취소돼 자동 확정할 수 없으면 환불·재고 영향을 확인하고 수동 보상 사건으로 전환한다.

### 복구 판정

- 대상 결제 시도가 `APPROVED` 또는 `DECLINED`로 수렴
- 승인 건은 예약 `CONFIRMED`, `RESERVATION_CONFIRMED` Outbox 한 건과 감사 로그 한 건 유지
- 같은 `paymentAttemptId` 재대사에도 추가 승인·Outbox가 생성되지 않음

## 환불 결과 불명

### 징후

- API가 `409 REFUND_RESULT_UNKNOWN`과 `refundAttemptId`를 반환
- `fan_event_refund_reconciliation_total{result="unknown"}` 증가
- 관리자 환불 결과 불명 목록에 오래된 시도가 누적

### 즉시 대응

1. 오류 응답의 `traceId`와 `refundAttemptId`로 환불 요청 로그를 확인한다.
2. 관리자 API에서 예약 ID, 원 결제 시도 ID, 금액과 요청 시각을 확인한다.
3. PG 환불 조회 API가 정상인지 확인한 뒤 단건 대사를 실행한다.

```http
GET /api/admin/refund-attempts/unknown?page=0&size=20
Authorization: Bearer <admin-token>

POST /api/admin/refund-attempts/{refundAttemptId}/reconcile
Authorization: Bearer <admin-token>
```

4. `resolved=false`이면 PG 결과가 아직 없으므로 `UNKNOWN`을 유지한다. 새 키로 환불을 다시 호출하거나 DB에서 예약을 직접 취소하지 않는다.
5. 거절로 확인되면 예약 `CONFIRMED`와 선점 재고를 유지하고 고객 안내 또는 PG 거절 원인 해소 절차로 전환한다.

### 복구 판정

- 대상 환불 시도가 `SUCCEEDED` 또는 `DECLINED`로 수렴
- 성공 건은 예약 `CANCELLED`, 재고 반환, `RESERVATION_CANCELLED` Outbox와 감사 로그 한 건 유지
- 거절 건은 예약 `CONFIRMED`와 기존 재고 수량 유지
- 같은 `refundAttemptId` 재대사에도 추가 환불·재고 반환·Outbox가 생성되지 않음

## 초기 경보 기준

`observability/alerts/fan-event-alerts.yml`은 전체 API p95 500ms, 5xx 5%, Outbox active backlog 100건, Outbox 지속 실패, 예약 rate-limit 거부 20%를 초기 기준으로 사용한다. 경보가 발생하면 단일 순간값이 아니라 설정된 5~10분 지속 여부와 URI별 지표를 먼저 확인한다.

이 임계값은 로컬 k6 기준선에서 출발한 값이다. 운영 SLO와 실제 트래픽 분포를 확보한 뒤 경보 민감도와 지속 시간을 조정하고 변경 근거를 사건·용량 계획 문서에 남긴다.

## 로컬 관측 환경 실행

```bash
docker compose up -d
./gradlew bootRun
docker compose -f compose.observability.yml up -d
```

- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000` (`admin` / `admin`, 로컬 전용)
- 애플리케이션 메트릭: `http://localhost:8080/actuator/prometheus`

운영에서는 메트릭 endpoint를 공개 인터넷에 노출하지 않고 내부 네트워크·인증 정책으로 보호한다.
