"""노고 시연 리플레이 — Virtual Console로 차량 신호를, adb로 앱 화면을 확인하며 시연 순서를 한 바퀴 돈다.

명세 v0.5 12절 '시연 순서'를 지금 앱(Design v2, 정차는 도착 때 한 번 묻기 #58)에 맞춘 순서다.
DECISIONS 11/8 '시연 순서' 참고. 휴게소 기어 P·D 단계는 지금 앱에서 화면 변화가 없어 뺐다.

  0 준비      콘솔: 기어 P · 운전석 문 닫힘 · 4석 착석 / 앱: 데모 고정 모드 켜기
  1 출발      앱: 출발하기 → 미리보기(태안 꽃지해수욕장 · 104km · 정체 38km) → [출발]
  2 주행      콘솔: 기어 D → 1초 안에 화면 가림(AC-C-1) → 기어 P → 홈 복귀
  3 도착      콘솔: 운전석 문 열기 → 계산 연출 → 영수증(2시간 10분 · 1.57배) + 제안
  4 선택      앱: [좋아요](방식 A) 또는 첫 항목(방식 B) → 기록 → [닫기] → 홈 최근 기록

사용
  python -I scripts/demo_replay.py            # 리허설: 단계마다 엔터, 앱은 사람이 누른다
  python -I scripts/demo_replay.py --auto     # 점검: 앱도 adb로 누르고 화면 글자를 확인(실패하면 종료 코드 1)
  python -I scripts/demo_replay.py --auto --reset   # 시작 전에 기록 초기화

필요한 것
  - 에뮬레이터(Pleos_Connect_v2.1.0)에 앱 설치(사용자 10), Virtual Console 페이지(http://127.0.0.1:8088/)가 열려 있어 bridge_ready
  - adb: 환경변수 ADB 또는 %LOCALAPPDATA%/Android/Sdk/platform-tools/adb.exe 또는 PATH
  - 날씨 서버가 없어도 된다(AC-C-3): 시연 날씨는 demo.json 값이고, 날씨 조회가 실패해도 '정보 없음'으로 진행한다.
    에뮬 네트워크를 끄면 콘솔도 끊기므로, 오프라인 점검은 서버 없는 빌드(기본 debug)로 돌린다.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid

VC = os.environ.get("VC", "http://127.0.0.1:8088")
PKG = "com.mist.pleos_app"
USER = "10"
FOCUS = (1700, 1290)  # 앱 창 아래 여백. uiautomator가 앱 창을 덤프하도록 초점을 준다(버튼 없는 자리)

# 화면에서 기대하는 글자(fixtures/effort/demo.json R1, strings.xml)
DEST = "태안 꽃지해수욕장"
EXPECT_PREVIEW = ["104km", "38km (경로의 36%)"]
EXPECT_DRIVING_HOME = f"{DEST}으로 가는 중이에요"
EXPECT_ARRIVE = f"{DEST}에 도착했어요"
EXPECT_RECEIPT = ["2시간 10분", "1.57배"]


# ------------------------------------------------------------ 콘솔
def vc_get(path: str) -> dict:
    with urllib.request.urlopen(VC + path, timeout=5) as r:
        return json.load(r)


def heartbeat_forever() -> None:
    """콘솔 페이지가 1초마다 보내는 POST /browser/session을 대신 보낸다. 브라우저 창이 숨거나 내비가 뜨면
    페이지 타이머가 느려져 emulator_inactive(503)로 떨어지는 것을 막는다(11/8 실측)."""
    cid = "demo-replay-" + uuid.uuid4().hex[:8]
    while True:
        try:
            urllib.request.urlopen(urllib.request.Request(f"{VC}/browser/session?client={cid}", method="POST"), timeout=3).read()
        except (urllib.error.URLError, OSError):
            pass
        time.sleep(1)


def vc_put(path: str) -> None:
    req = urllib.request.Request(VC + path, method="PUT")
    for attempt in range(10):                        # emulator_inactive면 하트비트가 살릴 때까지 최대 10초
        try:
            with urllib.request.urlopen(req, timeout=5) as r:
                r.read()
            print(f"    콘솔 PUT {path}")
            return
        except urllib.error.HTTPError as e:
            body = e.read()[:120]
            if e.code == 503 and attempt < 9:
                time.sleep(1)
                continue
            raise SystemExit(f"콘솔 PUT {path} 실패: {e.code} {body!r} — 콘솔 페이지를 다시 열고 bridge_ready를 확인하세요")


def gear(g: str) -> None: vc_put(f"/vehicle/gear?gear={g}")
def door(open_: bool) -> None: vc_put(f"/vehicle/body?door_fl={'true' if open_ else 'false'}")
def seat(area: str, on: bool) -> None: vc_put(f"/vehicle/seat?kind=occupied&area={area}&value={'true' if on else 'false'}")


def bridge_ready() -> bool:
    end = time.time() + 10
    while time.time() < end:
        if _bridge_once():
            return True
        time.sleep(1)
    return False


def _bridge_once() -> bool:
    try:
        d = vc_get("/vehicle/odometer")
        return bool(d.get("bridge_ready")) and bool(d.get("emulator_active", True))
    except (urllib.error.URLError, OSError, ValueError):
        return False


# ------------------------------------------------------------ adb
def adb_path() -> str:
    if os.environ.get("ADB"):
        return os.environ["ADB"]
    local = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe")
    if os.path.exists(local):
        return local
    return shutil.which("adb") or "adb"


ADB = adb_path()


def adb(*a: str) -> str:
    r = subprocess.run([ADB, *a], capture_output=True)
    return r.stdout.decode("utf-8", "replace")


def texts() -> list[tuple[str, int, int]]:
    """앱 창의 보이는 글자와 가운데 좌표."""
    adb("shell", "input", "tap", str(FOCUS[0]), str(FOCUS[1]))
    adb("shell", "uiautomator", "dump", "/sdcard/cg_ui.xml")
    x = adb("shell", "cat", "/sdcard/cg_ui.xml")
    out = []
    for m in re.finditer(r'<node [^>]*?text="([^"]*)"[^>]*?content-desc="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x):
        t, d, x1, y1, x2, y2 = m.groups()
        t = (t or d).replace("&#10;", "\n")
        if t:
            out.append((t, (int(x1) + int(x2)) // 2, (int(y1) + int(y2)) // 2))
    return out


def has(want: str, ts=None) -> bool:
    return any(want in t for t, _, _ in (ts if ts is not None else texts()))


def wait_for(want: str | list[str], timeout: float = 8.0, gone: bool = False) -> bool:
    wants = [want] if isinstance(want, str) else want
    end = time.time() + timeout
    while time.time() < end:
        ts = texts()
        ok = all(has(w, ts) for w in wants)
        if ok != gone:
            return True
        time.sleep(0.4)
    return False


def tap(want: str, exact: bool = True, timeout: float = 6.0, last: bool = False) -> bool:
    """글자가 같은(exact=False면 포함하는) 첫 요소를 누른다. last=True면 마지막 요소(시트 단추가 화면 글자와 겹칠 때)."""
    end = time.time() + timeout
    while time.time() < end:
        ts = texts()
        for t, x, y in (reversed(ts) if last else ts):
            if (t == want) if exact else (want in t):
                adb("shell", "input", "tap", str(x), str(y))
                print(f"    앱 누름 '{t}'")
                return True
        time.sleep(0.4)
    return False


def bring_app_front() -> None:
    adb("shell", "am", "start", "--user", USER, "-n", f"{PKG}/.MainActivity")
    time.sleep(2.5)


# ------------------------------------------------------------ 진행
class Run:
    def __init__(self, auto: bool):
        self.auto = auto
        self.results: list[tuple[str, bool, str]] = []

    def check(self, name: str, ok: bool, note: str = "") -> bool:
        self.results.append((name, ok, note))
        print(f"  {'✅' if ok else '❌'} {name}{(' — ' + note) if note else ''}")
        if not ok and self.auto:
            self.report()
            raise SystemExit(1)
        return ok

    def person(self, what: str) -> None:
        """리허설 모드: 발표자가 할 일을 보여 주고 엔터를 기다린다."""
        if not self.auto:
            input(f"  [발표자] {what}  ↵ ")

    def expect(self, name: str, want, timeout: float = 8.0) -> None:
        if self.auto:
            self.check(name, wait_for(want, timeout), "" if isinstance(want, str) else " · ".join(want))
        else:
            print(f"  기대: {want if isinstance(want, str) else ' · '.join(want)}")

    def report(self) -> None:
        ok = sum(1 for _, o, _ in self.results if o)
        print(f"\n결과: {ok}/{len(self.results)} 통과")
        for name, o, note in self.results:
            if not o:
                print(f"  실패: {name} {note}")


def step0(r: Run, reset: bool) -> None:
    print("\n0 준비")
    r.check("콘솔 bridge_ready", bridge_ready(), "아니면 http://127.0.0.1:8088/ 을 브라우저로 다시 여세요")
    gear("Park"); door(False)
    for a in ("driver", "passenger", "rear_left", "rear_right"):
        seat(a, True)
    if not r.auto:
        r.person("앱 홈에서 설정 → 데모 고정 모드 '켜기' → 뒤로" + (" (기록 초기화도)" if reset else ""))
        return
    bring_app_front()
    for _ in range(5):                                # 어느 화면에 있든 홈으로(지난 리허설이 남긴 영수증·시트·설정)
        ts = texts()
        if has("출발하기", ts) or has("가는 중이에요", ts):
            break
        if has("중간에 쉰 시간이 있었나요?", ts):
            tap("없음", last=True)
        elif has("닫기", ts):
            tap("닫기")
        elif has("뒤로", ts):
            tap("뒤로")
        time.sleep(0.8)
    if has("가는 중이에요") and tap("›"):            # 지난 리허설이 남긴 진행 중 출발은 그만두기(기록 안 남음)
        r.check("남은 진행 중 출발 그만두기", tap("그만두기"))
        time.sleep(0.8)
    r.check("홈 설정 단추", tap("설정"))
    time.sleep(0.8)
    if reset:
        r.check("기록 초기화", tap("기록 초기화") and tap("지우기")); time.sleep(0.6)
    on = [(t, x, y) for t, x, y in texts() if t == "켜기"]
    r.check("데모 고정 모드 켜기", bool(on))
    adb("shell", "input", "tap", str(on[0][1]), str(on[0][2]))
    time.sleep(0.5)
    tap("뒤로") or adb("shell", "input", "keyevent", "4")
    time.sleep(0.8)


def step1(r: Run) -> None:
    print("\n1 출발")
    if r.auto:
        if has(EXPECT_DRIVING_HOME):
            r.check("진행 중 출발 없음", False, "이전 시연이 진행 중이에요. 홈 '›' → 그만두기 뒤 다시 돌리세요")
        r.check("출발하기", tap("출발하기"))
    else:
        r.person("홈 [출발하기]")
    r.expect("출발 미리보기", [DEST] + EXPECT_PREVIEW)
    r.expect("시연용 값 표시(AC-C-7)", "시연용 값")
    if r.auto:
        r.check("[출발]", tap("출발"))
        time.sleep(2)
        bring_app_front()
    else:
        r.person("[출발] (순정 내비가 뜨면 앱으로 돌아오기)")
    r.expect("홈 진행 중", EXPECT_DRIVING_HOME)


def device_now() -> float:
    try:
        return float(adb("shell", "date", "+%s.%N").strip())
    except ValueError:
        return 0.0


def hide_latency(since: float) -> float | None:
    """콘솔 기어 D가 차에 들어간 시각 → 앱이 주행으로 바꾼 시각(logcat, 기기 시계 기준 초)."""
    log = adb("logcat", "-d", "-v", "epoch")
    set_at = hid_at = None
    for line in log.splitlines():
        m = re.match(r"\s*(\d+\.\d+)", line)
        if not m or float(m.group(1)) < since:
            continue
        t = float(m.group(1))
        if set_at is None and "VehicleApiDdsEmu" in line and "web-set" in line and "gear=Drive" in line:
            set_at = t
        if set_at is not None and hid_at is None and '"ev":"driving","on":true' in line:
            hid_at = t
    return None if set_at is None or hid_at is None else hid_at - set_at


def step2(r: Run) -> None:
    print("\n2 주행 (AC-C-1)")
    r.person("주행 시작 — 콘솔 기어 D를 넣어요")
    t0 = device_now()
    gear("Drive")
    if r.auto:
        hidden = wait_for(EXPECT_DRIVING_HOME, timeout=4, gone=True)
        r.check("기어 D → 화면 가림", hidden)
        dt = hide_latency(t0)
        r.check("1초 안에 가림(logcat: 기어 D → driving on)", dt is not None and dt <= 1.0, "로그 없음" if dt is None else f"{dt:.2f}초")
    else:
        print("  기대: 1초 안에 앱 화면 가림")
        r.person("가려진 것 확인")
    time.sleep(1.5)
    r.person("정차 — 콘솔 기어 P를 넣어요")
    gear("Park")
    r.expect("기어 P → 홈 복귀", EXPECT_DRIVING_HOME, timeout=6)


def step3(r: Run) -> None:
    print("\n3 도착")
    r.person("도착 — 콘솔 운전석 문을 열어요")
    door(True)
    r.expect("문 열림 → 도착 화면", EXPECT_ARRIVE, timeout=10)
    time.sleep(0.5)
    door(False)
    r.expect("영수증(계산 연출 뒤)", EXPECT_RECEIPT, timeout=15)
    # 계산 연출 뒤 '중간에 쉰 시간' 시트(F21). R1은 쉬지 않은 주행이라 '없음'
    r.expect("쉰 시간 질문", "중간에 쉰 시간이 있었나요?", timeout=8)
    if r.auto:
        r.check("쉰 시간 '없음'", tap("없음", last=True))   # 영수증 줄의 '없음'과 겹쳐 시트 단추(마지막)를 누른다
        time.sleep(1)
    else:
        r.person("쉰 시간 [없음]")


def step4(r: Run) -> None:
    print("\n4 선택")
    if not r.auto:
        r.person("[좋아요](A) 또는 항목(B) → [닫기]")
        r.expect("홈 최근 기록", DEST)
        return
    ts = texts()
    if has("좋아요", ts):
        r.check("방식 A [좋아요]", tap("좋아요"))
        r.expect("기록 남김", "오늘 기록에 남겼어요")
    else:
        item = next((t for t, _, _ in ts if t in ("커피", "밥", "공동 경비")), None)
        r.check("방식 B 항목", item is not None and tap(item), item or "항목 없음")
        r.expect("기록 남김", "오늘 기록에 남겼어요")
    r.check("[닫기]", tap("닫기"))
    r.expect("홈 최근 기록", DEST)
    r.check("진행 중 없음", not has(EXPECT_DRIVING_HOME))


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--auto", action="store_true", help="앱도 adb로 누르고 화면 글자를 확인한다")
    ap.add_argument("--reset", action="store_true", help="시작 전에 기록 초기화")
    a = ap.parse_args()
    threading.Thread(target=heartbeat_forever, daemon=True).start()
    r = Run(a.auto)
    t = time.time()
    step0(r, a.reset); step1(r); step2(r); step3(r); step4(r)
    if not a.auto:
        print(f"\n리허설 끝 ({time.time() - t:.0f}초)")
        return
    r.report()
    print(f"걸린 시간 {time.time() - t:.0f}초")
    if any(not o for _, o, _ in r.results):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
