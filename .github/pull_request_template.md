<!-- 작업 PR의 base 브랜치는 develop 입니다. main은 릴리스 전용입니다. -->

## 변경 내용

## 관련 AC·결정
<!-- 예: AC-F5-1, DECISIONS 열린 결정 5 -->

## 확인 방법

## 체크리스트
- [ ] base 브랜치가 `develop`인지 확인
- [ ] `make check` 통과 (서버 pytest · 앱 엔진 node 테스트 · 문구 검사)
- [ ] 엔진·상수·후보를 바꿨다면 `make fixtures`로 재생성해 같이 올림, 수식은 Python·JS 두 벌 다 고침
- [ ] 문서와 다르게 가는 결정이면 `DECISIONS.md`에 한 줄
- [ ] 앱 변경이면 Pleos Connect 에뮬레이터에서 동작 확인
