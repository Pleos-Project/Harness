package com.mist.pleos_app.app

import com.mist.pleos_app.engine.parseConstants
import com.mist.pleos_app.vehicle.VehicleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** S1 설정 → 엔진 입력(AC-S1-1·AC-F1-3). */
class SettingsTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "fixtures") }.first { File(it, "constants.json").exists() }
    private val constants = parseConstants(File(root, "constants.json").readText())
    private val ev = VehicleSnapshot(socPct = 80, rangeRemainingM = 245_000.0, evCapacityWh = 77_400.0)

    private fun balance(s: Settings, snap: VehicleSnapshot = ev) =
        homeBalance(constants, s.model, snap, mockFuelPct = s.mockFuelPct, price = s.priceFor(constants))

    @Test
    fun defaultPriceIsAverage() {
        val b = balance(Settings())
        assertTrue(b.averagePrice)
        assertEquals(null, normalizePrice(300, constants.homeChargeKrwPerKwh))   // 기본값과 같으면 평균 단가
        assertEquals(null, normalizePrice(0, constants.homeChargeKrwPerKwh))
        assertEquals(null, normalizePrice(null, constants.homeChargeKrwPerKwh))
    }

    @Test
    fun userPriceChangesBalance() {
        // AC-F1-3: 집 충전 단가를 바꾸면 잔고 원이 바뀌고 "평균 단가"가 빠진다
        val base = balance(Settings())
        val user = balance(Settings(priceHome = 400))
        assertNotEquals(base.balanceText, user.balanceText)
        assertTrue(!user.averagePrice)
        // EV면 휘발유 단가는 안 씀
        assertEquals(base.balanceText, balance(Settings(priceGas = 2_000)).balanceText)
    }

    @Test
    fun modelChangeUsesMockFuelForIce() {
        // AC-S1-1: 내연 차종으로 바꾸면 에뮬(연료 % 없음)은 모의 연료 %로 계산
        val ice = Settings(model = "grandeur_hev", mockFuelPct = 50)
        val b = balance(ice)
        assertTrue(b.balanceText, b.balanceText!!.isNotEmpty())
        assertNotEquals(b.balanceText, balance(ice.copy(mockFuelPct = 30)).balanceText)
    }

    @Test
    fun memoryStoreRoundTrip() {
        val st = MemorySettingsStore()
        st.save(Settings(model = "avante_hev", priceGas = 1_790, seatInvert = false))
        assertEquals("avante_hev", st.load().model)
        assertEquals(1_790.0, st.load().priceFor(constants)!!, 0.0)
        assertNull(Settings().priceFor(constants))
    }
}
