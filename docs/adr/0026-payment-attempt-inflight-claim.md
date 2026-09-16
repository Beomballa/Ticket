# ADR-0026: 동시 결제 확정의 원자 원장 생성과 처리 유예시간

- 상태: 승인
- 결정일: 2026-09-16

## 맥락

ADR-0025에서 PG 호출과 DB 트랜잭션을 분리해 연결 풀 중첩 점유를 제거했다. 분리된 경계에서는
같은 예약의 확정 요청 여러 개가 결제 시도 원장을 조회한 뒤 동시에 INSERT할 수 있다. 기존
`saveAndFlush`는 gateway 멱등키 유니크 충돌을 예외로 노출하고, 먼저 생성된 `REQUESTED`를
다른 요청이 즉시 `UNKNOWN`으로 변경해 실제 처리 중인 작업과 응답 유실을 구분하지 못했다.

## 결정

- 결제 시도 원장은 `INSERT ... ON CONFLICT DO NOTHING`으로 생성한다.
- `REQUESTED`, `UNKNOWN` 상태는 예약별 최대 한 건만 존재하도록 partial unique index를 둔다.
- 삽입 충돌 시 gateway 키의 정확한 원장, 없으면 같은 예약의 미해결 원장을 다시 조회한다.
- `requested_at + processing-timeout`이 지나지 않은 `REQUESTED`는 상태를 바꾸지 않고
  `409 IDEMPOTENCY_REQUEST_IN_PROGRESS`로 응답한다.
- 처리 유예시간이 지난 `REQUESTED`만 `UNKNOWN`과 즉시 대사 대상으로 전환한다.
- 기본 처리 유예시간은 30초이며 `app.payment.authorization.processing-timeout`으로 설정한다.
- claim 결과는 `created`, `in_progress`, `recovered_unknown`, `reused` counter로 관측한다.

## 결과

동일 gateway 키와 동일 예약의 서로 다른 키가 동시에 들어와도 원장은 한 건만 생성된다. 진행
중인 첫 요청의 상태를 후속 요청이 변경하지 않으며, PG 승인과 예약·Outbox 부작용도 한 번만
실행된다. 프로세스가 중단된 `REQUESTED`는 유예시간 뒤 기존 UNKNOWN 자동 대사 경로로 수렴한다.

## 운영 고려사항

`recovered_unknown` 증가는 단순 사용자 재시도보다 PG 장기 지연, 프로세스 중단 또는 승인 결과
기록 실패 신호에 가깝다. 5분 동안 5건을 초과하면 경보를 발생시키고 PG 지연과 애플리케이션
재시작 이력을 함께 확인한다. partial unique index 마이그레이션이 실패하면 기존 데이터에 예약별
미해결 원장이 여러 건 존재한다는 뜻이므로 원장을 임의 삭제하지 않고 PG 결과를 대사한 뒤 재실행한다.
