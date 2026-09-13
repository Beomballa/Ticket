DO $$
DECLARE
    demo_artist_id BIGINT;
    demo_event_id BIGINT;
    demo_session_id BIGINT;
BEGIN
    INSERT INTO members (email, password_hash, name, role)
    VALUES (
        'admin@stagepass.local',
        '$2y$10$FypdvJjVfha3mCXfrvtkfODVpmhJVjDZy2xuAYnR3gFcBpf6xYqxW',
        'StagePass Admin',
        'ADMIN'
    )
    ON CONFLICT (email) DO UPDATE SET role = 'ADMIN';

    INSERT INTO artists (name, description)
    VALUES ('StagePass Demo Artist', '로컬 시연을 위한 아티스트입니다.')
    ON CONFLICT (name) DO UPDATE SET description = EXCLUDED.description
    RETURNING id INTO demo_artist_id;

    SELECT id INTO demo_event_id
    FROM events
    WHERE artist_id = demo_artist_id AND title = 'StagePass Live: Zero Race'
    ORDER BY id DESC
    LIMIT 1;

    IF demo_event_id IS NULL THEN
        INSERT INTO events (
            artist_id, title, description, event_type, status, sales_start_at, sales_end_at
        ) VALUES (
            demo_artist_id,
            'StagePass Live: Zero Race',
            '한정 재고 선점과 멱등 결제를 직접 확인하는 포트폴리오 데모 이벤트입니다.',
            'CONCERT',
            'ON_SALE',
            CURRENT_TIMESTAMP - INTERVAL '1 day',
            CURRENT_TIMESTAMP + INTERVAL '30 days'
        ) RETURNING id INTO demo_event_id;
    END IF;

    SELECT id INTO demo_session_id
    FROM event_sessions
    WHERE event_id = demo_event_id AND name = '서울 1회차';

    IF demo_session_id IS NULL THEN
        INSERT INTO event_sessions (
            event_id, name, venue, starts_at, sales_start_at, sales_end_at
        ) VALUES (
            demo_event_id,
            '서울 1회차',
            'StagePass Arena',
            CURRENT_TIMESTAMP + INTERVAL '45 days',
            CURRENT_TIMESTAMP - INTERVAL '1 day',
            CURRENT_TIMESTAMP + INTERVAL '30 days'
        ) RETURNING id INTO demo_session_id;
    END IF;

    INSERT INTO sellable_inventory (
        event_session_id, inventory_type, name, price,
        total_quantity, available_quantity, version
    ) VALUES
        (demo_session_id, 'GENERAL_ADMISSION', 'VIP Standing', 165000, 50, 50, 0),
        (demo_session_id, 'GENERAL_ADMISSION', 'General Standing', 121000, 100, 100, 0)
    ON CONFLICT (event_session_id, name) DO NOTHING;
END $$;
