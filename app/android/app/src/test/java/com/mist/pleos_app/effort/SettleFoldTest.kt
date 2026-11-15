package com.mist.pleos_app.effort

import com.mist.pleos_app.app.Settings
import com.mist.pleos_app.engine.parseEffortConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** 최종 영수증 하단 중간 정산 줄(#118 arrive-mid-*): 전체 모드 = 정산 몫을 빼지 않은 제안, 포함 모드 = 엔진 남은 몫 제안. */
class SettleFoldTest {
    private val fixtures = locate()
    private val c = parseEffortConstants(File(fixtures, "constants.json").readText())
    private val scene = parseDemoScene(File(fixtures, "effort/demo.json").readText())

    /** 시연: 휴게소에서 [좋아요]로 정산한 뒤 도착(demo.json midway_arrival = R28). */
    private fun arrivedAfterMidway(): com.mist.pleos_app.engine.EffortReceipt {
        val src = DemoEffortSource(scene)
        val m = scene.midway!!
        val trip = src.demoTrip("t").let { t ->
            val mid = midwayReceiptOf(t, m.at, m.remainM, m.weatherTrip, c)!!
            t.copy(settlements = listOf(Settlement(m.at, mid.proposal!!.remainKrw!!, Settings.METHOD_A, CHOICE_ACCEPT, null)))
        }.copy(arrivedAt = scene.arrivedAt, weatherArr = scene.weatherArr, restAnswerMin = 0)
        return receiptOf(trip, c)!!
    }

    @Test
    fun foldOnlyAfterMidway() {
        val plain = receiptOf(DemoEffortSource(scene).demoTrip("p").copy(arrivedAt = scene.arrivedAt, weatherArr = scene.weatherArr, restAnswerMin = 0), c)!!
        assertNull(settleFoldOf(plain, Settings.METHOD_A))
        val r = arrivedAfterMidway()
        val a = settleFoldOf(r, Settings.METHOD_A)!!
        assertEquals(r.proposal!!.amountText, a.allValue)                         // 전체 = 노고 금액
        assertEquals(com.mist.pleos_app.engine.approxText(r.proposal!!.remainKrw!!).second, a.incValue)
        val b = settleFoldOf(r, Settings.METHOD_B)!!
        assertEquals(r.driveText, b.allValue)
        assertNotNull(b.incValue)
    }

    @Test
    fun fullModeProposalIgnoresSettled() {
        val r = arrivedAfterMidway()
        val full = receiptForSettleMode(r, included = false, n = 4, c = c).proposal!!
        val inc = receiptForSettleMode(r, included = true, n = 4, c = c).proposal!!
        assertEquals(r.proposal, inc)                                            // 포함 모드 = 엔진 그대로
        assertNull(full.settledKrw)
        assertEquals(r.proposal!!.amountText, full.amountText)                   // 같은 노고 금액
        assertEquals("한 사람 약 9천 원", full.a.mainText)                         // 시안 arrive-mid-a: 전체 기준
        assertEquals("한 사람 약 5천 원", inc.a.mainText)                          // 시안 arrive-mid-a-open: 남은 몫
    }

    private fun locate(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, "fixtures")
            if (f.isDirectory && File(f, "constants.json").exists()) return f
            dir = dir.parentFile
        }
        throw IllegalStateException("fixtures 를 찾지 못함")
    }
}
