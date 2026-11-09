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
        assertEquals("출발 때 내비가 안내한 경로의 36%가 막혀 있었어요", r.footnoteText)
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

    @Test
    fun noAutoArrivalOnResumeWithoutEta() {
        // 오프라인 등으로 출발 때 ETA를 못 받았으면 내비 경로도 실패했을 수 있다 → 다시 열 때 자동 도착하지 않음(에뮬 11/8)
        val noEta = OpenTrip("t1", "2026-11-17T16:00:00+09:00", "서울식물원", 1, 0.0)
        assertFalse(autoArrivalAllowed(noEta, ArrivalCue.RESUME))
        assertFalse(autoArrivalAllowed(noEta, ArrivalCue.DOOR_ON_SHOW))
        assertTrue(autoArrivalAllowed(noEta, ArrivalCue.DOOR))                     // 보이는 동안 문이 열리는 순간은 도착
        val withEta = noEta.copy(etaS = 2000.0)
        ArrivalCue.entries.forEach { assertTrue(autoArrivalAllowed(withEta, it)) }  // ETA가 있으면 지금처럼
        ArrivalCue.entries.forEach { assertTrue(autoArrivalAllowed(noEta.copy(demo = true), it)) }
    }

    @Test
    fun unknownGuideArrivesOnlyByDoor() {
        // 에뮬 11/9: 다시 보인 직후 내비 연결이 늦어 상태를 못 읽으면(null) 다시 보임으로는 도착하지 않음. 문이 열린 순간은 도착
        assertFalse(arrivalByGuide(null, ArrivalCue.RESUME))
        assertFalse(arrivalByGuide(null, ArrivalCue.DOOR_ON_SHOW))
        assertTrue(arrivalByGuide(null, ArrivalCue.DOOR))
        val guiding = GuideState(14000, true)
        ArrivalCue.entries.forEach { assertFalse(arrivalByGuide(guiding, it)) }          // 안내 중 + 남은 거리 → 중간 정차
        val done = GuideState(0, false)
        ArrivalCue.entries.forEach { assertTrue(arrivalByGuide(done, it)) }              // 안내가 끝났으면 도착
        assertTrue(arrivalByGuide(GuideState(null, false), ArrivalCue.RESUME))          // 한쪽만 읽어도 안내 아님이면 도착
    }

    @Test
    fun noAutoArrivalOnResumeRightAfterDeparture() {
        // 출발 직후 다시 열면 내비 안내 시작 전이라 "안내 안 함"으로 답한다 → 1분 안에는 다시 보임으로 도착하지 않음(에뮬 11/9)
        val t = OpenTrip("t1", "2026-11-17T16:00:00+09:00", "서울식물원", 1, 2000.0)
        val early = "2026-11-17T16:00:05+09:00"
        assertFalse(autoArrivalAllowed(t, ArrivalCue.RESUME, early))
        assertFalse(autoArrivalAllowed(t, ArrivalCue.DOOR_ON_SHOW, early))
        assertTrue(autoArrivalAllowed(t, ArrivalCue.DOOR, early))                       // 보이는 동안 문이 열리는 순간은 도착
        assertTrue(autoArrivalAllowed(t.copy(demo = true), ArrivalCue.RESUME, early))   // 시연은 그대로
        assertFalse(autoArrivalAllowed(t, ArrivalCue.RESUME, "2026-11-17T16:00:59+09:00"))
        assertTrue(autoArrivalAllowed(t, ArrivalCue.RESUME, "2026-11-17T16:01:00+09:00"))
        assertTrue(autoArrivalAllowed(t, ArrivalCue.RESUME, "2026-11-17T16:40:00+09:00"))
    }

    @Test
    fun lateArrivalIsUnsettledWithoutProposal() {
        // #68·#71: ETA 105분인데 211분 뒤에 도착이 잡히면(앱을 늦게 다시 엶) 영수증 대신 정리하지 못함
        val t = OpenTrip(
            "t1", "2026-11-17T16:00:00+09:00", "태안 꽃지해수욕장", 4, 6300.0, routeM = 104300.0,
            weatherDep = "rain", sunrise = "07:13", sunset = "17:20",
            arrivedAt = "2026-11-17T19:31:00+09:00", weatherArr = "rain",
        )
        val r = receiptOf(t, c)!!
        assertTrue(isUnsettled(r))
        assertEquals(c.copy.text.getValue("unsettled_text"), r.statusText)
        assertNull(proposalViewOf(r, com.mist.pleos_app.app.Settings.METHOD_A, c.items))   // 제안으로 가지 않음
        assertTrue(isUnsettled(receiptOf(t.copy(restAnswerMin = 60), c)))                     // 쉰 시간을 답해도 그대로
        // 210분(상한과 같음)이면 영수증
        assertFalse(isUnsettled(receiptOf(t.copy(arrivedAt = "2026-11-17T19:30:00+09:00"), c)))
        assertFalse(isUnsettled(null))
    }

    @Test
    fun unsettledCloseLeavesUnsettledRecord() {
        // 명준 #75 11/9(#71 바꿈): 정리하지 못함도 영수증·제안 없이 "정리하지 못함" 기록 1건을 남기고 닫는다
        val f = EffortFlow(MemoryEffortStore())
        f.depart(OpenTrip("t1", "2026-11-17T16:00:00+09:00", "태안 꽃지해수욕장", 4, 6300.0))
        f.arrive("2026-11-18T09:00:00+09:00", "none")
        val trip = f.state.openTrip!!
        val r = receiptOf(trip, c)!!
        assertTrue(isUnsettled(r))
        assertTrue(f.finish(recordOf(trip, r, null, "A", CHOICE_UNSETTLED)))
        assertEquals(EffortPhase.IDLE, f.phase)
        assertEquals(CHOICE_UNSETTLED, f.recent().single().choice)
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
