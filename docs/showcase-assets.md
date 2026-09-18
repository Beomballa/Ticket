# 공연 이미지와 레퍼런스

2026-09-18 작업. 참고 화면: https://nol.yanolja.com/ticket

NOL의 포스터 중심 공연 탐색, 장르 분류, 공연명·장소·일정의 정보 위계를 참고했다. 실제 NOL 이미지·브랜드 자산·공연 정보는 복사하지 않았다. StagePass 전용 가상 공연 포스터 4종을 내장 이미지 생성 도구로 생성했다. 아래 프롬프트가 최종 생성 요청이다. 별도 유료 API/CLI를 사용하지 않았다.

원본은 1024×1536 PNG이며 저장소에는 640×960 JPEG(품질 82)로 최적화했다. 변경된 그림이나 재합성 없이 크기와 형식만 변환했다. 총 용량은 약 700 KiB다.

| 파일 | 사용 범위 |
|---|---|
| frontend/public/images/events/midnight-jazz.jpg | 미드나잇 재즈 서울 / 부산 앙코르 |
| frontend/public/images/events/forest-sound.jpg | 포레스트 사운드 |
| frontend/public/images/events/first-letter.jpg | 첫 번째 편지 서울 / 부산 |
| frontend/public/images/events/studio-live.jpg | 스튜디오 라이브 |

공연명과 가상 아티스트명이 모두 일치할 때만 이미지를 연결한다. 원격 이미지 핫링크, 실존 인물 초상, 실제 공연사 로고는 사용하지 않는다. 가상 공연 시연 자산이며 실존 행사로 홍보하지 않는다.

## night

Use case: ads-marketing. Asset type: portrait 2:3 concert poster for a fictional StagePass demo event. Create a premium editorial poster for MIDNIGHT JAZZ. Deep midnight navy background, dramatic photographic close-up of a brushed brass saxophone curving through lower two thirds, restrained warm tungsten light and subtle film grain. Top third bold cream serif typography exactly 'MIDNIGHT' then 'JAZZ'. Bottom tiny text exactly 'STAGEPASS SESSIONS'. Sophisticated jazz record sleeve, not generic web illustration. Full bleed, no frame, no dates, no logos, no real artists, no watermark.

## forest

Use case: ads-marketing. Asset type: portrait 2:3 concert poster for a fictional StagePass demo event. A beautifully art-directed photograph of a sunlit outdoor music stage in a pine grove, empty wooden stage with microphone and electric guitar, hazy late afternoon summer sunlight, moss green and ivory palette, analog film grain. Bold elegant cream typography in upper third exactly 'FOREST' then 'SOUND'. Small bottom text exactly 'STAGEPASS LIVE'. Full bleed premium indie music poster, no border, no real performers, no venue names, no dates, no logos, no watermark.

## letter

Use case: ads-marketing. Asset type: portrait 2:3 fan meeting poster for a fictional StagePass demo event FIRST LETTER. Poetic editorial still life of folded ivory letters and translucent light blue ribbon on a softly lit powder blue tabletop, one small silver star charm, natural paper texture, considered diagonal composition with generous breathing room. Refined dark navy typography near top exactly 'FIRST' then 'LETTER'. Small bottom text exactly 'STAGEPASS FAN MEETING'. Premium tactile fan event artwork, no real people, no dates, no logos, no watermark, no UI.

## studio

Use case: ads-marketing. Asset type: portrait 2:3 live recording event poster for a fictional StagePass demo event STUDIO LIVE. High-end editorial photograph of a chrome vintage studio microphone on the right, soundproof recording room in background, deep oxblood red and warm off-white, restrained cinematic spotlight and analog grain. Bold condensed cream typography upper left exactly 'STUDIO' then 'LIVE'. Small bottom text exactly 'STAGEPASS ON AIR'. Full bleed poster, no real artists, no dates, no logos, no watermark, not a collage.
