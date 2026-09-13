# 장애 복구 런북

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

### 복구 판정

- 적체가 감소하고 새 이벤트의 처리 지연이 정상화
- 같은 event ID의 감사 로그가 1건으로 유지
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
