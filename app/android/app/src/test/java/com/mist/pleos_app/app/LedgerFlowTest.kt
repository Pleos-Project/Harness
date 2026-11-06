package com.mist.pleos_app.app

import com.mist.pleos_app.engine.Place
import com.mist.pleos_app.engine.parseConstants
import com.mist.pleos_app.ledger.MemoryLedgerStore
import com.mist.pleos_app.vehicle.VehicleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.OffsetDateTime

/** 장부 이벤트를 "언제" 넣는지(F10·F17·F8). 정산·핀·금액 규칙은 FixturesTest가 fixtures/ledger L1~L5로 대조한다. */
class LedgerFlowTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "fixtures") }.first { File(it, "constants.json").exists() }
    private val constants = parseConstants(File(root, "constants.json").readText())
    private val v = engineVehicle(constants, "ioniq5_lr", VehicleSnapshot(socPct = 80, rangeRemainingM = 245_000.0, evCapacityWh = 77_400.0))!!
    private val dumul = Place("양평 두물머리", 37.5363, 127.314, "p_dumulmeori")

    private var t = OffsetDateTime.parse("2026-12-03T14:00:00+09:00")
    private fun flow(store: MemoryLedgerStore = MemoryLedgerStore()) = LedgerFlow(store) { t }

    @Test
    fun openOncePerVisitAndSettle() {
        val f = flow()
        f.open(v, 12_340.0, seats = 2, recentDestination = null)       // 첫 실행: 스냅샷만(AC-S1-2)
        assertNull(f.settleCard)
        f.open(v, 12_500.0, seats = 2, recentDestination = null)       // 같은 방문 안에서는 다시 넣지 않는다
        assertNull(f.settleCard)

        f.onHidden(); t = t.plusHours(3)
        f.open(v, 12_472.0, seats = 1, recentDestination = dumul)      // 복귀 → 132km 정산(AC-F10-1·5)
        val card = f.settleCard!!
        assertTrue(card, card.startsWith("지난번 이후 132km · "))
        assertTrue(card.endsWith("마지막 목적지 양평 두물머리"))
        f.dismissSettle(); assertNull(f.settleCard)                    // 닫기

        f.onHidden(); t = t.plusHours(1)
        f.open(v, 12_472.0, seats = 1, recentDestination = dumul)      // Δodo 0 → 카드 없음(AC-F10-2)
        assertNull(f.settleCard)
    }

    @Test
    fun departArriveByDoorThenNoDoubleSettle() {
        // PT 5~6단계(fixtures/ledger L1): 출발 → 기어 P에서 운전석 문(Δodo 0) → 핀 + 이달 이동비, 다시 열어도 정산 두 번 없음
        val store = MemoryLedgerStore()
        val f = flow(store)
        f.open(v, 12_472.0, seats = 4, recentDestination = null)
        f.depart(dumul, routeKm = 37.2, odometerKm = 12_472.0)
        assertNotNull(f.state.trip)

        t = t.plusMinutes(1)
        assertFalse(f.onDoor(open = false, parked = true, v = v, odometerKm = 12_472.0, seats = 4))   // 기준값
        assertFalse(f.onDoor(open = true, parked = false, v = v, odometerKm = 12_472.0, seats = 4))   // 주행 중 열림은 아님
        f.onDoor(open = false, parked = true, v = v, odometerKm = 12_472.0, seats = 4)
        assertTrue(f.onDoor(open = true, parked = true, v = v, odometerKm = 12_472.0, seats = 4))     // 도착(AC-F17-1)
        assertNull(f.state.trip)
        val s = f.summary("2026-12")
        assertEquals(1, s.places)
        assertTrue(s.demo)                                             // Δodo 0 → 편도 추정 "시연용 값"
        assertEquals("이번 달 이동비 약 2천 원 · 목적지 1곳", s.text)   // DECISIONS 'PT 콘솔 값', PRD v1.6 F17

        f.onHidden(); t = t.plusMinutes(5)
        f.open(v, 12_472.0, seats = 4, recentDestination = dumul)      // AC-F17-4: 같은 구간 다시 안 쌓임
        assertNull(f.settleCard)
        assertEquals(1, f.rows("2026-12").size)

        // 저장 → 다시 읽기(재시작 후 유지, AC-F10-4)
        assertEquals(f.state, flow(store).state)
    }

    @Test
    fun arriveKeepsAveragePriceSource() {
        // fixtures/ledger L6(#29): 기본값 단가로 도착 → 그 스냅샷으로 다음 정산에도 "(평균 단가)"
        assertEquals("default", v.priceSource)
        val f = flow()
        f.open(v, 12_472.0, seats = 1, recentDestination = null)
        f.depart(dumul, routeKm = 37.2, odometerKm = 12_472.0)
        f.onDoor(open = false, parked = true, v = v, odometerKm = 12_472.0, seats = 1)
        assertTrue(f.onDoor(open = true, parked = true, v = v, odometerKm = 12_472.0, seats = 1))
        assertEquals("default", f.state.snapshot!!.priceSource)

        f.onHidden(); t = t.plusHours(2)
        f.open(v, 12_550.0, seats = 1, recentDestination = null)
        assertTrue(f.settleCard!!, f.settleCard!!.endsWith("(평균 단가)"))
    }

    @Test
    fun resetClearsEverything() {
        val f = flow()
        f.open(v, 100.0, 1, null); f.depart(dumul, 37.2, 100.0)
        f.reset()
        assertNull(f.state.snapshot); assertNull(f.state.trip)
        assertEquals("첫 드라이브를 기다리는 중", f.summary("2026-12").text)
        assertTrue(f.needsOpen)
    }
}
