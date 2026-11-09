package com.mist.pleos_app.effort

import com.mist.pleos_app.app.Settings
import com.mist.pleos_app.engine.effort
import com.mist.pleos_app.engine.parseEffortConstants
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 중간 정산(F27): 앱이 만드는 엔진 입력(출발 기록 + 지금 + 내비 남은 거리 + 정산 합)이 효성 fixture R27·R32·R33과 같은 답을 내는지.
 * fixture는 중간 정산 지점 1회 관측을 rain으로 넣었고, 앱은 1회 관측을 부르지 않으므로(June #97 답) 그 칸만 맞춰 비교한다.
 */
class MidwayTest {

    private val fixtures = locate("fixtures")
    private val c = parseEffortConstants(File(fixtures, "constants.json").readText())

    private fun expectedRemain(id: String): Double =
        Json.parseToJsonElement(File(fixtures, "effort/$id.json").readText()).jsonObject["expected"]!!.jsonObject["proposal"]!!
            .jsonObject["remain_krw"]!!.jsonPrimitive.double

    private val trip = OpenTrip(
        "t1", "2026-11-17T16:00:00+09:00", "태안 꽃지해수욕장", 4, 6300.0, routeM = 104300.0,
        congestionRatio = 0.36, congestionM = 37548.0, weatherDep = "rain", sunrise = "07:13", sunset = "17:20",
    )

    private fun midway(t: OpenTrip, now: String, remainM: Int) =
        effort(midwayInputOf(t, now, remainM, null)!!.copy(weatherArr = "rain"), c)

    @Test
    fun settleTwiceThenArriveLikeFixtures() {
        // R27: 17:05 · 남은 52.3km → 1.55배, 남은 금액 = 금액(처음)
        val r27 = midway(trip, "2026-11-17T17:05:00+09:00", 52300)
        assertEquals(1.55, r27.multiplier, 1e-9)
        assertEquals(expectedRemain("R27"), r27.proposal!!.remainKrw!!, 1e-6)
        val t1 = trip.copy(settlements = listOf(Settlement("2026-11-17T17:05:00+09:00", r27.proposal!!.remainKrw!!, Settings.METHOD_A, CHOICE_ACCEPT, r27.proposal!!.a.mainText)))

        // R32: 17:40 · 남은 24.3km, 앞서 R27만큼 정산
        val r32 = midway(t1, "2026-11-17T17:40:00+09:00", 24300)
        assertEquals(expectedRemain("R32"), r32.proposal!!.remainKrw!!, 1e-6)
        val t2 = t1.copy(settlements = t1.settlements + Settlement("2026-11-17T17:40:00+09:00", r32.proposal!!.remainKrw!!, Settings.METHOD_B, "coffee"))

        // R33: 18:10 도착, 정산 합 = R27 + R32 남은 금액 → 남은 몫으로 제안
        val arrived = t2.copy(arrivedAt = "2026-11-17T18:10:00+09:00", weatherArr = "rain", restAnswerMin = 0)
        val r33 = receiptOf(arrived, c)!!
        assertEquals(expectedRemain("R33"), r33.proposal!!.remainKrw!!, 1e-6)
        assertEquals(1.57, r33.multiplier, 1e-9)                            // 영수증은 출발~도착 전체(R1 그대로)
    }

    @Test
    fun noSettlementKeepsArrivalOutput() {
        // 정산이 없으면 settledKrw = null → 도착 출력이 이 기능 전과 같다(R1 · demo.json 그대로)
        assertNull(trip.settledKrw)
        val r = receiptOf(trip.copy(arrivedAt = "2026-11-17T18:10:00+09:00", weatherArr = "rain", restAnswerMin = 0), c)!!
        assertNull(r.proposal!!.remainKrw)
        assertNull(r.proposal!!.settledKrw)
    }

    @Test
    fun noMidwayWithoutRouteOrAfterArrival() {
        assertNull(midwayInputOf(trip.copy(routeM = null), "2026-11-17T17:05:00+09:00", 52300, null))
        assertNull(midwayInputOf(trip.copy(arrivedAt = "2026-11-17T18:10:00+09:00"), "2026-11-17T18:20:00+09:00", 0, null))
        val i = midwayInputOf(trip, "2026-11-17T17:05:00+09:00", 52300, null)!!
        assertEquals(0, i.restAnswerMin)                                    // 중간 정산은 쉰 시간을 묻지 않는다
        assertEquals(WEATHER_UNKNOWN, i.weatherArr)                         // 1회 관측은 부르지 않음(June #97 답)
        assertEquals(104300.0, i.midway!!.routeM, 0.0)
        assertEquals(52300.0, i.midway!!.remainM, 0.0)
    }

    @Test
    fun settlementsSurviveStoreAndRecord() {
        val s = Settlement("2026-11-17T17:05:00+09:00", 17964.189306487697, Settings.METHOD_A, CHOICE_ACCEPT, "한 사람 약 4천 원")
        val t = trip.copy(settlements = listOf(s), depLat = 37.4, depLng = 127.1)
        assertEquals(t, parseOpenTrip(t.toJson()))
        assertEquals(17964.189306487697, t.settledKrw!!, 0.0)
        // 기록에는 좌표 없이 정산 목록이 그대로
        val rec = EffortRecord("t1", "2026-11-17", "태안", 130.0, 1.57, 204.0, "A", null, CHOICE_CLOSE, "", n = 4, trip = t.copy(depLat = null, depLng = null))
        val back = parseEffortRecord(rec.toJson())
        assertEquals(listOf(s), back.trip!!.settlements)
        assertTrue(!rec.toJson().toString().contains("lat"))
    }

    @Test
    fun flowSettlesOnlyWhileDriving() {
        val f = EffortFlow(MemoryEffortStore())
        val s = Settlement("2026-11-17T17:05:00+09:00", 1000.0, Settings.METHOD_A, CHOICE_ACCEPT)
        assertTrue(!f.settle(s))                                             // 출발 기록 없음
        f.depart(trip)
        assertTrue(f.settle(s))
        assertTrue(f.settle(s.copy(at = "2026-11-17T17:40:00+09:00")))      // 여러 번 허용(명준 Q-08-02)
        assertEquals(2000.0, f.state.openTrip!!.settledKrw!!, 0.0)
        f.arrive("2026-11-17T18:10:00+09:00", "rain")
        assertTrue(!f.settle(s))                                             // 도착 뒤에는 중간 정산 없음
    }

    @Test
    fun midwayBTextSwapsToday() {
        assertEquals("여기까지는 밥값에서 운전자 빼고 나누는 게 어때요", midwayBText("오늘은 밥값에서 운전자 빼고 나누는 게 어때요"))
        assertEquals("다른 문장", midwayBText("다른 문장"))
    }

    private fun locate(rel: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, rel)
            if (f.isDirectory && File(f, "constants.json").exists()) return f
            dir = dir.parentFile
        }
        throw IllegalStateException("$rel 을 찾지 못함")
    }
}
