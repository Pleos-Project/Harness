"""운전 노고 엔진(명세 v0.5 F21·F23~F25) — 서버 쪽 대조. 앱(Kotlin FixturesTest)도 같은 fixtures/effort 를 읽는다.

1부: 명세 AC·12절 표를 손으로 옮긴 값(엔진 출력과 독립된 기대값)
2부: fixture 전수 — input → 엔진 출력이 저장된 expected와 같은가
3부: 표기 불변식 — 여러 입력을 훑어 화면 문구가 명세 2절(노고 표기·금지 문구)을 지키는가
"""

import dataclasses
import itertools
import json
import re
import sys
from pathlib import Path

import pytest

from chagok import effort as EF
from chagok.cards import load_constants

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
import lint_copy  # noqa: E402

C = EF.effort_constants(load_constants())
EFFORT = sorted((ROOT / "fixtures/effort").glob("R*.json"), key=lambda p: int(p.stem[1:]))


def load(p):
    return json.loads(p.read_text(encoding="utf-8"))


def fx(fid):
    return load(ROOT / "fixtures/effort" / f"{fid}.json")


def run(fid, **override):
    inp = dict(fx(fid)["input"], **override)
    return EF.effort(EF.input_from_json(inp), C)


def simple(minutes, n=2, **kw):
    """10:00 출발, 쉰 시간 없음(답 0), 정체 비율 0, ETA = 운전 시간, 맑음·낮."""
    h, m = divmod(600 + minutes, 60)
    inp = {"departed_at": "2026-11-17T10:00:00+09:00", "arrived_at": f"2026-11-17T{h:02d}:{m:02d}:00+09:00",
           "rest_answer_min": 0, "stops": None, "eta_s": minutes * 60,
           "congestion_ratio": 0, "congestion_m": 0, "congestion_s": None,
           "weather_dep": "none", "weather_arr": "none", "sunrise": "07:13", "sunset": "17:20", "n": n,
           "distance_km": None}
    inp.update(kw)
    return EF.effort(EF.input_from_json(inp), C)


# ---------------------------------------------------------------- 1부: 명세 12절 표 (손 계산값)
# (T, 노고, 배수, 금액, 금액 문구, 한 사람 문구, B)
SPEC_R = {
    "R1": (130, 204.285, 1.57, 36_430.825, "약 3만 6천 원", "약 9천 원", "meal"),
    "R2": (180, 180.0, 1.00, 32_100, "약 3만 2천 원", "약 1만 6천 원", "meal"),
    "R3": (180, 187.5, 1.04, 33_437.5, "약 3만 3천 원", "약 1만 7천 원", "meal"),
    "R4": (150, 153.75, 1.03, 27_418.75, "약 2만 7천 원", "약 1만 4천 원", "meal"),
    "R5": (60, 74.7, 1.25, 13_321.5, "약 1만 3천 원", "약 4천 원", "meal"),
    "R6": (10, 10.0, 1.00, 1_783.3, "약 2천 원", "약 1천 원", None),
    "R7": (66, 67.62, 1.02, 12_058.9, "약 1만 2천 원", "약 6천 원", "coffee"),
}


@pytest.mark.parametrize("fid", sorted(SPEC_R, key=lambda s: int(s[1:])))
def test_spec_12_table(fid):
    t, eff, mult, krw, krw_text, per_text, b = SPEC_R[fid]
    r = run(fid)
    assert r.drive_min == t
    assert r.effort_min == pytest.approx(eff, abs=1e-9)
    assert r.multiplier == mult
    p = r.proposal
    assert p.amount_krw == pytest.approx(krw, abs=0.05)   # 표는 소수 첫째 자리까지(1,783.3)
    assert (p.amount_text, p.per_person_text, p.b.item if p.b else None) == (krw_text, per_text, b)


def test_r8_alone_receipt_only():
    """명세 3절: 나눠 낼 인원 1명이면 영수증만(제안 A·B 없음). 영수증은 R1과 같다."""
    r1, r8 = run("R1"), run("R8")
    assert r8.proposal is None
    assert dataclasses.replace(r8, proposal=r1.proposal) == r1


def test_ac_f23_1_r1_headline_and_lines():
    r = run("R1")
    assert r.headline_text == "오늘 운전 2시간 10분 · 평소보다 1.57배 힘든 길"
    assert [(l.label, l.pct_text) for l in r.lines] == [
        ("비", "+40%"), ("정체 구간", "+9%"), ("예상보다 더", "+5%"), ("밤길", "+2%"), ("장시간", "+1%")]
    assert [l.detail_text for l in r.lines] == [None, "12km (경로의 35%)", None, None, None]
    assert (r.congestion_min, r.delay_min, r.night_min, r.rain_min, r.fatigue_excess_min) == (45.5, 25, 50, 130, 10)
    assert r.distance_text == "104km"


def test_congestion_ratio_by_default_segments_when_listed():
    """열린 결정 28(효성): 정체 분 = 정체 비율 × T(기본). 구간 목록(congestion_s)이 있으면 min(통과 시간, T)."""
    assert run("R1").congestion_min == 0.35 * 130
    r14 = run("R14")                              # R1 + 구간 목록 40분 → 예전 R1 값
    assert (r14.congestion_min, r14.effort_min, r14.multiplier) == (40, 202.8, 1.56)
    assert r14.proposal.amount_krw == 36_166 and r14.lines[1].pct_text == "+8%"
    assert r14.lines[1].detail_text == "12km (경로의 35%)"
    none = run("R1", congestion_ratio=None, congestion_m=None)
    assert none.congestion_min == 0 and "정체 정보 없음" in none.notes
    assert run("R1", congestion_ratio=1.5).congestion_min == 130      # 비율이 1을 넘어도 T까지


@pytest.mark.parametrize("m,ratio,want", [
    (12_000, 0.35, "12km (경로의 35%)"), (12_499, 0.36, "12km (경로의 36%)"), (12_500, 0.04, "13km (경로의 4%)"),
    (400, 0.004, "1km 미만 (경로의 1% 미만)"), (None, 0.35, None), (12_000, None, None),
])
def test_congestion_detail_text(m, ratio, want):
    assert EF.congestion_text(m, ratio) == want


def test_ac_f24_1_r1_amount():
    p = run("R1").proposal
    assert p.amount_krw == 36_430.825 and p.amount_text == "약 3만 6천 원"
    assert p.per_person_krw == 9_107.70625 and p.per_person_text == "약 9천 원"


def test_ac_f24_screen_a_copy():
    """방식 A 화면(노고 PRD v1.3 5-2, 명세 v0.5 F24 출력). 방식 B로 넘어가는 문구는 엔진에 없다(AC-F24-4)."""
    a = run("R1").proposal.a
    assert dataclasses.asdict(a) == {
        "title_text": "오늘 운전을 챙긴다면",
        "main_text": "한 사람 약 9천 원",
        "basis_text": "운전 노고 약 3만 6천 원을 운전자 포함 4명이 나눈 값",
        "wage_text": "최저임금 1분 178원 기준",
    }
    tiny = simple(1).proposal.a                   # 어림이 0이면 '1천 원 미만'
    assert tiny.main_text == "한 사람 1천 원 미만"
    assert tiny.basis_text == "운전 노고 1천 원 미만을 운전자 포함 2명이 나눈 값"


def test_ac_f24_2_wage_constant_changes_amount_and_source():
    taxi = dataclasses.replace(C, wage_per_hour=12_000, wage_label="서울 택시 시간요금")
    r = EF.effort(EF.input_from_json(fx("R1")["input"]), taxi)
    assert r.proposal.a.wage_text == "서울 택시 시간요금 1분 200원 기준"
    assert r.proposal.amount_krw == pytest.approx(204.285 * 200)
    assert r.proposal.amount_text == "약 4만 1천 원"
    assert r.proposal.a.basis_text == "운전 노고 약 4만 1천 원을 운전자 포함 4명이 나눈 값"


def test_ac_f25_1_b_suggestion():
    assert run("R1").proposal.driver_share_krw == pytest.approx(27_323.1, abs=0.05)
    assert run("R1").proposal.b.item == "meal"
    p = simple(30).proposal                       # 30분 카풀, 2명
    assert p.amount_krw == pytest.approx(5_350) and p.driver_share_krw == pytest.approx(2_675)
    assert p.b.item == "coffee" and p.b.text == "오늘은 커피값에서 운전자 빼고 나누는 게 어때요"
    assert run("R1").proposal.b.text == "오늘은 밥값에서 운전자 빼고 나누는 게 어때요"
    p6 = run("R6").proposal
    assert p6.driver_share_krw == pytest.approx(891.67, abs=0.01) and p6.b is None


@pytest.mark.parametrize("share,want", [
    (2_349.99, None), (2_350, "coffee"), (7_715.49, "coffee"), (7_715.5, "meal"),
    (39_865.49, "meal"), (39_865.5, "shared"), (500_000, "shared"),
])
def test_f25_b_boundaries(share, want):
    """명세 9절 경계표: 2,350 미만 제안 없음 / ~7,715.5 미만 커피 / ~39,865.5 미만 밥 / 이상 공동 경비."""
    item = EF.b_item(share, C.items)
    assert (item.key if item else None) == want


# ---------------------------------------------------------------- 1부: F21 정차(기본 답·보조 정차 목록) · 지연 문턱
def test_ac_f21_1_answer_splits_in_half():
    """기본: 답 30분 → 경과 160 − 30 = T 130, 장시간 65 + 65(R9)."""
    r = run("R9")
    assert (r.method, r.elapsed_min, r.drive_min, r.fatigue_segments_min, r.fatigue_excess_min) == (
        "answer", 160, 130, [65, 65], 0)
    assert run("R9", rest_answer_min=0).fatigue_excess_min == 40      # 쉬지 않았으면 160분 한 구간


def test_ac_f21_2_long_stop_splits_fatigue():
    r = run("R2")
    assert (r.drive_min, r.rest_min, r.fatigue_segments_min, r.fatigue_excess_min) == (180, 20, [120, 60], 0)
    assert r.multiplier_text == "평소와 같은 길" and r.lines == []


def test_ac_f21_3_short_stop_keeps_segment():
    r = run("R4")
    assert (r.drive_min, r.rest_min, r.fatigue_segments_min) == (150, 10, [150])
    assert r.fatigue_excess_min == 30 and r.effort_min == 153.75


def test_reset_boundary_is_inclusive():
    """15분 '이상'이면 나눈다(R12), 14분이면 이어진다."""
    assert run("R12").fatigue_segments_min == [120, 120]
    r = run("R12", stops=[{"start": "2026-11-18T00:00:00+09:00", "end": "2026-11-18T00:14:00+09:00"}])
    assert r.fatigue_segments_min == [241]


def test_stops_are_clipped_and_merged():
    base = "2026-11-17T"
    # 출발 전부터 이어진 정차·겹치는 정차·맞닿는 정차(10 + 10 = 20분 → 나눔)·도착 뒤 정차
    stops = [(f"{base}09:50:00+09:00", f"{base}10:05:00+09:00"),
             (f"{base}12:00:00+09:00", f"{base}12:10:00+09:00"),
             (f"{base}12:10:00+09:00", f"{base}12:20:00+09:00"),
             (f"{base}12:05:00+09:00", f"{base}12:08:00+09:00"),
             (f"{base}13:30:00+09:00", f"{base}14:00:00+09:00")]
    r = run("R2", stops=[{"start": s, "end": e} for s, e in stops])
    # [10:05, 12:00] 115분 + [12:20, 13:20] 60분
    assert (r.drive_min, r.rest_min, r.fatigue_segments_min) == (175, 25, [115, 60])


def test_answer_q_splits_in_half_only_from_15():
    assert run("R13").fatigue_segments_min == [150, 150] and run("R13").fatigue_excess_min == 60
    r0 = run("R13", rest_answer_min=0)           # 6시간 쉬지 않음 → 한 구간 360
    assert (r0.drive_min, r0.fatigue_segments_min, r0.fatigue_excess_min) == (360, [360], 240)
    r15 = run("R13", rest_answer_min=15)
    assert r15.fatigue_segments_min == [172.5, 172.5]


def test_stops_win_when_both_given():
    """앱은 둘 중 하나만 채운다. 둘 다 오면 쉰 지점을 아는 정차 목록으로 계산한다."""
    r = run("R2", rest_answer_min=60)
    assert (r.method, r.drive_min, r.fatigue_segments_min) == ("stops", 180, [120, 60])


def test_answer_night_is_prorated():
    r = run("R10")
    assert r.night_min == pytest.approx(130 * 150 / 180)
    assert r.notes == ["날씨 정보 없음", "정체 정보 없음"]


def test_ac_r7_delay_threshold_is_exclusive():
    """D < ETA의 10%면 0. R7은 6분 = 정확히 10%라 들어간다. 5분이면 0."""
    assert run("R7").delay_min == 6
    assert simple(65, eta_s=3600).delay_min == 0
    assert simple(66, eta_s=3600).delay_min == 6


def test_night_before_sunrise_and_across_midnight():
    assert run("R11").night_min == 103             # 05:30~07:13
    assert run("R12").night_min == 240             # 22:00~02:15, 정차 15분 빼고


def test_congestion_capped_by_drive_time():
    assert simple(30, congestion_s=3_000).congestion_min == 30


def test_weather_halves():
    r = run("R5")
    assert (r.rain_min, r.snow_min) == (0, 30)
    r2 = run("R5", weather_dep="rain")
    assert (r2.rain_min, r2.snow_min) == (30, 30)
    assert [l.key for l in r2.lines] == ["rain", "snow"]


# ---------------------------------------------------------------- 1부: 일출·일몰 (명세 7절 '검증')
SUN = load(ROOT / "fixtures/effort/sun.json")


def test_sun_matches_kasi_within_one_minute():
    assert len(SUN["cases"]) >= 2, "천문연 대조 날짜가 2개 이상 있어야 한다(#52)"
    for c in SUN["cases"]:
        for k in ("sunrise", "sunset"):
            got, kasi = EF.parse_hhmm(c["expected"][k]), EF.parse_hhmm(c["kasi"][k])
            assert abs(got - kasi) <= 1, f"{c['date']} {k}: 엔진 {c['expected'][k]} vs 천문연 {c['kasi'][k]}"


# ---------------------------------------------------------------- 2부: fixture 전수
def test_effort_fixtures_are_contiguous():
    assert [p.stem for p in EFFORT] == [f"R{i}" for i in range(1, len(EFFORT) + 1)]
    assert len(EFFORT) >= 8


@pytest.mark.parametrize("p", EFFORT, ids=lambda p: p.stem)
def test_effort_fixture(p):
    f = load(p)
    assert EF.effort(EF.input_from_json(f["input"]), C).to_json() == f["expected"]


def test_sun_fixture():
    for c in SUN["cases"]:
        rise, sset = EF.sun_times(SUN["lat"], SUN["lng"], c["date"], SUN["tz_offset_min"])
        assert {"sunrise": EF.hhmm(rise), "sunset": EF.hhmm(sset)} == c["expected"], c["date"]


# ---------------------------------------------------------------- 3부: 표기 불변식 (명세 2절)
MULT = re.compile(r"^(평소와 같은 길|평소보다 \d+\.\d\d배 힘든 길)$")
PCT = re.compile(r"^(\+[1-9]\d*%|\+1% 미만)$")
KRW = re.compile(r"^(약 (\d+만)?( ?\d천)? 원|1천 원 미만)$")
DETAIL = re.compile(r"^(\d+km|1km 미만) \(경로의 (\d+%|1% 미만)\)$")
DRIVE = re.compile(r"^(\d+시간( \d+분)?|\d+분|1분 미만)$")


def receipts():
    weathers = ["none", "rain", "snow", "unknown"]
    # 정체: (비율, km, 구간 통과 시간) — 정보 없음 / 정체 없음 / 비율만 / 비율 + 구간 목록 / 구간 목록만
    congestions = [(None, None, None), (0, 0, None), (0.36, 15_000, None), (0.36, 15_000, 900), (None, None, 99_999)]
    for minutes, wd, wa, n, (ratio, cm, cs) in itertools.product(
            [1, 7, 30, 66, 119, 121, 185, 301], weathers, weathers, [1, 2, 3, 4, 5], congestions):
        yield simple(minutes, n=n, weather_dep=wd, weather_arr=wa, congestion_ratio=ratio, congestion_m=cm,
                     congestion_s=cs, eta_s=minutes * 50)
    for p in EFFORT:
        yield EF.effort(EF.input_from_json(load(p)["input"]), C)


def texts(r):
    yield r.drive_text
    yield r.multiplier_text
    yield r.headline_text
    yield from r.notes
    yield from (l.pct_text for l in r.lines)
    yield from (l.label for l in r.lines)
    yield from (l.detail_text for l in r.lines if l.detail_text)
    if r.proposal:
        p = r.proposal
        yield from (p.amount_text, p.per_person_text)
        yield from dataclasses.asdict(p.a).values()
        if p.b:
            yield p.b.text


def test_copy_rules_hold_everywhere():
    for r in receipts():
        assert DRIVE.match(r.drive_text), r.drive_text
        assert MULT.match(r.multiplier_text), r.multiplier_text
        assert r.headline_text == f"오늘 운전 {r.drive_text} · {r.multiplier_text}"
        assert (r.multiplier == 1.0) == (r.multiplier_text == "평소와 같은 길")
        for l in r.lines:
            assert PCT.match(l.pct_text) and l.add_min > 0, l
            assert l.detail_text is None or (l.key == "congestion" and DETAIL.match(l.detail_text)), l
        if r.proposal:
            assert KRW.match(r.proposal.amount_text) and KRW.match(r.proposal.per_person_text), r.proposal
            a = r.proposal.a
            assert a.title_text == "오늘 운전을 챙긴다면"
            assert a.main_text == f"한 사람 {r.proposal.per_person_text}"
            assert a.basis_text.startswith(f"운전 노고 {r.proposal.amount_text}을 운전자 포함 ")
        for s in texts(r):
            for rx, msg in lint_copy.FORBIDDEN + lint_copy.COPY_RULES:
                assert not rx.search(s), f"{msg}: {s}"


def test_per_person_divides_unrounded_total():
    """명세 2절: 1인 몫·운전자 몫은 어림하기 전 총액에서 나눈다(R1: 36,166 ÷ 4 = 9,041.5)."""
    for n in (2, 3, 4, 5):
        p = simple(185, n=n).proposal
        assert p.per_person_krw == p.amount_krw / n
        assert p.driver_share_krw == p.amount_krw * (n - 1) / n


def test_lines_sum_to_effort():
    for r in receipts():
        assert r.effort_min == pytest.approx(r.drive_min + sum(l.add_min for l in r.lines), abs=1e-9)
