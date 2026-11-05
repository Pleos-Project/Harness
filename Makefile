.PHONY: dev serve test lint check check-app fixtures replay
PY ?= python
GRADLEW ?= ./gradlew

dev:       ## 서버 개발 (--reload: 코드가 바뀌면 세션이 날아감). 에뮬에서 http://10.0.2.2:8000
	cd server && uvicorn chagok.main:app --reload --port 8000 --host 0.0.0.0
serve:     ## 리허설·PT용 서버 (reload 없음)
	cd server && uvicorn chagok.main:app --port 8000 --host 0.0.0.0
test:      ## 서버 pytest (fixtures 대조 + 표기 규칙 불변식)
	cd server && $(PY) -m pytest -q
lint:      ## 화면 문구 전수 검사 (fixtures·design·app의 strings.xml·.kt·.html)
	$(PY) scripts/lint_copy.py
check: test lint  ## PR 전에 (빠름, JDK 없어도 됨)
check-app: ## 앱·엔진을 바꿨으면: 빌드 + Kotlin 엔진 fixture 대조(FixturesTest). JDK 21 필요 (윈도우: GRADLEW=gradlew.bat)
	cd app/android && $(GRADLEW) assembleDebug test
fixtures:  ## 엔진·상수·후보 변경 후 fixture·계약 샘플 재생성 → 검사
	$(PY) scripts/gen_fixtures.py && $(MAKE) check
replay:    ## Virtual Console로 PT 6단계 주입 (adb forward tcp:8088 tcp:8088 먼저)
	bash scripts/replay.sh pt
