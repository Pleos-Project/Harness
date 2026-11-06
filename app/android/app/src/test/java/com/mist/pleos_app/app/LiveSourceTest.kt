package com.mist.pleos_app.app

import com.mist.pleos_app.engine.DemoServer
import com.mist.pleos_app.engine.SwapResult
import com.mist.pleos_app.engine.parseCandidates
import com.mist.pleos_app.engine.parseConstants
import com.mist.pleos_app.engine.toJson
import com.mist.pleos_app.net.ChagokApi
import com.mist.pleos_app.vehicle.VehicleSnapshot
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress

/** 서버 우선 + 실패하면 내장 후보(app/README 3절, X2 '서버 타임아웃·오류', AC-F5-7). 가짜 서버는 JDK HttpServer. */
class LiveSourceTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "fixtures") }.first { File(it, "constants.json").exists() }
    private val constants = parseConstants(File(root, "constants.json").readText())
    private val candidates = parseCandidates(File(root, "candidates_demo.json").readText())
    private val d1 = engineVehicle(constants, "ioniq5_lr", VehicleSnapshot(socPct = 80, rangeRemainingM = 245_000.0, evCapacityWh = 77_400.0))!!

    private var server: HttpServer? = null
    private val bodies = mutableListOf<String>()

    /** /v1/cards 가 [status]로 답하는 가짜 서버. 200이면 같은 요청을 DemoServer로 돌린 응답(= 서버 데모 모드와 같은 모양). */
    private fun fake(status: Int): String {
        val ref = DemoServer(candidates)
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/v1/cards") { ex ->
            val body = ex.requestBody.readBytes().decodeToString()
            bodies += body
            val out = if (status == 200) {
                val req = com.mist.pleos_app.engine.parseCardsRequest(Json.parseToJsonElement(body).jsonObject)
                ref.cards(req.copy(demoSeed = constants.demoSeed)).toJson().toString()
            } else """{"detail":{"error":"candidates_not_ready"}}"""
            val bytes = out.encodeToByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    @After fun stop() { server?.stop(0) }

    /** 소스 하나를 계속 쓴다(fallback 세션 기억). */
    private fun flow(base: String): CardFlow {
        val src = LiveSource(ChagokApi(base), DemoServer(candidates))
        return CardFlow(constants) { src }
    }

    @Test
    fun serverOkShowsServerCardAndSendsNoRawVehicleValues() {
        val f = flow(fake(200))
        val ui = f.today(d1, seats = 1) as CardUi.Shown
        assertFalse(ui.offline)
        assertEquals("양평 두물머리", ui.card.name)                     // D1과 같은 앞면
        val sent = Json.parseToJsonElement(bodies.first()).jsonObject
        assertTrue("실서비스는 demo_seed 없이", sent["demo_seed"] == null || sent["demo_seed"] is kotlinx.serialization.json.JsonNull)
        // 원시 차량값(잔량 %·주행가능거리·누적 주행거리)은 보내지 않는다(CLAUDE.md, 계약 budget만)
        val raw = listOf("soc", "level_pct", "remaining_range_m", "odometer", "vin")
        assertTrue(sent.toString(), raw.none { it in sent.keys })
        assertTrue(sent.keys.toString(), "budget" in sent.keys)
    }

    @Test
    fun server503FallsBackToDemoOnceAndSwapsLocally() {
        val f = flow(fake(503))
        val ui = f.today(d1, seats = 1) as CardUi.Shown
        assertTrue(ui.offline)                                         // "잠시 후 다시 볼까요?"
        assertEquals("양평 두물머리", ui.card.name)
        assertEquals(1, bodies.size)                                   // 같은 뽑기 안에서 서버를 다시 기다리지 않음
        val next = f.swap(ui)                                          // fallback 세션의 교체는 내장 후보로
        assertTrue(next is CardUi.Shown)
        assertEquals(1, bodies.size)
    }

    @Test
    fun unreachableServerFallsBack() {
        val f = flow("http://127.0.0.1:1")                            // 연결 거부
        val ui = f.pick(d1, seats = 1, c = Conditions()) as CardUi.Shown
        assertTrue(ui.offline)
        assertNotNull(ui.card)
    }

    @Test
    fun swapNetworkErrorKeepsCard() {
        val src = LiveSource(ChagokApi(fake(200)), DemoServer(candidates))
        val f = CardFlow(constants) { src }
        val ui = f.today(d1, seats = 1) as CardUi.Shown
        server?.stop(0)                                                // 교체 전에 서버가 죽음
        val r = src.swap(ui.sessionId)
        assertTrue(r is SwapResult.Error && r.error == LiveSource.NETWORK)
        val next = f.swap(ui) as CardUi.Shown
        assertEquals(ui.card.id, next.card.id)                         // 앞면 유지 + offline
        assertTrue(next.offline)
    }
}
