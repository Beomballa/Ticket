# 이벤트 목록 조회 기준선

## 조회 목적

공개 상태인 이벤트를 키워드, 아티스트, 유형과 판매 기간으로 필터링한다. 결과는 `sales_start_at DESC, id DESC`로 고정 정렬한다.

## JPA와 QueryDSL 역할

- 이벤트 생성과 상태 규칙은 JPA 엔티티와 애플리케이션 트랜잭션이 담당한다.
- 목록은 QueryDSL DTO Projection으로 필요한 열만 조회한다.
- 연관 엔티티 그래프를 로딩하지 않고 아티스트를 명시적으로 조인한다.
- 현재 페이지 응답은 목록 쿼리 1회와 count 쿼리 1회, 총 2개의 SQL을 실행한다. 통합 테스트가 이 수를 고정한다.
- 상세 응답은 이벤트 헤더 1회와 회차·재고 평면 조회 1회, 총 2개의 SQL을 실행한다.
- 회차·재고 결과는 애플리케이션에서 계층 구조로 조립하므로 컬렉션 fetch join 페이지네이션과 N+1을 피한다.

## 초기 인덱스

```sql
CREATE INDEX idx_events_public_list
    ON events (status, sales_start_at DESC, id DESC);

CREATE INDEX idx_events_artist
    ON events (artist_id, id DESC);
```

## 최초 실행 계획

- 측정일: 2026-09-09
- 환경: PostgreSQL 16 Testcontainers, 이벤트 2건, 아티스트 1건
- 조건: 공개 상태 + `%tour%` 키워드 + 판매 시작일·ID 역순 + 20건 제한
- 계획: `Limit → Sort → Nested Loop → Seq Scan(events) + Index Scan(artists_pkey)`
- 정렬: quicksort, 25kB
- shared buffer hit: 3
- planning time: 0.122ms
- execution time: 0.063ms

작은 데이터셋에서는 이벤트 테이블 순차 스캔이 인덱스 접근보다 저렴하므로 정상적인 선택이다. 통합 테스트는 PostgreSQL에서 `ANALYZE, BUFFERS` 실행이 가능하고 planning·execution time 및 buffer 정보가 반환되는지 검증한다. 이 수치는 성능 성과가 아니라 이후 대량 데이터 비교를 위한 최초 기준선이다.

초기 데이터가 적을 때 PostgreSQL이 순차 스캔을 선택하는 것은 정상이다. 대량 테스트 데이터를 준비한 뒤 `EXPLAIN (ANALYZE, BUFFERS)`로 다음을 비교한다.

- 상태 필터와 정렬의 인덱스 사용 여부
- `%keyword%` 검색 비용과 `pg_trgm` 도입 필요성
- offset 증가에 따른 지연과 keyset pagination 전환 지점
- count 쿼리 비용과 별도 집계 전략 필요성

수치가 없는 상태에서 추가 인덱스나 Native Query를 도입하지 않는다.
