package com.mist.pleos_app.effort

import com.mist.pleos_app.engine.dbl
import com.mist.pleos_app.engine.parseEffortConstants
import com.mist.pleos_app.engine.parseObject
import com.mist.pleos_app.engine.str
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 도착(명세 v0.5 F22) → 영수증(F23): 중간 정차 구분, 출발 기록 → 엔진 입력, 시연 데이터 영수증. */
class ArriveTest {

    private val fixtures: File = locate("fixtures")
    private val c = parseEffortConstants(File(fixtures, "constants.json").readText())
    private val demoText = File(fixtures, "effort/demo.json").readText()

    @Test
    fun midStopOnlyWhenGuidingWithDistanceLeft() {
        // F22: 기어 P·문이어도 경로 안내 중이고 남은 거리 > 0이면 도착이 아니라 정차
        assertTrue(isMidStop(GuideState(remainM = 12_000, routeGuiding = true)))
        assertFalse(isMidStop(GuideState(remainM = 0, routeGuiding = true)))          // 도착 지점
        assertFalse(isMidStop(GuideState(remainM = 12_000, routeGuiding = false)))    // 안내 끝남(SAFE_GUIDING)
        assertFalse(isMidStop(GuideState(remainM = null, routeGuiding = true)))
        assertFalse(isMidStop(null))                                                  // 못 읽으면 도착으로
    }

    @Test
    fun demoTripReceiptMatchesDemoFixture() {
        // 시연 순서(명세 12절): demo.json으로 출발 → 도착 → 영수증이 fixture 기대값과 같다
        val src = DemoEffortSource(parseDemoScene(demoText))
        val f = EffortFlow(MemoryEffortStore())
        f.depart(src.demoTrip("d1"))
        assertNull(receiptOf(f.state.openTrip, c))                     // 도착 전에는 없음
        f.arrive(src.arrivedAt(), src.demoWeatherArr())
        f.answerRest(0)
        val r = receiptOf(f.state.openTrip, c)!!
        val expected = parseObject(demoText).getValue("expected").jsonObject
        assertEquals(expected.str("headline_text"), r.headlineText)
        assertEquals(expected.dbl("effort_min"), r.effortMin, 1e-9)
        assertEquals(expected.dbl("multiplier"), r.multiplier, 0.0)
        assertEquals("정체 구간은 내비 안내 경로 기준", r.footnoteText)
        assertTrue(r.lines.all { it.basisText != null })                 // AC-F23-4
    }

    @Test
    fun restAnswerRecalculates() {
        // F21: 영수증에서 "30분"을 고르면 T가 줄고 다시 계산된다
        val src = DemoEffortSource(parseDemoScene(demoText))
        val f = EffortFlow(MemoryEffortStore())
        f.depart(src.demoTrip("d1"))
        f.arrive(src.arrivedAt(), src.demoWeatherArr())
        val before = receiptOf(f.state.openTrip, c)!!
        f.answerRest(30)
        val after = receiptOf(f.state.openTrip, c)!!
        assertEquals(130.0, before.driveMin, 1e-9)
        assertEquals(100.0, after.driveMin, 1e-9)
        assertTrue(after.headlineText.startsWith("오늘 운전 1시간 40분"))
    }

    @Test
    fun arrivalWeatherPendingThenCompleted() {
        // AC-F22-1: 날씨를 기다리지 않고 영수증부터(날씨 정보 없음) → 받으면 채운다. 출발 때 못 구한 일출·일몰도
        val f = EffortFlow(MemoryEffortStore())
        val trip = OpenTrip("t1", "2026-11-17T16:00:00+09:00", "두물머리", 2, 6300.0, weatherDep = "rain")
        f.depart(trip)
        assertTrue(f.arrive("2026-11-17T18:10:00+09:00", weatherArr = null))
        assertNull(f.state.openTrip?.weatherArr)
        val pending = receiptOf(f.state.openTrip, c)!!
        assertTrue(pending.notes.contains("날씨 정보 없음"))
        assertEquals(0.0, pending.nightMin, 1e-9)                         // 일출·일몰을 모르면 밤길 0
        f.completeArrival("rain", sunrise = "07:13", sunset = "17:20")
        f.completeArrival("none", sunrise = "06:00", sunset = "18:00")   // 이미 채운 값은 그대로
        val t = f.state.openTrip!!
        assertEquals("rain", t.weatherArr)
        assertEquals("17:20", t.sunset)
        val done = receiptOf(t, c)!!
        assertFalse(done.notes.contains("날씨 정보 없음"))
        assertEquals(50.0, done.nightMin, 1e-9)                           // 17:20~18:10
    }

    @Test
    fun inputCarriesTripValues() {
        val t = OpenTrip(
            "t1", "2026-11-17T16:00:00+09:00", "두물머리", 4, 6300.0, routeM = 104300.0,
            congestionRatio = 0.36, congestionM = 37548.0, weatherDep = "rain", sunrise = "07:13", sunset = "17:20",
            arrivedAt = "2026-11-17T18:10:00+09:00", weatherArr = "rain", restAnswerMin = 15,
        )
        val i = effortInputOf(t)!!
        assertEquals(15, i.restAnswerMin)
        assertEquals(0.36, i.congestionRatio!!, 0.0)
        assertEquals(104.3, i.distanceKm!!, 1e-9)
        assertEquals(7 * 60 + 13, i.sunriseMin)
        assertNull(effortInputOf(t.copy(arrivedAt = null)))
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
