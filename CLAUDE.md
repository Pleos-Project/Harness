# CLAUDE.md — 이 레포에서 일하는 에이전트용 규칙

차곡(가제): Pleos Connect(AAOS) 차량 앱 + 추천 서버. 사람용 안내는 README.md, 결정은 DECISIONS.md.
제품 이름은 가제라 바뀔 수 있다. 코드 식별자(`chagok`, `ChagokEngine`, `cg-`)는 이름이 바뀌어도 고치지 않는다.

## 언어
- 답변·요약·질문은 **한국어**. 커밋 메시지·PR 본문·코드 주석·문서도 한국어(식별자·명령어·에러 원문은 그대로).

## 기준 문서 (읽기 순서)
1. `DECISIONS.md` — 동결 문서와 다르게 가는 것·열린 결정. **문서와 충돌하면 이쪽이 이긴다.**
2. `docs/기능명세서_v0.4.md` — 구현 기준, AC 번호(AC-F5-1 등). 작업·커밋에 AC 번호를 적는다.
3. `docs/PRD_v1.7.md` — 기능·화면·문구. 특히 5-2(하지 않는 것)·5-3(숫자 표기)·5-4(계산).
`docs/`는 고치지 않는다.

## 꼭 지킬 것
- **원시 차량값을 서버로 보내지 않는다.** 잔량(SOC·연료 %)·주행가능거리·누적 주행거리·VIN·위치 이력. 서버 요청은 `contract/openapi.yaml`의 `budget` 파생값만. 서버 모델은 `extra="forbid"`를 유지한다.
- **수식·순위 규칙은 두 벌**: `server/chagok/engine.py`·`cards.py` ↔ `app/android/app/src/main/java/com/mist/pleos_app/engine/`(`Engine.kt`·`DemoServer.kt`·`Ledger.kt`). 한쪽만 고치지 않는다. 둘 다 같은 fixtures를 통과해야 한다(서버 pytest, 앱 `FixturesTest`).
- **반올림**: Python은 `round_half_up`/`round_to`/`round1`만(내장 `round()` 금지), Kotlin은 `roundHalfUp`/`roundTo`/`round1`만(`kotlin.math.round()`·`Math.rint` 금지 — 둘 다 0.5를 짝수 쪽으로 보내 1원 단위로 어긋난다). 곱셈·나눗셈 순서도 같게.
- 앱 화면 코드에 금액·거리 수식을 따로 적지 않는다. 계산은 `engine` 패키지, 상수·데모 후보는 빌드 때 `fixtures/`에서 복사되는 `assets/data/`(`syncChagokData`)를 읽는다.
- **fixtures·contract/samples는 생성물**이다. 손으로 고치지 말고 `python scripts/gen_fixtures.py`. 기대값이 바뀌면 왜 바뀌었는지 PR에 적는다.
- **표기(PRD 5-3)**: 잔고·정산 천 원 반올림, 카드 비용 5천 원 어림 "~ 안팎", 1인 몫 천 원 어림, 어림이 0이면 "{단위} 미만", 경계 카드는 "빠듯"만, "추가 0원"은 정확한 0일 때만. 75분 넘는 카드에 "75분 안" 금지.
- **금지 문구(AC-C-8)**: 글레오와의 비교 주장, "예상 vs 실제", 주행 중 금액 미터·광고.
- **외부 API 키**(TourAPI 등)는 `.env`(이름은 `.env.example`)·GitHub Secrets에만. 코드·커밋·PR·로그·앱 빌드(assets)에 넣지 않는다.
- 백그라운드 수집·폴링·타이머 금지(앱). 제어 퍼미션 금지. 권한은 `contract/permissions.md`와 정확히 같게.
- 문서와 다르게 결정했으면 `DECISIONS.md`에 한 줄(상태: 제안). 남의 영역(열린 결정의 담당)은 기본값으로 두고 결정하지 않는다.

## 브랜치
`develop`에서 `feature/<작업명>`·`fix/<작업명>`을 따서 작업하고 PR base는 `develop`. `main`·`develop`에 직접 커밋·푸시하지 않는다(CONTRIBUTING.md).
- **PR 본문**은 `.github/pull_request_template.md`의 절·체크리스트를 그대로 옮겨 채운다(`gh pr create --body`는 템플릿을 건너뛴다).
- **리뷰어·멘션은 담당자 확인이 필요한 PR에만** 단다. 앱만 바꾼 PR(`app/` 화면·SDK 연동·앱 테스트)에는 리뷰어도 멘션도 달지 않는다.
  - 다는 경우: 다른 파트의 영역을 건드리거나 그 담당자가 정해야 할 것이 있을 때. 예: `fixtures/`·`contract/`(효성·June 중 작성자가 아닌 쪽), Kotlin 엔진 수식·`FixturesTest`(효성), 서버(효성), 문구·디자인·`DECISIONS.md`(명준), 이 규칙 자체.
  - 에이전트는 `--reviewer` 없이 PR을 올리고, 리뷰어를 달기 전에 작성자에게 "누구에게 무엇을 확인받을지" 먼저 묻는다.
  - 리뷰어를 달면 PR 본문에 `## @<아이디> 확인 부탁` 절을 두고, 항목마다 파일·줄(또는 화면·문구)과 정해 줄 것(선택지·지금 기본값)을 적는다. 아이디: 효성 `hyos0415`, June `june0103`, 명준 `thvlou`.
- **머지**는 CI 3개(server · app-android · copy)가 모두 통과한 걸 확인한 뒤 `gh pr merge --merge --delete-branch`로 한다. 실패나 대기 중이면 머지하지 않고 그대로 보고한다. `develop → main` 릴리스 PR은 사람이 시점을 정할 때만.

## 끝내기 전에
```
make check      # = cd server && python -m pytest -q ; python scripts/lint_copy.py   (빠름)
make check-app  # 앱·Kotlin 엔진을 바꿨으면 = cd app/android && ./gradlew assembleDebug test   (JDK 21 필요)
```
엔진·상수·후보를 바꿨으면 먼저 `make fixtures`. JDK가 없어 `check-app`을 못 돌렸으면 그렇다고 적고 PR의 CI `app-android` 결과로 확인한다. 실패를 숨기지 말고 그대로 보고한다.
