package com.mist.pleos_app.vehicle

/*
 * 차량값 한 장(화면이 보이는 동안의 최신값). SDK 타입을 화면·엔진까지 끌고 가지 않으려고 여기서 앱 타입으로 바꾼다.
 * 이 값은 기기 안에서만 쓴다. 서버로는 엔진이 만든 budget 파생값만 보낸다(CLAUDE.md, PRD 5-2).
 */

enum class GearState { PARK, DRIVE, OTHER, UNKNOWN }

/** 읽기 실패 사유. 권한 없음(SecurityException)과 미지원(일반 Exception)을 나눈다(Pleos 실측 11번 2-2). */
enum class ReadFailure { PERMISSION, UNSUPPORTED }

data class VehicleSnapshot(
    /** 리스너로 받는 값 */
    val socPct: Int? = null,
    val fuelPct: Int? = null,
    val rangeRemainingM: Double? = null,
    val seatsOccupied: Map<String, Boolean> = emptyMap(),   // 좌석 이름 → SDK 원시값(반전 전)
    val driverDoorOpen: Boolean? = null,
    val gear: GearState = GearState.UNKNOWN,
    val uxRestricted: Boolean? = null,
    /** 화면 진입 때만 읽는 값 */
    val evCapacityWh: Double? = null,
    val fuelCapacityMl: Double? = null,
    val fuelTypes: List<Int>? = null,                       // AAOS INFO_FUEL_TYPE (10 = 전기)
    val evEfficiencyKmPerKwh: Double? = null,
    val fuelEconomyLPer100km: Double? = null,
    val chargeSwitchOn: Boolean? = null,
    val odometerKm: Double? = null,
    /** 읽기 실패: 값 이름 → 사유 (X2 "차량 정보를 읽는 중", RANGE_REMAINING 권한 거부 → 조립식) */
    val failures: Map<String, ReadFailure> = emptyMap(),
)

/** AAOS FuelType.ELECTRIC. 연료 종류 배열에 이것만 있으면 전기차. */
const val FUEL_TYPE_ELECTRIC = 10

/** 전기/내연 자동 판별(명세 S1: 차종은 용량·연비 대체값에만, 전기/내연은 INFO_FUEL_TYPE). 모르면 null. */
fun isEv(s: VehicleSnapshot): Boolean? = s.fuelTypes?.takeIf { it.isNotEmpty() }?.let { types -> types.all { it == FUEL_TYPE_ELECTRIC } }

/**
 * 탄 사람 수. 에뮬레이터 SDK는 착석을 반대로 읽어서(app/README.md 4절) 설정 seat_invert(에뮬 ON, 실차 OFF)로 뒤집는다.
 * 운전석은 앱을 쓰는 사람이 있으니 최소 1명.
 */
fun seatCount(seats: Map<String, Boolean>, invert: Boolean): Int =
    seats.values.count { if (invert) !it else it }.coerceAtLeast(1)

/** 정차 = 기어 P이고 주행 규제 없음(명세 2절). 규제 값을 아직 못 받았으면 기어만 본다. */
fun isParked(s: VehicleSnapshot): Boolean = s.gear == GearState.PARK && s.uxRestricted != true

/**
 * 주행 중(명세 X1: 규제 ON 또는 기어 ≠ P) → 화면을 가리고 규제·기어 외 리스너를 해제한다.
 * 기어를 아직 모르면(앱 시작 직후·읽기 실패) 가리지 않는다. 규제가 켜져 있으면 기어와 상관없이 주행.
 */
fun isDriving(s: VehicleSnapshot): Boolean =
    s.uxRestricted == true || (s.gear != GearState.UNKNOWN && s.gear != GearState.PARK)

/** 잔량 %: 전기차면 SOC, 내연이면 연료 %. */
fun levelPct(s: VehicleSnapshot, ev: Boolean): Int? = if (ev) s.socPct else s.fuelPct
