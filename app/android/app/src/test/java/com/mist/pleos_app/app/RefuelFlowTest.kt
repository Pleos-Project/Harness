package com.mist.pleos_app.app

import com.mist.pleos_app.engine.parseConstants
import com.mist.pleos_app.vehicle.VehicleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** F9 충전·주유 직후 "언제" 띄우나. 금액 수식은 FixturesTest가 D6·D11로 대조한다. */
class RefuelFlowTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "fixtures") }.first { File(it, "constants.json").exists() }
    private val constants = parseConstants(File(root, "constants.json").readText())
    private val ev = engineVehicle(constants, "ioniq5_lr", VehicleSnapshot(socPct = 80, rangeRemainingM = 245_000.0, evCapacityWh = 77_400.0))!!
    private val ice = engineVehicle(constants, "grandeur_hev", VehicleSnapshot(rangeRemainingM = 245_000.0), mockFuelPct = 25)!!

    @Test
    fun chargeOnOffGivesD6Text() {
        // PT 1단계(D6): 충전 OFF → ON(SOC 20) → SOC 80 → OFF
        val f = RefuelFlow()
        assertNull(f.onCharge(false, 20, ev))
        assertNull(f.onCharge(true, 20, ev))
        assertNull(f.onCharge(true, 80, ev))                     // 충전 중 SOC 변화는 무시
        assertEquals("방금 약 1만 4천 원어치 충전", f.onCharge(false, 80, ev)!!.text)
        assertNull(f.onCharge(false, 80, ev))                    // 한 번만
    }

    @Test
    fun alreadyChargingAtOpenSaysDoneOnly() {
        val f = RefuelFlow()
        assertNull(f.onCharge(true, 50, ev))                     // 앱을 연 때 이미 충전 중 → 시작 SOC 모름
        val done = f.onCharge(false, 80, ev)!!
        assertNull(done.text)                                    // "충전 끝났어요"만
    }

    @Test
    fun iceNeverGetsChargeCardAndEvNeverGetsSheet() {
        val f = RefuelFlow()
        f.onCharge(false, null, ice); f.onCharge(true, null, ice)
        assertNull(f.onCharge(false, null, ice))
        f.onFuel(20, parked = true, v = ev)
        assertNull(f.onFuel(60, parked = true, v = ev))          // AC-F9-4
    }

    @Test
    fun fuelRiseThreePointsOpensSheet() {
        val f = RefuelFlow()
        assertNull(f.onFuel(25, parked = true, v = ice))
        assertNull(f.onFuel(27, parked = true, v = ice))         // 2%p 무시(AC-F9-3)
        val sheet = f.onFuel(28, parked = true, v = ice)         // 기준 25에서 3%p
        assertNotNull(sheet)
        assertEquals(25, sheet!!.startPct)
        assertEquals(70_000, sheet.fullKrw)                      // D11: 그랜저 HEV 50L·25%·1,856원 → 약 7만 원
        assertNull(f.onFuel(29, parked = true, v = ice))         // 기준이 28로 바뀜
    }

    @Test
    fun fuelRiseWhileDrivingOrAfterDropFollowsBase() {
        val f = RefuelFlow()
        f.onFuel(50, parked = true, v = ice)
        assertNull(f.onFuel(40, parked = false, v = ice))        // 주행으로 줄어듦 → 기준 40
        assertNull(f.onFuel(45, parked = false, v = ice))        // 주행 중 상승은 무시, 기준 45
        assertNull(f.onFuel(47, parked = true, v = ice))
        assertNotNull(f.onFuel(48, parked = true, v = ice))
    }
}
