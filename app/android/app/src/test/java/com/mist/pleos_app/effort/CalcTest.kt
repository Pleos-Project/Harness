package com.mist.pleos_app.effort

import com.mist.pleos_app.engine.parseEffortConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 계산 연출 체크포인트 6개(Design v2 calc, #75): 값은 엔진 영수증에서, 규칙은 프로토타입 calcItems와 같게. */
class CalcTest {

    private val c = parseEffortConstants(File(locate("fixtures"), "constants.json").readText())

    private val r1 = OpenTrip(
        "t1", "2026-11-17T16:00:00+09:00", "태안 꽃지해수욕장", 4, 6300.0, routeM = 104300.0,
        congestionRatio = 0.36, congestionM = 37548.0, weatherDep = "rain", sunrise = "07:13", sunset = "17:20",
        arrivedAt = "2026-11-17T18:10:00+09:00", weatherArr = "rain", restAnswerMin = 0,
    )

    @Test
    fun representativeSceneMatchesDesignDoneFrame() {
        // 시안 calc-done: 비 +40% · 38km +10% · 25분 +5% · 50분 +2% · 10분 +1% · 104km
        val items = calcItems(r1, receiptOf(r1, c)!!, c, "거리")
        assertEquals(listOf("weather", "congestion", "delay", "night", "fatigue", "distance"), items.map { it.key })
        assertEquals(listOf("비 +40%", "38km +10%", "25분 +5%", "50분 +2%", "10분 +1%", "104km"), items.map { it.value })
        assertEquals(listOf(CalcFx.RAIN, CalcFx.JAM, CalcFx.LATE, CalcFx.NIGHT, CalcFx.LONG, CalcFx.DIST), items.map { it.fx })
        assertEquals("+25분", items[2].tag)
        assertEquals("+10분", items[4].tag)
        assertEquals(104, items[5].km)
        assertEquals(CalcSay.DIST_GROUP, items[5].say)
        assertTrue(items.none { it.zero })
    }

    @Test
    fun valueSplitsIntoMainAndPctForTwoLines() {
        // #118 D1: 가로 창은 원 아래 값을 두 줄(위 "25분" / 아래 "+5%"). 거리는 % 없이 한 줄
        val items = calcItems(r1, receiptOf(r1, c)!!, c, "거리")
        assertEquals(listOf("비", "38km", "25분", "50분", "10분", "104km"), items.map { it.main })
        assertEquals(listOf("+40%", "+10%", "+5%", "+2%", "+1%", null), items.map { it.pct })
        // "+1% 미만"만 작은 글자(#118 D5 · S2)
        assertTrue(isUnderOnePct("+1% 미만"))
        assertFalse(isUnderOnePct("+1%"))
        assertFalse(isUnderOnePct(null))
    }

    @Test
    fun clearShortTripIsGrayAndUnknownWeather() {
        // 맑은 낮 짧은 주행: 날씨 맑음(해·낙엽), 정체 없음("쌩"), 나머지 없음 → 회색 원
        val short = r1.copy(etaS = 600.0, arrivedAt = "2026-11-17T16:10:00+09:00", weatherDep = "none", weatherArr = "none",
            congestionRatio = 0.0, congestionM = 0.0, routeM = 4000.0, sunset = "18:00", n = 1)
        val items = calcItems(short, receiptOf(short, c)!!, c, "거리")
        assertEquals(CalcFx.LEAVES, items[0].fx)
        assertTrue(items[0].zero)
        assertNull(items[0].value)
        assertEquals(CalcFx.ZIP, items[1].fx)
        assertEquals(CalcFx.SUN, items[3].fx)
        assertEquals(CalcSay.DIST_SOLO, items[5].say)
        assertFalse(items[5].zero)
        // 날씨를 못 받으면 "정보 없음"
        val unk = calcItems(r1.copy(weatherDep = "unknown", weatherArr = "unknown"), receiptOf(r1.copy(weatherDep = "unknown", weatherArr = "unknown"), c)!!, c, "거리")
        assertTrue(unk[0].unknown)
        assertEquals(CalcFx.NONE, unk[0].fx)
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
