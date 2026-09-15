# ADR-0023: Redis 대기열과 일회성 입장 토큰

## 상태

Accepted — 2026-09-15

## 배경

회원별 속도 제한만으로는 인기 이벤트 오픈 순간의 요청을 도착 순서대로 평탄화할 수 없다. 모든 요청이 재고 트랜잭션과 DB 커넥션까지 도달하면 재고 정합성이 유지되더라도 서비스 전체의 응답 시간이 급격히 나빠질 수 있다.

## 결정

운영자가 대기열을 연 이벤트만 Redis 대기열로 보호한다. 참가 순서는 클라이언트 시각이 아니라 Redis `INCR`로 만든 단조 증가 sequence를 Sorted Set score로 사용한다. 같은 회원이 다시 참가하면 기존 score를 그대로 반환한다.

입장 작업자는 Lua script 한 번에서 만료된 활성 입장을 제거하고, `활성 정원 - 현재 활성 수`와 배치 크기 중 작은 값만큼 `ZPOPMIN`한다. 따라서 여러 애플리케이션 인스턴스가 같은 이벤트를 동시에 처리해도 중복 입장과 정원 초과가 발생하지 않는다.

입장된 회원은 이벤트 ID, 회원 ID, 입장 generation, 만료 시각을 담은 HMAC-SHA256 토큰을 받는다. 예약 API는 서명과 scope, 만료를 확인한 뒤 Redis Lua로 `generation|Idempotency-Key`를 원자적으로 claim한다. 같은 멱등키의 재시도는 허용하지만 다른 멱등키 재사용은 거부한다. DB 예약 트랜잭션이 커밋된 뒤에만 활성 입장을 제거하며, 프로세스 중단 시에는 TTL 정리가 슬롯을 회수한다.

## 장애 정책

대기열 순서와 토큰 claim은 보안·공정성 경계이므로 Redis 상태를 확인하지 못하면 `503 WAITING_ROOM_UNAVAILABLE`로 fail-closed한다. 공개 이벤트 캐시는 기존처럼 DB로 우회하고 회원별 속도 제한은 fail-open이므로 장애 정책의 목적이 서로 다르다.

## 운영 지표

관리자 API `GET /api/admin/waiting-rooms`는 이벤트별 대기 수, 활성 입장 수, 활성 정원과 최근 1분 입장 처리량을 반환한다. `fan.event.waiting.room`과 `fan.event.waiting.room.admitted` 메트릭으로 참가·토큰·입장 결과를 관찰한다.

## 결과

PostgreSQL 예약·재고 트랜잭션은 변경하지 않고 그 앞단의 유입량만 제어한다. 이벤트별 대기열 활성화는 `POST /api/admin/events/{eventId}/waiting-room`, 종료는 동일 경로의 `DELETE`를 사용한다. 종료는 해당 이벤트의 대기·입장 상태를 제거하므로 판매 종료 또는 비상 우회 여부를 판단해 실행한다.
