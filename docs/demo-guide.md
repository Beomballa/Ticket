# 로컬 시연 가이드

## 1. API와 데이터 저장소 실행

```bash
./gradlew bootRun
```

애플리케이션이 PostgreSQL과 Redis를 시작하고 Flyway migration을 적용한다. 다른 터미널에서 재실행 가능한 데모 데이터를 넣는다.

```bash
docker compose exec -T postgres psql -U fan_event -d fan_event < scripts/demo-data.sql
docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U fan_event -d fan_event < scripts/showcase-data.sql
```

`http://localhost:8080/swagger-ui.html`에서 공개·회원·관리자 API 그룹과 요청·응답 스키마를 확인한다. 운영 콘솔에서는 PG 웹훅 Inbox와 늦은 승인 보상 환불의 최근 상태·오류·재처리 버튼도 확인할 수 있다.
Authorize에 로그인 응답의 JWT를 넣으면 보호 API를 문서에서 직접 호출할 수 있다. 실제 토큰이나
운영 계정 정보는 화면 캡처·문서·Git에 저장하지 않는다.

## 2. React UI 실행

```bash
cd frontend
npm install
npm run dev
```

브라우저에서 `http://localhost:5173`을 연다. 일반 사용자는 화면에서 새로 가입할 수 있다. 관리자 운영 콘솔은 다음 로컬 전용 계정을 사용한다.

- 이메일: `admin@stagepass.local`
- 비밀번호: `DemoPass123!`

## 3. 핵심 흐름

1. 이벤트 카드에서 회차와 잔여 재고를 연다.
2. 일반 사용자로 로그인하고 1매 예약을 눌러 10분 선점을 만든다.
3. `mock-approved`를 내부에서 사용하는 모의 결제 확정 버튼을 누른다.
   공연 창을 먼저 닫았다면 `/reservations`의 결제 대기 예약을 열고 **모의 결제 이어하기**를 누른다. 새로고침해도 내 예약 화면을 유지하며, 남은 시간은 최초 선점의 서버 만료 시각 기준이다.
4. `내 예약`에서 최신순 목록과 공연·회차·가격 스냅샷 상세를 확인하고 예약을 취소한다.
5. 관리자 계정으로 로그인해 운영 콘솔의 집계·최근 예약·재고 반환을 확인한다.
6. `X-Request-Id`가 포함된 실패 응답은 애플리케이션 로그와 Grafana에서 같은 ID로 추적한다.
7. Swagger UI에서 같은 흐름의 API 계약과 JWT 적용 범위를 확인한다.

결제 시간이 지나면 결제 버튼이 비활성화된다. **최신 상태 확인**으로 서버의 확정·취소·만료 상태를 다시 읽을 수 있다. 브라우저 시각은 안내용이며 최종 결제 가능 여부와 재고 반환은 서버에서 결정한다. 같은 상세 창에서 결제를 재시도할 때는 동일한 멱등 키를 유지한다. 상태 조회 실패를 결제 실패로 오인하지 않도록, 결제 성공 응답의 상태는 목록 재조회와 별도로 즉시 반영한다.

## 공연 탐색용 데모 데이터

`showcase-data.sql`은 가상 공연 6개, 회차 12개, 입장권 종류 24개를 추가한다. 재즈·인디 콘서트·팬미팅·공개방송의 판매 중 공연 4개와 오픈 예정 공연 2개로 구성한다. 날짜는 최초 실행 시점을 기준으로 생성한다. 재실행은 이미 있는 공연을 건너뛰므로 소비한 재고, 예약, 기존 일정은 유지된다. 데이터는 운영용 Flyway migration에 포함하지 않는다.

포스터 4종은 `frontend/public/images/events/`에 함께 버전 관리한다. 별도 이미지 서버나 API 키가 필요하지 않다. 아티스트·공연장·행사는 모두 가상이며 화면의 DEMO 표시를 제거하지 않는다. 실제 공연사의 포스터나 NOL 로고를 사용하지 않는다.

검색은 공연 제목과 아티스트명을 대상으로 하며 장르 조건과 함께 적용된다. 목록은 12개 단위 페이지로 읽는다. `GET /api/events`의 기존 필드를 유지하고 nullable `overview`(공연 시작/종료일, 공연장, 최저 등록 가격)를 추가했다. 회차가 없으면 `overview`가 null이고, 재고가 없으면 `minPrice`가 null이다. 여러 공연장이 있으면 상세 확인을 안내한다. 최저 가격에는 매진 티켓도 포함될 수 있으므로 구매 가능 가격을 보장하지 않는다.

조회는 목록·전체 개수·현재 페이지의 회차/가격 집계로 구성한다. 이벤트별 상세 API를 반복 호출하지 않는다. 공개 상태(PUBLISHED/ON_SALE)만 조회하며 DRAFT는 노출하지 않는다. 오픈 예정 데이터는 일정이 되어도 자동 판매 전환되지 않으며 운영 API로 ON_SALE 전환이 필요하다.

IntelliJ에서 백엔드(8080)를 실행하고 프론트엔드(5173)를 따로 실행한 뒤 `http://localhost:5173/`을 연다. 8080 루트는 티켓팅 UI 주소가 아니다.

## 4. 검증과 관측 환경

```bash
./gradlew clean test
cd frontend && npm run check
docker compose -f compose.observability.yml up -d
```

Prometheus는 `http://localhost:9090`, Grafana는 `http://localhost:3000`에서 확인한다.

## 주의

`scripts/demo-data.sql`은 로컬 빈 데이터베이스 시연용이다. 고정 관리자 비밀번호와 `localStorage` 토큰은 운영에 사용하지 않는다. 실제 배포는 secret manager의 JWT 키, 내부망으로 제한한 metric endpoint, 비활성화하거나 내부망으로 제한한 Swagger UI, HttpOnly Secure cookie 또는 BFF를 사용해야 한다.
