package com.mist.pleos_app.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
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
 * - onStart(화면이 보임): 기어·주행 규제 리스너(관문)를 등록하고, 정차면 좌석 리스너(데이터)를 등록
 * - 주행 전환(X1: 규제 ON 또는 기어 ≠ P): 데이터 리스너를 해제하고 관문만 남긴다(AC-X1-2). 정차로 돌아오면 다시 등록
 * - onStop(화면이 가려짐): 리스너를 **모두** 해제(AC-C-2: 백그라운드 SDK·AAOS 콜백 0건). 타이머·폴링 없음
 * - onDestroy: 연결 해제
 *
 * onFailed는 SecurityException(권한 없음)과 그 밖의 Exception(미지원 영역·값 없음)으로 나눈다.
 * VehicleException은 공개 클래스가 아니라 쓸 수 없다(Pleos 실측 11번 2-1).
 *
 * 노고 흐름(명세 v0.5)이 쓰는 값만 읽는다: 기어·주행 규제(X1·도착), 좌석(인원).
 * 운전석 문은 도착 계기에서 빼서 읽지 않는다(DECISIONS 11/13) → 권한 `CAR_DOORS`도 뺐다. [VehicleSnapshot.driverDoorOpen]은 피봇 전 코드용으로만 남는다.
 * 피봇 전 값(잔량·연비·용량·주행거리·충전 스위치·RANGE_REMAINING)은 읽지 않는다 → 권한도 뺐다(contract/permissions.md).
 * [VehicleSnapshot]의 그 필드는 피봇 전 코드(AppViewModel·ChagokApp)가 컴파일되도록 남겨 두고 정리 PR에서 지운다.
 */
class PleosVehicleRepository(context: Context) : VehicleRepository, DefaultLifecycleObserver {
    private val app = context.applicationContext
    private val flow = MutableStateFlow(VehicleSnapshot())
    override val state: StateFlow<VehicleSnapshot> = flow.asStateFlow()

    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private var ux: CarUxRestrictionsManager? = null
    private var started = false
    private var gateOn = false
    private var dataOn = false

    /** 앰비언트 라이트(F28). 설정 '앰비언트 라이트'(S20)를 [ambientEnabled]로 받는다. 보이는 동안·정차일 때만 켠다. */
    @Volatile var ambientEnabled = true
        set(v) { field = v; if (!v) ambient.restoreAll("setting") }
    val ambient = AmbientLight(PleosAmbientLightPort { vehicle }, PrefsAmbientStore(app), allowed = { ambientEnabled && started && !isDriving(flow.value) })

    override fun onCreate(owner: LifecycleOwner) {
        vehicle = runCatching { Vehicle(app).also { it.initialize() } }
            .onFailure { ChagokLog.event("vehicle_init_failed", "err" to it.javaClass.simpleName) }.getOrNull()
        car = runCatching { Car.createCar(app) }
            .onFailure { ChagokLog.event("car_connect_failed", "err" to it.javaClass.simpleName) }.getOrNull()
        ux = car?.let { runCatching { it.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager }.getOrNull() }
    }

    @Synchronized
    override fun onStart(owner: LifecycleOwner) {
        started = true
        readGate()
        registerGate()
        onGateChanged()
        ambient.recover()                                 // 지난번에 되돌리지 못하고 끝났으면 먼저 되돌림(F28)
    }

    @Synchronized
    override fun onStop(owner: LifecycleOwner) {
        ambient.restoreAll("stop")                        // 가려지면 그 자리에서 되돌리고 그 뒤로는 부르지 않음(AC-F28-3·AC-C-2)
        started = false
        unregisterData()
        unregisterGate()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        onStop(owner)
        runCatching { vehicle?.release() }
        runCatching { car?.disconnect() }
        vehicle = null; car = null; ux = null
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
        refreshGear()
        ux?.let { m -> guard("ux_restrictions") { flow.update { it.copy(uxRestricted = m.currentCarUxRestrictions?.isRequiresDistractionOptimization) } } }
    }

    /** 기어·규제가 바뀔 때마다: 주행이면 데이터 리스너를 끊고, 정차면 다시 붙인다(X1). */
    @Synchronized
    private fun onGateChanged() {
        if (!started) return
        val s = flow.value
        val driving = isDriving(s)
        ChagokLog.event("gate", "gear" to s.gear.name, "restricted" to s.uxRestricted, "driving" to driving)
        if (driving) ambient.restoreAll("driving")         // 주행 차단은 onStop이 오지 않아 X1에서 따로 되돌림(AC-F28-3)
        if (driving && dataOn) {
            unregisterData()
            ChagokLog.event("driving", "on" to true)
        } else if (!driving && !dataOn) {
            registerData()
            ChagokLog.event("driving", "on" to false)
        }
    }

    // ------------------------------------------------------------ 리스너 (화면이 보이는 동안만)
    // 기어: Pleos Gear API(P CAR_DRIVE, 명세 8절·실측). SDK가 deprecated로 AAOS GEAR_SELECTION을 권하지만
    // 그 속성은 A CAR_POWERTRAIN 권한이라 권한 목록(contract/permissions.md)에 없다 → 권한을 늘리지 않고 이걸 쓴다.
    @Suppress("DEPRECATION")
    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) { ok("gear"); flow.update { it.copy(gear = gear.toState()) }; onGateChanged() }
        override fun onFailed(e: Exception) = fail("gear", e)
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

    /**
     * 규제가 꺼질 때 기어를 Get으로 다시 읽는다. 에뮬(콘솔 D → P)에서 Pleos 기어 리스너가 PARK를 놓치는 일이
     * 3번 중 3번 있었다(11/6 실측: 규제는 false로 오는데 기어가 DRIVE에 머물러 화면이 계속 가려짐).
     */
    private val uxListener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { r ->
        flow.update { it.copy(uxRestricted = r?.isRequiresDistractionOptimization) }
        onGateChanged()
        // P 복귀를 놓치는 경우만 다시 읽는다. 규제가 켜지면 기어와 상관없이 주행이라 Get이 필요 없다(주행 중 Get 0건, AC-X1-2)
        if (r?.isRequiresDistractionOptimization == false) refreshGear()
    }

    private fun refreshGear() {
        @Suppress("DEPRECATION")
        vehicle?.let { v -> guard("gear") { v.getGear().getCurrentGearSelectionState({ g -> ok("gear"); flow.update { it.copy(gear = g.toState()) }; onGateChanged() }, { e -> fail("gear", e) }) } }
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
            seatListeners.forEach { (sv, l) -> guard("seats") { v.getSeat().registerSeatOccupancy(SeatArea(sv), l) } }
        }
        ChagokLog.event("vehicle_listen", "on" to true)
    }

    private fun unregisterData() {
        if (!dataOn) return
        dataOn = false
        vehicle?.let { v ->
            seatListeners.forEach { (sv, l) -> runCatching { v.getSeat().unregisterSeatOccupancy(SeatArea(sv), l) } }
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
