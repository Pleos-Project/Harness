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
           "rest_answer_min": 0, "eta_s": minutes * 60,
           "congestion_ratio": 0, "congestion_m": 0, "congestion_s": None,
           "weather_dep": "none", "weather_arr": "none", "sunrise": "07:13", "sunset": "17:20", "n": n,
           "distance_km": None}
    inp.update(kw)
    return EF.effort(EF.input_from_json(inp), C)


# ---------------------------------------------------------------- 1부: 명세 12절 표 (손 계산값)
# (T, 노고, 배수, 금액, 금액 문구, 한 사람 문구, B)
SPEC_R = {
    "R1": (130, 204.636, 1.57, 36_493.42, "약 3만 6천 원", "약 9천 원", "meal"),
    "R3": (180, 187.5, 1.04, 33_437.5, "약 3만 3천 원", "약 1만 7천 원", "meal"),
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
        ("날씨", "+40%"), ("정체 구간", "+10%"), ("예상보다 더", "+5%"), ("밤길", "+2%"), ("장시간", "+1%")]
    # 줄 값 = 노고 PRD v1.3 5-1 영수증 예시 그대로
    assert [l.detail_text for l in r.lines] == [
        "비, 주행 내내", "38km (경로의 36%)", "+25분", "50분", "2시간 넘긴 10분"]
    assert r.footnote_text == "정체 구간은 내비 안내 경로 기준"
    assert run("R3").footnote_text is None                 # 정체 줄이 없으면 주석도 없음
    assert (r.congestion_min, r.delay_min, r.night_min, r.rain_min, r.fatigue_excess_min) == (46.8, 25, 50, 130, 10)
    assert r.distance_text == "104km"


def test_congestion_ratio_by_default_segments_when_listed():
    """열린 결정 28(효성): 정체 분 = 정체 비율 × T(기본). 구간 목록(congestion_s)이 있으면 min(통과 시간, T)."""
    assert run("R1").congestion_min == 0.36 * 130
    r14 = run("R14")                              # R1 + 구간 목록 40분 → 예전 R1 값
    assert (r14.congestion_min, r14.effort_min, r14.multiplier) == (40, 202.8, 1.56)
    assert r14.proposal.amount_krw == 36_166 and r14.lines[1].pct_text == "+8%"
    assert r14.lines[1].detail_text == "38km (경로의 36%)"
    none = run("R1", congestion_ratio=None, congestion_m=None)
    assert none.congestion_min == 0 and "정체 정보 없음" in none.notes
    assert run("R1", congestion_ratio=1.5).congestion_min == 130      # 비율이 1을 넘어도 T까지


@pytest.mark.parametrize("m,ratio,want", [
    (12_000, 0.35, "12km (경로의 35%)"), (12_499, 0.36, "12km (경로의 36%)"), (12_500, 0.04, "13km (경로의 4%)"),
    (400, 0.004, "1km 미만 (경로의 1% 미만)"), (None, 0.35, None), (12_000, None, None),
])
def test_congestion_detail_text(m, ratio, want):
    assert EF.congestion_text(m, ratio, C) == want


@pytest.mark.parametrize("km,want", [(0.4, "1km 미만"), (0.49, "1km 미만"), (0.5, "1km"), (104.3, "104km"), (None, None)])
def test_distance_text_never_zero(km, want):
    """거리 표시도 어림이 0이면 '1km 미만'(명세 2절, June 리뷰 22:18). R6가 두 엔진에서 같은 경우를 덮는다."""
    assert simple(10, distance_km=km).distance_text == want
    assert run("R6").distance_text == "1km 미만"


BASIS_R1 = {
    "rain": "비 오는 날은 맑은 날보다 운전 부담이 40% 커요 · 시뮬레이터 30명 연구(Frontiers in Neuroscience 2026)",
    "congestion": "차가 몰린 길은 한산한 길보다 운전 부담이 27% 커요 · 시뮬레이터 연구(Shakouri, LSU 2015)",
    "delay": "예상보다 늦어진 시간은 정체와 같은 값으로 쳐요 · 예상 못 한 지연이 더 힘들다는 통근 연구(Wener·Evans 2011)",
    "night": "밤에는 낮보다 운전 부담이 4% 커요 · 트럭 운전자 50명 시뮬레이터 연구(Fitri 2025)",
    "fatigue": "2시간을 넘겨 이어서 운전한 시간에 더해요 · 2시간 운전하면 15분 쉬도록 한 법령 기준(화물자동차 운수사업법 시행규칙)",
}


def test_ac_f23_4_basis_line_per_shown_line():
    """줄을 탭하면 근거 한 줄(AC-F23-4). 보이는 줄마다 있고, 숫자는 rates_pct에서 채운다."""
    assert {l.key: l.basis_text for l in run("R1").lines} == BASIS_R1
    assert run("R5").lines[0].basis_text == (
        "눈 오는 날은 맑은 날보다 운전 부담이 49% 커요 · 시뮬레이터 30명 연구(Frontiers in Neuroscience 2026)")


def test_basis_follows_rate_constant():
    """가산율을 바꾸면 근거 문구의 숫자도 같이 바뀐다(상수 하나만 고치면 되게)."""
    c2 = dataclasses.replace(C, rates_pct=dict(C.rates_pct, rain=45))
    assert "운전 부담이 45% 커요" in EF.basis_text("rain", c2)
    assert EF.basis_text("distance", C) is None          # 거리는 표시만, 근거 없음


def _strings(obj):
    if isinstance(obj, str):
        yield obj
    elif isinstance(obj, dict):
        for v in obj.values():
            yield from _strings(v)
    elif isinstance(obj, list):
        for v in obj:
            yield from _strings(v)


def test_all_copy_in_constants_passes_copy_rules():
    """화면 문구는 전부 constants effort.copy·items에 있다(명준 #60). fixture에 안 나오는 것까지 금지 문구·표기 규칙을 지킨다."""
    cp = load(ROOT / "fixtures/constants.json")["effort"]["copy"]
    assert set(C.copy.basis) == set(EF.LINE_ORDER) and set(C.copy.line_labels) == set(EF.LINE_ORDER)
    texts_ = list(_strings(cp)) + [it.phrase_text for it in C.items] + [x for it in C.items for x in it.examples]
    for s in texts_:
        for rx, msg in lint_copy.FORBIDDEN + lint_copy.COPY_RULES:
            assert not rx.search(s), f"{msg}: {s}"
    for k in C.copy.basis:
        assert "{" not in EF.basis_text(k, C)


def test_copy_lives_only_in_constants():
    """문구 블록만 바꾸면 화면 문구가 바뀐다(엔진 코드에 문구가 없다). 디자인 확정본을 넣을 때 이 블록만 고치면 된다."""
    t = dict(C.copy.text, headline_text="[{drive}|{multiplier}]", a_title_text="A", b_text="B:{phrase}",
             footnote_congestion_text="F", weather_unknown_text="W")
    cp = dataclasses.replace(C.copy, text=t, line_labels={k: k.upper() for k in EF.LINE_ORDER},
                             line_detail=dict(C.copy.line_detail, delay_text="D{time}"))
    r = EF.effort(EF.input_from_json(fx("R1")["input"]), dataclasses.replace(C, copy=cp))
    assert r.headline_text == "[2시간 10분|평소보다 1.57배 힘든 길]"
    assert [l.label for l in r.lines] == ["RAIN", "CONGESTION", "DELAY", "NIGHT", "FATIGUE"]
    assert r.lines[2].detail_text == "D25분" and r.footnote_text == "F"
    assert (r.proposal.a.title_text, r.proposal.b.text) == ("A", "B:밥값")


def test_ac_f24_1_r1_amount():
    p = run("R1").proposal
    assert p.amount_krw == pytest.approx(36_493.42, abs=0.005) and p.amount_text == "약 3만 6천 원"
    assert p.per_person_krw == pytest.approx(9_123.355) and p.per_person_text == "약 9천 원"   # 명세 표기 9,123.36


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
    assert r.proposal.amount_krw == pytest.approx(204.636 * 200)
    assert r.proposal.amount_text == "약 4만 1천 원"
    assert r.proposal.a.basis_text == "운전 노고 약 4만 1천 원을 운전자 포함 4명이 나눈 값"


def test_ac_f25_1_b_suggestion():
    assert run("R1").proposal.driver_share_krw == pytest.approx(27_370.065)                     # 명세 표기 27,370.07
    assert run("R1").proposal.b.item == "meal"
    p = simple(30).proposal                       # 30분 카풀, 2명
    assert p.amount_krw == pytest.approx(5_350) and p.driver_share_krw == pytest.approx(2_675)
    assert p.b.item == "coffee" and p.b.text == "오늘은 커피값에서 운전자 빼고 나누는 게 어때요"
    assert run("R1").proposal.b.text == "오늘은 밥값에서 운전자 빼고 나누는 게 어때요"
    p6 = run("R6").proposal
    assert p6.driver_share_krw == pytest.approx(891.67, abs=0.01) and p6.b is None


def test_f25_shared_phrase_and_examples():
    """공동 경비는 '공동 경비에서'('값' 없음, 명준 결정), 그 자리 예시 목록을 함께 낸다. 커피·밥은 빈 목록."""
    b = run("R15").proposal.b
    assert b.item == "shared" and b.text == "오늘은 공동 경비에서 운전자 빼고 나누는 게 어때요"
    assert b.item_examples == ["공동 경비", "장 본 값", "펜션비", "바베큐장 이용료", "입장료", "물품 구매비"]
    assert b.item_examples[0] in b.text                 # 첫 값 = 문장 안의 말(화면은 이 자리를 바꿔 보여 줌)
    assert run("R1").proposal.b.item_examples == [] and simple(30).proposal.b.item_examples == []
    assert "장보기" not in json.dumps(dataclasses.asdict(run("R15")), ensure_ascii=False)


@pytest.mark.parametrize("share,want", [
    (2_349.99, None), (2_350, "coffee"), (7_705.49, "coffee"), (7_705.5, "meal"),
    (39_855.49, "meal"), (39_855.5, "shared"), (500_000, "shared"),
])
def test_f25_b_boundaries(share, want):
    """명세 9절 경계표(밥 10,711원, DECISIONS 11/9): 2,350 미만 제안 없음 / ~7,705.5 미만 커피 / ~39,855.5 미만 밥 / 이상 공동 경비."""
    item = EF.b_item(share, C.items)
    assert (item.key if item else None) == want


# ---------------------------------------------------------------- 1부: F21 정차(도착 때 답 하나, #62) · 지연 문턱
def test_ac_f21_1_answer_splits_in_half():
    """답 30분 → 경과 160 − 30 = T 130, 장시간 65 + 65(R9)."""
    r = run("R9")
    assert (r.elapsed_min, r.drive_min, r.rest_min, r.fatigue_segments_min, r.fatigue_excess_min) == (
        160, 130, 30, [65, 65], 0)
    assert run("R9", rest_answer_min=0).fatigue_excess_min == 40      # 쉬지 않았으면 160분 한 구간


def test_reset_boundary_is_inclusive():
    """답이 15분 '이상'이면 가운데에서 나눈다(R12), 없음(0)이면 한 구간."""
    assert run("R12").fatigue_segments_min == [120, 120]
    assert run("R12", rest_answer_min=0).fatigue_segments_min == [255]


def test_engine_takes_answer_only():
    """#62: 엔진 입력은 쉰 시간 답 하나. 자동으로 잰 정차 목록(stops)은 받지 않고, JSON에 남아 있어도 무시한다."""
    assert "stops" not in {f.name for f in dataclasses.fields(EF.EffortInput)}
    assert "method" not in dataclasses.asdict(run("R9"))
    stale = dict(fx("R9")["input"], stops=[{"start": "2026-11-17T11:00:00+09:00", "end": "2026-11-17T11:30:00+09:00"}])
    assert EF.effort(EF.input_from_json(stale), C) == run("R9")
    assert EF.input_from_json(dict(fx("R9")["input"], rest_answer_min=None)).rest_answer_min == 0


def test_answer_q_splits_in_half_only_from_15():
    assert run("R13").fatigue_segments_min == [150, 150] and run("R13").fatigue_excess_min == 60
    r0 = run("R13", rest_answer_min=0)           # 6시간 쉬지 않음 → 한 구간 360
    assert (r0.drive_min, r0.fatigue_segments_min, r0.fatigue_excess_min) == (360, [360], 240)
    r15 = run("R13", rest_answer_min=15)
    assert r15.fatigue_segments_min == [172.5, 172.5]


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
    # 한쪽만 비·눈이면 임시로 가장 단순하게 "비"·"눈"(PRD 5-1 예시에 없음, 디자인 확정 대기)
    assert [(l.label, l.detail_text) for l in r2.lines] == [("날씨", "비"), ("날씨", "눈")]
    assert r.lines[0].detail_text == "눈"
    assert run("R5", weather_dep="snow").lines[0].detail_text == "눈, 주행 내내"


@pytest.mark.parametrize("fid,key,override,want", [
    # 60분 이상은 ○시간 ○분. ETA 65 → 도착 상한 max(130, 125) = 130분 안(#68)
    ("R7", "delay", {"arrived_at": "2026-11-17T12:10:00+09:00", "eta_s": 3900}, "+1시간 5분"),
    ("R10", "night", {}, "1시간 48분"),          # 답 + 밤길 비례 108.3분 → 반올림
    ("R12", "night", {}, "4시간"),
    ("R13", "fatigue", {}, "2시간 넘긴 1시간"),   # 구간 150 + 150 → 넘긴 30 + 30을 합침
])
def test_line_detail_time_format(fid, key, override, want):
    line = next(l for l in run(fid, **override).lines if l.key == key)
    assert line.detail_text == want


# ---------------------------------------------------------------- 1부: 일출·일몰 (명세 7절 '검증')
SUN = load(ROOT / "fixtures/effort/sun.json")


def test_sun_matches_kasi_within_one_minute():
    assert len(SUN["cases"]) >= 2, "천문연 대조 날짜가 2개 이상 있어야 한다(#52)"
    for c in SUN["cases"]:
        for k in ("sunrise", "sunset"):
            got, kasi = EF.parse_hhmm(c["expected"][k]), EF.parse_hhmm(c["kasi"][k])
            assert abs(got - kasi) <= 1, f"{c['date']} {k}: 엔진 {c['expected'][k]} vs 천문연 {c['kasi'][k]}"


# ---------------------------------------------------------------- 1부: 도착 상한 (#68)
@pytest.mark.parametrize("fid,status,elapsed,limit", [
    ("R16", "ok", 210, 210), ("R17", "unsettled", 211, 210),     # ETA 105: max(210, 165)
    ("R18", "ok", 70, 70), ("R19", "unsettled", 71, 70),         # ETA 10: max(20, 70)
    ("R20", "ok", 720, 720), ("R21", "unsettled", 721, 720),     # ETA 400: max(800, 460)보다 12시간이 먼저
])
def test_arrival_cap_boundaries(fid, status, elapsed, limit):
    """경과가 상한과 같으면 영수증, 넘으면 정리하지 못함(#68 효성 결정)."""
    r = run(fid)
    assert (r.status, r.elapsed_min, r.elapsed_limit_min) == (status, elapsed, limit)
    assert (r.status_text is None) == (status == "ok")


def test_unsettled_skips_receipt():
    """정리하지 못함이면 금액·줄을 계산하지 않고, headline_text에도 안내 문구(effort.copy 임시)를 넣는다."""
    r = run("R17")
    assert r.status_text == r.headline_text == C.copy.text["unsettled_text"] == "이번 주행은 정리하지 못했어요"
    assert (r.lines, r.notes, r.proposal, r.footnote_text, r.distance_text) == ([], [], None, None, None)
    assert (r.drive_min, r.effort_min, r.multiplier, r.drive_text, r.multiplier_text) == (0, 0, 0, "", "")


@pytest.mark.parametrize("eta_min,limit_min", [(105, 210), (10, 70), (60, 120), (30, 90), (400, 720), (360, 720),
                                               (0, 720)])
def test_elapsed_limit(eta_min, limit_min):
    """상한 = min(max(ETA × 2, ETA + 60), 720). ETA를 모르면(0) 12시간만."""
    assert EF.elapsed_limit_s(eta_min * 60, C) == limit_min * 60


def test_arrival_cap_counts_rest_too():
    """경과는 도착 − 출발 그대로(쉰 시간을 빼지 않음). 1시간 쉬었다고 답해도 211분이면 정리하지 못함."""
    assert run("R17", rest_answer_min=60).status == "unsettled"
    assert run("R16", rest_answer_min=60).status == "ok"


def test_arrival_cap_without_eta():
    """ETA를 모르면(0) 12시간만 본다."""
    assert simple(700, eta_s=0).status == "ok"
    assert run("R21", eta_s=0).status == "unsettled"


# ---------------------------------------------------------------- 1부: 시간대별 강수 (#63, GET /v1/weather/trip)
def test_hourly_rain_stopped_midway():
    """출발 때 오다 그친 비: 시간대별이면 실제 겹친 40분만, 1회 관측 절반 규칙이면 72.5분(R22)."""
    assert run("R22").rain_min == 40
    assert run("R22", weather_trip=None).rain_min == 72.5


def test_hourly_rain_only_in_middle_scaled_by_rest():
    """출발·도착 맑음, 중간에만 비: 벽시계 120분 × T/경과(210/240) = 105분(R23). 1회 관측으로는 0."""
    r = run("R23")
    assert r.rain_min == 120 * 210 / 240 and r.lines[0].detail_text == "비"
    assert run("R23", weather_trip=None).rain_min == 0


def test_hourly_partial_unknown():
    """unknown 조각은 가산 0이고, 겹친 조각에 unknown이 있으면 임시 안내(effort.copy.weather_partial_text)."""
    r = run("R24")
    assert (r.rain_min, r.snow_min) == (0, 40)
    assert r.notes == [C.copy.text["weather_partial_text"]]


def test_hourly_middle_tie_goes_to_departure():
    """조각 가운데 = 경과 가운데면 출발 지점(R25). 출발을 1분 당기면 가운데가 앞으로 와서 도착 지점(맑음)."""
    r = run("R25")
    assert r.rain_min == 60 and r.lines[0].detail_text == "비, 주행 내내"
    early = run("R25", departed_at="2026-11-17T15:59:00+09:00")
    assert early.rain_min == 0 and early.lines == [] and early.notes == []


def test_hourly_all_unknown_falls_back():
    """겹친 조각이 모두 unknown이면 1회 관측 절반 규칙(R26 = R22에서 시간대별 입력을 뺀 것과 같음)."""
    assert run("R26") == run("R22", weather_trip=None)
    assert run("R26").notes == []          # 1회 관측은 출발 비·도착 맑음으로 판정됨


def test_weather_trip_input_matches_contract_response():
    """앱은 GET /v1/weather/trip 응답을 그대로 넘겨도 된다(status는 엔진이 쓰지 않음). 없으면 지금 규칙."""
    sample = load(ROOT / "contract/samples/weather.trip.ok.response.json")
    wt = EF.weather_trip_from_json(sample)
    assert [h.precip for h in wt.dep] == [h["precip"] for h in sample["dep"]] and len(wt.dep) == len(wt.arr)
    no_status = dict(fx("R22")["input"]["weather_trip"])
    no_status.pop("status")
    assert run("R22", weather_trip=no_status) == run("R22")
    assert "weather_trip" not in fx("R1")["input"] and "weather_trip" not in DEMO_INPUT_KEYS


DEMO_INPUT_KEYS = set(load(ROOT / "fixtures/effort/demo.json")["input"])


# ---------------------------------------------------------------- 1부: 중간 정산 (명준 Q-08-02, 효성 계산 규칙 11/9)
def midway_input(now="17:05", so_far_m=52_000, **kw):
    """R1 장면을 가는 중에 정산(시간대별 강수 없이 1회 관측 비·비로도 같은 비 줄)."""
    i = dict(fx("R1")["input"], arrived_at=f"2026-11-17T{now}:00+09:00", distance_km=None,
             midway={"route_m": 104_300, "remain_m": 104_300 - so_far_m})
    i.update(kw)
    return i


def mid(**kw):
    return EF.effort(EF.input_from_json(midway_input(**kw)), C)


def test_midway_design_example():
    """HANDOFF_v2 7-2 시안: 65분·52km → 비 +40%·정체 19km +10%·예상보다 더 +13분 +5%·1.55배·한 사람 약 4천 원."""
    r = run("R27")
    assert r.headline_text == "오늘 운전 1시간 5분 · 평소보다 1.55배 힘든 길"
    assert [(l.key, l.pct_text, l.detail_text) for l in r.lines] == [
        ("rain", "+40%", "비, 주행 내내"), ("congestion", "+10%", "19km (경로의 36%)"), ("delay", "+5%", "+13분")]
    assert r.distance_text == "52km"
    assert r.delay_min == pytest.approx(65 - 105 * 52_000 / 104_300)   # 기준 ETA = ETA × 지금까지 거리 ÷ 경로 거리
    p = r.proposal
    assert (p.amount_text, p.per_person_text, p.b.item) == ("약 1만 8천 원", "약 4천 원", "meal")
    assert p.amount_krw == pytest.approx(17_964.19, abs=0.005)
    assert (p.settled_krw, p.remain_krw) == (0.0, p.amount_krw)          # 처음 정산: 정산하면 금액 전체를 기록
    assert p.a.basis_text == "운전 노고 약 1만 8천 원을 운전자 포함 4명이 나눈 값"
    assert mid().to_json() == r.to_json()                                 # 시간대별 비 = 1회 관측 비·비


def test_arrival_after_midway_rounds_once():
    """도착 영수증은 출발~도착 전체, 제안은 남은 금액(원 단위) ÷ n을 마지막에 한 번만 어림.
    17:05 정산에서 장시간이 끊겨(65 + 65분) 노고 203.386 → 1.56배, 금액 36,270.50(효성 11/9 예상과 같음).
    (36,270.50 − 17,964.19) ÷ 4 = 4,576.58 → 약 5천 원."""
    r27 = run("R27")
    assert fx("R28")["input"]["settlements"] == [{"at": fx("R27")["input"]["arrived_at"],
                                                  "remain_krw": r27.proposal.remain_krw}]
    r = run("R28")
    assert r.fatigue_segments_min == [65, 65] and r.fatigue_excess_min == 0
    assert r.effort_min == pytest.approx(203.386, abs=1e-9) and r.multiplier == 1.56
    assert r.headline_text == "오늘 운전 2시간 10분 · 평소보다 1.56배 힘든 길"
    assert [l.key for l in r.lines] == ["rain", "congestion", "delay", "night"]   # 장시간 줄 없음
    j1, j28 = run("R1").to_json(), r.to_json()
    for k in ("proposal", "fatigue_segments_min", "fatigue_excess_min", "effort_min", "multiplier", "lines",
              "multiplier_text", "headline_text"):
        j1.pop(k)
        j28.pop(k)
    assert j1 == j28                                                              # 장시간 말고는 R1과 같음
    assert run("R1").lines[:4] == r.lines
    p = r.proposal
    settled = r27.proposal.remain_krw
    assert p.amount_krw == pytest.approx(36_270.50, abs=0.005) and p.amount_text == "약 3만 6천 원"
    assert p.settled_krw == settled and p.remain_krw == p.amount_krw - settled
    assert p.remain_krw == pytest.approx(18_306.31, abs=0.005)
    assert p.per_person_krw == p.remain_krw / 4 and p.per_person_text == "약 5천 원"
    assert p.per_person_krw == pytest.approx(4_576.58, abs=0.005)
    assert p.driver_share_krw == p.remain_krw * 3 / 4 and p.b.item == "meal"
    assert p.a.main_text == "한 사람 약 5천 원"
    assert p.a.basis_text == "운전 노고 약 3만 6천 원에서 중간에 챙긴 몫을 뺀 나머지를 운전자 포함 4명이 나눈 값"


R1_ARR = "2026-11-17T18:10:00+09:00"


def test_no_settlement_input_keeps_output():
    """settlements·midway가 없으면(빈 목록도) 지금 결과와 완전히 같다(출력에 키도 없음).
    도착 시각에 0원을 정산했다고 넘기면 구간이 그대로라 숫자는 같고 두 키만 생긴다."""
    base = run("R1").to_json()
    assert run("R1", settlements=[]).to_json() == base
    assert run("R1", settlements=None).to_json() == base
    bp = base.pop("proposal")
    assert "settled_krw" not in bp and "remain_krw" not in bp
    zero = run("R1", settlements=[{"at": R1_ARR, "remain_krw": 0}]).to_json()
    zp = zero.pop("proposal")
    assert zero == base
    assert (zp.pop("settled_krw"), zp.pop("remain_krw")) == (0.0, bp["amount_krw"])
    assert zp == bp


def test_settled_over_amount_never_negative():
    p = run("R1", settlements=[{"at": R1_ARR, "remain_krw": 50_000}]).proposal
    assert (p.remain_krw, p.per_person_krw, p.per_person_text, p.b) == (0.0, 0.0, "1천 원 미만", None)


@pytest.mark.parametrize("fid,item", [("R29", "coffee"), ("R30", None), ("R31", None)])
def test_remaining_share_picks_b_again(fid, item):
    """B는 남은 금액의 운전자 몫으로 5-3 경계를 다시 고른다. 0이거나 커피 1인분 절반(2,350) 미만이면 제안 없음."""
    p = run(fid).proposal
    n = fx(fid)["input"]["n"]
    assert p.driver_share_krw == p.remain_krw * (n - 1) / n
    assert (p.b.item if p.b else None) == item


def test_remaining_boundary_changes_item():
    assert run("R29", settlements=None).proposal.b.item == "meal"   # 정산 없이는 운전자 몫 10,700 → 밥
    assert run("R29").proposal.driver_share_krw == 2_350            # 남은 4,700 → 2,350 = 경계 '이상' → 커피
    assert run("R30").proposal.driver_share_krw == 2_349.5
    assert run("R31").proposal.remain_krw == 0.0


def test_two_settlements_sum():
    """정산할 때마다 settlements에 {at, remain_krw}를 쌓고 엔진이 합한다. 두 번 정산하면 합 = 두 번째 중간 정산 금액."""
    s1 = run("R27").proposal.remain_krw
    s2 = run("R32").proposal.remain_krw
    assert fx("R32")["input"]["settlements"] == [{"at": "2026-11-17T17:05:00+09:00", "remain_krw": s1}]
    assert fx("R33")["input"]["settlements"] == [{"at": "2026-11-17T17:05:00+09:00", "remain_krw": s1},
                                                 {"at": "2026-11-17T17:40:00+09:00", "remain_krw": s2}]
    assert run("R32").proposal.settled_krw == s1
    assert run("R33").proposal.settled_krw == s1 + s2
    assert s1 + s2 == pytest.approx(run("R32").proposal.amount_krw)
    r = run("R33")
    assert r.fatigue_segments_min == [65, 35, 30] and r.multiplier == 1.56
    p = r.proposal
    assert p.amount_krw == run("R28").proposal.amount_krw
    assert p.remain_krw == p.amount_krw - (s1 + s2)
    assert (p.per_person_text, p.b.item) == ("약 2천 원", "coffee")


# ---------------------------------------------------------------- 1부: 중간 정산 시각에서 장시간 끊기 (효성 11/9, #97 뒤)
def test_settlement_cuts_fatigue_instead_of_rest_split():
    """R34(정산 없음, 답 30분) → 가운데에서 105 + 105. R35(같은 주행 + 11:00 정산) → 정산 시각에서만 끊고
    구간 = 벽시계 × (T ÷ E): 60 × 210/240 = 52.5, 180 × 210/240 = 157.5 → 장시간 37.5분."""
    r34, r35 = run("R34"), run("R35")
    assert (r34.drive_min, r35.drive_min) == (210, 210)                        # Q는 두 경우 모두 T에서 뺌
    assert r34.fatigue_segments_min == [105, 105] and r34.fatigue_excess_min == 0
    assert r35.fatigue_segments_min == [52.5, 157.5] and r35.fatigue_excess_min == 37.5
    assert [(l.key, l.pct_text, l.detail_text) for l in r35.lines] == [("fatigue", "+2%", "2시간 넘긴 38분")]
    assert r35.multiplier == 1.02 and r34.multiplier == 1.0


def test_late_settlement_only_front_segment():
    """R36: 12:30 정산이 늦어 앞 구간 150분만 120분을 넘김 → 장시간 30분. 끊지 않으면 210분 한 구간(90분)."""
    r = run("R36")
    assert r.fatigue_segments_min == [150, 60] and r.fatigue_excess_min == 30
    assert run("R36", settlements=None).fatigue_excess_min == 90
    assert r.lines[-1].detail_text == "2시간 넘긴 30분"


@pytest.mark.parametrize("fid,segments,excess", [("R32", [65, 35], 0), ("R37", [60, 150], 30)])
def test_midway_cut_by_earlier_settlements(fid, segments, excess):
    """중간 정산 영수증도 그 앞의 정산 시각들로 끊는다(R32: 17:05, R37: 11:00)."""
    r = run(fid)
    assert r.fatigue_segments_min == segments and r.fatigue_excess_min == excess
    assert run(fid, settlements=None).fatigue_segments_min == [sum(segments)]


def test_settlement_times_sorted_and_clipped():
    """정산 시각은 시각 순으로 보고 [출발, 도착]으로 자른다. 길이 0인 구간은 넣지 않는다. 합은 넘긴 순서대로 더한다."""
    st = fx("R33")["input"]["settlements"]
    assert run("R33", settlements=st[::-1]).fatigue_segments_min == [65, 35, 30]
    out = [{"at": "2026-11-17T15:00:00+09:00", "remain_krw": 0}, {"at": "2026-11-17T19:00:00+09:00", "remain_krw": 0}]
    assert run("R1", settlements=out).fatigue_segments_min == [130]             # 출발 전·도착 뒤 → 길이 0
    dup = [st[0], dict(st[0], remain_krw=0)]
    assert run("R1", settlements=dup).fatigue_segments_min == [65, 65]          # 같은 시각 두 번 → 한 번 끊김


def test_settlement_with_rest_answer_keeps_drive():
    """중간 정산이 있어도 T = 경과 − Q(답 그대로). 정산 시각이 하나도 [출발, 도착] 안에 없어도 가운데 끊기는 하지 않는다."""
    r = run("R34", settlements=[{"at": "2026-11-17T14:00:00+09:00", "remain_krw": 0}])
    assert r.drive_min == 210 and r.fatigue_segments_min == [210] and r.fatigue_excess_min == 90


def test_midway_arrival_cap_uses_departure_eta():
    """도착 상한(#68)은 출발 때 ETA 그대로(효성 11/9). 기준 ETA(52.35분)로 줄였다면 112분이지만 경과 150분도 영수증."""
    r = mid(now="18:30")
    assert (r.status, r.elapsed_limit_min) == ("ok", 210)
    assert mid(now="19:31").status == "unsettled"   # 211분 > 210


def test_midway_input_made_by_engine():
    """지금까지 거리 = 경로 − 남은 거리는 엔진이 만든다. 앱이 넘긴 distance_km·congestion_m(경로 전체)은 중간 정산에서 안 쓴다."""
    assert mid().to_json() == mid(distance_km=104.3, congestion_m=99_999).to_json()
    z = mid(so_far_m=-5_000)            # 경로가 바뀌어 남은 거리가 경로보다 길면 지금까지 0
    assert (z.delay_min, z.distance_text) == (0, "1km 미만")
    u = EF.effort(EF.input_from_json(midway_input(midway={"route_m": 0, "remain_m": 0})), C)
    assert u.delay_min == 0              # 경로 거리를 모르면 예상보다 더 0


# ---------------------------------------------------------------- 1부: 시연 데이터 (명세 v0.5 12절)
DEMO = load(ROOT / "fixtures/effort/demo.json")


def test_demo_is_r1_scene():
    """시연 데이터 = R1 입력 그대로 + 화면용 scene. 목업·영상·PT가 같은 숫자(1.57배·한 사람 약 9천 원·밥)를 쓴다."""
    assert DEMO["input"] == fx("R1")["input"]
    e = DEMO["expected"]
    assert e["headline_text"] == "오늘 운전 2시간 10분 · 평소보다 1.57배 힘든 길"
    assert [l["pct_text"] for l in e["lines"]] == ["+40%", "+10%", "+5%", "+2%", "+1%"]
    assert e["proposal"]["a"]["main_text"] == "한 사람 약 9천 원"
    assert e["proposal"]["b"]["text"] == "오늘은 밥값에서 운전자 빼고 나누는 게 어때요"
    sc = DEMO["scene"]
    assert (sc["dest_name"], sc["route_m"], sc["n"], sc["rest_answer_label"]) == ("태안 꽃지해수욕장", 104_300, 4, "없음")
    assert sc["departure_preview"] == {"eta_text": "1시간 45분", "route_text": "104km",
                                       "congestion_text": "38km (경로의 36%)"}
    # 정체 km = 비율 × 경로 거리(명세 F20), 거리 표시도 같은 경로
    assert DEMO["input"]["congestion_m"] == DEMO["input"]["congestion_ratio"] * sc["route_m"]
    assert DEMO["input"]["distance_km"] * 1000 == sc["route_m"]


def test_demo_midway_scene():
    """시연 데이터 중간 정산 장면(효성 11/9): 휴게소 17:05·남은 52.3km → R27 영수증, [좋아요]로 정산 뒤 18:10 도착 → R28."""
    m, a = DEMO["midway"], DEMO["midway_arrival"]
    assert (m["input"], m["expected"]) == (fx("R27")["input"], fx("R27")["expected"])
    assert (a["input"], a["expected"]) == (fx("R28")["input"], fx("R28")["expected"])
    assert m["at"] == m["input"]["arrived_at"] == "2026-11-17T17:05:00+09:00"
    assert m["remain_m"] == m["input"]["midway"]["remain_m"] == 52_300
    assert m["input"]["midway"]["route_m"] == DEMO["scene"]["route_m"]
    # 정산 뒤 도착 입력 = 시연 입력(R1) + settlements 한 줄 {정산 시각, 중간 정산 영수증의 remain_krw}
    assert a["input"] == dict(DEMO["input"], settlements=[{"at": m["at"],
                                                           "remain_krw": m["expected"]["proposal"]["remain_krw"]}])
    assert EF.effort(EF.input_from_json(m["input"]), C).to_json() == m["expected"]
    assert EF.effort(EF.input_from_json(a["input"]), C).to_json() == a["expected"]
    assert m["expected"]["headline_text"] == "오늘 운전 1시간 5분 · 평소보다 1.55배 힘든 길"
    assert a["expected"]["headline_text"] == "오늘 운전 2시간 10분 · 평소보다 1.56배 힘든 길"
    assert a["expected"]["proposal"]["a"]["main_text"] == "한 사람 약 5천 원"


def test_demo_fixture():
    i = DEMO["input"]
    assert EF.effort(EF.input_from_json(i), C).to_json() == DEMO["expected"]
    p = EF.departure_preview(i["eta_s"], DEMO["scene"]["route_m"], i["congestion_ratio"], i["congestion_m"], C)
    assert dataclasses.asdict(p) == DEMO["scene"]["departure_preview"]


@pytest.mark.parametrize("eta_s,route_m,ratio,cm,want", [
    (6300, 104_300, 0.36, 37_548, ("1시간 45분", "104km", "38km (경로의 36%)")),
    (600, 400, 0.0, 0, ("10분", "1km 미만", None)),                 # 정체 0이면 정체 표시 없음
    (7200, 80_000, None, None, ("2시간", "80km", None)),              # 정체 정보 없음
])
def test_departure_preview(eta_s, route_m, ratio, cm, want):
    p = EF.departure_preview(eta_s, route_m, ratio, cm, C)
    assert (p.eta_text, p.route_text, p.congestion_text) == want


# ---------------------------------------------------------------- 2부: fixture 전수
def test_effort_fixture_ids():
    """R2·R4(자동으로 잰 정차 목록)는 #62에서 지웠다. 번호는 명세·AC·앱 테스트 참조가 안 바뀌게 비워 둔다.
    R16~R21은 도착 상한(#71), R22~는 시간대별 강수(#63) — 브랜치가 따로라 한쪽만 있어도 통과한다."""
    ids = {int(p.stem[1:]) for p in EFFORT}
    assert not ids & {2, 4}
    assert {1, 3} | set(range(5, 16)) <= ids
    assert set(range(22, 27)) <= ids
    assert set(range(27, 34)) <= ids   # 중간 정산(Q-08-02)
    assert set(range(34, 38)) <= ids   # 중간 정산 시각에서 장시간 끊기(효성 11/9)


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
TIME = r"(\d+시간( \d+분)?|\d+분|1분 미만)"
LINE_DETAIL = {   # 줄 값 형식(노고 PRD v1.3 5-1)
    "rain": re.compile(r"^비(, 주행 내내)?$"),
    "snow": re.compile(r"^눈(, 주행 내내)?$"),
    "delay": re.compile(rf"^\+{TIME}$"),
    "night": re.compile(rf"^{TIME}$"),
    "fatigue": re.compile(rf"^2시간 넘긴 {TIME}$"),
}


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
    if r.status_text:
        yield r.status_text
    yield r.drive_text
    yield r.multiplier_text
    yield r.headline_text
    yield from r.notes
    yield from (l.pct_text for l in r.lines)
    yield from (l.label for l in r.lines)
    yield from (l.detail_text for l in r.lines if l.detail_text)
    if r.footnote_text:
        yield r.footnote_text
    yield from (l.basis_text for l in r.lines if l.basis_text)
    if r.proposal:
        p = r.proposal
        yield from (p.amount_text, p.per_person_text)
        yield from dataclasses.asdict(p.a).values()
        if p.b:
            yield p.b.text
            yield from p.b.item_examples


def test_copy_rules_hold_everywhere():
    for r in receipts():
        assert r.status in ("ok", "unsettled")
        if r.status == "unsettled":     # 정리하지 못함: 영수증 값 없이 안내 문구만
            assert r.headline_text == r.status_text and r.lines == [] and r.proposal is None
            for rx, msg in lint_copy.FORBIDDEN + lint_copy.COPY_RULES:
                assert not rx.search(r.status_text), f"{msg}: {r.status_text}"
            continue
        assert r.status_text is None
        assert DRIVE.match(r.drive_text), r.drive_text
        assert MULT.match(r.multiplier_text), r.multiplier_text
        assert r.headline_text == f"오늘 운전 {r.drive_text} · {r.multiplier_text}"
        assert (r.multiplier == 1.0) == (r.multiplier_text == "평소와 같은 길")
        for l in r.lines:
            assert PCT.match(l.pct_text) and l.add_min > 0, l
            if l.key == "congestion":
                assert l.detail_text is None or DETAIL.match(l.detail_text), l
            else:
                assert LINE_DETAIL[l.key].match(l.detail_text), l
        assert (r.footnote_text is not None) == any(l.key == "congestion" for l in r.lines)
        if r.proposal:
            assert KRW.match(r.proposal.amount_text) and KRW.match(r.proposal.per_person_text), r.proposal
            a = r.proposal.a
            assert a.title_text == "오늘 운전을 챙긴다면"
            assert a.main_text == f"한 사람 {r.proposal.per_person_text}"
            if r.proposal.settled_krw:   # 앞서 정산한 몫이 있으면 '뺀 나머지'(중간 정산)
                assert a.basis_text.startswith(f"운전 노고 {r.proposal.amount_text}에서 중간에 챙긴 몫을 뺀 나머지를 운전자 포함 ")
            else:
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
