package com.mist.pleos_app.navi

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.mist.pleos_app.util.ChagokLog
import android.os.Handler
import android.os.Looper
import com.mist.pleos_app.engine.Place
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 출발(F8) 진행 상태. 실패하면 "내비를 열 수 없어요" + 재시도(명세 F8·X2). */
enum class DepartState { IDLE, REQUESTING, STARTED, FAILED }

/** 내비로 보낼 목적지. 서버 응답 Card에서 만든다(좌표·이름·id). 주소는 계약에 없어 이름으로 대신한다. */
data class Destination(val id: String, val name: String, val lat: Double, val lng: Double)

/**
 * NaviHelper로 순정 내비 경로 안내를 연다(명세 F8, Pleos 05·12번 문서, 실측 예제 SdkCheckActivity).
 * - 출발을 누를 때만 연결한다(initialize → onConnected → addListener → requestRoute).
 * - 성공 판정은 onRouteStarted(실측 약 9초 뒤). onRoutingCompleted는 실측에서 오지 않았다(Pleos README 「확인되지 않은 것」).
 * - 화면이 가려지면(onStop) 리스너를 빼고 release한다(AC-C-2: 백그라운드 콜백 0건).
 * - 경유지(requestRouteWithWaypoints)·trip 기록(F8 pending, 장부)은 P6에서 붙인다.
 */
class NaviRepository(context: Context) : DefaultLifecycleObserver {
    private companion object { const val TIMEOUT_MS = 2_000L }

    private val app = context.applicationContext
    private val flow = MutableStateFlow(DepartState.IDLE)
    val state: StateFlow<DepartState> = flow.asStateFlow()

    private var navi: NaviHelper? = null
    private var connected = false
    private var pending: Destination? = null
    private var recentWaiters = mutableListOf<(Place?) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    /** 최근 목적지 대기 시간 초과. 응답이 오거나 새로 요청하면 지운다(앞 방문의 타이머가 새 대기자에게 null을 넘기지 않게, 효성 #28 리뷰). */
    private val recentTimeout = Runnable { deliverRecent(null) }

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            flow.value = DepartState.STARTED
            ChagokLog.event("navi_route_started")
        }

        override fun onRecentDestinationInfo(recentDestinationInfoList: List<RecentDestinationInfo>) {
            // 목록 순서가 최신순인지는 문서·실측으로 확인되지 않았다(Pleos README 「확인되지 않은 것」) → 첫 항목을 최신으로 가정
            val first = recentDestinationInfoList.firstOrNull()
            deliverRecent(first?.let { Place(it.name, it.latitude, it.longitude) })
            ChagokLog.event("navi_recent", "count" to recentDestinationInfoList.size)
        }

        override fun onError(errorCode: NaviErrorCode) {
            if (flow.value == DepartState.REQUESTING) flow.value = DepartState.FAILED
            ChagokLog.event("navi_error", "code" to errorCode.name)
        }
    }

    @Synchronized
    fun depart(d: Destination) {
        flow.value = DepartState.REQUESTING
        ChagokLog.event("navi_depart")   // 장소·좌표는 로그에 남기지 않는다(AC-C-10)
        val n = navi
        if (n != null && connected) {
            request(n, d)
            return
        }
        pending = d
        if (n != null) return            // 연결 중
        try {
            navi = NaviHelper(app).also { h ->
                h.initialize(
                    onConnected = { onConnected() },
                    onFailed = { e -> failed("init", e) },
                )
            }
        } catch (e: Exception) {
            failed("init", e)
        }
    }

    /**
     * F10 정산의 "마지막 목적지". getRecentDestinationInfo → onRecentDestinationInfo. [TIMEOUT_MS] 안에 안 오거나
     * 연결에 실패하면 null(목적지 없이 금액만, T4).
     */
    @Synchronized
    fun fetchRecent(done: (Place?) -> Unit) {
        recentWaiters.add(done)
        main.removeCallbacks(recentTimeout)
        main.postDelayed(recentTimeout, TIMEOUT_MS)
        val n = navi
        if (n != null && connected) { askRecent(n); return }
        if (n != null) return
        try {
            navi = NaviHelper(app).also { h -> h.initialize(onConnected = { onConnected() }, onFailed = { deliverRecent(null) }) }
        } catch (e: Exception) {
            deliverRecent(null)
        }
    }

    private fun askRecent(n: NaviHelper) {
        try { n.getRecentDestinationInfo() } catch (e: Exception) { deliverRecent(null) }
    }

    @Synchronized
    private fun deliverRecent(p: Place?) {
        if (recentWaiters.isEmpty()) return
        main.removeCallbacks(recentTimeout)
        val ws = recentWaiters.toList(); recentWaiters.clear()
        main.post { ws.forEach { it(p) } }
    }

    @Synchronized
    private fun onConnected() {
        val n = navi ?: return
        connected = true
        runCatching { n.addListener(listener) }
        pending?.let { request(n, it) }
        pending = null
        if (recentWaiters.isNotEmpty()) askRecent(n)
    }

    private fun request(n: NaviHelper, d: Destination) {
        try {
            n.requestRoute(RouteInfo(latitude = d.lat, longitude = d.lng, poiName = d.name, poiId = d.id, address = d.name, poiSubId = "0"))
        } catch (e: Exception) {
            failed("request", e)
        }
    }

    private fun failed(step: String, e: Throwable) {
        flow.value = DepartState.FAILED
        ChagokLog.event("navi_failed", "step" to step, "err" to e.javaClass.simpleName)
    }

    /** 실패 안내를 지울 때(다시 시도하기 전) */
    fun reset() { if (flow.value != DepartState.REQUESTING) flow.value = DepartState.IDLE }

    @Synchronized
    override fun onStop(owner: LifecycleOwner) {
        navi?.let { n -> runCatching { n.removeListener(listener) }; runCatching { n.release() } }
        navi = null; connected = false; pending = null
        deliverRecent(null)
        if (flow.value == DepartState.REQUESTING) flow.value = DepartState.IDLE
    }
}
