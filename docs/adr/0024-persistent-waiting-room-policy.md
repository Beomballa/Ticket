# ADR-0024: 영속 대기열 정책과 Redis 런타임 복구

## 상태

Accepted — 2026-09-15

## 배경

대기열 활성 여부와 입장 용량을 Redis에만 저장하면 재시작이나 `FLUSHDB` 뒤 보호 대상 이벤트가
일반 예약 경로로 우회될 수 있다. 모든 이벤트에 같은 배치·정원·TTL을 적용하는 방식도 이벤트별
판매 규모를 반영하지 못하며, 운영 화면에서 설정값과 실제 Redis 상태의 불일치를 구분할 수 없다.

## 결정

PostgreSQL의 `event_waiting_room_policies`를 정책 원본으로 사용한다. 이벤트마다 활성 여부, 배치 크기,
활성 입장 정원과 입장 TTL을 저장하고 JPA 도메인이 범위와 `배치 크기 <= 활성 정원` 불변식을 검사한다.
관리자 목록은 QueryDSL projection으로 정책과 이벤트 제목을 조회한 뒤 Redis 통계와 결합한다.

예약 보호 여부는 DB 정책으로 판정한다. 활성 정책인데 Redis marker가 없다면 요청 경로와 주기 작업이
`SETNX`로 marker를 복구한다. 여러 인스턴스가 동시에 복구해도 한 인스턴스만 성공하며
`policy_sync=recovered` 또는 `recovered_on_request` 메트릭을 남긴다. Redis 접근 자체가 실패하면 보호
이벤트의 참가·입장·예약은 기존대로 fail-closed한다.

운영자는 정책 저장 API로 이벤트별 값을 변경하고, 복구 API로 전체 활성 정책을 즉시 동기화할 수 있다.
목록의 `redisStatus`는 `SYNCHRONIZED`, `MISSING`, `STALE`, `DISABLED`, `UNAVAILABLE` 중 하나다.

## 결과와 한계

Redis 유실이 대기열 우회로 이어지지 않고, 정책은 재배포와 Redis 재시작 뒤에도 유지된다. 다만 Redis의
대기 순번과 이미 발급한 입장 상태는 휘발 데이터이므로 백업 없이 복원하지 않는다. 전체 Redis 유실 뒤에는
marker만 복구하고 사용자는 다시 참가해야 하며, 기존 입장 토큰은 거부된다. 이 선택은 오래된 순번을
추정해 공정성을 훼손하는 것보다 명시적인 재진입과 fail-closed를 우선한다.
