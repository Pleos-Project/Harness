package com.mist.pleos_app.effort

import com.mist.pleos_app.engine.parseEffortConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** F20 ② 나란히 배치 정체 구간 목록(AC-F20-2): 거리 ÷ 평균 속도 합, 빈 목록은 다시 묻기, 출발 기록에 한 번만. */
class CongestionTest {

    private val c = parseEffortConstants(File(locate("fixtures"), "constants.json").readText())

    @Test
    fun secondsAreDistanceOverAverageSpeed() {
        val l = congestionFromList(listOf(
            CongestionSection(distanceM = 3000.0, avrSpeedKmh = 18.0, timeS = 660, jam = true),    // 600초(목록 시간 660초는 안 씀)
            CongestionSection(distanceM = 1000.0, avrSpeedKmh = 36.0, timeS = 120, jam = true),    // 100초
            CongestionSection(distanceM = 5000.0, avrSpeedKmh = 60.0, timeS = 300, jam = false),   // 원활: 빼기
        ))!!
        assertEquals(700.0, l.seconds, 1e-9)
        assertEquals(4000.0, l.meters, 1e-9)
        assertEquals(3, l.sections)
    }

    @Test
    fun emptyListMeansAskAgainAndNoJamMeansZero() {
        assertNull(congestionFromList(emptyList()))                                    // 첫 응답 0개 → 다시 묻기
        val smooth = congestionFromList(listOf(CongestionSection(2000.0, 50.0, 180, jam = false)))!!
        assertEquals(0.0, smooth.seconds, 0.0)                                         // 구간은 있는데 정체 없음 → 0
        assertEquals(1, smooth.sections)
    }

    @Test
    fun firstAnswerIsOnlyBaselineBecauseItMayBePreviousRoute() {
        // 11/8 실측: onRouteStarted 직후 직전 경로 4구간 → 2초 뒤 새 경로 19구간
        val old = listOf(CongestionSection(290.0, 20.0, 60, jam = true))
        val fresh = listOf(CongestionSection(1530.0, 25.0, 300, jam = true), CongestionSection(800.0, 70.0, 60, jam = false))
        val base = congestionSignature(old)
        assertEquals(CongestionStep.Retry, congestionStep(1, 3, old, base))              // 1회: 목록이 있어도 기준만
        assertEquals(CongestionStep.Retry, congestionStep(2, 3, old, base))              // 2회: 같으면 다시
        val take = congestionStep(3, 3, fresh, base) as CongestionStep.Take              // 3회: 달라졌으면 쓴다
        assertEquals(1530.0, take.list.meters, 0.0)
        assertEquals(CongestionStep.GiveUp, congestionStep(3, 3, old, base))             // 끝까지 같으면 그만(비율 그대로)
    }

    @Test
    fun emptyFirstAnswerThenListIsTaken() {
        val base = congestionSignature(emptyList())                                     // #51: 첫 응답 0개
        val list = listOf(CongestionSection(1000.0, 30.0, 120, jam = true))
        assertTrue(congestionStep(2, 3, list, base) is CongestionStep.Take)
        assertEquals(CongestionStep.GiveUp, congestionStep(3, 3, emptyList(), base))     // 끝내 0개 → 비율 그대로
    }

    @Test
    fun zeroSpeedFallsBackToSectionTime() {
        val l = congestionFromList(listOf(CongestionSection(500.0, 0.0, 240, jam = true)))!!
        assertEquals(240.0, l.seconds, 0.0)
    }

    @Test
    fun tripTakesListOnceBeforeArrival() {
        val f = EffortFlow(MemoryEffortStore())
        f.depart(OpenTrip("t1", "2026-11-17T16:00:00+09:00", "양평", 2, 6300.0, routeM = 100_000.0, congestionRatio = 0.36, congestionM = 36_000.0))
        val list = CongestionList(seconds = 1800.0, meters = 20_000.0, sections = 7)
        assertTrue(f.setCongestionList(list))
        val t = f.state.openTrip!!
        assertEquals(1800.0, t.congestionS!!, 0.0)
        assertEquals(20_000.0, t.congestionM!!, 0.0)
        assertEquals(0.2, t.congestionRatio!!, 1e-9)                                     // km·비율도 같은 목록에서
        assertEquals(1800.0, parseOpenTrip(t.toJson()).congestionS!!, 0.0)               // 저장·복원
        assertFalse(f.setCongestionList(list.copy(seconds = 60.0)))                     // 두 번째(같은 콜백 중복)는 무시
        f.arrive("2026-11-17T18:10:00+09:00")
        assertFalse(EffortFlow(MemoryEffortStore()).setCongestionList(list))             // 열린 출발 없음
    }

    @Test
    fun noListForDemoOrAfterArrival() {
        val demo = EffortFlow(MemoryEffortStore())
        demo.depart(OpenTrip("d", "2026-11-17T16:00:00+09:00", "태안", 4, 6300.0, demo = true))
        assertFalse(demo.setCongestionList(CongestionList(600.0, 5000.0, 3)))
        val f = EffortFlow(MemoryEffortStore())
        f.depart(OpenTrip("t1", "2026-11-17T16:00:00+09:00", "양평", 2, 6300.0))
        f.arrive("2026-11-17T18:10:00+09:00")
        assertFalse(f.setCongestionList(CongestionList(600.0, 5000.0, 3)))
    }

    @Test
    fun receiptUsesListMinutes() {
        // 같은 주행에서 비율(36% × 130분 = 46.8분) 대신 목록 30분을 쓴다(엔진: min(congestion_s, T))
        val base = OpenTrip(
            "t1", "2026-11-17T16:00:00+09:00", "양평", 2, 6300.0, routeM = 104_300.0, congestionRatio = 0.36, congestionM = 37_548.0,
            arrivedAt = "2026-11-17T18:10:00+09:00", weatherDep = "none", weatherArr = "none", sunrise = "07:13", sunset = "19:00", restAnswerMin = 0,
        )
        val byRatio = receiptOf(base, c)!!
        val byList = receiptOf(base.copy(congestionS = 1800.0, congestionM = 20_000.0), c)!!
        assertEquals(46.8, byRatio.congestionMin, 1e-9)
        assertEquals(30.0, byList.congestionMin, 1e-9)
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
