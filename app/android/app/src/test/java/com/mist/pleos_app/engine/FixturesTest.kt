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
