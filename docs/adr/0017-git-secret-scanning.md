# ADR-0017: 전체 Git 이력 비밀정보 검사

- 상태: Accepted
- 결정일: 2026-09-13

## 배경

`.gitignore`와 커밋 전 문자열 검사는 새 파일의 실수를 줄이지만, 과거 커밋에 남은 토큰·개인키·클라우드
자격증명을 놓칠 수 있다. 삭제된 비밀도 Git 이력에서는 계속 복구할 수 있으므로 PR과 `main` push마다
자동 검사가 필요하다.

## 결정

- GitHub Actions에 백엔드·프론트와 독립적인 `Secret scan` 작업을 추가한다.
- checkout은 `fetch-depth: 0`으로 전체 이력을 받고 자격증명은 작업 디렉터리에 유지하지 않는다.
- Gitleaks 8.30.1 공식 컨테이너를 tag가 아니라 multi-architecture image digest로 고정한다.
- `gitleaks git`으로 전체 이력을 검사하고 탐지 결과는 `--redact`로 마스킹한다.
- 작업 권한은 `contents: read`만 허용하며 제3자 Action에 `GITHUB_TOKEN`을 전달하지 않는다.
- 기존 checkout·Java·Node·Gradle 액션도 Node 24 계열의 검증한 릴리스 SHA로 고정한다.
- 저장소가 깨끗하므로 baseline이나 allowlist를 만들지 않는다. 향후 오탐은 정확한 fingerprint 또는 최소
  경로만 근거와 함께 예외 처리한다.

## 대안

- Gitleaks Action v3는 Node 24를 사용하고 설정이 간단하지만 PR·push 이벤트에서는 변경 커밋 범위를
  중심으로 검사하며 GitHub Token을 사용한다. 전체 이력 검사와 최소 권한 요구에 맞지 않아 제외했다.
- 정규식 셸 스크립트는 의존성이 적지만 엔트로피, 공급자별 토큰 형식과 인코딩된 값을 충분히 다루기
  어렵다.
- baseline은 도입이 빠르지만 실제 과거 유출을 정상 상태로 고정할 수 있어 현재는 사용하지 않는다.

## 결과

새 PR과 `main` push는 현재 diff뿐 아니라 저장소의 전체 Git 이력에 비밀정보가 있으면 실패한다.
이미 노출된 자격증명은 파일 삭제만으로 안전해지지 않으므로 탐지 시 폐기·교체를 먼저 수행하고 필요하면
합의된 범위에서 이력을 재작성한다. 컨테이너 버전 갱신은 새 digest의 공식 릴리스와 로컬 전체 이력 검사를
확인한 뒤 별도 커밋으로 수행한다.
