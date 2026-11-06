package com.mist.pleos_app.navi

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.mist.pleos_app.util.ChagokLog
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
    private val app = context.applicationContext
    private val flow = MutableStateFlow(DepartState.IDLE)
    val state: StateFlow<DepartState> = flow.asStateFlow()

    private var navi: NaviHelper? = null
    private var connected = false
    private var pending: Destination? = null

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            flow.value = DepartState.STARTED
            ChagokLog.event("navi_route_started")
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

    @Synchronized
    private fun onConnected() {
        val n = navi ?: return
        connected = true
        runCatching { n.addListener(listener) }
        pending?.let { request(n, it) }
        pending = null
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
        if (flow.value == DepartState.REQUESTING) flow.value = DepartState.IDLE
    }
}
