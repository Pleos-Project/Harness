package com.mist.pleos_app.effort

import com.mist.pleos_app.app.Settings
import com.mist.pleos_app.engine.parseEffortConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** 주행 기록 목록 · 상세(Q-08-01, #75 v41): 목록 줄, 기록에 남기는 엔진 입력(좌표 없음), 상세 영수증 다시 계산. */
class RecordViewTest {

    private val fixtures = locate("fixtures")
    private val c = parseEffortConstants(File(fixtures, "constants.json").readText())
    private val scene = parseDemoScene(File(fixtures, "effort/demo.json").readText())

    private fun arrivedDemo(): OpenTrip = DemoEffortSource(scene).demoTrip("d1")
        .copy(arrivedAt = scene.arrivedAt, weatherArr = scene.weatherArr, restAnswerMin = 0, depLat = 37.40, depLng = 127.11)

    @Test
    fun logLineLikeDesign() {
        // 시안: "2시간 10분 · 평소보다 1.57배 · 함께 4명", "1시간 · 평소 같은 길 · 함께 2명"(1.00은 엔진 문구)
        val r = EffortRecord("t1", "2026-11-17", "태안 꽃지해수욕장", 130.0, 1.57, 204.0, "A", "한 사람 약 9천 원", "accept", "", n = 4)
        assertEquals("2시간 10분 · 평소보다 1.57배 · 함께 4명", recordLogLine(r, c))
        assertEquals("1시간 · 평소 같은 길 · 함께 2명", recordLogLine(r.copy(driveMin = 60.0, multiplier = 1.0, n = 2), c))
        assertEquals("55분 · 평소보다 1.14배", recordLogLine(r.copy(driveMin = 55.0, multiplier = 1.14, n = null), c))
    }

    @Test
    fun unsettledLeavesRecordWithoutReceipt() {
        // 명준 #75 11/9: 정리하지 못함도 기록 1건. 목록·카드 "정리하지 못함", 상세는 영수증 없이
        val trip = arrivedDemo().copy(arrivedAt = "2026-11-10T23:59:00+09:00")    // 경과가 상한(ETA × 2 등)을 넘음
        val receipt = receiptOf(trip, c)!!
        assertEquals(RECEIPT_UNSETTLED, receipt.status)
        val rec = recordOf(trip, receipt, null, Settings.METHOD_A, CHOICE_UNSETTLED)
        assertNull(rec.suggestion)
        assertNull(rec.trip!!.depLat)
        assertEquals("정리하지 못함 · 함께 4명", recordLogLine(rec, c))
        assertEquals("정리하지 못함 · 4명", recordSummary(rec, c))
        assertNull(recordReceiptOf(rec, c))
        assertEquals(rec, parseEffortRecord(rec.toJson()))
    }

    @Test
    fun recordKeepsEngineInputWithoutCoords() {
        val trip = arrivedDemo()
        val receipt = receiptOf(trip, c)!!
        val view = proposalViewOf(receipt, Settings.METHOD_A, c.items)
        val rec = recordOf(trip, receipt, view, Settings.METHOD_A, CHOICE_ACCEPT)
        assertNull(rec.trip!!.depLat)                                              // AC-N-4: 좌표는 기록에 남지 않음
        assertNull(rec.trip!!.depLng)
        val back = parseEffortRecord(rec.toJson())
        assertEquals(rec, back)                                                    // 저장·복원
        assertFalse(rec.toJson().toString().contains("37.4"))
        // 상세 영수증 = 그날 영수증(같은 상수)
        val again = recordReceiptOf(back, c)!!
        assertEquals(receipt.multiplierText, again.multiplierText)
        assertEquals(receipt.lines.map { it.key to it.pctText }, again.lines.map { it.key to it.pctText })
        assertNotNull(again.proposal?.amountText)
    }

    @Test
    fun oldRecordWithoutInputHasNoReceipt() {
        val old = parseEffortRecord(
            EffortRecord("t0", "2026-11-02", "가평", 60.0, 1.0, 60.0, "A", null, "close", "", n = 2).toJson(),
        )
        assertNull(old.trip)
        assertNull(recordReceiptOf(old, c))
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
