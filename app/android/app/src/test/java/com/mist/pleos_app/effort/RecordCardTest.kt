package com.mist.pleos_app.effort

import com.mist.pleos_app.app.Settings
import com.mist.pleos_app.engine.parseEffortConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 홈 기록 카드(#118 HANDOFF_v3 5-2): 배수 · 길 조건 칩(더한 몫 큰 순 최대 3개 + 남은 수) · 시간·인원. */
class RecordCardTest {

    private val fixtures = locate("fixtures")
    private val c = parseEffortConstants(File(fixtures, "constants.json").readText())
    private val scene = parseDemoScene(File(fixtures, "effort/demo.json").readText())

    @Test
    fun demoRecordChipsFollowReceipt() {
        val trip = DemoEffortSource(scene).demoTrip("d1").copy(arrivedAt = scene.arrivedAt, weatherArr = scene.weatherArr, restAnswerMin = 0)
        val receipt = receiptOf(trip, c)!!
        val rec = recordOf(trip, receipt, proposalViewOf(receipt, Settings.METHOD_A, c.items), Settings.METHOD_A, CHOICE_ACCEPT)
        val v = recordCardOf(rec, c)
        val added = receipt.lines.filter { it.addMin > 0 }.sortedByDescending { it.addMin }.map { it.key }
        assertEquals(added.take(3), v.chips)                       // 엔진 영수증 줄 그대로, 큰 순
        assertEquals((added.size - 3).coerceAtLeast(0), v.more)
        assertTrue(receipt.multiplierText.contains(v.multiplier!! + "배"))
        assertNull(v.flatText)
        assertTrue(v.meta.endsWith("4명"))
    }

    @Test
    fun flatUnsettledAndOld() {
        val old = EffortRecord("t0", "2026-11-02", "가평", 60.0, 1.0, 60.0, "A", null, "close", "", n = 2)
        val v = recordCardOf(old, c)
        assertNull(v.multiplier)
        assertEquals("평소와 같은 길", v.flatText)
        assertEquals(emptyList<String>(), v.chips)                 // 예전 기록(입력 없음): 칩 없음
        assertEquals("1시간 · 2명", v.meta)
        assertEquals("1.57", recordCardOf(old.copy(multiplier = 1.57), c).multiplier)
        assertEquals("혼자", recordCardOf(old.copy(n = 1), c).meta.substringAfter(" · "))
        val un = recordCardOf(old.copy(choice = CHOICE_UNSETTLED, n = 4), c)
        assertEquals(UNSETTLED_LABEL, un.flatText)
        assertEquals("4명", un.meta)
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
