# 로컬 시연 가이드

## 1. API와 데이터 저장소 실행

```bash
./gradlew bootRun
```

애플리케이션이 PostgreSQL과 Redis를 시작하고 Flyway migration을 적용한다. 다른 터미널에서 재실행 가능한 데모 데이터를 넣는다.

```bash
docker compose exec -T postgres psql -U fan_event -d fan_event < scripts/demo-data.sql
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
4. `내 예약`에서 최신순 목록과 공연·회차·가격 스냅샷 상세를 확인하고 예약을 취소한다.
5. 관리자 계정으로 로그인해 운영 콘솔의 집계·최근 예약·재고 반환을 확인한다.
6. `X-Request-Id`가 포함된 실패 응답은 애플리케이션 로그와 Grafana에서 같은 ID로 추적한다.
7. Swagger UI에서 같은 흐름의 API 계약과 JWT 적용 범위를 확인한다.

## 4. 검증과 관측 환경

```bash
./gradlew clean test
cd frontend && npm run check
docker compose -f compose.observability.yml up -d
```

Prometheus는 `http://localhost:9090`, Grafana는 `http://localhost:3000`에서 확인한다.

## 주의

`scripts/demo-data.sql`은 로컬 빈 데이터베이스 시연용이다. 고정 관리자 비밀번호와 `localStorage` 토큰은 운영에 사용하지 않는다. 실제 배포는 secret manager의 JWT 키, 내부망으로 제한한 metric endpoint, 비활성화하거나 내부망으로 제한한 Swagger UI, HttpOnly Secure cookie 또는 BFF를 사용해야 한다.
