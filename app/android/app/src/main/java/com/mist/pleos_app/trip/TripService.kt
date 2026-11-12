package com.mist.pleos_app.trip

import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.mist.pleos_app.MainActivity
import com.mist.pleos_app.R
import com.mist.pleos_app.util.ChagokLog

/**
 * 주행 중 포그라운드 서비스(DECISIONS 11/12 '주행 중 포그라운드 서비스', F22).
 * - 실제 출발(시연 아님)부터 도착·그만두기까지만 뜬다. 뷰모델이 [sync]로 켜고 끈다.
 * - 기본 배치에서 내비가 앱 창을 덮어 앱이 얼어도, 서비스가 건 내비 리스너로 안내 끝(도착·안내 종료)을 바로 받는다(11/12 에뮬 실측).
 * - 안내가 끝났는데 앱이 안 보이면 도착 확인 알림(카테고리 msg)을 1번 올린다. 누르면 노고가 열리고 뷰모델이 기존 도착 판정을 탄다.
 * - 내비가 목적지 도착(`onDestinationArrived`)을 알리면 노고를 앞으로 띄운다(DECISIONS 11/12 '목적지 도착 때 노고 띄우기', 제안).
 *   AOSP는 이 실행을 막지만(BAL_BLOCK) Pleos 홈이 노고 창을 보여 준다(11/12 에뮬 실측). 안 뜨면 도착 확인 알림이 남는다.
 *   안내 종료(`onRouteCancelled`)는 도착이 아닐 수 있어 띄우지 않고 알림만.
 * - 폴링·타이머 없이 이벤트만 받는다. 차량값·위치를 읽거나 서버로 보내지 않는다.
 */
class TripService : Service() {

    companion object {
        private const val CH_TRIP = "trip_quiet"      // 상주 알림(중요도 최소, 소리·팝업 없이)
        private const val CH_ARRIVE = "arrive"        // 도착 확인 알림(팝업)
        private const val ID_TRIP = 1
        private const val ID_ARRIVE = 2
        private const val EXTRA_DEST = "dest"

        /** 앱 화면이 보이는지(MainActivity onStart·onStop). 보이면 안내 끝은 화면 쪽 리스너가 처리하니 알림을 올리지 않는다. */
        @Volatile var appVisible = false

        /** 앱이 안 보일 때 받은 안내 끝("arrived"·"cancelled"). 앱이 다시 보일 때 뷰모델이 [takeGuideEnd]로 가져간다. 새 안내가 시작되면 지운다. */
        @Volatile private var guideEnd: String? = null

        /** 안 보이는 동안 받은 안내 끝을 1번만 넘긴다. */
        fun takeGuideEnd(): String? = guideEnd.also { guideEnd = null }

        /** 앱이 보이면 도착 확인 알림은 할 일이 끝났으니 내린다(MainActivity onStart). */
        fun onAppShown(context: Context) {
            runCatching { context.getSystemService(NotificationManager::class.java).cancel(ID_ARRIVE) }
        }

        /**
         * [destName]이 있으면(진행 중 실제 출발) 서비스를 띄우고, null이면 끈다. 같은 상태면 아무것도 하지 않는다.
         * 앱이 보이는 동안(출발·도착·다시 보임) 부르므로 Android 백그라운드 시작 제한에 걸리지 않는다.
         */
        fun sync(context: Context, destName: String?) {
            val app = context.applicationContext
            val intent = Intent(app, TripService::class.java)
            if (destName == null) {
                guideEnd = null
                if (running) app.stopService(intent)
                return
            }
            if (running && destName == current) return
            runCatching { app.startForegroundService(intent.putExtra(EXTRA_DEST, destName)) }
                .onFailure { ChagokLog.event("trip_service_failed", "step" to "start", "err" to it.javaClass.simpleName) }
        }

        @Volatile private var running = false
        @Volatile private var current: String? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private var navi: NaviHelper? = null
    private var notified = false
    /** 이번 안내에서 노고를 이미 띄웠는지. 안내 종료 알림을 먼저 올렸어도 뒤에 온 목적지 도착에는 띄운다. */
    private var opened = false

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            main.post {
                guideEnd = null; notified = false; opened = false
                nm().cancel(ID_ARRIVE)
                ChagokLog.event("trip_service_guide", "kind" to "started")
            }
        }

        // 이벤트가 두 번씩 와도(11/7 실측) 알림은 안내 한 번에 1번
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) { main.post { guideEnded("arrived") } }

        override fun onRouteCancelled() { main.post { guideEnded("cancelled") } }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        channels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val dest = intent?.getStringExtra(EXTRA_DEST)
        if (dest == null) { stopSelf(); return START_NOT_STICKY }   // 시스템이 다시 만든 경우 등: 출발 기록은 앱이 다시 보일 때 sync로 맞춘다
        try {
            startForeground(ID_TRIP, tripNotification(dest), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (e: Exception) {
            ChagokLog.event("trip_service_failed", "step" to "foreground", "err" to e.javaClass.simpleName)
            stopSelf(); return START_NOT_STICKY
        }
        val first = !running
        running = true; current = dest
        if (first) connect()
        ChagokLog.event("trip_service_start", "first" to first)   // 목적지 이름은 로그에 남기지 않는다(AC-C-10)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        navi?.let { n -> runCatching { n.removeListener(listener) }; runCatching { n.release() } }
        navi = null
        running = false; current = null
        nm().cancel(ID_ARRIVE)
        main.removeCallbacksAndMessages(null)
        ChagokLog.event("trip_service_stop")
        super.onDestroy()
    }

    private fun connect() {
        try {
            navi = NaviHelper(applicationContext).also { h ->
                h.initialize(
                    onConnected = { runCatching { h.addListener(listener) }; ChagokLog.event("trip_service_navi", "ok" to true) },
                    onFailed = { e -> ChagokLog.event("trip_service_navi", "ok" to false, "err" to e.javaClass.simpleName) },
                )
            }
        } catch (e: Exception) {
            ChagokLog.event("trip_service_navi", "ok" to false, "err" to e.javaClass.simpleName)
        }
    }

    /** 안내 끝. 앱이 보이면 화면 쪽(NaviRepository → 뷰모델)이 처리하니 기억·알림 없이 넘긴다. */
    private fun guideEnded(kind: String) {
        ChagokLog.event("trip_service_guide", "kind" to kind, "visible" to appVisible)
        if (appVisible) return
        if (guideEnd != "arrived") guideEnd = kind               // 도착이 안내 종료보다 강한 근거라 덮어쓰지 않는다
        if (!notified) {
            notified = true
            nm().notify(ID_ARRIVE, arriveNotification())      // 띄우기가 막혀도 눌러서 열 수 있게 먼저 올린다
        }
        if (kind == "arrived" && !opened) { opened = true; bringToFront() }
    }

    /** 목적지 도착 → 노고를 앞으로. 이미 있는 화면을 쓴다(CLEAR_TOP·SINGLE_TOP). 열리면 뷰모델이 다시 보임(RESUME)으로 도착을 판정해 계산을 시작한다. */
    private fun bringToFront() {
        try {
            startActivity(openIntent())
            ChagokLog.event("trip_service_open", "ok" to true)
        } catch (e: Exception) {
            ChagokLog.event("trip_service_open", "ok" to false, "err" to e.javaClass.simpleName)
        }
    }

    private fun nm() = getSystemService(NotificationManager::class.java)

    private fun channels() {
        nm().createNotificationChannels(listOf(
            NotificationChannel(CH_TRIP, getString(R.string.trip_channel_trip), NotificationManager.IMPORTANCE_MIN),
            NotificationChannel(CH_ARRIVE, getString(R.string.trip_channel_arrive), NotificationManager.IMPORTANCE_HIGH),
        ))
    }

    /**
     * 누르면 노고를 연다. 이미 있는 화면을 앞으로 가져온다(CLEAR_TOP·SINGLE_TOP).
     * 런처 인텐트를 그대로 쓰면 같은 작업 위에 MainActivity가 하나 더 쌓였다(11/12 에뮬).
     */
    private fun openApp(): PendingIntent =
        PendingIntent.getActivity(this, 0, openIntent(), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun openIntent(): Intent = Intent(this, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /**
     * 포그라운드 서비스에 꼭 필요한 상주 알림. Pleos 알림 센터·팝업은 카테고리 navigation·msg만 보여 주는데(11/12 실측),
     * navigation으로 두면 팝업이 내비 회전 안내 자리를 덮고 다른 팝업이 올 때까지 안 닫혀서(중요도를 낮춰도 같음)
     * Pleos가 보여 주지 않는 service로 둔다. 사용자에게 보이는 건 도착 확인 알림(msg)뿐이다.
     */
    private fun tripNotification(dest: String): Notification =
        Notification.Builder(this, CH_TRIP)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.trip_noti_title, dest))
            .setContentText(getString(R.string.trip_noti_text))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .build()

    private fun arriveNotification(): Notification =
        Notification.Builder(this, CH_ARRIVE)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.trip_noti_arrive_title))
            .setContentText(getString(R.string.trip_noti_arrive_text))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
}
