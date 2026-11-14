package com.mist.pleos_app.trip

import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
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
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.mist.pleos_app.MainActivity
import com.mist.pleos_app.R
import com.mist.pleos_app.effort.GUIDE_ARRIVED
import com.mist.pleos_app.effort.GUIDE_ROUTE_FAILED
import com.mist.pleos_app.effort.GuideEnd
import com.mist.pleos_app.effort.RouteStart
import com.mist.pleos_app.effort.isoNow
import com.mist.pleos_app.util.ChagokLog
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * 주행 중 포그라운드 서비스(DECISIONS 11/12 '주행 중 포그라운드 서비스', F22).
 * - 실제 출발(시연 아님)부터 도착·그만두기까지만 뜬다. 뷰모델이 [sync]로 켜고 끈다.
 * - 기본 배치에서 내비가 앱 창을 덮어 앱이 얼어도, 서비스가 건 내비 리스너로 안내 끝(도착·안내 종료)을 바로 받는다(11/12 에뮬 실측).
 * - 안내 끝은 종류와 **받은 시각**을 기기에 남긴다(#145). 뷰모델은 도착 신호면 그 시각으로 도착하고, 안내 종료면 "도착했나요?"를 묻는다.
 *   시동이 완전히 꺼져 프로세스가 끝나도 남아 있어서 다시 켠 뒤 앱을 열면 그대로 쓴다.
 * - 안내가 끝났는데 앱이 안 보이면 도착 확인 알림(카테고리 msg)을 1번 올린다. 누르면 노고가 열린다.
 *   도착 신호든 안내 종료든 **노고를 스스로 앞으로 띄우지 않는다**(명세 v0.6 F22 '알림과 열기', DECISIONS 11/14 '여는 방법').
 *   계산은 열린 뒤 화면 쪽 기어 리스너가 P에서 시작한다. 예외는 경로 요청 실패(F20 예외, #132) 하나: [출발] 직후 정차 중이라 출발 화면으로 되돌린다.
 * - 내비 이벤트만 받는다. 차량값(기어 포함)·위치를 읽지 않고, 폴링·타이머가 없다(명세 v0.6 F22 '주행 중 서비스').
 */
class TripService : Service() {

    companion object {
        private const val CH_TRIP = "trip_quiet"      // 상주 알림(중요도 최소, 소리·팝업 없이)
        private const val CH_ARRIVE = "arrive"        // 도착 확인 알림(팝업)
        private const val ID_TRIP = 1
        private const val ID_ARRIVE = 2
        private const val EXTRA_DEST = "dest"
        private const val EXTRA_FRESH = "fresh"
        /** 도착 확인 알림의 [닫기]: 알림만 지운다. 내비·출발 기록·남긴 안내 끝은 그대로(AC-N-6). */
        private const val ACTION_DISMISS_ARRIVE = "com.mist.pleos_app.trip.DISMISS_ARRIVE"
        private const val PREFS = "trip_service"
        private const val K_KIND = "end_kind"
        private const val K_AT = "end_at"
        private const val K_RS_NAME = "route_name"
        private const val K_RS_DUR = "route_duration_s"
        private const val K_RS_AT = "route_at"

        /** 앱 화면이 보이는지(MainActivity onStart·onStop). 보이면 안내 끝은 화면 쪽 리스너가 처리하니 알림을 올리지 않는다. */
        @Volatile var appVisible = false

        /**
         * 길을 보내기 전에 내비가 이미 안내 중이었다(뷰모델이 읽어 넣음, #132). 곧 오는 첫 취소는 이전 안내의 것이라 실패로 보지 않는다.
         * 11/13 에뮬: 이전 안내 취소는 요청 0.2~0.3초, 경로 요청 실패의 취소는 약 3.4초(이미 내비가 노고를 덮은 뒤)에 온다.
         */
        @Volatile var expectOldCancel = false

        /** 서비스가 지금 떠 있는지. 진행 중 출발인데 false면 주행이 끊겼다 이어진 것(뷰모델이 묻는다, #145). */
        val isRunning: Boolean get() = running

        private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** 앱이 안 보이는 동안 받은 안내 끝을 1번 넘기고 지운다. */
        fun takeGuideEnd(context: Context): GuideEnd? {
            val p = prefs(context)
            val kind = p.getString(K_KIND, null)
            val at = p.getString(K_AT, null)
            p.edit().remove(K_KIND).remove(K_AT).apply()
            return if (kind != null && at != null) GuideEnd(kind, at) else null
        }

        private fun clearGuideEnd(context: Context) { prefs(context).edit().remove(K_KIND).remove(K_AT).apply() }

        /** 앱이 안 보이는 동안 받은 목적지 변경(F22, AC-F22-10)을 1번 넘기고 지운다. 프로세스가 끝나도 남는다. */
        fun takeRouteStart(context: Context): RouteStart? {
            val p = prefs(context)
            val name = p.getString(K_RS_NAME, null)
            val at = p.getString(K_RS_AT, null)
            val dur = p.getInt(K_RS_DUR, 0)
            clearRouteStart(context)
            return if (name != null && at != null) RouteStart(name, dur, at) else null
        }

        private fun clearRouteStart(context: Context) { prefs(context).edit().remove(K_RS_NAME).remove(K_RS_DUR).remove(K_RS_AT).apply() }

        /** 앱이 보이면 도착 확인 알림은 할 일이 끝났으니 내린다(MainActivity onStart). */
        fun onAppShown(context: Context) {
            runCatching { context.getSystemService(NotificationManager::class.java).cancel(ID_ARRIVE) }
        }

        /**
         * [destName]이 있으면(진행 중 실제 출발) 서비스를 띄우고, null이면 끈다. 같은 상태면 아무것도 하지 않는다.
         * 앱이 보이는 동안(출발·도착·다시 보임) 부르므로 Android 백그라운드 시작 제한에 걸리지 않는다.
         * [fresh]는 방금 [출발]해서 길을 보내기 직전인지. 아니면(시동이 완전히 꺼졌다 켜진 뒤 다시 보임 등) 이어 가는 주행이다.
         */
        fun sync(context: Context, destName: String?, fresh: Boolean = false) {
            val app = context.applicationContext
            val intent = Intent(app, TripService::class.java)
            if (destName == null) {
                clearGuideEnd(app); clearRouteStart(app)
                if (running) app.stopService(intent)
                return
            }
            if (running && destName == current) return
            runCatching { app.startForegroundService(intent.putExtra(EXTRA_DEST, destName).putExtra(EXTRA_FRESH, fresh)) }
                .onFailure { ChagokLog.event("trip_service_failed", "step" to "start", "err" to it.javaClass.simpleName) }
        }

        @Volatile private var running = false
        @Volatile private var current: String? = null

        /**
         * 받은 안내 끝(종류·시각)을 기기에 남긴다. 앱이 다시 보일 때 [takeGuideEnd]로 1번 넘긴다.
         * 도착이 안내 종료보다 강한 근거라 이미 남은 도착은 덮어쓰지 않는다.
         * 서비스가 부르고, debug 빌드의 촬영용 도착 신호(src/debug ShootArriveReceiver)도 같은 자리를 쓴다.
         */
        fun keepGuideEnd(context: Context, kind: String, at: String) {
            val p = prefs(context)
            if (p.getString(K_KIND, null) != GUIDE_ARRIVED) p.edit().putString(K_KIND, kind).putString(K_AT, at).apply()
        }

        /** 도착 확인 알림을 올린다. 누르면 노고가 열린다(스스로 앞으로 띄우지 않음, F22). 채널이 없으면 먼저 만든다. */
        fun notifyArrive(context: Context, kind: String, at: String, dest: String?) {
            channels(context)
            context.getSystemService(NotificationManager::class.java).notify(ID_ARRIVE, arriveNotification(context, kind, at, dest))
        }

        private fun channels(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
                NotificationChannel(CH_TRIP, context.getString(R.string.trip_channel_trip), NotificationManager.IMPORTANCE_MIN),
                NotificationChannel(CH_ARRIVE, context.getString(R.string.trip_channel_arrive), NotificationManager.IMPORTANCE_HIGH),
            ))
        }

        /**
         * 누르면 노고를 연다. 이미 있는 화면을 앞으로 가져온다(CLEAR_TOP·SINGLE_TOP).
         * 런처 인텐트를 그대로 쓰면 같은 작업 위에 MainActivity가 하나 더 쌓였다(11/12 에뮬).
         */
        private fun openApp(context: Context): PendingIntent =
            PendingIntent.getActivity(context, 0, openIntent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        private fun openIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /**
         * 도착 확인 알림(디자인 v2 arr-noti). OS(Pleos)가 그리고, 앱은 아이콘·제목·본문·버튼·누르면 갈 곳만 정한다.
         * 도착 신호면 "목적지에 도착했어요"와 장소·신호 시각을 보여 주고, 안내 종료(도착이 아닐 수 있음)는 "도착했나요?"를 유지한다.
         * 버튼은 [열기](= 알림 누름과 같음) · [닫기](알림만 지움). 앱을 스스로 띄우지 않는다(F22 '알림과 열기').
         */
        private fun arriveNotification(context: Context, kind: String, at: String, dest: String?): Notification {
            val arrived = kind == GUIDE_ARRIVED
            val title = context.getString(if (arrived) R.string.trip_noti_arrive_title_arrived else R.string.trip_noti_arrive_title)
            val text = if (arrived) context.getString(R.string.trip_noti_arrive_text_arrived, dest?.takeIf { it.isNotBlank() } ?: context.getString(R.string.trip_noti_dest_fallback), clock(at))
            else context.getString(R.string.trip_noti_arrive_text)
            val b = Notification.Builder(context, CH_ARRIVE)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(text.substringBefore("\n"))
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(openApp(context))
                .addAction(Notification.Action.Builder(null, context.getString(R.string.trip_noti_open), openApp(context)).build())
                .addAction(Notification.Action.Builder(null, context.getString(R.string.trip_noti_close), dismissArrive(context)).build())
            largeIcon(context)?.let { b.setLargeIcon(it) }
            return b.build()
        }

        private fun dismissArrive(context: Context): PendingIntent =
            PendingIntent.getService(context, 1, Intent(context, TripService::class.java).setAction(ACTION_DISMISS_ARRIVE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        /** 신호 받은 시각(ISO)을 기기 시간대 기준 HH:mm으로. 읽지 못하면 지금 시각. */
        private fun clock(at: String): String {
            val fmt = DateTimeFormatter.ofPattern("HH:mm")
            return runCatching { OffsetDateTime.parse(at).atZoneSameInstant(java.time.ZoneId.systemDefault()).format(fmt) }
                .getOrElse { java.time.LocalTime.now().format(fmt) }
        }

        /**
         * 큰 아이콘 100px = 노고 앱 아이콘 단색(로고 v2): 주간 lime-solid · 야간 ink-solid. 알림에는 그라데이션을 쓰지 않는다(HANDOFF_v4 2 · 5절).
         * 주간 · 야간은 화면 테마와 같은 시스템 야간 모드로 고른다.
         */
        private fun largeIcon(context: Context): Bitmap? = runCatching {
            val res = context.resources
            val night = (res.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val d = context.getDrawable(if (night) R.drawable.nogo_icon_ink_solid else R.drawable.nogo_icon_lime_solid) ?: return null
            val px = (100 * res.displayMetrics.density).toInt().coerceAtLeast(1)
            Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also { bm -> d.setBounds(0, 0, px, px); d.draw(Canvas(bm)) }
        }.getOrNull()
    }

    private val main = Handler(Looper.getMainLooper())
    private var navi: NaviHelper? = null
    private var notified = false
    /** 경로 요청 실패로 노고를 이미 띄웠는지(한 번만). */
    private var opened = false
    /**
     * 이번 출발의 안내가 시작됐는지(onRouteStarted). 그 전에 오는 취소는 이전 안내의 것이거나 경로 요청 실패라(#132, 11/13 에뮬)
     * 도착 확인 알림을 올리지 않는다. 실패는 화면 쪽(NaviRepository → 뷰모델)이 출발 화면으로 되돌린다.
     */
    private var routeStarted = false
    /** 경로 요청 실패를 이미 넘겼다(내비가 취소를 두 번 보내도 한 번만). */
    private var failedSent = false

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            val rs = RouteStart(routeStartInfo.name, routeStartInfo.duration, isoNow())
            main.post { guideRestarted("started"); routeStarted(rs) }
        }

        // 경유지를 넣거나 지우면 onRouteStarted 없이 onRouteCancelled → onWaypointChanged가 온다(11/14 에뮬, #145).
        // 앞의 취소는 안내 종료가 아니라 경로를 다시 잡은 것이라 새 안내 시작처럼 지운다. 출발 기록은 그대로(name은 경유지 이름)
        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) { main.post { guideRestarted("waypoint") } }

        // 이벤트가 두 번씩 와도(11/7 실측) 알림은 안내 한 번에 1번. 받은 시각은 콜백이 온 때(#145)
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) { val at = isoNow(); main.post { guideEnded(GUIDE_ARRIVED, at) } }

        override fun onRouteCancelled() { val at = isoNow(); main.post { guideEnded("cancelled", at) } }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        channels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISMISS_ARRIVE) {
            nm().cancel(ID_ARRIVE)
            if (!running) stopSelf()   // 서비스가 이미 끝난 뒤의 [닫기]면 이 호출로 뜬 서비스만 정리
            return START_NOT_STICKY
        }
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
        val fresh = intent.getBooleanExtra(EXTRA_FRESH, false)
        if (fresh) { routeStarted = false; failedSent = false }
        else if (first) {
            // 이어 가는 주행(#145 추가): 순정 내비는 시동을 껐다 켜도 도착 전이면 안내를 이어 주는데, 그 안내의 시작은 서비스가 뜨기 전에 지나갔다.
            // 시작을 기다리면 이어진 안내의 도착 신호를 버리고 [안내 종료]를 경로 요청 실패로 잘못 본다(#132 판단은 방금 출발에만).
            // 그래서 이미 시작된 안내로 둔다. 안내가 사라졌으면 끝 이벤트가 안 오고, 새 안내가 시작되면 onRouteStarted가 다시 맞춘다.
            routeStarted = true
        }
        if (first) connect()
        ChagokLog.event("trip_service_start", "first" to first, "fresh" to fresh)   // 목적지 이름은 로그에 남기지 않는다(AC-C-10)
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

    /** 새 안내가 시작됨(목적지를 바꿈 · 다시 안내 · 경유지 변경) → 앞서 받은 안내 끝과 도착 확인 알림을 지운다. */
    private fun guideRestarted(kind: String) {
        clearGuideEnd(this); notified = false; opened = false; routeStarted = true
        nm().cancel(ID_ARRIVE)
        ChagokLog.event("trip_service_guide", "kind" to kind)
    }

    /**
     * 새 안내의 목적지가 지금 목적지와 다르면(주행 중 목적지 변경, F22 · AC-F22-10) 이름 · duration · 받은 시각을 기기에 남기고
     * 상주 알림 제목을 바꾼다. 출발 기록은 앱이 보일 때 뷰모델이 다시 잡는다. 같은 이름(첫 안내 · 재안내)은 남기지 않는다.
     */
    private fun routeStarted(rs: RouteStart) {
        val name = rs.name.trim()
        val same = name.isEmpty() || name == current
        ChagokLog.event("trip_service_route", "same" to same, "duration" to (rs.durationS > 0))   // 이름은 남기지 않는다(AC-C-10)
        if (same) return
        prefs(this).edit().putString(K_RS_NAME, name).putInt(K_RS_DUR, rs.durationS).putString(K_RS_AT, rs.at).apply()
        current = name
        runCatching { nm().notify(ID_TRIP, tripNotification(name)) }
    }

    /** 안내 끝. 앱이 보이면 화면 쪽(NaviRepository → 뷰모델)이 처리하니 기억·알림 없이 넘긴다. */
    private fun guideEnded(kind: String, at: String) {
        ChagokLog.event("trip_service_guide", "kind" to kind, "visible" to appVisible, "started" to routeStarted)
        if (!routeStarted) {
            // 이번 출발의 안내가 시작되기 전의 취소: 이전 안내의 것이거나 경로 요청 실패(#132)
            if (kind != "cancelled") return
            if (expectOldCancel) { expectOldCancel = false; return }
            if (appVisible) return                                    // 보이면 화면 쪽(NaviRepository)이 처리
            if (failedSent) return
            failedSent = true
            prefs(this).edit().putString(K_KIND, GUIDE_ROUTE_FAILED).putString(K_AT, at).apply()
            ChagokLog.event("trip_service_route_failed")
            open()                                                    // 노고를 앞으로: 출발 화면 "내비로 길을 보내지 못했어요" · [다시 시도]
            return
        }
        if (appVisible) return
        keepGuideEnd(this, kind, at)
        if (!notified) {
            notified = true
            notifyArrive(this, kind, at, current)                     // 누르면 노고가 열리고, 남긴 신호 시각으로 도착한다
        }
    }

    /**
     * 노고를 앞으로. 경로 요청 실패(#132)에만 쓴다: [출발] 직후 정차 중이고, 열리면 뷰모델이 출발 화면으로 되돌린다.
     * 이미 있는 화면을 쓴다(CLEAR_TOP·SINGLE_TOP). AOSP는 막지만(BAL_BLOCK) Pleos 홈이 노고 창을 보여 준다(11/12 에뮬 실측).
     */
    private fun open() {
        if (opened || appVisible) return
        opened = true
        try {
            startActivity(openIntent(this))
            ChagokLog.event("trip_service_open", "ok" to true)
        } catch (e: Exception) {
            ChagokLog.event("trip_service_open", "ok" to false, "err" to e.javaClass.simpleName)
        }
    }

    private fun nm() = getSystemService(NotificationManager::class.java)

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
            .setContentIntent(openApp(this))
            .build()
}
