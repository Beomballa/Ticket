CREATE INDEX idx_reservations_status_created
    ON reservations (status, created_at DESC, id DESC);

CREATE INDEX idx_inventory_availability
    ON sellable_inventory (available_quantity, event_session_id, id);

CREATE INDEX idx_reservation_items_inventory_reservation
    ON reservation_items (inventory_id, reservation_id);
