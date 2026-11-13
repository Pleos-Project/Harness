package com.mist.pleos_app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mist.pleos_app.effort.DepartNavi
import com.mist.pleos_app.effort.EffortAmbient
import com.mist.pleos_app.effort.EffortViewModel
import com.mist.pleos_app.effort.TripKeeper
import com.mist.pleos_app.trip.TripService
import com.mist.pleos_app.navi.Destination
import com.mist.pleos_app.navi.NaviPlace
import com.mist.pleos_app.navi.TripPreview
import com.mist.pleos_app.vehicle.seatCount
import com.mist.pleos_app.vehicle.isParked
import com.mist.pleos_app.effort.GuideState
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.mist.pleos_app.ui.EffortApp
import com.mist.pleos_app.ui.theme.ChagokTheme
import com.mist.pleos_app.util.ChagokLog
import com.mist.pleos_app.navi.NaviRepository
import com.mist.pleos_app.vehicle.PleosVehicleRepository
import com.mist.pleos_app.vehicle.isDriving

/**
 * 한 Activity + Compose. 첫 화면은 노고 정산 흐름(명세 v0.5 3절, [EffortApp])이다.
 * 차량값은 [PleosVehicleRepository]가 이 Activity 수명주기에 맞춰 화면이 보일 때만 리스너를 등록한다(AC-C-2).
 * 피봇 전 흐름(잔고·한 장·장부: AppViewModel·ChagokApp)은 CI가 옛 fixture를 대조하는 동안 코드만 남겨 두고,
 * 12/3 전 정리 PR에서 지운다(DECISIONS '기존 기획 코드 정리 순서').
 */
class MainActivity : ComponentActivity() {

    private val vm: EffortViewModel by viewModels()
    private lateinit var vehicle: PleosVehicleRepository
    private lateinit var navi: NaviRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vehicle = PleosVehicleRepository(this)
        lifecycle.addObserver(vehicle)
        navi = NaviRepository(this)                      // 출발 화면(F20): 즐겨찾기·최근 목적지·현재 위치·requestTrip·requestRoute
        navi.onCongestion = vm::onCongestionList          // F20 ②: 나란히 배치일 때만 오는 정체 구간 목록
        navi.onGuideEnd = vm::onGuideEnded                // F22: 나란히 배치에서 내비가 안내를 끝냄 → 기어 P에서 도착 확인(DECISIONS 11/12)
        navi.onGuideStart = vm::onGuideStarted
        lifecycle.addObserver(navi)
        val n = navi
        vm.navi = object : DepartNavi {
            override fun location(done: (Pair<Double, Double>?) -> Unit) = n.fetchLocation { done(it?.let { p -> p.lat to p.lng }) }
            override fun places(done: (List<NaviPlace>) -> Unit) = n.fetchNaviPlaces(done)
            override fun preview(d: Destination, done: (TripPreview?) -> Unit) = n.previewTrip(d, done)
            override fun route(d: Destination) = n.depart(d)
            override fun guide(done: (GuideState?) -> Unit) = n.fetchGuide(done)
            override fun listen() = n.listen()
        }
        // F28 앰비언트 라이트: 정산할 때 켜고 놓기(뷰모델) → 차량 쪽 AmbientLight
        val amb = vehicle.ambient
        vm.ambient = object : EffortAmbient {
            override fun acquire(reason: String) = amb.acquire(reason)
            override fun release(reason: String) = amb.release(reason)
        }
        // 주행 중 포그라운드 서비스(DECISIONS 11/12): 실제 출발~도착 동안 내비 안내 끝(종류·시각)을 받아 남기고 도착 확인 알림(#145)
        val ctx = applicationContext
        vm.tripKeeper = object : TripKeeper {
            override fun sync(destName: String?) = TripService.sync(ctx, destName)
            override fun takeGuideEnd() = TripService.takeGuideEnd(ctx)
            override val running: Boolean get() = TripService.isRunning
        }
        askNotifications()
        // 다시 보일 때마다 도착 여부를 1번 본다(기본 배치에서는 도착 순간 차곡이 멈춰 있음, 명세 F22)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { TripService.appVisible = true; TripService.onAppShown(ctx); vm.onShown() }
            override fun onStop(owner: LifecycleOwner) { TripService.appVisible = false }
        })
        ChagokLog.event("app_create", "restored" to (savedInstanceState != null))
        ChagokLog.event("app_create_noti", "granted" to notificationsGranted())
        setContent {
            val snapshot by vehicle.state.collectAsStateWithLifecycle()
            val settings by vm.settings.collectAsStateWithLifecycle()
            val route by n.state.collectAsStateWithLifecycle()
            androidx.compose.runtime.LaunchedEffect(settings.ambientLight) { vehicle.ambientEnabled = settings.ambientLight }   // S20(AC-F28-5)
            ChagokTheme {
                EffortApp(
                    vm, driving = isDriving(snapshot), parked = isParked(snapshot),
                    seats = seatCount(snapshot.seatsOccupied, invert = settings.seatInvert), route = route,
                ) {
                    val moved = moveTaskToBack(true)
                    ChagokLog.event("back_to_background", "moved" to moved)
                }
            }
        }
    }

    private val notiPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        ChagokLog.event("noti_permission", "granted" to ok)
    }

    private fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** 알림 권한(Android 13+ 런타임). 없으면 앱을 켤 때 1번 묻는다. 거절해도 흐름은 그대로(알림만 안 뜸). */
    private fun askNotifications() {
        if (!notificationsGranted()) notiPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
