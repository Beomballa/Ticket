# MVP 데이터 모델

초기 MVP는 지정 좌석이 아닌 수량 기반 티켓과 굿즈 재고를 같은 판매 재고로 다룬다.

```mermaid
erDiagram
    MEMBERS ||--o{ RESERVATIONS : creates
    MEMBERS ||--o{ IDEMPOTENCY_REQUESTS : owns
    MEMBERS ||--o{ AUDIT_LOGS : acts
    ARTISTS ||--o{ EVENTS : holds
    EVENTS ||--o{ EVENT_SESSIONS : has
    EVENT_SESSIONS ||--o{ SELLABLE_INVENTORY : exposes
    RESERVATIONS ||--|{ RESERVATION_ITEMS : contains
    SELLABLE_INVENTORY ||--o{ RESERVATION_ITEMS : reserves
    OUTBOX_EVENTS ||--o{ CONSUMED_OUTBOX_EVENTS : consumed_by

    MEMBERS {
        bigint id PK
        varchar email UK
        varchar role
    }
    EVENTS {
        bigint id PK
        bigint artist_id FK
        varchar status
        timestamptz sales_start_at
        timestamptz sales_end_at
    }
    EVENT_SESSIONS {
        bigint id PK
        bigint event_id FK
        timestamptz starts_at
    }
    SELLABLE_INVENTORY {
        bigint id PK
        bigint event_session_id FK
        integer total_quantity
        integer available_quantity
        bigint version
    }
    RESERVATIONS {
        bigint id PK
        bigint member_id FK
        varchar status
        timestamptz expires_at
        bigint version
    }
    RESERVATION_ITEMS {
        bigint id PK
        bigint reservation_id FK
        bigint inventory_id FK
        integer quantity
    }
    IDEMPOTENCY_REQUESTS {
        bigint id PK
        bigint member_id FK
        varchar request_scope
        varchar idempotency_key
        char request_fingerprint
    }
    OUTBOX_EVENTS {
        uuid id PK
        varchar aggregate_type
        varchar event_type
        jsonb payload
        varchar status
    }
    CONSUMED_OUTBOX_EVENTS {
        varchar consumer_name PK
        uuid event_id PK,FK
        timestamptz consumed_at
    }
    AUDIT_LOGS {
        bigint id PK
        bigint actor_member_id FK
        varchar target_type
        varchar target_id
    }
```

## 불변식

- `available_quantity`는 0 이상이며 `total_quantity`를 초과할 수 없다.
- 한 예약에서 같은 재고는 하나의 항목으로만 존재한다.
- 멱등키는 회원·요청 범위 안에서 유일하다.
- 만료 조회와 Outbox 폴링은 부분 인덱스로 활성 상태만 탐색한다.
- Outbox 소비 이력은 소비자명·이벤트 ID 조합으로 유일해 DB 부작용의 중복 반영을 막는다.
- 애플리케이션 상태 전이는 도메인 로직이, 값의 최소 안전선은 DB 제약조건이 보호한다.
