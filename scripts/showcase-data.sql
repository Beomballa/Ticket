-- Local portfolio fixtures only. Fictional artists, venues and events; no real ticket sales.
-- Existing schedules, reservations and consumed stock are preserved on every rerun.
BEGIN;
SELECT pg_advisory_xact_lock(78342109);
DO $$
DECLARE
    show RECORD;
    artist_key BIGINT;
    event_key BIGINT;
    session_key BIGINT;
    slot INTEGER;
    sale_start TIMESTAMPTZ;
    sale_end TIMESTAMPTZ;
BEGIN
    FOR show IN SELECT * FROM (VALUES
        ('미드나잇 콰르텟', '미드나잇 재즈: Midnight Jazz', 'CONCERT', 'StagePass 블루룸 · 서울',
         '색소폰과 피아노, 더블베이스와 드럼이 만나는 늦은 저녁의 재즈 공연. 소규모 비지정석 라이브로 준비했습니다.', 59000, 80, 21, FALSE),
        ('포레스트 사운드 클럽', '포레스트 사운드: Forest Sound', 'CONCERT', 'StagePass 포레스트 가든 · 서울',
         '숲을 닮은 무대에서 만나는 인디 밴드 라이브. 스탠딩 구역에서 기타와 보컬의 소리를 가까이 즐겨보세요.', 88000, 150, 28, FALSE),
        ('서하 프로젝트', '첫 번째 편지: First Letter', 'FAN_MEETING', 'StagePass 레터홀 · 서울',
         '함께 읽는 편지, 근황 토크와 어쿠스틱 무대로 구성한 첫 팬미팅. 회차별 동일 프로그램이며 비지정석으로 운영합니다.', 55000, 120, 35, FALSE),
        ('온에어 컬렉티브', '스튜디오 라이브: Studio Live', 'PUBLIC_BROADCAST', 'StagePass 스튜디오 B · 서울',
         '음악이 녹음되는 순간을 함께하는 공개 라이브 세션. 공연과 짧은 제작 토크가 이어지는 스튜디오 방청 프로그램입니다.', 25000, 60, 14, FALSE),
        ('미드나잇 콰르텟', '미드나잇 재즈: 앙코르 세션', 'CONCERT', 'StagePass 블루룸 · 부산',
         '미드나잇 재즈의 두 번째 도시. 같은 테마를 새로운 편곡으로 선보이는 비지정석 앙코르 라이브입니다.', 59000, 80, 49, TRUE),
        ('서하 프로젝트', '첫 번째 편지: 두 번째 이야기', 'FAN_MEETING', 'StagePass 레터홀 · 부산',
         '팬들과 함께 완성하는 두 번째 편지. 새로운 토크 주제와 어쿠스틱 무대로 구성한 비지정석 팬미팅입니다.', 55000, 100, 56, TRUE)
    ) AS shows(artist, title, kind, venue, description, price, capacity, days_until, upcoming)
    LOOP
        INSERT INTO artists (name, description)
        VALUES (show.artist, 'StagePass 시연을 위해 만든 가상 아티스트입니다.')
        ON CONFLICT (name) DO NOTHING;
        SELECT id INTO artist_key FROM artists WHERE name = show.artist;
        SELECT id INTO event_key FROM events WHERE artist_id = artist_key AND title = show.title ORDER BY id LIMIT 1;
        IF event_key IS NOT NULL THEN CONTINUE; END IF;

        sale_start := CASE WHEN show.upcoming THEN CURRENT_TIMESTAMP + INTERVAL '7 days'
                           ELSE CURRENT_TIMESTAMP - INTERVAL '1 day' END;
        sale_end := CURRENT_TIMESTAMP + (show.days_until - 1) * INTERVAL '1 day';
        INSERT INTO events (artist_id, title, description, event_type, status, sales_start_at, sales_end_at)
        VALUES (artist_key, show.title, show.description || E'\n[데모] 가상 공연·공연장이며 실제 결제나 관람권 발급은 이루어지지 않습니다.',
                show.kind, CASE WHEN show.upcoming THEN 'PUBLISHED' ELSE 'ON_SALE' END, sale_start, sale_end)
        RETURNING id INTO event_key;

        FOR slot IN 1..2 LOOP
            INSERT INTO event_sessions (event_id, name, venue, starts_at, sales_start_at, sales_end_at)
            VALUES (event_key, slot || '회차', show.venue,
                    date_trunc('day', CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul') AT TIME ZONE 'Asia/Seoul'
                        + (show.days_until + slot - 1) * INTERVAL '1 day' + INTERVAL '19 hours', sale_start, sale_end)
            RETURNING id INTO session_key;
            INSERT INTO sellable_inventory (event_session_id, inventory_type, name, price, total_quantity, available_quantity, version)
            VALUES (session_key, 'GENERAL_ADMISSION', '일반 입장권 · 비지정석', show.price, show.capacity, show.capacity, 0),
                   (session_key, 'GENERAL_ADMISSION', '프리미엄 입장권 · 비지정석', show.price + 22000, 30, 30, 0);
        END LOOP;
    END LOOP;
END $$;
COMMIT;
