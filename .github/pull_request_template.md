<!-- 작업 PR의 base 브랜치는 develop 입니다. main은 릴리스 전용입니다. -->

## 변경 내용

## 관련 AC·결정
<!-- 예: AC-F5-1, DECISIONS 열린 결정 5 -->

## 확인 방법

## 앱 반영 요청 (design/ PR만 — 다른 PR은 지우기)
<!-- 머지되면 design-handoff 워크플로가 이 절을 작업 지시로 읽고 앱(Compose) 반영 PR을 자동으로 연다. 비우면 diff만 보고 반영한다.
     첫 시안·큰 개편처럼 개발단이 직접 입힐 PR은 `수동 반영` 라벨을 단다(자동 반영 안 돎). -->
- 바뀐 조각·토큰:
- 새로 생긴·바뀐 `data-field`·`data-derived`:
- 앱에서 할 일(화면·상태·인터랙션):
- 반영하지 않을 것(시안 전용·나중에):

## 체크리스트
- [ ] base 브랜치가 `develop`인지 확인
- [ ] `make check` 통과 (서버 pytest · 문구 검사), 앱·엔진을 바꿨으면 `make check-app`(또는 CI app-android)
- [ ] 엔진·상수·후보를 바꿨다면 `make fixtures`로 재생성해 같이 올림, 수식은 Python·Kotlin 두 벌 다 고침
- [ ] 문서와 다르게 가는 결정이면 `DECISIONS.md`에 한 줄
- [ ] 앱 변경이면 Pleos Connect 에뮬레이터에서 동작 확인
