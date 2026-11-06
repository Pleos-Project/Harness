# 블라인드 검수 — A(규칙 A1) vs B(A1 + A2 사전 큐레이션)

PRD 7-4의 '규칙 vs LLM'을 실제로 내보낼 수 있는 두 안의 비교로 한다(DECISIONS 11/6 '블라인드 검수 방식').

| | A = 규칙(A1) | B = A1 + A2 |
|---|---|---|
| 후보 | 관광지 + 실내(박물관 등) + 축제(TourAPI) | 같음 |
| 거르기 | 범위 · 90분 · 테마 · 행사 날짜 · 제외 이름·분류 | 같음 |
| 점수 | 기존 5항목 + 목적지성(소분류) + 날씨 | A + A2 평판 |
| 이유 한 줄 | 규칙 문장(소분류 · 추가 0원 · 시간) | A2 한 줄 소개(근거 있는 것), 없으면 A 문장 |

- 고르는 틀이 같고 A2만 다르다. 결과는 곧 **"AI 사전 큐레이션이 값을 하나"**다.
- 두 쪽 모두 서버 실서비스 순서(`live.candidates_for` → `cards._ranked`, 카카오 실경로)라서, 이기는 쪽을 서버 `.env`의 `CHAGOK_RANKER=a1|b` 하나로 바로 배포한다.
- 판정: '가고 싶다' 평균 차이(B − A) **0.5 이상이면 A2 도입**, 미만이면 A1만. '납득된다' 0.5 이상이면 AI 한 줄 소개(F14) 채택.

## 조건

D1 출발지(성남 판교)·예산(추가 주유·충전 없이, 편도 도로 약 98km) + 아래 다섯 가지 조건. 나들이 날은 `--date`(기본은 다음 토요일)로 정하며, 축제는 그날 열리는 것만 들어간다.

| 조건 | 테마 | 날씨 |
|---|---|---|
| 바다 · 공원 · 도시 · 축제 | 그 테마 하나 | 맑음(가정) |
| 비 오는 날 | 테마 없음(오늘의 한 곳) | 모든 후보 비 1(가정) |

## 순서

1. **후보·날씨 캐시.** `cd server && python -m chagok.sources refresh`(TourAPI 약 160회), `python -m chagok.weather refresh`(검수에는 안 쓰지만 서버가 씀)
2. **A2 큐레이션.** `.env`에 `ANTHROPIC_API_KEY`를 넣고 실행한다.
   ```
   cd server
   uv run --no-project --python 3.12 --with anthropic python -m chagok.curate plan --scope d1   # 묶음 수·예상 비용
   uv run --no-project --python 3.12 --with anthropic python -m chagok.curate run --scope d1 --max-usd 7
   ```
3. **검수 목록.** 카카오 실경로를 쓴다(`KAKAO_REST_API_KEY`).
   ```
   python scripts/blind_review/blind_review.py conditions     # 조건별 범위 안 후보 수
   python scripts/blind_review/blind_review.py run --date 2026-11-07
   ```
   화면에는 개수만 나온다. 어느 쪽이 무엇을 골랐는지는 `out/key.json`·`out/run-*.json`에만 있다.
4. **점수 페이지 게시.** `out/page.html`을 Claude 아티팩트로 게시하고, 팀원에게 **Contributor 이상**으로 공유한다. capabilities는 다음과 같다.
   ```
   {db: {rules: [{path: "ratings", read: "admin", write: "owner"},
                 {path: "ratings/{self}", read: "interact", write: "interact"}]},
    user: {}}
   ```
   - 저장이 안 되는 사람은 페이지의 "점수 복사"(JSON)를 효성에게 보낸다.
5. **집계.** 아티팩트 DB의 `ratings`를 `out/ratings.json`으로 받은 뒤(Claude에게 "검수 점수 내려받아 집계해"), 다음을 실행한다.
   ```
   python scripts/blind_review/blind_review.py tally scripts/blind_review/out/ratings.json [복사본.json …]
   ```
   결과는 `out/result.md`(판정·조건별·평가자별)에 나온다.
6. **기록.** `result.md`·`run-*.json`·`key.json`을 PR로 남기고, DECISIONS에 판정과 `CHAGOK_RANKER` 값을 적는다.

## 블라인드 지키기

- 실행한 사람도 점수를 다 매기기 전에는 `out/key.json`·`out/run-*.json`을 열지 않는다. A2 결과(`curation` 표)도 들여다보지 않는다.
- 페이지에는 출처·순위·장소 id가 없다(코드는 무작위 4자). 평가자마다 순서를 다르게 섞는다.
- 장소 카드에는 두 쪽 모두 같은 정보만 보인다(이름·지역·소분류·시간·비용, 행사는 기간). 이유 한 줄은 2단계에서 보여 준다.
- 1단계(장소)를 제출해야 2단계(이유 한 줄)가 열리고, 그 뒤에는 장소 점수를 고칠 수 없다.
  - 이유 문장의 생김새(규칙 문장 vs 소개 문장)로 출처가 짐작되기 때문이다.

## 한계(결과를 읽을 때)

- 5명 × 조건 5개 × 장소 10곳 안팎이라 표본이 작다. 0.5점은 운영 기준이지 통계 검정이 아니다. 평가자별 차이를 같이 본다.
- 두 쪽이 같은 곳을 고르면 장소는 한 번만 보이고, 점수는 양쪽에 모두 들어간다. 이유 문장이 같으면 그것도 한 번만 묻는다.
- A2 소개의 사실 여부는 사람이 확인하지 않았다. 근거 URL이 검색 결과에 있었는지만 코드로 확인한다.
- 카카오 실경로는 실행 시각의 교통을 반영한다.
