package com.mist.pleos_app.navi

import ai.pleos.playground.navi.constants.BookmarkType
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RoutePointType
import ai.pleos.playground.navi.constants.TrafficCongestionType
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripRequest
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

/** 카드 출발점(명세 F5: 내비 현재 위치 → 직전 위치 → 설정 집 위치). 기기 메모리에만, 로그·저장 안 함(AC-C-10·AC-N-4). */
data class LatLng(val lat: Double, val lng: Double)

/** 내비로 보낼 목적지(좌표·이름·id). 주소가 없으면 이름으로 대신한다(RouteInfo.address 필수). */
data class Destination(val id: String, val name: String, val lat: Double, val lng: Double, val address: String? = null)

/** 출발 화면 목적지 후보 중 내비에서 온 것(최근 목적지·즐겨찾기). 좌표는 메모리에만 둔다(AC-N-4). */
data class NaviPlace(val dest: Destination, val kind: Kind) {
    enum class Kind { HOME, OFFICE, BOOKMARK, RECENT }
}

/** 즐겨찾기 뒤에 최근 목적지를 붙이고, 이름이 같은 곳은 앞의 것 하나만 남긴다. */
fun mergeNaviPlaces(bookmarks: List<NaviPlace>, recent: List<NaviPlace>): List<NaviPlace> =
    (bookmarks + recent).filter { it.dest.name.isNotBlank() }.distinctBy { it.dest.name }

/**
 * 출발 화면 미리보기(명세 v0.5 F20): `requestTrip` → `onTripInfo`.
 * 정체 비율 = 경로 위 SLOW·HEAVY 구간 비율의 합(TrafficInfo startRatio~endRatio, 4% 단위, #51 June).
 */
data class TripPreview(val etaS: Int, val distanceM: Int, val congestionRatio: Double) {
    /** 정체 거리(m) = 비율 × 경로 거리. 표시만(열린 결정 28). */
    val congestionM: Double get() = congestionRatio * distanceM
}

/**
 * NaviHelper로 순정 내비 경로 안내를 연다(명세 F8, Pleos 05·12번 문서, 실측 예제 SdkCheckActivity).
 * - 출발을 누를 때만 연결한다(initialize → onConnected → addListener → requestRoute).
 * - 성공 판정은 onRouteStarted(실측 약 9초 뒤). onRoutingCompleted는 실측에서 오지 않았다(Pleos README 「확인되지 않은 것」).
 * - 화면이 가려지면(onStop) 리스너를 빼고 release한다(AC-C-2: 백그라운드 콜백 0건).
 * - 경유지(requestRouteWithWaypoints)·trip 기록(F8 pending, 장부)은 P6에서 붙인다.
 */
class NaviRepository(context: Context) : DefaultLifecycleObserver {
    private companion object {
        const val TIMEOUT_MS = 2_000L
        const val TRIP_TIMEOUT_MS = 3_000L      // requestTrip 응답은 곳당 0.3~0.8초(app/README 4절)
    }

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
    private var locationWaiters = mutableListOf<(LatLng?) -> Unit>()
    private val locationTimeout = Runnable { deliverLocation(null) }

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            flow.value = DepartState.STARTED
            ChagokLog.event("navi_route_started")
        }

        override fun onRecentDestinationInfo(recentDestinationInfoList: List<RecentDestinationInfo>) {
            // 목록 순서가 최신순인지는 문서·실측으로 확인되지 않았다(Pleos README 「확인되지 않은 것」) → 첫 항목을 최신으로 가정
            val first = recentDestinationInfoList.firstOrNull()
            deliverRecent(first?.let { Place(it.name, it.latitude, it.longitude) })
            deliverRecentList(recentDestinationInfoList.map {
                NaviPlace(Destination(it.poiId, it.name, it.latitude, it.longitude, it.address), NaviPlace.Kind.RECENT)
            })
            ChagokLog.event("navi_recent", "count" to recentDestinationInfoList.size)
        }

        override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) {
            deliverBookmarks(bookmarkInfoList.map {
                val kind = when (it.category) {
                    BookmarkType.HOME -> NaviPlace.Kind.HOME
                    BookmarkType.OFFICE -> NaviPlace.Kind.OFFICE
                    else -> NaviPlace.Kind.BOOKMARK
                }
                NaviPlace(Destination(it.poiId, it.name, it.latitude, it.longitude, it.address), kind)
            })
            // 이름이 빈 즐겨찾기(집·회사 미등록 칸)는 목록에서 뺀다 → 몇 개였는지만 남긴다(이름·좌표는 로그에 안 남김)
            ChagokLog.event("navi_bookmark", "count" to bookmarkInfoList.size, "named" to bookmarkInfoList.count { it.name.isNotBlank() })
        }

        override fun onTripInfo(tripInfo: TripInfo) {
            // 동시에 보낸 요청의 응답이 섞여 올 수 있다 → 목적지 이름으로 맞춘다(app/README 4절)
            val dest = tripInfo.summary.routePoints.firstOrNull { it.pointType == RoutePointType.DESTINATION }?.poiName
            val ratio = tripInfo.summary.trafficConditions
                .filter { it.congestionType == TrafficCongestionType.SLOW || it.congestionType == TrafficCongestionType.HEAVY }
                .sumOf { (it.endRatio - it.startRatio).coerceAtLeast(0) } / 100.0
            deliverTrip(dest, TripPreview(tripInfo.eta, tripInfo.distance, ratio.coerceIn(0.0, 1.0)))
        }

        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            val ok = currentLocationInfo.latitude != 0.0 || currentLocationInfo.longitude != 0.0
            deliverLocation(if (ok) LatLng(currentLocationInfo.latitude, currentLocationInfo.longitude) else null)
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

    /**
     * 카드 출발점: getCurrentLocationInfo → onCurrentLocationInfo(P NAVI_ROUTE, 명세 F5 출발점).
     * [TIMEOUT_MS] 안에 안 오거나 연결에 실패하면 null(→ 직전 위치 → 집 위치). 카드를 뽑을 때만 부른다(폴링 없음).
     */
    @Synchronized
    fun fetchLocation(done: (LatLng?) -> Unit) {
        locationWaiters.add(done)
        main.removeCallbacks(locationTimeout)
        main.postDelayed(locationTimeout, TIMEOUT_MS)
        val n = navi
        if (n != null && connected) { askLocation(n); return }
        if (n != null) return
        try {
            navi = NaviHelper(app).also { h -> h.initialize(onConnected = { onConnected() }, onFailed = { deliverLocation(null) }) }
        } catch (e: Exception) {
            deliverLocation(null)
        }
    }

    private fun askLocation(n: NaviHelper) {
        try { n.getCurrentLocationInfo() } catch (e: Exception) { deliverLocation(null) }
    }

    @Synchronized
    private fun deliverLocation(p: LatLng?) {
        if (locationWaiters.isEmpty()) return
        main.removeCallbacks(locationTimeout)
        val ws = locationWaiters.toList(); locationWaiters.clear()
        main.post { ws.forEach { it(p) } }
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

    // ------------------------------------------------------------ 노고 출발 화면(명세 v0.5 F20)
    private var recentListWaiter: ((List<NaviPlace>) -> Unit)? = null
    private var bookmarkWaiter: ((List<NaviPlace>) -> Unit)? = null
    private val recentListTimeout = Runnable { deliverRecentList(emptyList()) }
    private val bookmarkTimeout = Runnable { deliverBookmarks(emptyList()) }
    private var tripWaiter: Pair<String, (TripPreview?) -> Unit>? = null
    private val tripTimeout = Runnable { deliverTrip(null, null, force = true) }
    /** 연결되면 할 일(출발 화면 조회). 연결 실패·화면 가림이면 버린다. */
    private val onReady = mutableListOf<(NaviHelper) -> Unit>()

    /** 연결돼 있으면 바로, 아니면 연결 뒤에 [action]. 연결에 실패하면 [fail]. */
    @Synchronized
    private fun withNavi(fail: () -> Unit, action: (NaviHelper) -> Unit) {
        val n = navi
        if (n != null && connected) { action(n); return }
        onReady.add(action)
        if (n != null) return
        try {
            navi = NaviHelper(app).also { h -> h.initialize(onConnected = { onConnected() }, onFailed = { onReady.clear(); fail() }) }
        } catch (e: Exception) {
            onReady.clear(); fail()
        }
    }

    /**
     * 출발 화면 목적지 후보: 즐겨찾기(집·회사 먼저) + 최근 목적지(getBookmarkInfo·getRecentDestinationInfo, P NAVI_ROUTE).
     * 각각 [TIMEOUT_MS] 안에 안 오면 빈 목록. 같은 이름은 한 번만. 출발 화면에 들어올 때 1번(폴링 없음).
     */
    fun fetchNaviPlaces(done: (List<NaviPlace>) -> Unit) {
        var recent: List<NaviPlace>? = null
        var bookmarks: List<NaviPlace>? = null
        fun maybeDone() {
            val r = recent ?: return
            val b = bookmarks ?: return
            val order = listOf(NaviPlace.Kind.HOME, NaviPlace.Kind.OFFICE, NaviPlace.Kind.BOOKMARK)
            done(mergeNaviPlaces(b.sortedBy { order.indexOf(it.kind) }, r))
        }
        synchronized(this) {
            recentListWaiter = { recent = it; maybeDone() }
            bookmarkWaiter = { bookmarks = it; maybeDone() }
            main.removeCallbacks(recentListTimeout); main.postDelayed(recentListTimeout, TIMEOUT_MS)
            main.removeCallbacks(bookmarkTimeout); main.postDelayed(bookmarkTimeout, TIMEOUT_MS)
        }
        withNavi(fail = { deliverRecentList(emptyList()); deliverBookmarks(emptyList()) }) { n ->
            try { n.getRecentDestinationInfo() } catch (e: Exception) { deliverRecentList(emptyList()) }
            try { n.getBookmarkInfo() } catch (e: Exception) { deliverBookmarks(emptyList()) }
        }
    }

    /**
     * 출발 화면 미리보기: requestTrip → onTripInfo(P NAVI_ROUTE). 목적지를 고를 때 1번.
     * [TRIP_TIMEOUT_MS] 안에 안 오거나 실패하면 null → ETA·정체 없이 출발(정체 정보 없음, F20 예외).
     * 새로 요청하면 앞 요청의 응답은 버린다.
     */
    fun previewTrip(d: Destination, done: (TripPreview?) -> Unit) {
        synchronized(this) {
            tripWaiter?.second?.let { prev -> main.post { prev(null) } }
            tripWaiter = d.name to done
            main.removeCallbacks(tripTimeout); main.postDelayed(tripTimeout, TRIP_TIMEOUT_MS)
        }
        withNavi(fail = { deliverTrip(null, null, force = true) }) { n ->
            try {
                n.requestTrip(TripRequest(latitude = d.lat, longitude = d.lng, poiId = d.id, poiName = d.name, poiSubId = "0"))
            } catch (e: Exception) {
                deliverTrip(null, null, force = true)
                ChagokLog.event("navi_trip_failed", "err" to e.javaClass.simpleName)
            }
        }
    }

    @Synchronized
    private fun deliverRecentList(list: List<NaviPlace>) {
        val w = recentListWaiter ?: return                   // 같은 콜백이 두 번 와도 한 번만(#51 June)
        recentListWaiter = null
        main.removeCallbacks(recentListTimeout)
        main.post { w(list) }
    }

    @Synchronized
    private fun deliverBookmarks(list: List<NaviPlace>) {
        val w = bookmarkWaiter ?: return
        bookmarkWaiter = null
        main.removeCallbacks(bookmarkTimeout)
        main.post { w(list) }
    }

    /** [dest]가 기다리는 목적지 이름과 다르면(늦게 온 앞 요청의 응답) 버린다. [force]면 이름과 상관없이 끝낸다(시간 초과·실패). */
    @Synchronized
    private fun deliverTrip(dest: String?, p: TripPreview?, force: Boolean = false) {
        val (name, w) = tripWaiter ?: return
        if (!force && dest != null && dest != name) return
        tripWaiter = null
        main.removeCallbacks(tripTimeout)
        ChagokLog.event("navi_trip", "ok" to (p != null), "eta" to p?.etaS, "ratio" to p?.congestionRatio)
        main.post { w(p) }
    }

    @Synchronized
    private fun onConnected() {
        val n = navi ?: return
        connected = true
        runCatching { n.addListener(listener) }
        pending?.let { request(n, it) }
        pending = null
        if (recentWaiters.isNotEmpty()) askRecent(n)
        if (locationWaiters.isNotEmpty()) askLocation(n)
        val ready = onReady.toList(); onReady.clear()
        ready.forEach { it(n) }
    }

    private fun request(n: NaviHelper, d: Destination) {
        try {
            // 주소가 빈 문자열이면 이름으로 채운다(#57 June)
            val address = d.address?.takeIf { it.isNotBlank() } ?: d.name
            n.requestRoute(RouteInfo(latitude = d.lat, longitude = d.lng, poiName = d.name, poiId = d.id, address = address, poiSubId = "0"))
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
        onReady.clear()
        deliverRecent(null)
        deliverLocation(null)
        deliverRecentList(emptyList())
        deliverBookmarks(emptyList())
        deliverTrip(null, null, force = true)
        if (flow.value == DepartState.REQUESTING) flow.value = DepartState.IDLE
    }
}
