package com.mist.pleos_app.effort

import com.mist.pleos_app.engine.parseEffortConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** 홈(Design v2 home, #75) 표기: 조사, 기록 카드 요약 줄, 출발·도착 예상 시각, 기록의 인원 저장. */
class HomeViewTest {

    private val c = parseEffortConstants(File(locate("fixtures"), "constants.json").readText())

    @Test
    fun particleRo() {
        assertEquals("태안 꽃지해수욕장으로", euro("태안 꽃지해수욕장"))
        assertEquals("양평 두물머리로", euro("양평 두물머리"))
        assertEquals("서울로", euro("서울"))            // ㄹ받침
        assertEquals("probe dest으로", euro("probe dest"))
    }

    @Test
    fun recordSummaryLikeDesign() {
        // 시안: "2시간 10분 · 1.57배 · 4명", "1시간 · 평소 같은 길 · 2명"(배수 1.00은 엔진 문구)
        val r = EffortRecord("t1", "2026-11-17", "태안 꽃지해수욕장", 130.0, 1.57, 204.0, "A", "한 사람 약 9천 원", "accept", "", n = 4)
        assertEquals("2시간 10분 · 1.57배 · 4명", recordSummary(r, c))
        assertEquals("1시간 · 평소 같은 길 · 2명", recordSummary(r.copy(driveMin = 60.0, multiplier = 1.0, n = 2), c))
        assertEquals("55분 · 1.20배", recordSummary(r.copy(driveMin = 55.0, multiplier = 1.2, n = null), c))   // 예전 기록(인원 없음)
    }

    @Test
    fun clocks() {
        val t = OpenTrip("t1", "2026-11-17T14:20:00+09:00", "태안 꽃지해수욕장", 4, 6300.0)
        assertEquals("14:20", clockText(t.departedAt))
        assertEquals("16:05", etaClock(t))
        assertNull(etaClock(t.copy(etaS = 0.0)))
    }

    @Test
    fun recordKeepsPeopleCount() {
        val r = EffortRecord("t1", "2026-11-17", "꽃지", 130.0, 1.57, 204.0, "B", "meal", "coffee", "h", n = 3)
        assertEquals(3, parseEffortRecord(r.toJson()).n)
        assertNull(parseEffortRecord(r.copy(n = null).toJson()).n)
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
