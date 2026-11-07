# docs/ — 기준 문서

여기 문서는 고치지 않는다. 이후 바뀌는 건 `fixtures/`, `contract/`, `DECISIONS.md` 셋 중 하나이고, 거기에 없으면 안 바뀐 것이다.
문서와 하네스가 다르면 **DECISIONS.md가 이긴다**(다르게 가는 이유가 거기 적혀 있어야 한다).

**11/7 운전 노고 정산으로 전면 피봇했다(#46).** 기준 문서를 아래 두 개로 바꿨다.

| 파일 | 문서 | 기준 범위 |
|---|---|---|
| `PRD_노고정산_v1.2.md` | 차곡 운전 노고 정산 PRD v1.2 (명준 기획 틀, 효성 연산 로직·금액화, 11/7) | 기능·화면·문구·연산 로직 |
| `기능명세서_v0.5.md` | 차곡 기능 명세서 v0.5 (효성, 11/7, #48) | 구현 기준본. AC 번호(AC-F20-1~), fixture R1~R8 |

**`archive/`** 에는 피봇 전 문서(PRD v1.6, 기능명세서 v0.4)가 있다. 잔고 → 한 장 → 출발 흐름의 기존 코드(`server/chagok/engine.py`·`cards.py`, Kotlin `Engine.kt`·`DemoServer.kt`·`Ledger.kt`, fixture D·L·T)를 정리하기 전까지 그 코드의 주석·AC 번호가 가리키는 참고용으로만 둔다.

| 피봇 전 참고 | |
|---|---|
| (링크) [서버·추정 엔진 PRD v0.1](https://claude.ai/artifact/M6ZJW24C9XCv86MYzmskcf) (효성, 11/4) | 피봇 전 수식·API·fixture 원안 |
| (링크) [PRD v1.2 개발 가능성 검토](https://claude.ai/artifact/3tDCZKcGyEZwzwGSrRjWy1) (11/5) | 참고 |

레포를 공개(1차 제출용 깃)로 돌릴 때는 이 폴더를 다시 본다. 내부 참고와 결정 이력이 들어 있다(DECISIONS 열린 결정 19).
