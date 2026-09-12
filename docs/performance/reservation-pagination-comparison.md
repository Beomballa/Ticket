# 예약 offset과 커서 페이지네이션 비교

## 재현 조건

- 측정일: 2026-09-12
- 환경: Apple Silicon 로컬, Java 21, PostgreSQL 16 Testcontainers
- 데이터: 동일 회원의 `CONFIRMED` 예약 50,000건
- 조회 위치: 49,001번째 행
- 페이지 크기: 20
- 워밍업: 전략별 10회
- 측정: 전략별 100회, 같은 프로세스에서 번갈아 실행
- 인덱스: `idx_reservations_member_created (member_id, created_at DESC, id DESC)`

측정값은 운영 용량 보장이 아니라 같은 환경에서 쿼리 형태의 상대 비용을 비교한 결과다.

## 결과

| 방식 | p50 | p95 | p99 | 페이지 SQL |
|---|---:|---:|---:|---:|
| offset 49,000 | 3.067ms | 3.388ms | 4.586ms | 1회 |
| 행 값 커서 | 0.213ms | 0.295ms | 0.376ms | 1회 |

- p50: 약 93.1% 감소, 14.4배 빠름
- p95: 약 91.3% 감소, 11.5배 빠름
- p99: 약 91.8% 감소, 12.2배 빠름

## 실행 계획 차이

offset은 인덱스를 사용하더라도 앞선 약 49,000개 인덱스 항목을 읽은 뒤 버린다. 커서는 다음
행 값 조건이 인덱스 시작점에 포함된다.

```sql
AND (created_at, id) < (?, ?)
ORDER BY created_at DESC, id DESC
LIMIT 20
```

커서 계획은 `Index Cond`에 `ROW(created_at, id) < ROW(...)`가 표시됐고, 측정 실행에서는 shared
buffer hit 4, execution time 0.018ms였다. 애플리케이션 왕복과 JDBC 매핑을 포함한 100회 분포는
위 표의 수치다.

## 실패한 1차 실험

논리적으로 같은 OR 조건은 이 데이터와 계획에서 인덱스 seek가 되지 않았다.

| 방식 | p50 | p95 | p99 |
|---|---:|---:|---:|
| offset 49,000 | 3.258ms | 3.427ms | 3.785ms |
| OR 커서 조건 | 3.276ms | 3.423ms | 3.801ms |

성능 개선은 ‘커서를 도입했다’는 이름이 아니라 실제 SQL 형태와 실행 계획에서 나왔다. 이 실패
기록은 QueryDSL 조건이 의미상 같더라도 PostgreSQL 옵티마이저가 동일한 접근 경로를 선택한다고
가정하면 안 된다는 근거다.

## 재현

```bash
./gradlew clean test --tests '*comparesDeepOffsetAndCursorPaginationOnSameDataset' --info
./gradlew test --tests '*adminReservationCursorPaginationHasNoDuplicatesAndUsesOneQueryPerPage'
```

첫 테스트는 데이터 생성, 워밍업, p50·p95·p99 측정과 두 `EXPLAIN (ANALYZE, BUFFERS)` 검증을
수행한다. 두 번째 테스트는 두 페이지의 중복·누락, 잘못된 커서 응답과 페이지당 SQL 1회를
검증한다.
