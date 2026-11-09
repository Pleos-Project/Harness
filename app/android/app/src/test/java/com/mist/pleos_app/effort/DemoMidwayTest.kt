package com.mist.pleos_app.effort

import com.mist.pleos_app.app.Settings
import com.mist.pleos_app.engine.parseEffortConstants
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * 시연 중간 정산(F27, 효성 #105): 시연 출발 기록 + demo.json midway(17:05 · 남은 52.3km · 그 시간대 날씨)로 앱이 낸 영수증이
 * midway.expected(= R27)와 같고, [좋아요]로 정산한 뒤 시연 도착이 midway_arrival.expected(= R28)와 같은지.
 */
class DemoMidwayTest {

    private val fixtures = locate("fixtures")
    private val c = parseEffortConstants(File(fixtures, "constants.json").readText())
    private val text = File(fixtures, "effort/demo.json").readText()
    private val scene = parseDemoScene(text)
    private val root = Json.parseToJsonElement(text).jsonObject

    private fun expected(key: String): JsonObject = root.getValue(key).jsonObject.getValue("expected").jsonObject
    private fun JsonObject.num(k: String) = getValue(k).jsonPrimitive.double

    @Test
    fun demoMidwayThenArrivalLikeFixture() {
        assertNotNull(scene.midway)
        val m = scene.midway!!
        assertEquals("2026-11-17T17:05:00+09:00", m.at)
        assertEquals(52300, m.remainM)

        // 휴게소 17:05: 앱 시연 경로(출발 기록 → midwayReceiptOf)로 낸 영수증 = midway.expected
        val trip = DemoEffortSource(scene).demoTrip("d1")
        val r = midwayReceiptOf(trip, m.at, m.remainM, m.weatherTrip, c)!!
        val e = expected("midway")
        assertEquals(e.num("multiplier"), r.multiplier, 1e-9)
        assertEquals(e.getValue("proposal").jsonObject.num("remain_krw"), r.proposal!!.remainKrw!!, 1e-6)
        assertEquals(e.getValue("proposal").jsonObject.getValue("per_person_text").jsonPrimitive.content, r.proposal!!.perPersonText)

        // [좋아요]로 정산 → 시연 도착(scene.arrivedAt) = midway_arrival.expected(1.56배, 남은 몫)
        val settled = trip.copy(settlements = listOf(Settlement(m.at, r.proposal!!.remainKrw!!, Settings.METHOD_A, CHOICE_ACCEPT, r.proposal!!.a.mainText)))
        val arrived = settled.copy(arrivedAt = scene.arrivedAt, weatherArr = scene.weatherArr, restAnswerMin = 0)
        val a = receiptOf(arrived, c)!!
        val ea = expected("midway_arrival")
        assertEquals(ea.num("multiplier"), a.multiplier, 1e-9)
        assertEquals(ea.getValue("proposal").jsonObject.num("remain_krw"), a.proposal!!.remainKrw!!, 1e-6)
        assertEquals(ea.getValue("proposal").jsonObject.getValue("per_person_text").jsonPrimitive.content, a.proposal!!.perPersonText)
    }

    @Test
    fun demoWithoutSettlementStaysR1() {
        // 중간 정산을 하지 않은 시연 도착은 지금처럼 demo.json expected(R1, 1.57배)
        val trip = DemoEffortSource(scene).demoTrip("d1").copy(arrivedAt = scene.arrivedAt, weatherArr = scene.weatherArr, restAnswerMin = 0)
        assertEquals(root.getValue("expected").jsonObject.num("multiplier"), receiptOf(trip, c)!!.multiplier, 1e-9)
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
