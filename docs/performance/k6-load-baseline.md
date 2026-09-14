# k6 공개 조회·예약 경합 부하 기준선

## 목적

Cache-Aside 공개 상세와 동일 재고 예약의 대표 부하를 로컬에서 반복하고, 응답 시간뿐 아니라 초과 판매 불변식을 자동 판정한다. 이 수치는 운영 용량 보장이 아니라 코드 변경 전후 비교를 위한 개발 기준선이다.

## 재현 환경

- 측정일: 2026-09-13
- 호스트: Apple M5 Pro, arm64, 메모리 48GiB
- Java: OpenJDK 21.0.10
- Docker Engine: 29.4.0
- PostgreSQL 16, Redis 7.4, Spring Boot 3.5.16
- 부하 도구: 공식 `grafana/k6:2.0.0` arm64 Docker image
- 애플리케이션과 DB는 같은 로컬 호스트에서 실행

## 실행

API가 8080 포트에서 실행 중일 때 다음 한 명령으로 데이터를 준비하고 두 시나리오와 재고 검증을 수행한다.

```bash
./scripts/run-load-test.sh
```

사용자와 예약 시도 수는 환경 변수로 조정할 수 있다. 실행 시 사용자 수만큼 전용 계정을 준비하며, 기본값은 사용자 20명과 경쟁 재고 100개다.

```bash
LOAD_USERS=20 LOAD_ITERATIONS=200 ./scripts/run-load-test.sh
```

스크립트는 `StagePass Load Test` 이벤트에 속한 기존 예약만 제거하고 재고를 100개로 초기화한다. 다른 이벤트 데이터는 건드리지 않는다.

## 시나리오 A — 공개 이벤트 상세 Cache-Aside

- executor: ramping-vus
- 단계: 2초 동안 10 VU, 5초 동안 30 VU, 2초 동안 0 VU
- 각 iteration: 공개 이벤트 상세 조회, inventory 존재 확인, 50ms think time
- 자동 기준: p95 < 200ms, HTTP 실패율 < 1%, check 성공률 > 99%

| 지표 | 결과 |
|---|---:|
| HTTP 요청 | 2,337 |
| 처리율 | 258.24 req/s |
| 평균 | 6.50ms |
| p90 | 10.71ms |
| p95 | 12.66ms |
| 최대 | 19.18ms |
| HTTP 실패 | 0% |
| check 성공 | 4,673 / 4,673 |

상세 캐시는 사전에 한 번 채워진 상태에서 측정했다. cache miss 비용과 cold start는 이 수치에 포함되지 않는다.

## 시나리오 B — 인기 재고 선점·확정 경합

- executor: shared-iterations
- 20 VU, 예약 시도 200회, 동일 재고 100개
- VU별 사용자 토큰으로 회원 속도 제한의 정상 범위 유지
- 성공한 선점은 `mock-approved`로 즉시 확정
- 예상 응답: 선점 201 또는 재고 소진 409; 429와 그 밖의 응답은 실패
- 자동 기준: p95 < 500ms, HTTP 실패율 < 1%, 예상 외 응답 < 1%, 429 0건

| 지표 | 결과 |
|---|---:|
| HTTP 요청 | 322 |
| 처리율 | 170.08 req/s |
| 전체 iteration | 200 |
| iteration 처리율 | 105.64/s |
| 평균 | 21.74ms |
| p90 | 65.66ms |
| p95 | 68.22ms |
| 최대 | 85.91ms |
| 선점 성공·확정 | 100 |
| 정상 품절 응답 | 100 |
| 429 / 예상 외 응답 | 0 / 0 |
| HTTP 실패 | 0% |

## 정합성 결과

k6 종료 후 PostgreSQL에서 다음 불변식을 검사한다.

```text
initial stock = available stock + sum(reservation item quantity)
100           = 0               + 100
```

`available_stock < 0` 또는 합계 불일치가 있으면 `verify-load-test.sql`이 예외를 발생시켜 실행 전체를 실패 처리한다. 이번 측정은 음수 재고와 초과 판매가 모두 0이었다.

## 경보 초기값

측정 기준과 운영 여유를 반영해 Prometheus에 다음 초기 rule을 추가했다.

- 전체 API p95 > 500ms가 10분 지속
- 5xx 비율 > 5%가 5분 지속
- Outbox active backlog > 100이 10분 지속
- Outbox delivery 실패가 5분 지속
- 예약 rate-limit 거부 비율 > 20%가 5분 지속
- 결제·환불 `UNKNOWN` 최장 체류 시간 > 5분이 5분 지속

`promtool`로 7개 rule 문법을 검증했다. 이 값은 로컬 측정에서 출발한 초기값이며 실제 예상 트래픽·SLO와 운영 하드웨어를 기준으로 재조정해야 한다.

## 해석과 한계

- DB, Redis, API, 부하 발생기가 같은 개발 머신을 공유하므로 네트워크 지연과 운영 topology가 반영되지 않는다.
- 9초 읽기·2.6초 경합 부하는 지속 부하, connection pool 포화와 GC 안정성을 판단하기에 짧다.
- 모의 결제는 외부 network latency, timeout과 webhook을 포함하지 않는다.
- 단일 재고 기준이며 다중 재고 장바구니와 취소·만료가 섞인 경합은 별도 시나리오가 필요하다.
