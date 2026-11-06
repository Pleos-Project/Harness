package com.mist.pleos_app.app

import com.mist.pleos_app.engine.DemoServer
import com.mist.pleos_app.engine.parseCandidates
import com.mist.pleos_app.engine.parseConstants
import com.mist.pleos_app.vehicle.VehicleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** F2·F3·F5 화면 흐름(오프라인 데모). 순위·배지 값 자체는 FixturesTest가 서버 fixture로 대조한다. */
class CardModelTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "fixtures") }.first { File(it, "constants.json").exists() }
    private val constants = parseConstants(File(root, "constants.json").readText())
    private val candidates = parseCandidates(File(root, "candidates_demo.json").readText())
    private fun flow() = CardFlow(constants, DemoServer(candidates))

    private fun ev(soc: Int, rangeM: Double) =
        engineVehicle(constants, "ioniq5_lr", VehicleSnapshot(socPct = soc, rangeRemainingM = rangeM, evCapacityWh = 77_400.0))!!

    private val d1 = ev(80, 245_000.0)     // PT D1(DECISIONS 'PT 콘솔 값')
    private val d9 = ev(12, 40_000.0)      // AC-F2-2: 잔고 40km → 추가 0원 비활성

    @Test
    fun todayD1PicksDumulmeori() {
        // AC-F5-1: D1 + demo_seed → 앞면 "양평 두물머리", 추가 0원 그대로(자동 전환 없음)
        val ui = flow().today(d1, seats = 1) as CardUi.Shown
        assertEquals("양평 두물머리", ui.card.name)
        assertNull(ui.raisedTo)
        assertTrue(ui.card.display.zeroExtra)
        assertEquals(2, ui.swapsLeft)
    }

    @Test
    fun todayLowBalanceRaises() {
        // DECISIONS '잔고 부족 시 오늘의 한 곳': 추가 0원 범위가 없으면 +1만(없으면 +3만)으로 자동 전환
        val ui = flow().today(d9, seats = 1)
        assertTrue(ui is CardUi.Shown || ui is CardUi.Empty)
        if (ui is CardUi.Shown) {
            assertNotNull(ui.raisedTo)
            assertFalse(ui.card.display.zeroExtra)    // 자동 전환된 카드에 0원 약속 없음
        }
    }

    @Test
    fun swapTwiceThenAskTheme() {
        // AC-F5-3: 교체 2회 뒤 3번째는 "테마를 바꿔 볼까요?" → 범위 화면
        val f = flow()
        val first = f.today(d1, 1) as CardUi.Shown
        val s1 = f.swap(first) as CardUi.Shown
        assertEquals(1, s1.swapsLeft)
        val s2 = f.swap(s1) as CardUi.Shown
        assertEquals(0, s2.swapsLeft)
        assertEquals(3, setOf(first.card.id, s1.card.id, s2.card.id).size)
        assertTrue(f.swap(s2) is CardUi.AskTheme)
    }

    @Test
    fun presetsAndDefaults() {
        val p1 = presets(d1, days = 1)
        assertEquals(listOf(0, 10_000, 30_000), p1.map { it.extraKrw })
        assertTrue(p1.all { it.enabled })
        assertEquals(75, p1[0].radiusKm)                       // (245−50)÷2÷1.3 ≈ 75 (AC-F2-1, PT 콘솔 값)
        assertTrue(presets(d1, days = 2)[0].radiusKm < p1[0].radiusKm)   // AC-F2-3: 1박은 더 작다
        assertEquals(0, defaultExtra(d1, 1))
        assertFalse(presets(d9, 1)[0].enabled)                 // AC-F2-2
        assertEquals(10_000, defaultExtra(d9, 1))
    }

    @Test
    fun themeToggleAndMinutes() {
        assertEquals(listOf("sea"), toggleTheme(emptyList(), "sea"))
        assertEquals(listOf("sea", "lake"), toggleTheme(listOf("sea"), "lake"))
        assertEquals(listOf("lake", "river"), toggleTheme(listOf("sea", "lake"), "river"))   // AC-F3-1
        assertEquals(listOf("lake"), toggleTheme(listOf("sea", "lake"), "sea"))
        assertEquals("42분", minutesText(42))
        assertEquals("1시간", minutesText(60))
        assertEquals("1시간 10분", minutesText(70))
    }
}
