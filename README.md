# Fan Event Platform

[![CI](https://github.com/Beomballa/Ticket/actions/workflows/ci.yml/badge.svg)](https://github.com/Beomballa/Ticket/actions/workflows/ci.yml)

공연·팬 이벤트 예약과 한정 재고의 동시성, 멱등성, 만료 및 재처리를 검증하는 백엔드 중심 포트폴리오 프로젝트다.

## 기술 기준선

- Java 21, Spring Boot 3.5
- Spring Data JPA, QueryDSL 5.1
- PostgreSQL, Redis, Flyway
- Testcontainers, JUnit 5
- Actuator, Micrometer
- springdoc OpenAPI, Swagger UI
- Gradle 8.14 Wrapper
- React 19, TypeScript 7, Vite 8
- GitHub Actions 백엔드·프론트·Gitleaks 품질 게이트

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

실행 중인 API 계약은 `http://localhost:8080/swagger-ui.html`에서 탐색한다. JSON 계약은 전체
`/v3/api-docs`와 대상별 `/v3/api-docs/public`, `/v3/api-docs/member`, `/v3/api-docs/admin`으로
제공한다. 회원·관리자 문서에는 JWT bearer 인증 요구 사항이 표시되며 로그인·이벤트 조회는
인증 없는 계약으로 유지된다. 각 엔드포인트에는 실제 예외 흐름에 맞는 400·401·403·404·409·422·429
응답과 `ApiError` 예시가 연결되며, 예약 속도 제한의 429 응답에는 `Retry-After` 헤더도 표시된다.
운영에서는 다음 설정으로 문서 노출을 끄거나 내부망으로 제한한다.

```bash
export SPRINGDOC_API_DOCS_ENABLED=false
export SPRINGDOC_SWAGGER_UI_ENABLED=false
```

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
export PG_WEBHOOK_SECRET='replace-with-a-random-webhook-signing-secret'
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
`CONFIRMED` 취소는 환불 성공이 확인된 뒤 재고를 반환한다. 동일 명령을 반복해도 결제·환불·재고
반환을 다시 실행하지 않는다.

결제 승인 전에 PG 멱등키와 토큰 fingerprint를 `payment_attempts` 원장에 기록하며 토큰 원문은
저장하지 않는다. 응답 유실을 재현하는 `mock-timeout-approved` 토큰은 PG에는 승인 결과를 남기고
API에는 `409 PAYMENT_RESULT_UNKNOWN`을 반환한다. 관리자는
`GET /api/admin/payment-attempts/unknown`에서 결과 불명 시도를 조회하고
`POST /api/admin/payment-attempts/{paymentAttemptId}/reconcile`로 PG 결과를 대사한다. 승인 결과는
예약 확정·Outbox·감사 로그와 함께 반영되며 같은 대사 요청을 반복해도 부작용은 한 번만 발생한다.

확정 예약의 취소 전에 승인 결제를 참조하는 `refund_attempts` 원장과 예약별 고정 PG 멱등키를
저장한다. `mock-refund-declined`, `mock-refund-timeout-succeeded`,
`mock-refund-timeout-declined` 결제 토큰으로 환불 거절과 응답 유실을 재현할 수 있다. 결과 불명은
`409 REFUND_RESULT_UNKNOWN`을 반환하며 예약과 재고를 유지한다. 관리자는
`GET /api/admin/refund-attempts/unknown`과
`POST /api/admin/refund-attempts/{refundAttemptId}/reconcile`로 결과를 대사한다. 성공이 확인된 경우에만
예약 취소·재고 반환·Outbox·감사 로그를 함께 반영한다.

결제·환불 `UNKNOWN` 원장은 기본 10초마다 최대 20건씩 자동 대사한다. 짧은 DB 트랜잭션이
`FOR UPDATE SKIP LOCKED`로 대상을 선점하고 30초 처리 임대를 남긴 뒤 선점 트랜잭션을 종료하므로,
PG 조회 중에는 선점 잠금을 점유하지 않는다. 아직 결과가 없거나 조회가 실패하면 10초부터 최대
10분까지 지수 백오프로 재예약한다.
프로세스가 중단돼도 임대 만료 뒤 다른 인스턴스가 이어받으며, 수동 관리자 API는 비상 복구 경로로
계속 사용할 수 있다. 간격과 배치 크기는 `app.payment.reconciliation.*`에서 조정한다.
관리자 운영 콘솔은 결제·환불별 결과 불명 건수, 자동 시도 횟수, 다음 실행 시각과 현재 임대 상태를
표시하며 긴급 건은 화면의 `지금 대사` 버튼으로 기존 관리자 대사 API를 실행할 수 있다.

모의 PG의 확정 결과는 `POST /api/payment/webhooks/mock`으로도 수신한다. `X-PG-Timestamp`와 원문
body를 `timestamp.body`로 결합한 HMAC-SHA256 값을 `X-PG-Signature: v1=<hex>`로 전달하고,
`X-PG-Event-Id`는 이벤트마다 고유해야 한다. 서명과 5분 허용 시차를 통과한 본문만 PostgreSQL
Inbox에 저장한다. 같은 event ID·같은 본문 재전달은 기존 결과를 반환하고 다른 본문은 충돌로
거부한다. 처리 임대와 시도 번호가 프로세스 중단 및 늦은 작업자 완료를 방지하며 결제·환불의 최종
반영은 기존 대사 트랜잭션을 공유한다. 관리자는 운영 콘솔 또는
`GET /api/admin/payment-webhooks`에서 상태·오류를 확인하고 `FAILED` 건을
`POST /api/admin/payment-webhooks/{eventId}/retry`로 재처리한다.

결제 결과가 승인으로 확인된 시점에 예약이 이미 `EXPIRED`라면 예약을 되살리거나 재고를 다시
차감하지 않는다. 결제 승인 원장과 `LATE_PAYMENT_COMPENSATION` 환불 시도를 한 트랜잭션으로
기록하고, 예약별 고정 PG 멱등키로 자동 전액 환불한다. 즉시 응답 유실은 `UNKNOWN` 환불 대사와
지수 백오프로 이어진다. 관리자는 운영 콘솔 또는 `GET /api/admin/payment-compensations`에서
보상 상태·시도·오류를 확인하고 `POST /api/admin/payment-compensations/{attemptId}/retry`로
긴급 재처리할 수 있다. 보상 성공은 이미 반환된 재고와 만료 예약 상태를 변경하지 않는다.

인기 이벤트는 운영자가 이벤트별 Redis 대기열을 열어 예약 트래픽을 평탄화할 수 있다. 참가 순서는
Redis 단조 증가 sequence로 정하고 재참가에도 최초 순서를 유지한다. Lua script가 배치 크기와 활성 정원을
동시에 확인해 여러 서버의 입장 작업이 겹쳐도 정원을 넘지 않는다. 입장 회원에게는 이벤트·회원·만료가
서명된 2분 토큰을 발급하며 예약 생성의 `X-Admission-Token`에서 검증한다. 토큰은 한 멱등키에만 claim되어
동일 요청 재시도는 허용하고 다른 예약으로의 재사용은 거부한다. 운영 콘솔은 대기·활성 입장·최근 1분
처리량을 표시하며 Redis 장애 시 보호 이벤트 예약은 `503 WAITING_ROOM_UNAVAILABLE`로 닫힌다.

인증 사용자는 `GET /api/reservations`에서 상태·생성 기간별로 자신의 예약만 조회하고,
`GET /api/reservations/{id}`에서 공연·회차·재고·수량과 요청 당시 가격 스냅샷을 확인한다.
목록은 QueryDSL 집계 Projection으로 본문과 count를 각각 한 번 실행하고, 상세는 소유권을 포함한
헤더와 항목 쿼리 총 2회로 조립한다. 존재하지 않거나 다른 회원의 예약은 동일한 404로 응답한다.

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
커밋 이후 관련 키를 무효화한다. 예약 선점·취소·만료로 잔여 재고가 바뀔 때도 커밋 후 상세 키를
삭제한다. Redis 조회·저장 장애는 DB 원본 응답으로 우회한다. 예약 생성은
회원별 1분 20회 fixed-window 제한을 적용하며 초과 시 `429 RESERVATION_RATE_LIMITED`와
`Retry-After`를 반환한다. Redis 장애 때 속도 제한은 fail-open으로 동작해 예약 원본 기능을
유지한다.

최대 자동 시도 횟수에 도달한 Outbox 실패 이벤트는 관리자가
`GET /api/admin/outbox-events/exhausted`에서 확인하고
`POST /api/admin/outbox-events/{eventId}/retry`로 다시 `PENDING`에 투입한다. 전체 운영 조회는
`GET /api/admin/outbox-events`의 상태·이벤트 유형·aggregate ID·최소 시도 횟수·생성 기간 필터를
사용한다. 수동 재처리는 원자적 조건 UPDATE로 같은 이벤트의 동시 요청 중 하나만 허용하며
감사 로그와 `fan.event.outbox.manual.retry` 메트릭을 남긴다.

## 검증

```bash
./gradlew clean test
(cd frontend && npm run check)
docker run --rm -v "$PWD:/repo" \
  ghcr.io/gitleaks/gitleaks:v8.30.1@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f \
  git --redact --no-banner --exit-code 1 /repo
```

백엔드 통합 테스트는 Testcontainers로 PostgreSQL·Redis를 시작하고 Flyway 마이그레이션, JPA 초기화와
QueryDSL 설정을 함께 검증한다. 프론트 검증은 상태·오류 표현 단위 테스트, TypeScript 검사와 production
bundle 생성을 실행한다. Gitleaks는 현재 파일만이 아니라 전체 Git 이력을 검사하며 탐지 값은 로그에서
마스킹한다. 탐지되면 값을 출력하거나 단순 allowlist 처리하지 않고 먼저 자격증명을 폐기·교체한 뒤
[장애 복구 런북](docs/runbooks/incident-response.md)의 절차를 따른다.

재고 잠금 전략 비교 실험만 다시 실행하려면 다음 명령을 사용한다.

```bash
./gradlew test --tests '*comparesStockConcurrencyStrategiesWithoutOverselling' --info
```

실행 중인 로컬 API에 대해 공개 상세와 예약 경합 부하 기준선을 재현하려면 Docker만으로 k6를 실행한다.
전용 `StagePass Load Test` 이벤트 데이터만 초기화하며, 기본값은 사용자 20명·예약 시도 200회·재고
100개다.

```bash
./scripts/run-load-test.sh
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
- [ADR-0014: 최대 시도 Outbox 이벤트의 수동 재처리](docs/adr/0014-outbox-manual-retry.md)
- [ADR-0015: 사용자 내 예약 QueryDSL 읽기 모델](docs/adr/0015-member-reservation-query-model.md)
- [ADR-0016: 런타임 OpenAPI 계약과 대상별 문서 그룹](docs/adr/0016-runtime-openapi-contract.md)
- [ADR-0017: 전체 Git 이력 비밀정보 검사](docs/adr/0017-git-secret-scanning.md)
- [ADR-0018: 결제 시도 원장과 결과 불명 대사](docs/adr/0018-payment-attempt-reconciliation.md)
- [ADR-0019: 환불 시도 원장과 결과 불명 대사](docs/adr/0019-refund-attempt-reconciliation.md)
- [ADR-0020: 결과 불명 자동 대사 임대와 백오프](docs/adr/0020-automatic-reconciliation.md)
- [ADR-0021: 서명 검증 PG 웹훅 Inbox와 멱등 처리](docs/adr/0021-signed-payment-webhook-inbox.md)
- [ADR-0022: 예약 만료 후 늦은 승인 보상 환불 Saga](docs/adr/0022-late-payment-compensation-saga.md)
- [ADR-0023: Redis 대기열과 일회성 입장 토큰](docs/adr/0023-redis-waiting-room-admission-token.md)
- [이벤트 목록 조회 기준선](docs/performance/event-list-baseline.md)
- [관리자 조회 실행 계획](docs/performance/admin-query-plan.md)
- [offset과 커서 페이지네이션 비교](docs/performance/reservation-pagination-comparison.md)
- [재고 잠금 전략 비교 실험](docs/performance/stock-lock-strategy-comparison.md)
- [k6 공개 조회·예약 경합 부하 기준선](docs/performance/k6-load-baseline.md)
- [장애 복구 런북](docs/runbooks/incident-response.md)
- [로컬 시연 가이드](docs/demo-guide.md)
- [백엔드 포트폴리오·면접 요약](docs/portfolio/backend-portfolio.md)

세부 요구사항과 단계별 작업 현황은 Notion 프로젝트 문서에서 관리한다.
