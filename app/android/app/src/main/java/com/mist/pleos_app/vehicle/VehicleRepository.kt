package com.mist.pleos_app.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.FuelPercentListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.mist.pleos_app.util.ChagokLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 화면이 차량값을 받는 입구. 실제 구현은 [PleosVehicleRepository], 미리보기·테스트는 [FakeVehicleRepository]. */
interface VehicleRepository {
    val state: StateFlow<VehicleSnapshot>
}

class FakeVehicleRepository(initial: VehicleSnapshot = VehicleSnapshot()) : VehicleRepository {
    private val flow = MutableStateFlow(initial)
    override val state: StateFlow<VehicleSnapshot> = flow.asStateFlow()
    fun set(s: VehicleSnapshot) { flow.value = s }
}

/**
 * Pleos Vehicle SDK + AAOS Car API로 차량값을 읽는다(명세 2절 "데이터 읽기 시점", 8절 신호 표).
 *
 * 수명주기(Activity에 observer로 붙인다):
 * - onCreate: SDK·Car 연결만
 * - onStart(화면이 보임): 기어·주행 규제 리스너(관문)를 등록하고, 정차면 진입 때 값(용량·연료 종류·연비·충전 스위치·
 *   누적 주행거리)을 1회 읽은 뒤 나머지 리스너(데이터)를 등록
 * - 주행 전환(X1: 규제 ON 또는 기어 ≠ P): 데이터 리스너를 해제하고 관문만 남긴다(AC-X1-2). 정차로 돌아오면 다시 읽고 등록
 * - onStop(화면이 가려짐): 리스너를 **모두** 해제(AC-C-2: 백그라운드 SDK·AAOS 콜백 0건). 타이머·폴링 없음
 * - onDestroy: 연결 해제
 *
 * onFailed는 SecurityException(권한 없음)과 그 밖의 Exception(미지원 영역·값 없음)으로 나눈다.
 * VehicleException은 공개 클래스가 아니라 쓸 수 없다(Pleos 실측 11번 2-1).
 * 충전 스위치는 SDK에 리스너가 없어(AAR 2.3.2) 진입 때만 읽는다 → F9 작업 때 AAOS EV_CHARGE_SWITCH 콜백 검토.
 */
class PleosVehicleRepository(context: Context) : VehicleRepository, DefaultLifecycleObserver {
    private val app = context.applicationContext
    private val flow = MutableStateFlow(VehicleSnapshot())
    override val state: StateFlow<VehicleSnapshot> = flow.asStateFlow()

    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private var props: CarPropertyManager? = null
    private var ux: CarUxRestrictionsManager? = null
    private var started = false
    private var gateOn = false
    private var dataOn = false

    override fun onCreate(owner: LifecycleOwner) {
        vehicle = runCatching { Vehicle(app).also { it.initialize() } }
            .onFailure { ChagokLog.event("vehicle_init_failed", "err" to it.javaClass.simpleName) }.getOrNull()
        car = runCatching { Car.createCar(app) }
            .onFailure { ChagokLog.event("car_connect_failed", "err" to it.javaClass.simpleName) }.getOrNull()
        props = car?.let { runCatching { it.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager }.getOrNull() }
        ux = car?.let { runCatching { it.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager }.getOrNull() }
    }

    @Synchronized
    override fun onStart(owner: LifecycleOwner) {
        started = true
        readGate()
        registerGate()
        onGateChanged()
    }

    @Synchronized
    override fun onStop(owner: LifecycleOwner) {
        started = false
        unregisterData()
        unregisterGate()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        onStop(owner)
        runCatching { vehicle?.release() }
        runCatching { car?.disconnect() }
        vehicle = null; car = null; props = null; ux = null
    }

    // ------------------------------------------------------------ 실패 기록
    private fun fail(name: String, e: Throwable) {
        val kind = if (e is SecurityException) ReadFailure.PERMISSION else ReadFailure.UNSUPPORTED
        flow.update { it.copy(failures = it.failures + (name to kind)) }
        ChagokLog.event("vehicle_read_failed", "value" to name, "kind" to kind.name)
    }

    private fun ok(name: String) = flow.update { if (name in it.failures) it.copy(failures = it.failures - name) else it }

    /** SDK 호출 자체가 바로 던지는 경우(권한 없음 등)도 같은 경로로 기록한다. */
    private inline fun guard(name: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { fail(name, e) }
    }

    // ------------------------------------------------------------ 관문: 기어·주행 규제 (화면이 보이는 동안 늘)
    private fun readGate() {
        @Suppress("DEPRECATION")
        vehicle?.let { v -> guard("gear") { v.getGear().getCurrentGearSelectionState({ g -> ok("gear"); flow.update { it.copy(gear = g.toState()) }; onGateChanged() }, { e -> fail("gear", e) }) } }
        ux?.let { m -> guard("ux_restrictions") { flow.update { it.copy(uxRestricted = m.currentCarUxRestrictions?.isRequiresDistractionOptimization) } } }
    }

    /** 기어·규제가 바뀔 때마다: 주행이면 데이터 리스너를 끊고, 정차면 다시 읽고 붙인다(X1). */
    @Synchronized
    private fun onGateChanged() {
        if (!started) return
        val driving = isDriving(flow.value)
        if (driving && dataOn) {
            unregisterData()
            ChagokLog.event("driving", "on" to true)
        } else if (!driving && !dataOn) {
            readData()
            registerData()
            ChagokLog.event("driving", "on" to false)
        }
    }

    // ------------------------------------------------------------ 데이터: 정차 화면에 들어올 때 1회
    private fun readData() {
        val v = vehicle
        if (v != null) {
            guard("ev_efficiency") {
                v.getEvBattery().getCumulativeEvEfficiency(
                    { x -> ok("ev_efficiency"); flow.update { it.copy(evEfficiencyKmPerKwh = x?.toDouble()?.takeIf { d -> d > 0 }) } },
                    { e -> fail("ev_efficiency", e) })
            }
            guard("fuel_economy") {
                v.getFuel().getCumulativeFuelEconomy(
                    { x -> ok("fuel_economy"); flow.update { it.copy(fuelEconomyLPer100km = x?.toDouble()?.takeIf { d -> d > 0 }) } },
                    { e -> fail("fuel_economy", e) })
            }
            guard("charge_switch") {
                v.getEvBattery().getEvChargeSwitchState(
                    { on -> ok("charge_switch"); flow.update { it.copy(chargeSwitchOn = on) } },
                    { e -> fail("charge_switch", e) })
            }
            guard("odometer") {
                v.getOdometer().getCurrentDistanceDriven(
                    { km -> ok("odometer"); flow.update { it.copy(odometerKm = km?.toDouble()) } },
                    { e -> fail("odometer", e) })
            }
            // 리스너 등록 즉시 현재값이 1회 오지만(실측), 첫 화면을 빨리 채우려고 Get도 한 번 부른다.
            guard("soc") { v.getEvBattery().getEvBatteryStateOfCharge({ x -> ok("soc"); flow.update { it.copy(socPct = x) } }, { e -> fail("soc", e) }) }
            guard("fuel_pct") { v.getFuel().getFuelPercent({ x -> ok("fuel_pct"); flow.update { it.copy(fuelPct = x) } }, { e -> fail("fuel_pct", e) }) }
        }
        val p = props
        if (p != null) {
            readProp(p, VehiclePropertyIds.INFO_EV_BATTERY_CAPACITY, "ev_capacity") { x -> flow.update { it.copy(evCapacityWh = (x as? Number)?.toDouble()) } }
            readProp(p, VehiclePropertyIds.INFO_FUEL_CAPACITY, "fuel_capacity") { x -> flow.update { it.copy(fuelCapacityMl = (x as? Number)?.toDouble()) } }
            readProp(p, VehiclePropertyIds.INFO_FUEL_TYPE, "fuel_type") { x ->
                val types = (x as? Array<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: (x as? IntArray)?.toList()
                flow.update { it.copy(fuelTypes = types) }
            }
            readProp(p, VehiclePropertyIds.RANGE_REMAINING, "range_remaining") { x -> flow.update { it.copy(rangeRemainingM = (x as? Number)?.toDouble()) } }
        }
    }

    private fun readProp(p: CarPropertyManager, id: Int, name: String, set: (Any?) -> Unit) = guard(name) {
        val value = p.getProperty<Any>(id, 0)
        set(value?.value)
        ok(name)
    }

    // ------------------------------------------------------------ 리스너 (화면이 보이는 동안만)
    private val socListener = object : EvBatteryStateOfChargeListener {
        override fun onEvBatteryStateOfChargeUpdated(value: Int?) { ok("soc"); flow.update { it.copy(socPct = value) } }
        override fun onFailed(e: Exception) = fail("soc", e)
    }
    private val fuelListener = object : FuelPercentListener {
        override fun onFuelPercentUpdated(value: Int?) { ok("fuel_pct"); flow.update { it.copy(fuelPct = value) } }
        override fun onFailed(e: Exception) = fail("fuel_pct", e)
    }
    // 기어: Pleos Gear API(P CAR_DRIVE, 명세 8절·실측). SDK가 deprecated로 AAOS GEAR_SELECTION을 권하지만
    // 그 속성은 A CAR_POWERTRAIN 권한이라 권한 목록(contract/permissions.md)에 없다 → 권한을 늘리지 않고 이걸 쓴다.
    @Suppress("DEPRECATION")
    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) { ok("gear"); flow.update { it.copy(gear = gear.toState()) }; onGateChanged() }
        override fun onFailed(e: Exception) = fail("gear", e)
    }
    private val doorArea = DoorArea(DoorValue.ROW_1_LEFT)
    private val doorListener = object : DoorPositionListener {
        override fun onPositionUpdated(pos: Int?) { ok("driver_door"); flow.update { it.copy(driverDoorOpen = pos?.let { p -> p > 0 }) } }
        override fun onFailed(e: Exception) = fail("driver_door", e)
    }

    /** 1·2열 좌석. 차에 없는 좌석은 onFailed(미지원)로 오고 인원 계산에서 빠진다. */
    private val seatValues = listOf(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_2_CENTER, SeatValue.ROW_2_RIGHT)
    private val seatListeners: Map<SeatValue, SeatOccupancyListener> = seatValues.associateWith { sv ->
        object : SeatOccupancyListener {
            override fun onSeatOccupancyUpdated(occupancy: Boolean?) {
                flow.update { s -> s.copy(seatsOccupied = if (occupancy == null) s.seatsOccupied - sv.name else s.seatsOccupied + (sv.name to occupancy)) }
            }
            override fun onFailed(e: Exception) {
                flow.update { s -> s.copy(seatsOccupied = s.seatsOccupied - sv.name) }
                if (e is SecurityException) fail("seats", e)   // 미지원 좌석은 정상(차마다 다름)이라 기록하지 않는다
            }
        }
    }

    private val propCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            if (value.propertyId == VehiclePropertyIds.RANGE_REMAINING) {
                ok("range_remaining")
                flow.update { it.copy(rangeRemainingM = (value.value as? Number)?.toDouble()) }
            }
        }
        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            if (propertyId == VehiclePropertyIds.RANGE_REMAINING) fail("range_remaining", Exception("property error"))
        }
    }

    private val uxListener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { r ->
        flow.update { it.copy(uxRestricted = r?.isRequiresDistractionOptimization) }
        onGateChanged()
    }

    private fun registerGate() {
        if (gateOn) return
        gateOn = true
        @Suppress("DEPRECATION")
        vehicle?.let { v -> guard("gear") { v.getGear().registerGearSelection(gearListener) } }
        ux?.let { m -> guard("ux_restrictions") { m.registerListener(uxListener) } }
    }

    private fun unregisterGate() {
        if (!gateOn) return
        gateOn = false
        @Suppress("DEPRECATION")
        vehicle?.let { v -> runCatching { v.getGear().unregisterGearSelection(gearListener) } }
        ux?.let { m -> runCatching { m.unregisterListener() } }
    }

    private fun registerData() {
        if (dataOn) return
        dataOn = true
        vehicle?.let { v ->
            guard("soc") { v.getEvBattery().registerEvBatteryStateOfCharge(socListener) }
            guard("fuel_pct") { v.getFuel().registerFuelPercent(fuelListener) }
            guard("driver_door") { v.getDoor().registerDoorPosition(doorArea, doorListener) }
            seatListeners.forEach { (sv, l) -> guard("seats") { v.getSeat().registerSeatOccupancy(SeatArea(sv), l) } }
        }
        props?.let { p ->
            guard("range_remaining") {
                @Suppress("DEPRECATION")
                p.registerCallback(propCallback, VehiclePropertyIds.RANGE_REMAINING, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            }
        }
        ChagokLog.event("vehicle_listen", "on" to true)
    }

    private fun unregisterData() {
        if (!dataOn) return
        dataOn = false
        vehicle?.let { v ->
            runCatching { v.getEvBattery().unregisterEvBatteryStateOfCharge(socListener) }
            runCatching { v.getFuel().unregisterFuelPercent(fuelListener) }
            runCatching { v.getDoor().unregisterDoorPosition(doorArea, doorListener) }
            seatListeners.forEach { (sv, l) -> runCatching { v.getSeat().unregisterSeatOccupancy(SeatArea(sv), l) } }
        }
        props?.let { p ->
            @Suppress("DEPRECATION")
            runCatching { p.unregisterCallback(propCallback) }
        }
        ChagokLog.event("vehicle_listen", "on" to false)
    }
}

private fun VehicleGear?.toState(): GearState = when (this) {
    null, VehicleGear.GEAR_UNKNOWN -> GearState.UNKNOWN
    VehicleGear.GEAR_PARK -> GearState.PARK
    VehicleGear.GEAR_DRIVE -> GearState.DRIVE
    else -> GearState.OTHER
}
