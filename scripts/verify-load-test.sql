DO $$
DECLARE
    initial_stock INTEGER;
    available_stock INTEGER;
    reserved_stock BIGINT;
BEGIN
    SELECT inventory.total_quantity, inventory.available_quantity
    INTO initial_stock, available_stock
    FROM sellable_inventory inventory
    JOIN event_sessions session ON session.id = inventory.event_session_id
    JOIN events event ON event.id = session.event_id
    WHERE event.title = 'StagePass Load Test'
      AND inventory.name = 'Contended Seat';

    SELECT COALESCE(sum(item.quantity), 0)
    INTO reserved_stock
    FROM reservation_items item
    JOIN sellable_inventory inventory ON inventory.id = item.inventory_id
    JOIN event_sessions session ON session.id = inventory.event_session_id
    JOIN events event ON event.id = session.event_id
    WHERE event.title = 'StagePass Load Test'
      AND inventory.name = 'Contended Seat';

    IF available_stock < 0 OR available_stock + reserved_stock <> initial_stock THEN
        RAISE EXCEPTION '재고 불변식 위반: initial=%, available=%, reserved=%',
            initial_stock, available_stock, reserved_stock;
    END IF;
    RAISE NOTICE '재고 불변식 통과: initial=%, available=%, reserved=%',
        initial_stock, available_stock, reserved_stock;
END $$;
