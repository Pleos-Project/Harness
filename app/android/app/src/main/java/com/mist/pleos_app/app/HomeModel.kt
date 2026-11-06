package com.mist.pleos_app.app

import com.mist.pleos_app.engine.Constants
import com.mist.pleos_app.engine.Vehicle
import com.mist.pleos_app.engine.computeRange
import com.mist.pleos_app.engine.lPer100kmToKmPerL
import com.mist.pleos_app.engine.vehicleInputs
import com.mist.pleos_app.vehicle.VehicleSnapshot
import com.mist.pleos_app.vehicle.isEv

/**
 * 홈 잔고 카드(F1)에 필요한 값. 차량값 → 엔진 입력으로 바꾸는 규칙만 있고 수식은 engine 패키지가 한다.
 * 안드로이드 의존이 없어 JUnit으로 바로 확인한다(HomeModelTest).
 */
data class HomeBalance(
    /** "배터리에 1만 9천 원어치 · 약 245km". 값을 아직 못 읽었으면 null → "차량 정보를 읽는 중"(X2) */
    val balanceText: String?,
    /** 단가 출처가 앱 기본값이면 true → "평균 단가"(PRD 5-3) */
    val averagePrice: Boolean,
    /** 차종 표·모의 값을 쓰는 중이면 true → 화면 구석 "시연용 값"(AC-C-7) */
    val demoValues: Boolean,
    /** INFO_FUEL_TYPE과 설정 차종의 전기/내연이 다르면 true → 설정 확인(S1에서 처리) */
    val fuelTypeMismatch: Boolean,
)

/**
 * 명세 F1 입력 규칙.
 * - 잔량: 전기 SOC, 내연 연료 %(에뮬은 콘솔에 없어 설정 모의 값 [mockFuelPct])
 * - 용량: 전기는 AAOS INFO_EV_BATTERY_CAPACITY(Wh → kWh) > 차종 표. 내연 탱크는 에뮬이 15,000mL 고정이라 차종 표(명세 8절)
 * - 연비: SDK 누적값(이미 실연비) > 차종 표 × 0.85. 내연은 L/100km → km/L(AC-F1-4)
 * - 주행가능거리: AAOS RANGE_REMAINING(m), 없으면 0 → 엔진이 조립식으로 계산(AC-F1-2)
 */
fun homeBalance(
    constants: Constants?,
    model: String,
    s: VehicleSnapshot,
    mockFuelPct: Int? = null,
    price: Double? = null,
): HomeBalance {
    val spec = constants?.vehicles?.get(model) ?: return HomeBalance(null, averagePrice = price == null, demoValues = false, fuelTypeMismatch = false)
    val ev = spec.fuelType == "ev"
    val sdkEv = isEv(s)
    val level = if (ev) s.socPct else (s.fuelPct ?: mockFuelPct)
    val capacitySdk = if (ev) s.evCapacityWh?.takeIf { it > 0 }?.div(1000.0) else null
    val efficiencySdk = if (ev) s.evEfficiencyKmPerKwh else s.fuelEconomyLPer100km?.let { lPer100kmToKmPerL(it) }?.takeIf { it > 0 }
    val usedMockLevel = !ev && s.fuelPct == null && mockFuelPct != null
    val demo = efficiencySdk == null || capacitySdk == null || usedMockLevel
    val v = engineVehicle(constants, model, s, mockFuelPct, price)
        ?: return HomeBalance(null, price == null, demo, sdkEv != null && sdkEv != ev)
    return HomeBalance(
        balanceText = computeRange(v).balanceText,
        averagePrice = v.priceSource == "default",
        demoValues = demo,
        fuelTypeMismatch = sdkEv != null && sdkEv != ev,
    )
}

/** 차량값 → 엔진 [Vehicle]. 잔량을 아직 못 읽었으면 null. 규칙은 [homeBalance] 설명과 같다(잔고·범위·카드 요청이 모두 이걸 쓴다). */
fun engineVehicle(
    constants: Constants?,
    model: String,
    s: VehicleSnapshot,
    mockFuelPct: Int? = null,
    price: Double? = null,
): Vehicle? {
    val spec = constants?.vehicles?.get(model) ?: return null
    val ev = spec.fuelType == "ev"
    val level = (if (ev) s.socPct else (s.fuelPct ?: mockFuelPct)) ?: return null
    return vehicleInputs(
        constants, model, level.toDouble(),
        remainingRangeM = s.rangeRemainingM ?: 0.0,
        capacitySdk = if (ev) s.evCapacityWh?.takeIf { it > 0 }?.div(1000.0) else null,
        efficiencySdk = if (ev) s.evEfficiencyKmPerKwh else s.fuelEconomyLPer100km?.let { lPer100kmToKmPerL(it) }?.takeIf { it > 0 },
        price = price,
    )
}
