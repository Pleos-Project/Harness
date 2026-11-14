package com.mist.pleos_app.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Kotlin 엔진이 서버(Python)와 같은 fixtures 를 통과하는지. fixtures/ 는 scripts/gen_fixtures.py 생성물.
 * 실행: cd app/android && ./gradlew test   (CI app-android job)
 */
class FixturesTest {

    private val root: File = locateFixtures()
    private val constants = parseConstants(File(root, "constants.json").readText())
    private val candidates = parseCandidates(File(root, "candidates_demo.json").readText())

    // ------------------------------------------------------------ 앱 로컬 E1 · 요청
    @Test
    fun appLocalRangeAndRequest() {
        val files = fixtures("cards", "D").filter { "app_local" in load(it) }
        assertTrue("app_local fixture가 없다", files.isNotEmpty())
        for (f in files) {
            val fx = load(f)
            val inp = fx.getValue("app_local").jsonObject.getValue("inputs").jsonObject
            val v = vehicleInputs(
                constants, inp.str("model"), inp.dbl("level_pct"),
                remainingRangeM = inp.dblOrNull("remaining_range_m") ?: 0.0,
                capacitySdk = inp.dblOrNull("capacity_sdk"), efficiencySdk = inp.dblOrNull("efficiency_sdk"),
                price = inp.dblOrNull("price"),
            )
            val extra = inp.integerOrNull("extra_krw") ?: 0
            val days = inp.integerOrNull("days") ?: 1
            val r = computeRange(v, extra, days)
            val local = obj(
                "fuel_type" to v.fuelType, "capacity" to v.capacity, "efficiency" to v.efficiency,
                "price_per_unit" to v.pricePerUnit, "price_source" to v.priceSource,
                "balance_km" to round1(r.balanceKm), "balance_krw" to r.balanceKrw, "balance_text" to r.balanceText,
                "margin_km" to round1(r.marginKm), "usable_km" to round1(r.usableKm), "radius_km" to round1(r.radiusKm),
                "range_source" to r.rangeSource, "zero_available" to r.zeroAvailable,
            )
            assertJson(fx.getValue("app_local").jsonObject.getValue("expected"), local, "${f.name} app_local")

            val want = fx["request"]?.jsonObject ?: continue
            val req = cardsRequest(
                constants.originLat, constants.originLng, v, r, extraKrw = extra, days = days,
                seats = want.integer("seats_occupied"), themes = want.strList("themes"),
                recentVisits = want.strList("recent_visits"), demoSeed = constants.demoSeed,
            )
            assertJson(JsonObject(want - "session_id"), req.toJson(), "${f.name} request")
        }
    }

    // ------------------------------------------------------------ 오프라인 데모 = 서버 응답
    @Test
    fun demoServerMatchesServerFixtures() {
        val files = fixtures("cards", "D").filter { "request" in load(it) }
        assertTrue(files.size >= 4)
        for (f in files) {
            val fx = load(f)
            val server = DemoServer(candidates)
            var req = parseCardsRequest(fx.getValue("request").jsonObject)
            val from = fx.strOrNull("session_from")
            req = if (from != null) {
                val first = server.cards(parseCardsRequest(load(File(root, "cards/$from.json")).getValue("request").jsonObject))
                req.copy(sessionId = first.sessionId)
            } else {
                req.copy(sessionId = null)
            }
            val res = server.cards(req)
            assertJson(fx.getValue("expected"), res.toJson(maskSession = "<session>"), "${f.name} response")

            val swaps = fx["expected_swaps"]?.jsonArray ?: continue
            for ((i, w) in swaps.withIndex()) {
                val want = w.jsonObject
                when (val s = server.swap(res.sessionId!!)) {
                    is SwapResult.Error -> assertEquals("${f.name} swap ${i + 1}", want.strOrNull("error"), s.error)
                    is SwapResult.Ok -> assertJson(
                        want, obj("card" to s.card.toJson(), "swaps_left" to s.swapsLeft, "stack_left" to s.stackLeft),
                        "${f.name} swap ${i + 1}",
                    )
                }
            }
        }
    }

    @Test
    fun serverResponseRoundTrip() {
        val expected = load(File(root, "cards/D1.json")).getValue("expected").jsonObject
        assertJson(expected, parseCardsResponse(expected).toJson(), "D1 parse → toJson")
    }

    // ------------------------------------------------------------ 정산·충전
    @Test
    fun settleFixtures() {
        val files = fixtures("settle", "T") + File(root, "cards/D5.json")
        for (f in files) {
            val fx = load(f)
            val s = settle(
                parseSnapshot(fx.objOrNull("snapshot_prev")), fx.dbl("odometer_now"),
                fx.strOrNull("last_destination"), fx.integerOrNull("span_days") ?: 0,
            )
            assertJson(fx.getValue("expected"), s.toJson(), f.name)
        }
    }

    @Test
    fun chargeD6() {
        val fx = load(File(root, "cards/D6.json"))
        val c = chargeAmount(fx.dbl("charge_start_soc"), fx.dbl("charge_end_soc"), fx.dbl("capacity_kwh"), fx.dbl("price"))
        val want = fx.getValue("expected").jsonObject
        assertEquals(want.integer("krw"), c.krw)
        assertEquals(want.str("text"), c.text)
    }

    @Test
    fun fullTankD11() {
        val fx = load(File(root, "cards/D11.json"))
        val c = fullTankAmount(fx.dbl("fuel_start_pct"), fx.dbl("tank_l"), fx.dbl("price"))
        val want = fx.getValue("expected").jsonObject
        assertEquals(want.dbl("liters"), round1(c.liters * 10) / 10, 0.0)
        assertEquals(want.integer("krw"), c.krw)
        assertEquals(want.str("text"), c.text)
    }

    // ------------------------------------------------------------ 장부
    @Test
    fun ledgerFixtures() {
        val files = fixtures("ledger", "L")
        assertTrue(files.isNotEmpty())
        for (f in files) {
            val fx = load(f)
            var st = parseLedgerState(fx.getValue("state0").jsonObject)
            for ((i, step) in fx.getValue("steps").jsonArray.withIndex()) {
                val s = step.jsonObject
                val (next, out) = ledgerStep(st, parseLedgerEvent(s.getValue("event").jsonObject))
                val exp = s.getValue("expected").jsonObject
                assertJson(exp.getValue("output"), out.toJson(), "${f.name} step ${i + 1} output")
                assertJson(exp.getValue("state"), next.toJson(), "${f.name} step ${i + 1} state")
                st = next
            }
            assertJson(fx.getValue("expected_month"), monthSummary(st.rows, fx.str("month")).toJson(), "${f.name} month")
        }
    }

    // ------------------------------------------------------------ 운전 노고 (명세 v0.5 F21·F23~F25)
    private val effortConstants = parseEffortConstants(File(root, "constants.json").readText())

    /** AC-F23-2: R1~ 전부를 Kotlin 엔진이 서버(effort.py)와 같게 낸다. */
    @Test
    fun effortFixtures() {
        val files = fixtures("effort", "R")
        assertTrue("effort fixture가 R1~R8보다 적다: ${files.map { it.name }}", files.size >= 8)
        for (f in files) {
            val fx = load(f)
            val r = effort(parseEffortInput(fx.getValue("input").jsonObject), effortConstants)
            assertJson(fx.getValue("expected"), r.toJson(), f.name)
        }
    }

    /** 시연 데이터(명세 v0.5 12절): R1 입력에서 날짜만 시연 날짜(11/10), 엔진 출력과 출발 화면 미리보기가 서버와 같다. */
    @Test
    fun effortDemo() {
        val fx = load(File(root, "effort/demo.json"))
        val input = fx.getValue("input").jsonObject
        assertJson(load(File(root, "effort/R1.json")).getValue("input"), undate(input), "demo input = R1 input(날짜만 다름)")
        assertEquals("2026-11-10T16:00:00+09:00", input.str("departed_at"))
        assertJson(fx.getValue("expected"), effort(parseEffortInput(input), effortConstants).toJson(), "demo expected")
        val scene = fx.getValue("scene").jsonObject
        val preview = departurePreview(
            input.dbl("eta_s"), scene.dbl("route_m"), input.dblOrNull("congestion_ratio"), input.dblOrNull("congestion_m"),
            effortConstants,
        )
        assertJson(scene.getValue("departure_preview"), preview.toJson(), "demo departure_preview")

        // 휴게소 중간 정산 장면(효성 11/9): 17:05 중간 정산 = R27, [좋아요]로 정산한 뒤 18:10 도착 = R28
        val mid = fx.getValue("midway").jsonObject
        val after = fx.getValue("midway_arrival").jsonObject
        for ((part, id) in listOf(mid to "R27", after to "R28")) {
            val r = load(File(root, "effort/$id.json"))
            assertJson(r.getValue("input"), undate(part.getValue("input")), "demo $id input(날짜만 다름)")
            assertJson(r.getValue("expected"), part.getValue("expected"), "demo $id expected")
            val actual = effort(parseEffortInput(part.getValue("input").jsonObject), effortConstants).toJson()
            assertJson(part.getValue("expected"), actual, "demo $id 엔진")
        }
        val midIn = parseEffortInput(mid.getValue("input").jsonObject)
        assertEquals(mid.str("at"), midIn.arrivedAt)
        assertEquals(mid.dbl("remain_m"), midIn.midway!!.remainM, 0.0)
        // 정산 뒤 도착 입력 = 시연 입력(R1) + settlements 한 줄 {정산 시각, 중간 정산 영수증의 remainKrw}
        val afterIn = parseEffortInput(after.getValue("input").jsonObject)
        val midRemain = effort(midIn, effortConstants).proposal!!.remainKrw!!
        assertEquals(listOf(EffortSettlement(mid.str("at"), midRemain)), afterIn.settlements)
        assertEquals(parseEffortInput(input).copy(settlements = afterIn.settlements), afterIn)
    }

    /** 명세 AC 숫자·문구를 fixture와 따로 확인(AC-F23-1·AC-F24-1·AC-F25-1). */
    @Test
    fun effortSpecR1() {
        val r = effort(parseEffortInput(load(File(root, "effort/R1.json")).getValue("input").jsonObject), effortConstants)
        // 정체 분 = 비율 0.36 × T 130 = 46.8(열린 결정 28) → 1.57배, 정체 +10%
        assertEquals("오늘 운전 2시간 10분 · 평소보다 1.57배 힘든 길", r.headlineText)
        assertEquals(listOf("+40%", "+10%", "+5%", "+2%", "+1%"), r.lines.map { it.pctText })
        // 줄 값 = 노고 PRD v1.3 5-1 영수증 예시 그대로
        assertEquals(listOf("날씨", "정체 구간", "예상보다 더", "밤길", "장시간"), r.lines.map { it.label })
        assertEquals(
            listOf("비, 주행 내내", "38km (경로의 36%)", "+25분", "50분", "2시간 넘긴 10분"),
            r.lines.map { it.detailText },
        )
        assertEquals("출발 때 내비가 안내한 경로의 36%가 붐볐어요", r.footnoteText)   // 시안 v104 정체 줄 펼침 첫 줄
        // AC-F23-4: 줄마다 근거 한 줄, {pct}는 rates_pct 값
        assertEquals(
            "비 오는 날은 운전 부담이 40% 늘어요 · 시뮬레이터 30명 연구(Frontiers in Neuroscience 2026)",
            r.lines[0].basisText,
        )
        assertTrue(r.lines.all { it.basisText != null && "{pct}" !in it.basisText!! })
        val p = r.proposal!!
        assertEquals(36_493.42, p.amountKrw, 0.0)
        assertEquals("약 3만 6천 원", p.amountText)
        assertEquals(9_123.355, p.perPersonKrw, 1e-9)
        assertEquals("약 9천 원", p.perPersonText)
        assertEquals(27_370.065, p.driverShareKrw, 1e-6)
        // 방식 A 화면(노고 PRD v1.3 5-2)과 방식 B 화면은 따로다
        assertEquals("오늘 운전을 챙긴다면", p.a.titleText)
        assertEquals("한 사람 약 9천 원", p.a.mainText)
        assertEquals("운전 노고 약 3만 6천 원을 운전자 포함 4명이 나눈 값", p.a.basisText)
        assertEquals("최저임금 1분 178원 기준", p.a.wageText)
        assertEquals("meal", p.b?.item)
        assertEquals("오늘은 밥값에서 운전자 빼는 게 어때요", p.b?.text)
        assertEquals(emptyList<String>(), p.b?.itemExamples)
        val r8 = effort(parseEffortInput(load(File(root, "effort/R8.json")).getValue("input").jsonObject), effortConstants)
        assertEquals(null, r8.proposal)
        // 거리 0.4km → "0km"가 아니라 "1km 미만"(명세 2절, June 리뷰)
        val r6 = effort(parseEffortInput(load(File(root, "effort/R6.json")).getValue("input").jsonObject), effortConstants)
        assertEquals("1km 미만", r6.distanceText)
        // 구간 목록(나란히 배치)이 있으면 비율 대신 통과 시간 40분 → 예전 R1 값(R14)
        val r14 = effort(parseEffortInput(load(File(root, "effort/R14.json")).getValue("input").jsonObject), effortConstants)
        assertEquals(202.8, r14.effortMin, 1e-9)
        assertEquals(36_166.0, r14.proposal!!.amountKrw, 0.0)
        // AC-F21-1: 답 30분 → T 130, 장시간 65 + 65(R9)
        val r9 = effort(parseEffortInput(load(File(root, "effort/R9.json")).getValue("input").jsonObject), effortConstants)
        assertEquals(listOf(65.0, 65.0), r9.fatigueSegmentsMin)
        // 공동 경비: "값" 없이 "공동 경비에서", 그 자리 예시 목록(R15)
        val b15 = effort(parseEffortInput(load(File(root, "effort/R15.json")).getValue("input").jsonObject), effortConstants)
            .proposal!!.b!!
        assertEquals("오늘은 공동 경비에서 운전자 빼는 게 어때요", b15.text)
        assertEquals(listOf("공동 경비", "장 본 값", "펜션비", "바베큐장 이용료", "입장료", "물품 구매비"), b15.itemExamples)
    }

    /** 도착 상한(#68): 경과가 max(ETA × 2, ETA + 60분)·12시간과 같으면 영수증, 넘으면 정리하지 못함. */
    @Test
    fun effortArrivalCap() {
        val want = listOf("R16" to "ok", "R17" to "unsettled", "R18" to "ok", "R19" to "unsettled", "R20" to "ok", "R21" to "unsettled")
        for ((id, status) in want) {
            val r = effort(parseEffortInput(load(File(root, "effort/$id.json")).getValue("input").jsonObject), effortConstants)
            assertEquals(id, status, r.status)
            if (status == "unsettled") {
                assertEquals(id, "이번 주행은 정리하지 못했어요", r.statusText)
                assertEquals(id, r.statusText, r.headlineText)
                assertTrue(id, r.lines.isEmpty() && r.proposal == null)
            } else {
                assertEquals(id, null, r.statusText)
            }
        }
        assertEquals(210.0 * 60, elapsedLimitS(105.0 * 60, effortConstants), 0.0)
        assertEquals(70.0 * 60, elapsedLimitS(10.0 * 60, effortConstants), 0.0)
        assertEquals(720.0 * 60, elapsedLimitS(400.0 * 60, effortConstants), 0.0)
        assertEquals(720.0 * 60, elapsedLimitS(0.0, effortConstants), 0.0)
    }

    /** 시간대별 강수(#63): 조각 가운데로 출발·도착 관측을 고르고, 벽시계 겹침 × T/경과. 전부 unknown이면 1회 관측 규칙. */
    @Test
    fun effortWeatherTrip() {
        fun run(id: String) =
            effort(parseEffortInput(load(File(root, "effort/$id.json")).getValue("input").jsonObject), effortConstants)
        assertEquals(40.0, run("R22").rainMin, 0.0)                     // 출발 비 → 그침: 겹친 40분만
        assertEquals(120.0 * 210 / 240, run("R23").rainMin, 0.0)        // 중간에만 비, × T/경과
        assertEquals(listOf("일부 시간은 날씨 정보 없음"), run("R24").notes)   // partial
        assertEquals("비, 주행 내내", run("R25").lines[0].detailText)     // 가운데 같으면 출발 지점
        assertEquals(72.5, run("R26").rainMin, 0.0)                     // 전부 unknown → 절반 규칙
        // 날씨를 반만 받음(#149): 날씨 줄 하나에 적고 셈은 절반 규칙 그대로, note는 남는다
        val r38 = run("R38")
        assertEquals("출발 비 · 도착 정보 없음", r38.lines[0].detailText)
        assertEquals("도착 날씨를 받지 못해 운전 시간의 앞 절반만 출발 날씨(비)로 셌어요", r38.lines[0].basisText)
        assertEquals(60.0, r38.rainMin, 0.0)
        assertEquals(listOf("날씨 정보 없음"), r38.notes)
        assertEquals("출발 정보 없음 · 도착 눈", run("R39").lines[0].detailText)
        // 계약 응답을 그대로 넘겨도 된다(status는 안 씀)
        val sample = parseObject(File(root, "../contract/samples/weather.trip.ok.response.json").readText())
        val wt = parseWeatherTrip(sample)
        assertEquals(3, wt.dep.size)
        assertEquals("rain", wt.dep[0].precip)
    }

    /** 중간 정산(명준 Q-08-02, 효성 계산 규칙 11/9): 시안 예시, 남은 금액을 마지막에 한 번 어림, B 경계 다시 고르기, 두 번 정산. */
    @Test
    fun effortMidway() {
        fun run(id: String) =
            effort(parseEffortInput(load(File(root, "effort/$id.json")).getValue("input").jsonObject), effortConstants)
        // 시안: 65분·52km → 1.55배, 정체 19km, 예상보다 더 +13분(기준 ETA = 105분 × 52 ÷ 104.3), 한 사람 약 4천 원
        val r27 = run("R27")
        assertEquals("오늘 운전 1시간 5분 · 평소보다 1.55배 힘든 길", r27.headlineText)
        assertEquals(listOf("비, 지금까지 내내", "19km (경로의 36%)", "+13분"), r27.lines.map { it.detailText })   // 중간 정산 날씨(시안 v104)
        assertEquals("출발 때 내비가 안내한 경로의 36%가 붐볐어요", r27.footnoteText)
        assertEquals("52km", r27.distanceText)
        val p27 = r27.proposal!!
        assertEquals("약 1만 8천 원", p27.amountText)
        assertEquals("한 사람 약 4천 원", p27.a.mainText)
        assertEquals(0.0, p27.settledKrw!!, 0.0)
        assertEquals(p27.amountKrw, p27.remainKrw!!, 0.0)
        // 도착: 영수증은 출발~도착 전체, 제안은 남은 금액(원 단위) ÷ n을 한 번만 어림 → 약 5천 원
        val r1 = run("R1")
        val r28 = run("R28")
        // 17:05 정산에서 장시간이 끊겨 65 + 65분 → 장시간 줄 없음, 1.56배(효성 11/9). 장시간 말고는 R1과 같다
        assertEquals(listOf(65.0, 65.0), r28.fatigueSegmentsMin)
        assertEquals("오늘 운전 2시간 10분 · 평소보다 1.56배 힘든 길", r28.headlineText)
        assertEquals(r1.lines.dropLast(1), r28.lines)
        assertEquals(203.386, r28.effortMin, 1e-9)
        assertEquals(36_270.50, r28.proposal!!.amountKrw, 0.005)
        assertEquals(
            r1.copy(
                proposal = null, fatigueSegmentsMin = r28.fatigueSegmentsMin, fatigueExcessMin = 0.0,
                effortMin = r28.effortMin, multiplier = r28.multiplier, lines = r28.lines,
                multiplierText = r28.multiplierText, headlineText = r28.headlineText,
            ),
            r28.copy(proposal = null),
        )
        val p28 = r28.proposal!!
        assertEquals(p27.remainKrw!!, p28.settledKrw!!, 0.0)
        assertEquals(p28.amountKrw - p27.remainKrw!!, p28.remainKrw!!, 0.0)
        assertEquals("한 사람 약 5천 원", p28.a.mainText)
        // 시안 v104: 남은 금액(원 단위 18,306.31)을 한 번만 어림해 보여 준다. 어림한 값끼리 빼지 않음
        assertEquals("남은 운전 노고 약 1만 8천 원을 운전자 포함 4명이 나눈 값", p28.a.basisText)
        // B는 남은 금액의 운전자 몫으로 경계를 다시 고른다(2,350 같음 → 커피, 1원 아래·0 → 제안 없음)
        assertEquals("coffee", run("R29").proposal!!.b?.item)
        assertEquals(null, run("R30").proposal!!.b)
        assertEquals(null, run("R31").proposal!!.b)
        assertEquals(0.0, run("R31").proposal!!.remainKrw!!, 0.0)
        // 두 번 정산: 기록할 합 = 정산마다의 remainKrw 합
        assertEquals(p27.remainKrw!! + run("R32").proposal!!.remainKrw!!, run("R33").proposal!!.settledKrw!!, 0.0)
        // 정산 입력이 없으면 출력에 키도 없다(기존 R* 그대로)
        assertTrue("settled_krw" !in r1.proposal!!.toJson())
    }

    /** 중간 정산 시각에서 장시간 끊기(효성 11/9): 정산이 있으면 그 시각에서만 끊고(Q로 가운데 끊지 않음), 구간 = 벽시계 × (T ÷ E). */
    @Test
    fun effortFatigueSplit() {
        fun input(id: String) = parseEffortInput(load(File(root, "effort/$id.json")).getValue("input").jsonObject)
        fun run(id: String) = effort(input(id), effortConstants)
        assertEquals(listOf(105.0, 105.0), run("R34").fatigueSegmentsMin)        // 정산 없음 + 답 30분 → 가운데
        val r35 = run("R35")
        assertEquals(listOf(52.5, 157.5), r35.fatigueSegmentsMin)                // 같은 주행 + 11:00 정산 → 그 시각에서만
        assertEquals(37.5, r35.fatigueExcessMin, 0.0)
        assertEquals(listOf("2시간 넘긴 38분"), r35.lines.map { it.detailText })
        assertEquals(listOf(150.0, 60.0), run("R36").fatigueSegmentsMin)         // 늦은 정산 → 앞 구간만 장시간
        assertEquals(90.0, effort(input("R36").copy(settlements = emptyList()), effortConstants).fatigueExcessMin, 0.0)
        assertEquals(listOf(65.0, 35.0), run("R32").fatigueSegmentsMin)          // 중간 정산 영수증도 앞 정산 시각에서
        assertEquals(listOf(60.0, 150.0), run("R37").fatigueSegmentsMin)
        // 정산 시각은 시각 순으로 보고 [출발, 도착]으로 자르며, 길이 0인 구간은 넣지 않는다
        val r33 = input("R33")
        assertEquals(
            listOf(65.0, 35.0, 30.0),
            effort(r33.copy(settlements = r33.settlements.reversed()), effortConstants).fatigueSegmentsMin,
        )
        val out = listOf(
            EffortSettlement("2026-11-17T15:00:00+09:00", 0.0), EffortSettlement("2026-11-17T19:00:00+09:00", 0.0),
        )
        assertEquals(listOf(130.0), effort(input("R1").copy(settlements = out), effortConstants).fatigueSegmentsMin)
        assertEquals(listOf(65.0), run("R31").fatigueSegmentsMin)                // 정산 시각 = 지금 → 길이 0 구간 없음
    }

    /** 명세 9절 경계표(밥 10,711원, DECISIONS 11/9): 2,350 미만 제안 없음 / 7,705.5 이상 밥 / 39,855.5 이상 공동 경비. */
    @Test
    fun effortBBoundaries() {
        val cases = listOf(
            2_349.99 to null, 2_350.0 to "coffee", 7_705.49 to "coffee", 7_705.5 to "meal",
            39_855.49 to "meal", 39_855.5 to "shared",
        )
        for ((share, want) in cases) assertEquals("G=$share", want, bItem(share, effortConstants.items)?.key)
    }

    /** 일출·일몰 계산이 서버와 같은 분을 낸다(fixtures/effort/sun.json). */
    @Test
    fun sunTimesFixture() {
        val fx = load(File(root, "effort/sun.json"))
        val cases = fx.getValue("cases").jsonArray
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            val o = c.jsonObject
            val (rise, set) = sunTimes(fx.dbl("lat"), fx.dbl("lng"), o.str("date"), fx.integer("tz_offset_min"))
            assertJson(o.getValue("expected"), obj("sunrise" to hhmm(rise), "sunset" to hhmm(set)), "sun ${o.str("date")}")
        }
    }

    /** 일러스트 변형: 같은 장소 → 같은 그림, 범위는 1..variants. */
    @Test
    fun illustrationVariantIsStable() {
        assertEquals(illustrationName("river", "p_dumulmeori", 4), illustrationName("river", "p_dumulmeori", 4))
        for (c in candidates) {
            val n = illustrationName(c.theme, c.id, 3).substringAfterLast('-').toInt()
            assertTrue("${c.id} → $n", n in 1..3)
        }
        assertEquals("sea-1", illustrationName("sea", "p_eulwangri", 1))
    }

    /** 비교 함수가 느슨해서 아무거나 통과시키지 않는지(값 하나만 달라도 잡는다, 326 == 326.0 은 같다). */
    @Test
    fun comparatorCatchesDifferences() {
        val expected = load(File(root, "cards/D1.json")).getValue("expected").jsonObject
        val picked = expected.getValue("picked").jsonObject
        val display = picked.getValue("display").jsonObject
        val broken = JsonObject(expected + ("picked" to JsonObject(picked + ("display" to JsonObject(display + ("badge_text" to JsonPrimitive("빠듯")))))))
        assertTrue("배지 문구 하나 다른 걸 못 잡음", jsonDiff(expected, broken, "$") != null)
        assertTrue("키 하나 빠진 걸 못 잡음", jsonDiff(expected, JsonObject(expected - "hint"), "$") != null)
        assertTrue("정수·실수 같은 값을 다르다고 봄", jsonDiff(JsonPrimitive(326), JsonPrimitive(326.0), "$") == null)
        assertTrue("1원 차이를 못 잡음", jsonDiff(JsonPrimitive(7920), JsonPrimitive(7921), "$") != null)
    }

    // ------------------------------------------------------------ helpers
    private fun load(f: File): JsonObject = parseObject(f.readText())

    /** 시연 입력의 날짜(2026-11-10)를 R1 날짜(2026-11-17)로 되돌린다. 날짜 말고는 R1·R27·R28 입력과 같다. */
    private fun undate(e: JsonElement): JsonObject = parseObject(e.toString().replace("2026-11-10", "2026-11-17"))

    private fun fixtures(dir: String, prefix: String): List<File> =
        (File(root, dir).listFiles() ?: emptyArray())
            .filter { it.name.startsWith(prefix) && it.name.endsWith(".json") }
            .sortedBy { it.name.removePrefix(prefix).removeSuffix(".json").toInt() }

    private fun assertJson(expected: JsonElement, actual: JsonElement, what: String) {
        val diff = jsonDiff(expected, actual, "$")
        if (diff != null) fail("$what: $diff\n  expected=$expected\n  actual  =$actual")
    }

    /** 숫자는 값으로 비교(326 == 326.0), 나머지는 구조·문자열 그대로. 다르면 경로를 돌려준다. */
    private fun jsonDiff(a: JsonElement, b: JsonElement, path: String): String? {
        if (a is JsonNull || b is JsonNull) return if (a is JsonNull && b is JsonNull) null else "$path: $a ≠ $b"
        if (a is JsonObject && b is JsonObject) {
            if (a.keys != b.keys) return "$path: 키 다름 ${a.keys} ≠ ${b.keys}"
            for (k in a.keys) jsonDiff(a.getValue(k), b.getValue(k), "$path.$k")?.let { return it }
            return null
        }
        if (a is JsonArray && b is JsonArray) {
            if (a.size != b.size) return "$path: 길이 ${a.size} ≠ ${b.size}"
            for (i in a.indices) jsonDiff(a[i], b[i], "$path[$i]")?.let { return it }
            return null
        }
        if (a is JsonPrimitive && b is JsonPrimitive) {
            if (a.isString || b.isString) return if (a.isString && b.isString && a.content == b.content) null else "$path: $a ≠ $b"
            val ab = a.booleanOrNull
            val bb = b.booleanOrNull
            if (ab != null || bb != null) return if (ab == bb) null else "$path: $a ≠ $b"
            return if (a.content.toDouble() == b.content.toDouble()) null else "$path: $a ≠ $b"
        }
        return "$path: 형식 다름 $a ≠ $b"
    }

    private fun locateFixtures(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, "fixtures/constants.json")
            if (f.exists()) return f.parentFile
            dir = dir.parentFile
        }
        throw IllegalStateException("fixtures/constants.json 을 찾지 못함 (작업 폴더: ${File("").absolutePath})")
    }
}
