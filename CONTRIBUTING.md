# 기여 규칙

브랜치 전략은 June이 `Pleos-Project/App`에 정한 방식을 그대로 쓴다.

| 브랜치 | 용도 |
|---|---|
| `main` | 배포(릴리스) 브랜치. 시연·제출 기준. `develop`에서 릴리스할 때만 PR로 머지 |
| `develop` | 개발 통합 브랜치(기본 브랜치). 모든 작업 브랜치는 여기로 머지 |
| `feature/<작업명>` | 기능 개발. `develop`에서 분기 |
| `fix/<작업명>` | 버그 수정. `develop`에서 분기 |

## 작업 흐름

```bash
git switch develop
git pull
git switch -c feature/<작업명>
# 작업 후
make check                      # 서버 테스트·문구 검사. 엔진·상수·후보를 바꿨으면 먼저 make fixtures
make check-app                  # 앱·Kotlin 엔진을 바꿨으면 (JDK 21, 없으면 PR의 CI app-android로 확인)
git push -u origin feature/<작업명>
gh pr create --base develop
```

- `main`, `develop`에는 직접 푸시하지 않고 PR로만 머지한다.
- 작업 PR의 base는 항상 `develop`. 릴리스(11/10 시연, 11/15 제출, 12/3 PT 직전)는 `develop` → `main` PR.
- `fixtures/`·`contract/`를 건드리는 PR은 서버(효성)·앱(June) 둘 다 리뷰.
- 리뷰어·멘션은 해당 파트 담당자의 확인이 필요한 PR에만 단다. 앱만 바꾼 PR에는 달지 않는다. 달 때는 본문에 `## @<아이디> 확인 부탁` 절로 무엇을 확인할지 적는다(자세히는 CLAUDE.md '브랜치').
- PR 제목·커밋 메시지는 한국어. 관련 AC 번호(예: AC-F23-1, 기능명세서 v0.5)를 적는다.
- 문서(`docs/`)와 다르게 가는 결정은 같은 PR에서 `DECISIONS.md`에 한 줄.

영역별 시작점은 README.md "사람별 시작점", 에이전트 규칙은 CLAUDE.md.
