# Fan Event Platform

공연·팬 이벤트 예약과 한정 재고의 동시성, 멱등성, 만료 및 재처리를 검증하는 백엔드 중심 포트폴리오 프로젝트다.

## 기술 기준선

- Java 21, Spring Boot 3.5
- Spring Data JPA, QueryDSL 5.1
- PostgreSQL, Redis, Flyway
- Testcontainers, JUnit 5
- Actuator, Micrometer
- Gradle 8.14 Wrapper
- React 19, TypeScript 7, Vite 8

JPA는 애그리거트 저장과 상태 변경에 사용하고, QueryDSL은 동적 목록·관리자 조회·집계에 사용한다. Native SQL은 실행 계획과 측정 결과로 필요성이 확인된 경우에만 ADR을 남기고 도입한다.

## 로컬 실행

필수 도구는 JDK 21과 Docker다. 별도 Gradle 설치는 필요하지 않다.

```bash
./gradlew bootRun
```

Spring Boot Docker Compose 연동이 `compose.yml`의 PostgreSQL과 Redis를 시작한다. 직접 인프라를 제어하려면 다음처럼 실행할 수 있다.

```bash
docker compose up -d
./gradlew bootRun
```

기본 health endpoint는 `http://localhost:8080/actuator/health`다.

### 3분 로컬 데모

API를 실행한 뒤 다른 터미널에서 재실행 가능한 데모 데이터를 넣고 React UI를 시작한다.

```bash
docker compose exec -T postgres psql -U fan_event -d fan_event < scripts/demo-data.sql
cd frontend
npm install
npm run dev
```

`http://localhost:5173`에서 이벤트 예약 흐름을 확인할 수 있다. 화면에서 일반 사용자로 가입할 수 있으며,
운영 콘솔은 로컬 전용 `admin@stagepass.local` / `DemoPass123!` 계정을 사용한다. 상세 순서는
[로컬 시연 가이드](docs/demo-guide.md)에 정리했다. 고정 데모 계정과 브라우저 토큰 저장 방식은
운영 환경에 사용하지 않는다.

관측 환경은 애플리케이션 실행 후 별도 Compose로 시작한다.

```bash
docker compose -f compose.observability.yml up -d
```

Prometheus는 `http://localhost:9090`, 사전 구성된 Grafana 대시보드는
`http://localhost:3000`에서 확인한다. 로컬 Grafana 기본 계정은 `admin` / `admin`이다.
모든 HTTP 응답의 `X-Request-Id`와 오류 본문의 `traceId`로 요청을 추적할 수 있다.

운영 환경에서는 반드시 32바이트 이상의 별도 JWT 서명을 설정한다.

```bash
export JWT_SECRET='replace-with-a-production-secret-at-least-32-bytes'
```

인증 API는 `POST /api/auth/signup`, `POST /api/auth/login`이며 로그인 응답의 Access Token을
`Authorization: Bearer <token>`으로 전달한다. `GET /api/members/me`는 현재 로그인 회원을 확인하고,
`/api/admin/**`는 `ADMIN` 역할만 접근할 수 있다.

`POST /api/reservations`는 인증된 회원의 재고를 선점하고 10분 동안 유효한 `PENDING` 예약을 만든다.
요청한 모든 재고 차감과 예약·가격 스냅샷 저장은 하나의 트랜잭션으로 처리되며, 항목 하나라도
재고가 부족하면 전체 요청이 롤백된다. 고경합 재고 차감은
`available_quantity >= 요청 수량` 조건을 포함한 단일 UPDATE로 처리해 음수 재고와 읽기-쓰기
경합을 방지한다.

예약 확정은 `POST /api/reservations/{id}/confirm`, 취소는
`POST /api/reservations/{id}/cancel`을 사용한다. MVP 모의 결제의 승인 토큰은
`mock-approved`이며 다른 토큰은 `PAYMENT_DECLINED`로 처리된다. `PENDING` 취소는 재고만 반환하고,
`CONFIRMED` 취소는 모의 환불 후 재고를 반환한다. 동일 명령을 반복해도 결제·환불·재고 반환을
다시 실행하지 않는다.

확정되지 않은 `PENDING` 예약은 만료 시각 이후 배치 작업이 `EXPIRED`로 전환하고 재고를
반환한다. 만료 대상은 `FOR UPDATE SKIP LOCKED`로 최대 50건씩 선점하며 상태 변경과 재고 반환을
같은 트랜잭션에서 처리한다. 여러 애플리케이션 인스턴스가 동시에 실행하거나 작업이 롤백된 뒤
재실행되어도 같은 예약의 재고는 한 번만 반환된다.

예약 확정·취소·만료 이벤트는 상태 변경과 같은 트랜잭션에서 Outbox에 저장한다. 폴링
퍼블리셔는 최대 20건을 `SKIP LOCKED`로 선점하고 30초 처리 임대를 사용한다. 실패 이벤트는
최대 5회까지 지수 백오프로 재시도하며, 소비자명·이벤트 ID 처리 이력으로 재전달 시 DB 부작용을
한 번만 적용한다. 현재 테스트 소비자는 예약 이벤트를 감사 로그로 기록한다.

예약 생성과 확정 요청에는 `Idempotency-Key` 헤더가 필수다. 회원·요청 범위·키 조합을 24시간
유지하며, 같은 키와 같은 요청은 최초 성공 응답을 그대로 재생한다. 같은 키를 다른 요청 본문에
재사용하면 `409 IDEMPOTENCY_KEY_REUSED`를 반환한다. 실패한 비즈니스 요청은 키까지 함께
롤백되므로 원인을 해결한 뒤 같은 키로 재시도할 수 있다.

관리자는 `GET /api/admin/reservations`에서 상태·회원·이벤트·생성 기간으로 예약을 조회하고,
`GET /api/admin/inventory`에서 이벤트·회차·유형·잔여 수량·품절 여부로 재고를 조회한다.
두 목록은 QueryDSL DTO Projection을 사용하며 `page`, `size`를 지원한다. 예약은
`created_at DESC, id DESC`, 재고는 공연 시각과 각 ID의 오름차순으로 정렬 순서를 고정한다.
`GET /api/admin/reservations/summary`는 상태별 건수와 확정 매출을 한 번의 집계 쿼리로 반환한다.
깊은 목록 이동에는 `GET /api/admin/reservations/cursor`를 사용한다. 첫 요청은 `size`만 전달하고,
응답의 `nextCursor`를 다음 요청의 `cursor`로 전달한다. 커서는 `(created_at, id)` 경계를 담은
불투명 문자열이며 전체 건수 count 없이 페이지당 SQL 1회만 실행한다.

공개 이벤트 상세는 Redis Cache-Aside로 5분간 저장한다. 이벤트·아티스트·회차·재고 변경은 DB
커밋 이후 관련 키를 무효화한다. Redis 조회·저장 장애는 DB 원본 응답으로 우회한다. 예약 생성은
회원별 1분 20회 fixed-window 제한을 적용하며 초과 시 `429 RESERVATION_RATE_LIMITED`와
`Retry-After`를 반환한다. Redis 장애 때 속도 제한은 fail-open으로 동작해 예약 원본 기능을
유지한다.

## 검증

```bash
./gradlew clean test
cd frontend && npm run check
```

백엔드 통합 테스트는 Testcontainers로 PostgreSQL·Redis를 시작하고 Flyway 마이그레이션, JPA 초기화와
QueryDSL 설정을 함께 검증한다. 프론트 검증은 상태·오류 표현 단위 테스트, TypeScript 검사와 production
bundle 생성을 실행한다.

재고 잠금 전략 비교 실험만 다시 실행하려면 다음 명령을 사용한다.

```bash
./gradlew test --tests '*comparesStockConcurrencyStrategiesWithoutOverselling' --info
```

## 문서

- [MVP 데이터 모델](docs/architecture/erd.md)
- [ADR-0001: 모듈형 모놀리스](docs/adr/0001-modular-monolith.md)
- [ADR-0002: 이벤트 판매 상태 전이](docs/adr/0002-event-lifecycle.md)
- [ADR-0003: JWT 기반 인증과 역할 인가](docs/adr/0003-jwt-authentication.md)
- [ADR-0004: 예약 선점 트랜잭션 기준선](docs/adr/0004-reservation-hold-transaction.md)
- [ADR-0005: 예약 확정·취소와 모의 결제 경계](docs/adr/0005-reservation-payment-boundary.md)
- [ADR-0006: 예약 생성·확정 멱등키](docs/adr/0006-reservation-idempotency.md)
- [ADR-0007: 고경합 재고 차감 전략](docs/adr/0007-high-contention-inventory-decrement.md)
- [ADR-0008: 예약 만료 선점과 재고 반환](docs/adr/0008-reservation-expiration.md)
- [ADR-0009: Transactional Outbox와 멱등 소비](docs/adr/0009-transactional-outbox.md)
- [ADR-0010: 관리자 예약·재고 QueryDSL 조회](docs/adr/0010-admin-querydsl-read-model.md)
- [ADR-0011: 예약 커서 페이지네이션](docs/adr/0011-reservation-cursor-pagination.md)
- [ADR-0012: Redis Cache-Aside와 예약 속도 제한](docs/adr/0012-redis-cache-rate-limit.md)
- [ADR-0013: 백엔드 시연을 위한 최소 React 운영 UI](docs/adr/0013-minimal-react-operations-ui.md)
- [이벤트 목록 조회 기준선](docs/performance/event-list-baseline.md)
- [관리자 조회 실행 계획](docs/performance/admin-query-plan.md)
- [offset과 커서 페이지네이션 비교](docs/performance/reservation-pagination-comparison.md)
- [재고 잠금 전략 비교 실험](docs/performance/stock-lock-strategy-comparison.md)
- [장애 복구 런북](docs/runbooks/incident-response.md)
- [로컬 시연 가이드](docs/demo-guide.md)
- [백엔드 포트폴리오·면접 요약](docs/portfolio/backend-portfolio.md)

세부 요구사항과 단계별 작업 현황은 Notion 프로젝트 문서에서 관리한다.
