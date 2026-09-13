CREATE INDEX idx_outbox_admin_status_created
    ON outbox_events (status, created_at DESC, id DESC)
    INCLUDE (attempts, event_type, aggregate_id);
