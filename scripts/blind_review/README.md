# 블라인드 검수 — 규칙 vs LLM (PRD v1.3 7-4)

같은 조건(D1 + 테마 3가지)에서 **규칙 상위 5곳**과 **LLM 5곳**을 섞어 팀 5명이 점수를 매긴다.

- 장소마다 "가고 싶다" 1~5점. **평균 차이(LLM − 규칙) 0.5점 미만이면 규칙 확정**(11/11까지)
- 카드의 이유 한 줄마다 "납득된다" 1~5점(판정 기준은 없고 같이 보고한다)

| 파일 | 하는 일 |
|---|---|
| `prompt.md` | LLM에 주는 프롬프트 한 장(system·user). 바꾸면 run 기록의 `prompt_sha`가 바뀐다 |
| `blind_review.py` | 테마 선택 → 규칙 순위 → LLM 호출 → 섞기 → 점수 페이지 → 집계 |
| `page.html` | 점수 페이지 틀. 실행하면 `out/page.html`에 목록이 들어간다 |
| `out/` | 실행 결과(gitignore). 정답 키가 있어 **점수를 다 매기기 전에는 열지 않는다** |

## 공정하게 비교하려고 맞춘 것

- **입력이 같다.** 조건은 `fixtures/cards/D1.json`의 요청에서 `demo_seed`만 뺀 실서비스 요청에 테마 하나를 붙인다.
  - 데모 후보 12곳으로는 테마당 1~2곳뿐이라 서버의 TourAPI 후보(약 2,300곳)를 쓴다.
  - LLM에는 규칙이 순위를 매기는 것과 같은 후보(추정 범위 안 전체)를 **가나다순**으로 준다. 규칙 순위가 새지 않게 하려는 것이다.
  - 원시 차량값은 보내지 않는다. 범위는 예산 파생값(`usable_km` ÷ 2 = 편도 도로)만 쓴다.
- **규칙 쪽은 서버 코드를 그대로 부른다.** `live.candidates_for` → `cards._ranked`의 상위 5곳이다. 추정값으로 12곳을 골라 카카오 실경로로 바꾼 뒤, 실경로 후보끼리 순위를 매긴다(서버 응답과 같은 순서).
- **표시 값이 같다.** LLM이 고른 곳도 같은 카카오 실경로와 같은 카드 계산(`cards.evaluate`)을 거친다.
  - 실경로로 다시 재서 범위·90분을 벗어나면 LLM이 함께 낸 예비 2곳 중 다음 것을 쓴다.
  - 목록에 없는 id, 같은 장소의 다른 항목, 빈 이유는 버리고 `run-*.json`에 이유를 남긴다.
- **테마는 자동으로 고른다.** 성격이 다른 묶음(물가 / 녹지 / 도시)마다 D1 범위 안 후보가 가장 많은 테마를 하나씩 고른다(30곳 미만인 묶음은 건너뜀).
  - 11/5 캐시 기준으로 바다 80 · 공원 636 · 도시 156곳이 뽑힌다.
  - 후보가 많을수록 규칙의 동점 처리("반경의 절반쯤", `cards.sort_ranked`)가 순위를 가르는 비중이 커져서, 규칙에 불리한 쪽으로 시험하는 셈이다.
- **모델**: `claude-opus-5-5`, effort `high`, 구조화 출력(JSON 스키마), 거절 시 서버 측 대체 모델(`fallbacks: "default"`). Opus 5.5는 temperature를 받지 않아 같은 입력도 결과가 조금씩 다를 수 있다. 그래서 **1회 실행 결과를 그대로 기록**한다(요청 id·원문 응답·사용량).

## 순서

1. **후보 캐시 준비.** 다음 둘 중 하나로 `server/data/chagok.sqlite3`를 만든다.
   - 운영 서버 캐시를 읽기 전용으로 복사(서버와 같은 후보). 접속은 `deploy/README.md`를 따르고, 서버에서 `sqlite3` 백업을 만든 뒤 `scp`로 가져온다.
   - 직접 갱신: `cd server && python -m chagok.sources refresh`. TourAPI 약 120회 호출이다.
2. **키 넣기.** `.env`에 `KAKAO_REST_API_KEY`(실경로)와 `ANTHROPIC_API_KEY`(LLM)를 넣는다(`.env.example` 참고). 키는 채팅·PR·로그에 붙이지 않는다.
3. **무료로 확인**(외부 호출 없음):
   ```
   python scripts/blind_review/blind_review.py themes    # 테마별 후보 수·자동 선택
   python scripts/blind_review/blind_review.py prompt    # out/prompt-<테마>.md 로 보낼 내용 확인
   ```
4. **실행**: LLM 3회 + 카카오 약 50회. LLM 비용은 1달러 안팎으로 추정한다(입력 약 5만 자).
   ```
   uv run --no-project --python 3.12 --with anthropic python scripts/blind_review/blind_review.py run
   ```
   화면에는 개수만 나온다. 어느 쪽이 무엇을 골랐는지는 `out/key.json`·`out/run-*.json`에만 있다.
5. **점수 페이지 게시.** `out/page.html`을 Claude 아티팩트로 게시한다. 이때 capabilities는 다음과 같이 준다.
   ```
   {db: {rules: [{path: "ratings", read: "admin", write: "owner"},
                 {path: "ratings/{self}", read: "interact", write: "interact"}]},
    user: {}}
   ```
   - 평가자 각자가 자기 점수 문서(`ratings/<id>`)만 읽고 쓴다. 다른 사람 점수는 소유자만 볼 수 있다.
   - 팀원에게는 **Contributor 이상**으로 공유한다. 보기 전용이거나 저장소에 연결되지 않으면 페이지가 "점수 복사"(JSON) 모드로 바뀌고, 그 글을 효성에게 보내면 된다.
6. **집계.** 다 모이면 아티팩트 DB의 `ratings` 목록을 `out/ratings.json`으로 저장한다(Claude에게 "검수 점수 내려받아 집계해" 하면 된다). 복사로 받은 JSON도 같이 넣는다.
   ```
   python scripts/blind_review/blind_review.py tally scripts/blind_review/out/ratings.json [복사본.json …]
   ```
   `out/result.md`에 판정·테마별·평가자별 차이가 나온다.
7. **기록.** 결과(`result.md`)와 실행 기록(`run-*.json`, `key.json`)을 `docs/`가 아닌 곳(예: `research/blind_review/`)에 PR로 남기고, DECISIONS에 판정 한 줄을 적는다.

## 블라인드 지키기

- 실행한 사람도 점수를 다 매기기 전에는 `out/key.json`·`out/run-*.json`·`out/prompt-*.md`를 열지 않는다. `items.json`·`page.html`은 출처가 없어 괜찮다.
- 페이지에는 출처·순위·장소 id가 없다(코드는 무작위 4자). 평가자마다 순서를 다르게 섞는다.
- 1단계(장소)를 제출해야 2단계(이유 한 줄)가 열리고, 그 뒤에는 장소 점수를 고칠 수 없다.
  - 규칙 이유는 "추가 0원 안 · 75분 안 · 바다"처럼 틀이 같아서 이유를 보면 출처가 짐작된다. 그래서 장소 점수를 먼저 잠근다.

## 한계(결과를 읽을 때)

- 5명 × 테마 3개 × 장소 10곳 안팎이라 표본이 작다. 0.5점 기준은 PRD가 정한 운영 기준이지 통계 검정이 아니다. 평가자별 차이를 같이 본다.
- 두 쪽이 같은 곳을 고르면 장소는 한 번만 보여 주고, 그 점수는 양쪽 평균에 모두 들어간다.
- LLM 이유 한 줄의 사실 여부는 확인하지 않는다. 프롬프트 규칙을 어긴 문구(금액·주차·시간 숫자·40자 초과 등)는 버리지 않고 `run-*.json`의 `flags`에만 남긴다.
  - F14(AI 한 줄 소개)를 실제로 넣는다면 PRD 7-4대로 필터·신고 기능이 필요하다.
- 카카오 실경로는 실행 시각의 교통을 반영한다. 다시 실행하면 시간이 조금 달라질 수 있다.
