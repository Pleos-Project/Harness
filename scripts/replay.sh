#!/usr/bin/env bash
# Virtual Console REST로 fixture 상태를 주입한다. 리허설·테스트 공용. 손으로 치지 않는다.
#   adb forward tcp:8088 tcp:8088 가 먼저 되어 있어야 한다.
#   사용: scripts/replay.sh D1 | D2 | D3 | D5 <odo> | D6 | D7 | D8 | D9 | X1 | pt
#         pt = PT 6단계 전부(단계 사이 엔터로 진행), X1 = 주행 전환 숨김·복귀(AC-C-1·AC-X1-1)
# 기어는 PUT /vehicle/gear?gear=Park|Drive (H_Mate 실측: Drive면 속도 0이어도 주행 규제가 켜짐).
# 착석·문·충전 스위치는 콘솔 API 경로 미확인 → 안내만 찍고 콘솔 UI에서 조작한다(June 확인 중).
set -euo pipefail
VC="${VC:-http://127.0.0.1:8088}"

put() { curl -fsS -o /dev/null -w "  PUT %{url_effective} → %{http_code}\n" -X PUT "$VC$1"; }
manual() { echo "  [콘솔 UI] $1"; }
pause() { read -r -p "  ↵ 다음 단계 " _; }

soc()   { put "/vehicle/ev-battery?field=soc&value=$1"; }
cap()   { put "/vehicle/ev-battery?field=capacity&value=$1"; }     # kWh로 넣으면 속성은 Wh로 읽힘
range() { put "/vehicle/odometer?remaining_range_m=$1"; }
odo()   { put "/vehicle/odometer?traveled_distance_km=$1"; }        # 올리기만 가능
gear()  { put "/vehicle/gear?gear=$1"; }                            # Park | Drive

case "${1:-}" in
  D1) echo "D1 기본";             cap 77.4; soc 80; range 245000 ;;
  D2) echo "D2 잔량 하락";        soc 35;  range 100000; echo "  (앱: 1초 디바운스 후 재호출 1건)" ;;
  D3) echo "D3 동승";             manual "착석 4" ;;
  D5) echo "D5 정산";             echo "  현재 odometer:"; curl -fsS "$VC/vehicle/odometer"; echo; odo "${2:?현재값+132 를 넘기세요}" ;;
  D6) echo "D6 충전 직후";        cap 77.4; range 245000; soc 50; manual "충전 스위치 ON"; soc 80; manual "충전 스위치 OFF" ;;
  D7) echo "D7 조립식 fallback";  range 0 ;;
  D8) echo "D8 용량 60kWh";       cap 60; echo "  (앱: 홈에 다시 들어가야 바뀜, AC-F1-5)" ;;
  D9) echo "D9 잔고 40km";        soc 12; range 40000 ;;
  X1) echo "X1 주행 전환";        gear Drive; echo "  기대: 1초 안에 화면 가림"; pause
                                  gear Park;  echo "  기대: 직전 화면·같은 카드로 복귀" ;;
  pt)
    # 0단계에서 PT 전체의 기준 상태를 먼저 맞춘다. 1단계 충전 OFF 때 바로 한 장이 뽑히고 세션에 고정되므로,
    # 주행가능거리가 그 전에 D1 값(245km)이어야 2단계 앞면이 '양평 두물머리'가 된다
    # (지난 리허설의 3단계 값 100km가 남아 있으면 다른 곳이 뽑힌다. D1 범위 200~280km 밖이어도 마찬가지).
    echo "0단계: 기준 상태"
    manual "앱 설정 → 기록 초기화(S1), 데모 고정 모드 켬"
    cap 77.4; range 245000; soc 50
    echo "  현재 odometer(이 값 기준으로 정산·도착이 계산됨):"; curl -fsS "$VC/vehicle/odometer"; echo
    gear Park; manual "착석 1, 운전석 문 닫힘 확인"; pause
    echo "1단계: 충전 직후";     manual "충전 ON"; soc 80; manual "충전 OFF  → '방금 약 7천 원어치 충전' → 한 장"; pause
    echo "2단계: 한 장 공개";    echo "  기대: 양평 두물머리 · 42분 · 추가 충전 없이 · 여유 50km"; pause
    echo "3단계: 배지 변화";     soc 35; range 100000; echo "  1초 뒤 같은 카드, 배지만 '+1만 원 안팎'"; pause
    echo "4단계: 1인 몫";        manual "착석 4 → '1인 2천 원 안팎'"; pause
    echo "5단계: 출발";          manual "앱에서 출발 1탭 → 순정 내비"; pause
    echo "6단계: 도착";          gear Park; manual "운전석 문 열기 → 핀 + '이번 달 이동비 약 2천 원 · 다녀온 곳 1곳'" ;;
  *) echo "usage: $0 {D1|D2|D3|D5 <odo>|D6|D7|D8|D9|X1|pt}"; exit 1 ;;
esac
