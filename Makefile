.PHONY: dev serve test test-app lint check fixtures replay
PY ?= python

dev:       ## 서버 개발 (--reload: 코드가 바뀌면 세션이 날아감). 에뮬에서 http://10.0.2.2:8000
	cd server && uvicorn chagok.main:app --reload --port 8000 --host 0.0.0.0
serve:     ## 리허설·PT용 서버 (reload 없음)
	cd server && uvicorn chagok.main:app --port 8000 --host 0.0.0.0
test:      ## 서버 pytest (fixtures 대조 + 표기 규칙 불변식)
	cd server && $(PY) -m pytest -q
test-app:  ## 앱 엔진 JS가 같은 fixtures를 통과하는지
	node --test "app/shared/test/*.test.js"
lint:      ## 화면 문구 전수 검사
	$(PY) scripts/lint_copy.py
check: test test-app lint  ## PR 올리기 전 전부 (CI와 같음)
fixtures:  ## 엔진·상수·후보 변경 후 fixture·계약 샘플 재생성 → 전부 검사
	$(PY) scripts/gen_fixtures.py && $(MAKE) check
replay:    ## Virtual Console로 PT 6단계 주입 (adb forward tcp:8088 tcp:8088 먼저)
	bash scripts/replay.sh pt
