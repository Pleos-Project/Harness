package com.mist.pleos_app.effort

import com.mist.pleos_app.engine.parseEffortConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 오늘 운전 돌아보기 기준 5줄(명세 v0.6 F29, 명준 11/14 아티팩트 rv-*): 값은 엔진 영수증에서, 금액 · 배수 · 퍼센트는 없다(AC-F29-1). */
class ReviewTest {

    private val c = parseEffortConstants(File(locate("fixtures"), "constants.json").readText())
    private val w = ReviewWords(none = "없음", unknown = "정보 없음", whole = "%1\$s · 내내", depOnly = "출발할 때 %1\$s", longOver = "2시간 넘게")

    private val r1 = OpenTrip(
        "t1", "2026-11-17T16:00:00+09:00", "태안 꽃지해수욕장", 4, 6300.0, routeM = 104300.0,
        congestionRatio = 0.36, congestionM = 37548.0, weatherDep = "rain", sunrise = "07:13", sunset = "17:20",
        arrivedAt = "2026-11-17T18:10:00+09:00", weatherArr = "rain",
    )

    @Test
    fun representativeSceneMatchesDoneFrame() {
        // 시안 rv-done: 비 · 내내 · 38km · 25분 · 50분 · 2시간 넘게
        val items = reviewItems(r1, receiptOf(r1, c)!!, c, w)
        assertEquals(listOf("weather", "congestion", "delay", "night", "fatigue"), items.map { it.key })
        assertEquals(listOf("비 · 내내", "38km", "25분", "50분", "2시간 넘게"), items.map { it.value })
        assertTrue(items.all { it.on })
        assertEquals(listOf(0, 38, 25, 50, 0), items.map { it.countTo })
        assertEquals(listOf(ReviewPose.SHY, ReviewPose.FOCUS, ReviewPose.SHY, ReviewPose.FOCUS, ReviewPose.RELIEF), items.map { it.pose })
        // 금액 · 배수 · 퍼센트 없음(AC-F29-1)
        assertTrue(items.none { it.value.contains("%") || it.value.contains("원") || it.value.contains("배") })
        // 예상보다 더: 예상 도착 시각(출발 16:00 + ETA 105분) → 큰 숫자가 25분 넘어감(HANDOFF_v4 6-4)
        assertEquals(ReviewFx.LATE, items[2].fx)
        assertEquals("17:45", items[2].lateFrom)
        assertEquals("18:10", hhmm(items[2].lateFromMin + items[2].countTo))
        // 장시간 눈금 70%
        assertEquals(.70f, items[4].barMark)
        // 약 10초(시안: 있는 줄 1.6초 × 5 + 시작 0.6 + 마무리 0.9)
        assertEquals(9500L, reviewTotalMs(items))
    }

    @Test
    fun clearShortTripIsAllNone() {
        // rv-clear: 다 없음이면 점선 원 + "없음" 5줄
        val short = r1.copy(etaS = 600.0, arrivedAt = "2026-11-17T16:10:00+09:00", weatherDep = "none", weatherArr = "none",
            congestionRatio = 0.0, congestionM = 0.0, routeM = 4000.0, sunset = "18:00", n = 2)
        val items = reviewItems(short, receiptOf(short, c)!!, c, w)
        assertTrue(items.none { it.on })
        assertEquals(List(5) { "없음" }, items.map { it.value })
        assertEquals(CalcSay.WEATHER_CLEAR, items[0].say)
    }

    @Test
    fun unknownWeatherAndJam() {
        // 날씨 · 정체를 못 받음 → "정보 없음"(점선 원)
        val unk = r1.copy(weatherDep = WEATHER_UNKNOWN, weatherArr = WEATHER_UNKNOWN, congestionRatio = null, congestionM = null)
        val items = reviewItems(unk, receiptOf(unk, c)!!, c, w)
        assertFalse(items[0].on)
        assertEquals("정보 없음", items[0].value)
        assertEquals(CalcSay.WEATHER_NONE, items[0].say)
        assertFalse(items[1].on)
        assertEquals("정보 없음", items[1].value)
    }

    @Test
    fun departureOnlyRain() {
        // 날씨를 반만 받음(출발 비 · 도착 모름, #149 D7) → "출발할 때 비"
        val half = r1.copy(weatherArr = WEATHER_UNKNOWN)
        val items = reviewItems(half, receiptOf(half, c)!!, c, w)
        assertTrue(items[0].on)
        assertEquals("출발할 때 비", items[0].value)
    }

    @Test
    fun castTableIsSingleSource() {
        // 표정 → 기준 짝(6-5)은 ReviewCast 한곳에서. 기쁨(joy)은 넣지 않는다
        assertEquals(listOf("FOCUS", "SHY", "RELIEF", "PROUD"), ReviewPose.values().map { it.name })
        assertEquals(ReviewPose.SHY to ReviewMotion.SLUMP, ReviewCast.byKey["delay"])
        assertEquals(ReviewPose.PROUD to ReviewMotion.JUMP, ReviewCast.done)
    }

    @Test
    fun clockWrapsAfterMidnight() {
        assertEquals("00:15", hhmm(24 * 60 + 15))
        val late = r1.copy(departedAt = "2026-11-17T23:30:00+09:00", etaS = 3600.0)
        assertEquals("00:30", reviewItems(late, receiptOf(late, c)!!, c, w)[2].lateFrom)
        assertEquals(null, reviewItems(r1.copy(etaS = 0.0), receiptOf(r1.copy(etaS = 0.0), c)!!, c, w)[2].lateFrom)
    }

    @Test
    fun departureUnknownArrivalRainIsNotDepartureOnly() {
        // 출발 날씨를 못 받고 도착만 비 → "출발할 때 비"가 아니다
        val arrOnly = r1.copy(weatherDep = WEATHER_UNKNOWN)
        val items = reviewItems(arrOnly, receiptOf(arrOnly, c)!!, c, w)
        assertTrue(items[0].on)
        assertEquals("비", items[0].value)
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
