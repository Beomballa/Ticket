# 관리자 예약·재고 조회 실행 계획

## 검증 대상

- API: `GET /api/admin/reservations`, `GET /api/admin/inventory`
- 환경: PostgreSQL 16 Testcontainers
- 데이터: 동일 회원 예약 10,000건과 예약 항목 10,000건
- 예약 상태 분포: `CONFIRMED` 9,990건, `PENDING` 10건
- 쿼리: `PENDING` 필터, 생성 시각·ID 역순, 20건 제한, 항목 수·수량 집계

## 적용 인덱스

```sql
CREATE INDEX idx_reservations_status_created
    ON reservations (status, created_at DESC, id DESC);

CREATE INDEX idx_inventory_availability
    ON sellable_inventory (available_quantity, event_session_id, id);

CREATE INDEX idx_reservation_items_inventory_reservation
    ON reservation_items (inventory_id, reservation_id);
```

## 결과

`EXPLAIN (ANALYZE, BUFFERS)`에서 희소한 `PENDING` 조건은
`idx_reservations_status_created`를 사용한다. 결과는 그룹 집계 뒤
`created_at DESC, id DESC` 순으로 제한된다. 테스트는 실행 계획에 인덱스명, 실제 실행 시간과
버퍼 정보가 모두 포함되는지 검증한다.

기능 통합 테스트에서 예약 목록과 재고 목록은 각각 SQL 2회로 고정된다.

1. DTO 본문 조회 1회
2. 페이지 전체 건수 조회 1회

데이터 건수만 증가할 때 추가 SQL이 발생하지 않으므로 N+1이 없다. 예약 정렬은 동일 회원의 연속
예약 두 건을 조회해 최신 ID가 먼저 반환되는 것으로 검증한다.

## 재현

```bash
./gradlew clean test --tests '*adminReservationQueryPlanUsesStatusCreatedIndexOnLargeDataset'
./gradlew test --tests '*adminReservationAndInventorySearchUseStableProjectionQueries'
```

실행 계획은 통합 테스트가 `generate_series`로 1만 건을 준비하고 `ANALYZE` 이후 직접 검증하므로
개발자의 로컬 샘플 데이터에 의존하지 않는다.

## 해석과 후속 측정

현재 결과는 선택도가 높은 상태 필터의 인덱스 접근과 일정한 SQL 수를 입증한다. 모든 상태를
조회하거나 깊은 offset으로 이동하면 정렬·스캔 비용은 다시 커질 수 있다. 다음 커서
페이지네이션 작업에서 동일 데이터 규모와 요청 분포로 offset 대비 p50/p95/p99, 읽은 행 수와
버퍼 수를 비교한다.
