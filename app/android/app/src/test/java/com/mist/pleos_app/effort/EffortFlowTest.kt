package com.mist.pleos_app.effort

import com.mist.pleos_app.engine.parseObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 노고 흐름 상태(명세 v0.5 F20·F22·F26·X2, AC-C-11). */
class EffortFlowTest {

    private fun trip(id: String = "t1") = OpenTrip(
        tripId = id, departedAt = "2026-11-17T16:00:00+09:00", destName = "태안 꽃지해수욕장", n = 4, etaS = 6300.0,
        routeM = 104300.0, congestionRatio = 0.36, congestionM = 37548.0, weatherDep = "rain", sunrise = "07:13", sunset = "17:20",
    )

    private fun record(id: String = "t1", choice: String = "accept") = EffortRecord(
        id, "2026-11-17", "태안 꽃지해수욕장", 130.0, 1.57, 204.636, "A", "한 사람 약 9천 원", choice,
        "오늘 운전 2시간 10분 · 평소보다 1.57배 힘든 길",
    )

    @Test
    fun departOnceEvenIfCalledTwice() {
        // AC-F20-1: 같은 출발이 두 번 와도 1건
        val f = EffortFlow(MemoryEffortStore())
        assertEquals(EffortPhase.IDLE, f.phase)
        assertTrue(f.depart(trip()))
        assertFalse(f.depart(trip()))
        assertEquals(EffortPhase.DRIVING, f.phase)
        assertEquals("t1", f.state.openTrip?.tripId)
    }

    @Test
    fun openTripSurvivesRestart() {
        // X2·AC-C-11: 재시작해도 열린 출발 기록 유지 → 홈 "진행 중"
        val store = MemoryEffortStore()
        EffortFlow(store).depart(trip())
        val again = EffortFlow(store)
        assertEquals(EffortPhase.DRIVING, again.phase)
        assertEquals("태안 꽃지해수욕장", again.state.openTrip?.destName)
    }

    @Test
    fun arriveOnlyOnceAndOnlyWithTrip() {
        val f = EffortFlow(MemoryEffortStore())
        // X2: 출발 기록 없이 도착 신호 → 무시
        assertFalse(f.arrive("2026-11-17T18:10:00+09:00", "rain"))
        assertEquals(EffortPhase.IDLE, f.phase)
        f.depart(trip())
        assertTrue(f.arrive("2026-11-17T18:10:00+09:00", "rain"))
        // AC-F22-2: 두 번째 도착은 무시(영수증이 두 번 생기지 않음)
        assertFalse(f.arrive("2026-11-17T18:30:00+09:00", "none"))
        assertEquals(EffortPhase.ARRIVED, f.phase)
        assertEquals("2026-11-17T18:10:00+09:00", f.state.openTrip?.arrivedAt)
        assertEquals("rain", f.state.openTrip?.weatherArr)
    }

    @Test
    fun restAnswerOnlyAfterArrival() {
        // F21: 정차는 도착 때 답 하나로만(#62). 도착 전에는 받지 않는다
        val f = EffortFlow(MemoryEffortStore())
        f.depart(trip())
        f.answerRest(30)
        assertEquals(null, f.state.openTrip?.restAnswerMin)
        f.arrive("2026-11-17T18:10:00+09:00")
        f.answerRest(30)
        assertEquals(30, f.state.openTrip?.restAnswerMin)
    }

    @Test
    fun finishKeepsOneRecordAndClosesTrip() {
        // AC-F26-1·2: 어떤 선택이든 기록 1건, 재시작해도 남음
        val store = MemoryEffortStore()
        val f = EffortFlow(store)
        f.depart(trip())
        assertFalse(f.finish(record()))                        // 도착 전에는 기록하지 않음
        f.arrive("2026-11-17T18:10:00+09:00")
        assertFalse(f.finish(record("other")))                 // 다른 출발의 기록은 받지 않음
        assertTrue(f.finish(record(choice = "close")))
        assertFalse(f.finish(record()))                        // 닫힌 뒤 다시 와도 1건
        assertEquals(EffortPhase.IDLE, f.phase)
        val again = EffortFlow(store)
        assertEquals(1, again.state.records.size)
        assertEquals("close", again.state.records[0].choice)
    }

    @Test
    fun recentIsNewestFirstThree() {
        val f = EffortFlow(MemoryEffortStore())
        for (i in 1..4) {
            f.depart(trip("t$i"))
            f.arrive("2026-11-17T18:10:00+09:00")
            f.finish(record("t$i"))
        }
        assertEquals(listOf("t4", "t3", "t2"), f.recent().map { it.tripId })
        assertEquals(4, f.state.records.size)
    }

    @Test
    fun departAgainReplacesUnfinishedTrip() {
        val f = EffortFlow(MemoryEffortStore())
        f.depart(trip("t1"))
        assertTrue(f.depart(trip("t2")))
        assertEquals("t2", f.state.openTrip?.tripId)
        f.discardTrip()
        assertEquals(EffortPhase.IDLE, f.phase)
    }

    @Test
    fun stopLeavesNoRecord() {
        // AC-F27-1: [멈추기] 뒤 기록이 0건. 멈추기는 EffortViewModel.discardTrip() → EffortFlow.discardTrip()이다.
        val store = MemoryEffortStore()
        val f = EffortFlow(store)
        f.depart(trip())
        f.discardTrip()
        assertEquals(EffortPhase.IDLE, f.phase)
        assertNull(f.state.openTrip)
        assertEquals(0, f.state.records.size)
        assertTrue(f.recent().isEmpty())
        val again = EffortFlow(store)                         // 재시작 뒤에도 0건
        assertEquals(0, again.state.records.size)
        assertEquals(EffortPhase.IDLE, again.phase)
    }

    @Test
    fun stateJsonRoundTripHasNoCoordinates() {
        // AC-F20-4·AC-N-4: 저장 JSON에 좌표 필드가 없다
        val s = EffortState(
            openTrip = trip().copy(arrivedAt = "2026-11-17T18:10:00+09:00", weatherArr = "rain", restAnswerMin = 0, demo = true),
            records = listOf(record()),
        )
        val json = s.toJson()
        assertEquals(s, parseEffortState(parseObject(json.toString())))
        val text = json.toString()
        for (k in listOf("\"lat\"", "\"lng\"", "\"lon\"", "latitude", "longitude")) assertFalse(k, text.contains(k))
    }

    @Test
    fun demoSceneBuildsTripFromDemoJson() {
        // fixtures/effort/demo.json(#55) 모양: scene + input
        val text = """
            {"id":"demo","scene":{"dest_name":"태안 꽃지해수욕장","route_m":104300,"n":4,"rest_answer_label":"없음"},
             "input":{"departed_at":"2026-11-17T16:00:00+09:00","arrived_at":"2026-11-17T18:10:00+09:00","rest_answer_min":0,
                      "stops":null,"eta_s":6300,"congestion_ratio":0.36,"congestion_m":37548.0,"congestion_s":null,
                      "weather_dep":"rain","weather_arr":"rain","sunrise":"07:13","sunset":"17:20","n":4,"distance_km":104.3}}
        """.trimIndent()
        val src = DemoEffortSource(parseDemoScene(text))
        assertTrue(src.demo)
        val t = src.demoTrip("d1")
        assertEquals(trip("d1").copy(demo = true), t)
        assertEquals("2026-11-17T18:10:00+09:00", src.arrivedAt())
        assertEquals("rain", src.demoWeatherArr())
        assertNull(LiveEffortSource { "x" }.demoTrip())
    }
}
