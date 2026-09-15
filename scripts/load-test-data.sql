BEGIN;

INSERT INTO members (email, password_hash, name, role)
SELECT
    format('load-user-%s@stagepass.local', number),
    '$2y$10$zZbQtXOhMIVHoLrfOnGue.gWY9gBFwr8pVjTteP73ubt.1goHjpoG',
    format('Load User %s', number),
    'USER'
FROM generate_series(1, :load_users) number
ON CONFLICT (email) DO UPDATE
SET password_hash = EXCLUDED.password_hash,
    role = 'USER';

INSERT INTO members (email, password_hash, name, role)
VALUES (
    'load-admin@stagepass.local',
    '$2y$10$zZbQtXOhMIVHoLrfOnGue.gWY9gBFwr8pVjTteP73ubt.1goHjpoG',
    'Load Admin',
    'ADMIN'
)
ON CONFLICT (email) DO UPDATE
SET password_hash = EXCLUDED.password_hash,
    role = 'ADMIN';

INSERT INTO artists (name, description)
VALUES ('StagePass Load Artist', 'k6 전용 로컬 부하 테스트 데이터')
ON CONFLICT (name) DO UPDATE SET description = EXCLUDED.description;

INSERT INTO events (
    artist_id, title, description, event_type, status, sales_start_at, sales_end_at)
SELECT id, 'StagePass Load Test', '한정 재고 경합 부하 테스트 전용 이벤트',
       'CONCERT', 'ON_SALE', CURRENT_TIMESTAMP - INTERVAL '1 day',
       CURRENT_TIMESTAMP + INTERVAL '7 days'
FROM artists
WHERE name = 'StagePass Load Artist'
  AND NOT EXISTS (SELECT 1 FROM events WHERE title = 'StagePass Load Test');

INSERT INTO event_sessions (
    event_id, name, venue, starts_at, sales_start_at, sales_end_at)
SELECT id, 'Load Session', 'Load Arena', CURRENT_TIMESTAMP + INTERVAL '30 days',
       CURRENT_TIMESTAMP - INTERVAL '1 day', CURRENT_TIMESTAMP + INTERVAL '7 days'
FROM events
WHERE title = 'StagePass Load Test'
ON CONFLICT (event_id, name) DO NOTHING;

INSERT INTO sellable_inventory (
    event_session_id, inventory_type, name, price,
    total_quantity, available_quantity, version)
SELECT id, 'GENERAL_ADMISSION', 'Contended Seat', 10000, 100, 100, 0
FROM event_sessions
WHERE name = 'Load Session'
  AND event_id = (SELECT id FROM events WHERE title = 'StagePass Load Test' ORDER BY id DESC LIMIT 1)
ON CONFLICT (event_session_id, name) DO NOTHING;

CREATE TEMP TABLE load_reservations ON COMMIT DROP AS
SELECT DISTINCT item.reservation_id AS id
FROM reservation_items item
JOIN sellable_inventory inventory ON inventory.id = item.inventory_id
JOIN event_sessions session ON session.id = inventory.event_session_id
JOIN events event ON event.id = session.event_id
WHERE event.title = 'StagePass Load Test';

DELETE FROM consumed_outbox_events
WHERE event_id IN (
    SELECT id FROM outbox_events
    WHERE aggregate_type = 'RESERVATION'
      AND aggregate_id IN (SELECT id::text FROM load_reservations)
);
DELETE FROM outbox_events
WHERE aggregate_type = 'RESERVATION'
  AND aggregate_id IN (SELECT id::text FROM load_reservations);
DELETE FROM audit_logs
WHERE target_type = 'RESERVATION'
  AND target_id IN (SELECT id::text FROM load_reservations);
DELETE FROM audit_logs
WHERE target_type = 'REFUND_ATTEMPT'
  AND target_id IN (
    SELECT id::text FROM refund_attempts
    WHERE reservation_id IN (SELECT id FROM load_reservations)
  );
DELETE FROM audit_logs
WHERE target_type = 'PAYMENT_ATTEMPT'
  AND target_id IN (
    SELECT id::text FROM payment_attempts
    WHERE reservation_id IN (SELECT id FROM load_reservations)
  );
DELETE FROM payment_webhook_inbox
WHERE gateway_idempotency_key IN (
    SELECT gateway_idempotency_key FROM refund_attempts
    WHERE reservation_id IN (SELECT id FROM load_reservations)
    UNION
    SELECT gateway_idempotency_key FROM payment_attempts
    WHERE reservation_id IN (SELECT id FROM load_reservations)
);
DELETE FROM refund_attempts WHERE reservation_id IN (SELECT id FROM load_reservations);
DELETE FROM payment_attempts WHERE reservation_id IN (SELECT id FROM load_reservations);
DELETE FROM reservation_items WHERE reservation_id IN (SELECT id FROM load_reservations);
DELETE FROM idempotency_requests
WHERE member_id IN (SELECT id FROM members WHERE email LIKE 'load-user-%@stagepass.local');
DELETE FROM reservations WHERE id IN (SELECT id FROM load_reservations);

UPDATE sellable_inventory inventory
SET total_quantity = 100,
    available_quantity = 100,
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
FROM event_sessions session, events event
WHERE inventory.event_session_id = session.id
  AND session.event_id = event.id
  AND event.title = 'StagePass Load Test';

COMMIT;
