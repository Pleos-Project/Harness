package com.mist.pleos_app.app

import com.mist.pleos_app.engine.parseConstants
import com.mist.pleos_app.vehicle.GearState
import com.mist.pleos_app.vehicle.VehicleSnapshot
import com.mist.pleos_app.vehicle.isEv
import com.mist.pleos_app.vehicle.isDriving
import com.mist.pleos_app.vehicle.isParked
import com.mist.pleos_app.vehicle.seatCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 차량값 → 엔진 입력 규칙(명세 F1·F11·2절). 금액 수식 자체는 FixturesTest가 fixtures로 확인한다. */
class HomeModelTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "fixtures") }.first { File(it, "constants.json").exists() }
    private val constants = parseConstants(File(root, "constants.json").readText())

    /** PT D1 콘솔 값(DECISIONS 'PT 콘솔 값'): SOC 80, 용량 77.4kWh, Remaining Range 245km. 전비는 에뮬에 없음. */
    private val d1 = VehicleSnapshot(socPct = 80, rangeRemainingM = 245_000.0, evCapacityWh = 77_400.0, fuelTypes = listOf(10))

    @Test
    fun d1Balance() {
        val b = homeBalance(constants, "ioniq5_lr", d1)
        assertEquals("배터리에 1만 9천 원어치 · 약 245km", b.balanceText)
        assertTrue(b.averagePrice)          // 설정 단가 없음 → "평균 단가"
        assertTrue(b.demoValues)            // 전비가 차종 표 → "시연용 값"
        assertFalse(b.fuelTypeMismatch)
    }

    @Test
    fun capacityFromCarWins() {
        // AC-F1-5: 콘솔 용량 60kWh(60,000Wh) → 차종 표(77.4)보다 우선. 60 × 0.8 × 300 = 14,400 → 1만 4천
        val b = homeBalance(constants, "ioniq5_lr", d1.copy(evCapacityWh = 60_000.0))
        assertEquals("배터리에 1만 4천 원어치 · 약 245km", b.balanceText)
    }

    @Test
    fun noLevelMeansReading() {
        // X2 "차량 정보를 읽는 중": 잔량을 아직 못 읽음
        assertNull(homeBalance(constants, "ioniq5_lr", d1.copy(socPct = null)).balanceText)
        assertNull(homeBalance(null, "ioniq5_lr", d1).balanceText)
    }

    @Test
    fun iceUsesMockFuelAndUnitConversion() {
        // 에뮬 내연: 연료 %가 콘솔에 없어 설정 모의 값, 연비 8.0 L/100km → 12.5km/L(AC-F1-4)
        val s = VehicleSnapshot(fuelEconomyLPer100km = 8.0, fuelTypes = listOf(1))
        val b = homeBalance(constants, "grandeur_hev", s, mockFuelPct = 50)
        assertTrue(b.balanceText!!.startsWith("탱크에 "))
        assertTrue(b.demoValues)            // 모의 연료 %
        assertNull(homeBalance(constants, "grandeur_hev", s).balanceText)
    }

    @Test
    fun fuelTypeMismatchFlag() {
        assertTrue(homeBalance(constants, "grandeur_hev", d1, mockFuelPct = 50).fuelTypeMismatch)
        assertEquals(true, isEv(d1))
        assertNull(isEv(VehicleSnapshot()))
    }

    @Test
    fun seatsAndParking() {
        // 에뮬은 SDK 착석이 반대(app/README 4절): 원시값 false = 앉음 → seat_invert ON
        val raw = mapOf("ROW_1_LEFT" to false, "ROW_1_RIGHT" to false, "ROW_2_LEFT" to true, "ROW_2_RIGHT" to false)
        assertEquals(3, seatCount(raw, invert = true))
        assertEquals(1, seatCount(raw, invert = false))
        assertEquals(1, seatCount(emptyMap(), invert = true))   // 운전자 최소 1명
        assertTrue(isParked(VehicleSnapshot(gear = GearState.PARK)))
        assertFalse(isParked(VehicleSnapshot(gear = GearState.PARK, uxRestricted = true)))
        assertFalse(isParked(VehicleSnapshot(gear = GearState.DRIVE, uxRestricted = false)))
    }

    @Test
    fun drivingHidesScreen() {
        // X1: 규제 ON 또는 기어 ≠ P → 가림. 기어를 아직 모르면 가리지 않는다(시작 직후 빈 화면 방지)
        assertFalse(isDriving(VehicleSnapshot()))
        assertFalse(isDriving(VehicleSnapshot(gear = GearState.PARK, uxRestricted = false)))
        assertTrue(isDriving(VehicleSnapshot(gear = GearState.DRIVE)))
        assertTrue(isDriving(VehicleSnapshot(gear = GearState.OTHER, uxRestricted = false)))       // R·N
        assertTrue(isDriving(VehicleSnapshot(gear = GearState.PARK, uxRestricted = true)))         // 콘솔 driving_regulation on
        assertTrue(isDriving(VehicleSnapshot(gear = GearState.UNKNOWN, uxRestricted = true)))
    }
}
