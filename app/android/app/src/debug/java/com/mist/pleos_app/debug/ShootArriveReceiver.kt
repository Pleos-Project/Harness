package com.mist.pleos_app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mist.pleos_app.effort.GUIDE_ARRIVED
import com.mist.pleos_app.effort.OpenTrip
import com.mist.pleos_app.effort.PrefsEffortStore
import com.mist.pleos_app.effort.parseDemoScene
import com.mist.pleos_app.trip.TripService
import com.mist.pleos_app.util.ChagokLog

/**
 * 촬영용 도착 신호(debug 빌드에만 있다, 시연 영상 시나리오 5번 컷).
 *
 * 에뮬은 차가 움직이지 않아 순정 내비의 `onDestinationArrived`가 오지 않고, 시연 고정 모드는 [도착했어요]로만 도착한다.
 * 그래서 시연 주행이 가는 중일 때 이 리시버가 주행 중 서비스(TripService)가 도착 신호를 받았을 때와 같은 일을 한다:
 * 안내 끝(도착 · 시연 도착 시각)을 기기에 남기고, 앱이 안 보이면 도착 확인 알림을 올린다. 앱을 앞으로 띄우지 않는다.
 * [열기]를 누르면 뷰모델 onShown이 남긴 신호를 받아(BuildConfig.DEBUG 분기) 기어 P에서 시연 값으로 도착한다.
 *
 * 보내는 법(에뮬 앱은 사용자 10, 보내는 쪽은 adb shell = DUMP 권한):
 * `adb shell am broadcast --user 10 -n com.mist.pleos_app/.debug.ShootArriveReceiver`
 * 결과는 logcat 태그 chagok의 `debug_shoot_arrive`(ok · why).
 */
class ShootArriveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val trip = runCatching { PrefsEffortStore(context).load().openTrip }.getOrNull()
        val demoAt = runCatching {
            context.assets.open(DEMO_ASSET).bufferedReader().use { parseDemoScene(it.readText()).arrivedAt }
        }.getOrNull()
        when (val r = shootArrival(trip, demoAt, TripService.appVisible)) {
            is ShootArrival.Skip -> ChagokLog.event("debug_shoot_arrive", "ok" to false, "why" to r.why)
            is ShootArrival.Post -> {
                TripService.keepGuideEnd(context, GUIDE_ARRIVED, r.at)
                TripService.notifyArrive(context, GUIDE_ARRIVED, r.at, r.dest)
                ChagokLog.event("debug_shoot_arrive", "ok" to true)   // 목적지 이름은 로그에 남기지 않는다(AC-C-10)
            }
        }
    }

    private companion object { const val DEMO_ASSET = "data/effort/demo.json" }
}

sealed interface ShootArrival {
    data class Post(val at: String, val dest: String) : ShootArrival
    data class Skip(val why: String) : ShootArrival
}

/**
 * 촬영용 도착 신호를 올릴지. 시연 주행이 가는 중이고 앱이 가려져 있을 때만(앱이 보이면 서비스도 알림을 올리지 않는다).
 * 도착 시각은 시연 데이터의 도착 시각(demo.json arrived_at, 18:10)이다.
 */
fun shootArrival(trip: OpenTrip?, demoArrivedAt: String?, appVisible: Boolean): ShootArrival = when {
    trip == null -> ShootArrival.Skip("no_trip")
    trip.arrived -> ShootArrival.Skip("arrived")
    !trip.demo -> ShootArrival.Skip("not_demo")
    demoArrivedAt == null -> ShootArrival.Skip("no_demo_data")
    appVisible -> ShootArrival.Skip("visible")
    else -> ShootArrival.Post(demoArrivedAt, trip.destName)
}
