package com.mist.pleos_app.effort

import com.mist.pleos_app.engine.parseEffortConstants
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** 도착 화면(Design v2 arrive, #75)에 쓰는 표기 도움 함수. */
class ArriveViewTest {

    private val c = parseEffortConstants(File(locate("fixtures"), "constants.json").readText())

    @Test
    fun receiptCodeFromTripAndEngine() {
        // 시안 "#2026 1107 104 130"과 같은 꼴: 연 · 월일 · 경로 km · 운전 분
        val t = OpenTrip(
            "t1", "2026-11-17T16:00:00+09:00", "태안 꽃지해수욕장", 4, 6300.0, routeM = 104300.0,
            congestionRatio = 0.36, congestionM = 37548.0, weatherDep = "rain", sunrise = "07:13", sunset = "17:20",
            arrivedAt = "2026-11-17T18:10:00+09:00", weatherArr = "rain", restAnswerMin = 0,
        )
        val r = receiptOf(t, c)!!
        assertEquals("#2026 1117 104 130", receiptCode(t, r))
        assertEquals("#2026 1117 104 100", receiptCode(t, receiptOf(t.copy(restAnswerMin = 30), c)!!))
        assertEquals("#2026 1117 000 130", receiptCode(t.copy(routeM = null), r))
    }

    @Test
    fun particleAndDay() {
        assertEquals("밥이에요", ieyo("밥"))
        assertEquals("커피예요", ieyo("커피"))
        assertEquals("공동 경비예요", ieyo("공동 경비"))
        assertEquals("11월 7일", dayText("2026-11-07T16:00:00+09:00"))
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
