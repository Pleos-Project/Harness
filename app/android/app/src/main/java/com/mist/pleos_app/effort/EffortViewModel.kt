package com.mist.pleos_app.effort

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.mist.pleos_app.BuildConfig
import com.mist.pleos_app.app.PayerFlow
import com.mist.pleos_app.app.PayerState
import com.mist.pleos_app.app.PrefsPayerStore
import com.mist.pleos_app.app.PrefsSettingsStore
import com.mist.pleos_app.app.Settings
import com.mist.pleos_app.engine.EffortConstants
import com.mist.pleos_app.engine.EffortReceipt
import com.mist.pleos_app.engine.departurePreview
import com.mist.pleos_app.engine.epochS
import com.mist.pleos_app.engine.hhmm
import com.mist.pleos_app.engine.sunTimes
import com.mist.pleos_app.engine.parseEffortConstants
import com.mist.pleos_app.navi.Destination
import com.mist.pleos_app.navi.NaviPlace
import com.mist.pleos_app.navi.TripPreview
import com.mist.pleos_app.net.EffortApi
import com.mist.pleos_app.net.PlacesResult
import com.mist.pleos_app.net.PlacesStatus
import com.mist.pleos_app.util.ChagokLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** 출발 화면이 쓰는 내비 기능(NaviRepository). 화면이 보일 때만 부른다(AC-C-2). */
interface DepartNavi {
    fun location(done: (Pair<Double, Double>?) -> Unit)
    fun places(done: (List<NaviPlace>) -> Unit)
    fun preview(d: Destination, done: (TripPreview?) -> Unit)
    /** [replacing] = 보내기 전에 내비가 이미 안내 중이었음(이전 안내 취소를 실패로 보지 않게). */
    fun route(d: Destination, replacing: Boolean = false)
    /** 도착인지 중간 정차인지(F22). */
    fun guide(done: (GuideState?) -> Unit)
    /** 보이는 동안 내비 이벤트(안내 끝)를 받도록 연결만 해 둔다. 화면이 가려지면 리스너를 놓는다. */
    fun listen() {}
}

/** 앰비언트 라이트(F28) 켜 달라는 이유를 차량 쪽(AmbientLight)에 넘긴다. MainActivity가 넣는다. 없으면(테스트) 조명 없이. */
interface EffortAmbient {
    fun acquire(reason: String)
    fun release(reason: String)
}

/**
 * 주행 중 포그라운드 서비스(DECISIONS 11/12, TripService). MainActivity가 넣는다. 없으면(테스트) 서비스 없이.
 * - [sync]: 진행 중 실제 출발의 목적지 이름이면 띄우고, null이면 끈다
 * - [takeGuideEnd]: 앱이 안 보이는 동안 서비스가 받은 안내 끝(종류·받은 시각)을 1번 가져온다. 프로세스가 끝나도 남는다
 * - [running]: 서비스가 지금 떠 있는지. 진행 중 출발인데 없으면 주행이 끊겼다 이어진 것(시동이 완전히 꺼짐 등)
 */
interface TripKeeper {
    fun sync(destName: String?, fresh: Boolean = false)
    fun takeGuideEnd(): GuideEnd?
    val running: Boolean
    /** 보내기 전에 내비가 이미 안내 중이었음 → 서비스가 첫 취소를 이전 안내의 것으로 본다(#132). */
    fun expectOldCancel(v: Boolean) {}
    /** 앱이 안 보이는 동안 서비스가 받은 목적지 변경(F22, AC-F22-10)을 1번 가져온다. */
    fun takeRouteStart(): RouteStart? = null
}

/** 내비 안내 끝([GUIDE_ARRIVED]·[GUIDE_CANCELLED])과 받은 시각(ISO). */
data class GuideEnd(val kind: String, val at: String)

/** "도착했나요?"(#145). 예면 [at]으로 도착 처리. [kind]: cancelled(안내 종료) · arrived(ETA 없는 도착 신호) · interrupted(주행이 끊겼다 이어짐). */
data class ArriveAsk(val at: String, val kind: String)

/** 노고 흐름 화면(명세 v0.5 3절). */
/** 도착 화면(RECEIPT)은 영수증과 제안을 한 화면에 둔다(Design v2 arrive, #75). */
enum class EffortScreen { HOME, DEPART, SETTINGS, REVIEW, RECEIPT, LOG, RECORD, MIDWAY }

/** 홈·출발·영수증 화면이 보는 상태. */
data class EffortUi(
    val phase: EffortPhase,
    val openTrip: OpenTrip?,
    val recent: List<EffortRecord>,
    val records: List<EffortRecord> = emptyList(),      // 주행 기록 목록(최근 순, Q-08-01)
    val record: EffortRecord? = null,                   // 기록 상세로 연 기록
    val demo: Boolean,              // 지금 쓰는 출처가 시연 데이터인지(AC-C-7)
    val demoAvailable: Boolean,     // 시연 데이터(assets/data/effort/demo.json)가 들어 있는지
    val receipt: EffortReceipt? = null,                 // 도착했으면 엔진 영수증(F23)
    val weatherPending: Boolean = false,                // 도착 날씨를 받는 중
    val restQuestion: String? = null,                   // effort.copy rest_question_text("중간에 쉰 시간")
    val restAnswers: List<Pair<Int, String>> = emptyList(),   // (분, 문구): 없음·15분·30분·1시간 이상
    val proposal: ProposalView? = null,                 // 설정 방식(A/B)의 제안 화면. null이면 영수증만(F25 '제안 없음'·혼자 탄 주행)
    val settleFold: SettleFoldView? = null,             // 최종 영수증 하단 중간 정산 줄(#118). null = 중간 정산 안 한 주행
    val settleIncluded: Boolean = false,                // 중간 정산 포함 모드(제안 = 남은 몫). 처음은 전체 모드
    val unsettled: Boolean = false,                     // 정리하지 못함(#68): 영수증 대신 안내 문구(receipt.statusText)와 [닫기]만
    val items: List<com.mist.pleos_app.engine.EffortItem> = emptyList(),   // 방식 B 항목(공동 경비 예시 포함)
    val done: ArriveDone? = null,                       // 고른 뒤: 기록은 남겼고 같은 화면에 "남겼어요"를 보여 주는 중
    val reviewItems: List<ReviewItem> = emptyList(),    // 오늘 운전 돌아보기 기준 5줄(F29, 도착 뒤 · 정리하지 못함이 아닐 때)
    val justFinished: String? = null,                   // 방금 끝난 기록 trip_id(알림 "방금", 메모리만)
    val notiDot: Boolean = false,                       // 알림 종 활성(울리는 종 + 빨간 점): 기록이 저장되면 켜고 창을 닫으면(읽음) 끔(11/9 명준 2차)
    val notiOpen: Boolean = false,                      // 알림 창 열림
    val notiFresh: Set<String> = emptySet(),            // 아직 안 읽은 알림(trip_id). 창을 닫으면 읽음
    val constants: com.mist.pleos_app.engine.EffortConstants? = null,
    val recordCount: Int = 0,                           // 남은 기록 수(설정 › 기록 초기화 "N건을 지워요")
    val midway: MidwayUi? = null,                       // 중간 정산 화면(F27)
    val midwayAvailable: Boolean = false,               // 가는 중 홈 내 차 그림 = [지금까지의 노고 보기]
    val arriveAsk: ArriveAsk? = null,                   // 홈 "도착했나요?" 시트(#145)
)

/**
 * 노고 흐름의 화면 이동·복원(AC-C-11·AC-N-2)과 출발 기록·선택 기록.
 * - 화면 스택은 SavedStateHandle → 다른 앱에 갔다 오거나 프로세스가 다시 만들어져도 같은 화면
 * - 출발 기록·기록은 [EffortStore] → 앱을 다시 켜도 홈에 "진행 중"(X2)
 * - 실제/시연 값은 [EffortSource] 하나로 고른다(설정 '데모 고정 모드')
 */
class EffortViewModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {

    private val flow = EffortFlow(PrefsEffortStore(app))

    /** 시연 데이터(빌드 때 fixtures/effort/demo.json → assets/data/effort/demo.json). 없으면 null. */
    private val demoScene: DemoScene? = readAsset(app, DEMO_ASSET)?.let { t ->
        runCatching { parseDemoScene(t) }.onFailure { ChagokLog.event("effort_demo_invalid", "err" to it.javaClass.simpleName) }.getOrNull()
    }
    private val demoSource = demoScene?.let(::DemoEffortSource)

    /** 설정 '시연 고정 모드' 설명 줄용 "목적지 · 배수"(#75 v41). 시연 데이터나 상수가 없으면 null. */
    val demoSummary: Pair<String, String>? by lazy { demoScene?.let { sc -> effortConstants?.let { demoSummaryOf(sc, it) } } }

    /** 노고 상수·문구(constants.json effort, 빌드 때 fixtures/에서 복사). 없으면 미리보기 문구 없이 진행. */
    /** 출발 미리보기 배경의 카카오 분류 표(constants.json preview_bg, #187). 못 읽으면 null = 검색 결과도 중립. */
    private val previewBgTable: PreviewBgTable? = readAsset(app, "data/constants.json")?.let(::parsePreviewBgTable)
    private val effortConstants: EffortConstants? = readAsset(app, "data/constants.json")?.let { t ->
        runCatching { parseEffortConstants(t) }.onFailure { ChagokLog.event("effort_constants_invalid", "err" to it.javaClass.simpleName) }.getOrNull()
    }
    private val liveSource = LiveEffortSource()

    // ------------------------------------------------------------ 설정(S20)
    private val settingsStore = PrefsSettingsStore(app)
    private val settingsFlow = MutableStateFlow(settingsStore.load())
    val settings: StateFlow<Settings> = settingsFlow.asStateFlow()

    fun updateSettings(next: Settings) {
        settingsStore.save(next); settingsFlow.value = next
        ui.value = uiOf()
        ChagokLog.event("effort_settings", "method" to next.proposalMethod, "demo" to next.demoFixed)
    }

    /** 데모 고정 모드이고 시연 데이터가 있으면 시연, 아니면 실제. */
    val source: EffortSource
        get() = demoSource?.takeIf { settingsFlow.value.demoFixed } ?: liveSource

    // ------------------------------------------------------------ 상태
    /** 고른 뒤 [닫기] 전까지 보여 줄 영수증·제안(메모리만, 기록은 이미 저장됨). */
    private var done: ArriveDone? = null
    /** 최종 영수증 하단 중간 정산 줄의 모드(#118). 새 출발 · 도착마다 전체 모드로 돌아간다. */
    private var settleIncluded = false

    /** 영수증 하단 "중간 정산 있음 ⇅" / "운전 노고 전체 ⇅": 전체 ↔ 포함 모드. 고른 뒤에는 바꾸지 않는다. */
    fun toggleSettle() {
        if (done != null) return
        settleIncluded = !settleIncluded
        refresh()
    }
    /** 방금 끝난 기록(홈 "방금" 표시). 다음 출발 화면에 들어가면 지운다. */
    private var justFinished: String? = null
    // 알림(#118 v93~v96, HANDOFF_v3 5-7 · 8절): 메모리에만 둔다. 앱을 다시 켜도 남길지는 기획 확인
    private var notiDot = false
    private var notiOpen = false
    private var notiFresh: Set<String> = emptySet()

    /** 기록이 저장됐을 때: 종에 빨간 점, 그 기록은 새 알림. */
    private fun notifySaved(tripId: String) { notiDot = true; notiFresh = notiFresh + tripId }

    /** 종: 창을 열고, 닫으면 읽음(울리는 종 · 빨간 점 끔, 새 알림 굵기 해제). */
    fun toggleNoti() {
        if (notiOpen) closeNoti() else { notiOpen = true; refresh() }
    }

    fun closeNoti() {
        if (!notiOpen) return
        notiOpen = false; notiDot = false; notiFresh = emptySet(); refresh()
    }

    private val ui = MutableStateFlow(uiOf())
    val state: StateFlow<EffortUi> = ui.asStateFlow()

    private fun uiOf(): EffortUi {
        val d = done
        val trip = flow.state.openTrip ?: d?.trip
        val receipt = if (flow.state.openTrip != null) receiptOf(trip, effortConstants) else d?.receipt
        return EffortUi(
            phase = flow.phase,
            openTrip = trip,
            recent = flow.recent(),
            records = flow.state.records,
            record = saved.get<String>(KEY_RECORD)?.let { id -> flow.state.records.firstOrNull { it.tripId == id } },
            recordCount = flow.state.records.size,
            demo = source.demo || trip?.demo == true,
            demoAvailable = demoSource != null,
            receipt = receipt,
            weatherPending = trip?.arrived == true && trip.weatherArr == null,
            restQuestion = effortConstants?.copy?.text?.get("rest_question_text"),
            restAnswers = effortConstants?.copy?.restAnswers.orEmpty(),
            proposal = d?.proposal ?: proposalViewOf(receipt?.let { r -> trip?.let { t -> receiptForSettleMode(r, settleIncluded, t.n, effortConstants) } ?: r }, settingsFlow.value.proposalMethod, effortConstants?.items.orEmpty()),
            settleFold = settleFoldOf(receipt, settingsFlow.value.proposalMethod),
            settleIncluded = settleIncluded,
            unsettled = isUnsettled(receipt),
            items = effortConstants?.items.orEmpty(),
            done = d,
            justFinished = justFinished,
            notiDot = notiDot, notiOpen = notiOpen, notiFresh = notiFresh,
            constants = effortConstants,
            midway = midway,
            arriveAsk = arriveAsk?.takeIf { flow.phase == EffortPhase.DRIVING },
            // 실제 주행이고 경로 거리를 알 때만(지금까지 거리 = 경로 − 내비 남은 거리). 시연 데이터에는 중간 정산 장면이 없다
            midwayAvailable = trip != null && !trip.arrived && (!trip.demo || demoScene?.midway != null) && (trip.routeM ?: 0.0) > 0 && effortConstants != null,
            reviewItems = if (trip?.arrived == true && receipt != null && !isUnsettled(receipt) && effortConstants != null)
                reviewItems(trip, receipt, effortConstants, reviewWords()) else emptyList(),
        )
    }

    private fun refresh() { ui.value = uiOf() }

    // ------------------------------------------------------------ 화면 스택
    val stack: StateFlow<List<EffortScreen>> = saved.getStateFlow(KEY_STACK, listOf(EffortScreen.HOME.name))
        .map { names -> names.mapNotNull { n -> EffortScreen.entries.firstOrNull { it.name == n } }.ifEmpty { listOf(EffortScreen.HOME) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, listOf(EffortScreen.HOME))

    fun open(screen: EffortScreen) {
        val names = saved.get<List<String>>(KEY_STACK) ?: listOf(EffortScreen.HOME.name)
        val i = names.indexOf(screen.name)
        saved[KEY_STACK] = if (i >= 0) names.take(i + 1) else names + screen.name
    }

    /** 뒤로. 홈이면 false → Activity가 moveTaskToBack(true)로 숨긴다(CCP Back, Pleos 앱 개발 가이드). */
    /** 기록 상세(Q-08-01): 홈 최근 기록 카드나 목록 줄에서. */
    fun openRecord(tripId: String) {
        saved[KEY_RECORD] = tripId
        refresh()
        open(EffortScreen.RECORD)
    }

    fun back(): Boolean {
        val names = saved.get<List<String>>(KEY_STACK) ?: return false
        if (names.size <= 1) return false
        saved[KEY_STACK] = names.dropLast(1)
        if (names.last() == EffortScreen.MIDWAY.name) { midway = null; refresh() }
        if (done != null) { done = null; refresh() }   // 고른 뒤 뒤로 = [닫기]와 같음(기록은 이미 저장됨)
        return true
    }

    private fun home() { saved[KEY_STACK] = listOf(EffortScreen.HOME.name) }

    // ------------------------------------------------------------ 출발(F20)
    /** MainActivity가 넣는다(NaviRepository). 없으면(미리보기·테스트) 내비 후보·미리보기·경로 없이 진행. */
    var navi: DepartNavi? = null

    /** F28: 앱이 보이는 동안 브랜드 색으로 켜 둔다(화면 EffortApp이 부름, 구두 합의 11/9). */
    var ambient: EffortAmbient? = null

    /** 주행 중 포그라운드 서비스. 출발·도착·그만두기·다시 보일 때 [syncTrip]으로 맞춘다. */
    var tripKeeper: TripKeeper? = null

    /** 진행 중 실제 출발(시연 아님)이면 서비스를 띄우고, 아니면 끈다(DECISIONS 11/12). [fresh]는 [출발] 직후(길을 보내기 전). */
    private fun syncTrip(fresh: Boolean = false) {
        val trip = flow.state.openTrip
        val live = flow.phase == EffortPhase.DRIVING && trip != null && !trip.demo && !source.demo
        tripKeeper?.sync(if (live) trip!!.destName else null, fresh)
    }

    private val api = EffortApi(BuildConfig.API_BASE_URL)
    /** 시간대별 강수는 서버가 기상청 호출을 3초에서 끊으므로 앱은 4초까지 기다린다(#66 June 답). */
    private val tripApi = EffortApi(BuildConfig.API_BASE_URL, timeoutMs = TRIP_TIMEOUT_MS.toInt())
    private val departFlow = MutableStateFlow(DepartUi())
    val departUi: StateFlow<DepartUi> = departFlow.asStateFlow()

    /** 출발 화면에 들어온 뒤 읽은 현재 위치와 그 지점 날씨. 메모리에만 두고 저장·로그하지 않는다(AC-N-4·AC-C-10). */
    private var here: Pair<Double, Double>? = null
    private var weatherDep: String? = null
    /** 마지막으로 내비에 보낸 목적지(경로 요청 실패 시 다시 시도용, 메모리만). */
    private var lastRoute: Destination? = null
    private var searchJob: Job? = null

    /** 홈 [동승 출발] → 출발 화면. 실제 모드면 내비 후보·현재 위치·출발 날씨를 1번씩 읽는다(폴링 없음). */
    fun openDepart() {
        justFinished = null
        settleIncluded = false
        open(EffortScreen.DEPART)
        departFlow.value = DepartUi(bgTable = previewBgTable)
        askFlow.value = true
        here = null; weatherDep = null
        // 시연: 화면 · 기능은 같고 최근 목적지는 앱 안 시연 목록(내비 · 위치 · 날씨를 부르지 않음)
        if (source.demo) {
            demoScene?.let { s -> departFlow.update { it.copy(naviOptions = demoRecents(s), bgWeather = s.weatherDep, bgHour = hourOf(s.departedAt), bgDemoKinds = s.bgKinds) } }
            return
        }
        val n = navi ?: return
        departFlow.update { it.copy(naviLoading = true) }
        n.places { list -> departFlow.update { it.copy(naviOptions = list.map { p -> p.toOption() }, naviLoading = false) } }
        n.location { loc ->
            here = loc
            if (loc == null) { weatherDep = WEATHER_UNKNOWN; return@location }
            viewModelScope.launch {
                val w = withTimeoutOrNull(NET_TIMEOUT_MS) { withContext(Dispatchers.IO) { api.weather(loc.first, loc.second) } } ?: WEATHER_UNKNOWN
                weatherDep = w
                departFlow.update { it.copy(bgWeather = w) }   // 미리보기 배경 날씨(비 · 눈)
                ChagokLog.event("effort_weather", "at" to "dep", "precip" to weatherDep)
            }
        }
    }

    fun setQuery(q: String) { departFlow.update { it.copy(query = q) } }

    /** 검색 지우기: 결과·상태를 비우고 목적지 카드로 돌아간다. */
    fun clearSearch() { searchJob?.cancel(); departFlow.update { it.copy(query = "", searching = false, searchStatus = null, searchResults = emptyList()) } }

    /** 고른 목적지를 다시 누르면 선택을 푼다(Design v2 depart: 미리보기 빈 자리). */
    fun unselect() { departFlow.update { it.copy(selected = null, preview = null, previewText = null, previewing = false, routeFailed = false) } }

    /** 시연 출발 화면의 미리보기(엔진 departurePreview, demo.json 값). */
    val demoPreview: com.mist.pleos_app.engine.DeparturePreview?
        get() = demoScene?.let { s -> effortConstants?.let { c -> departurePreview(s.etaS, s.routeM, s.congestionRatio, s.congestionM, c) } }

    /** 키보드 [검색]·검색 버튼을 눌렀을 때만 1번(자동 완성 없음, #57 June). 3초 안에 못 받으면 unavailable. */
    fun search() {
        val q = departFlow.value.query.trim()
        if (q.isEmpty()) return
        searchJob?.cancel()
        if (source.demo) {   // 시연: 서버 대신 앱 안 시연 검색
            val (status, list) = demoScene?.let { demoSearch(q, it) } ?: (PlacesStatus.EMPTY to emptyList())
            departFlow.update { it.copy(searching = false, searchStatus = status, searchResults = list, searchSentLocation = true) }
            return
        }
        val loc = here   // 이번 검색에 보내는 위치(없으면 null → 정확도순 안내)
        departFlow.update { it.copy(searching = true, searchStatus = null, searchResults = emptyList(), searchSentLocation = loc != null) }
        searchJob = viewModelScope.launch {
            val r = withTimeoutOrNull(NET_TIMEOUT_MS) { withContext(Dispatchers.IO) { api.places(q, loc) } }
                ?: PlacesResult(PlacesStatus.UNAVAILABLE, emptyList())
            departFlow.update { it.copy(searching = false, searchStatus = r.status, searchResults = r.places.map { p -> p.toOption() }) }
            ChagokLog.event("effort_search", "status" to r.status.name, "count" to r.places.size, "here" to (loc != null))
        }
    }

    /** 목적지 고르기 → requestTrip으로 ETA·경로 거리·정체 비율을 미리 받는다(출발 뒤에는 차곡이 멈춰 못 받음, #51). */
    fun select(o: DestOption) {
        if (source.demo) {   // 시연: 어느 곳이든 미리보기는 시연 장면 값(이름만 고른 곳)
            departFlow.update { it.copy(selected = o, preview = null, previewText = demoPreview, previewing = false) }
            return
        }
        departFlow.update { it.copy(selected = o, preview = null, previewText = null, previewing = navi != null, routeFailed = false) }
        navi?.preview(o.dest) { p ->
            val text = p?.let { t -> effortConstants?.let { c -> departurePreview(t.etaS.toDouble(), t.distanceM.toDouble(), t.congestionRatio, t.congestionM, c) } }
            departFlow.update { if (it.selected == o) it.copy(preview = p, previewText = text, previewing = false) else it }
        }
    }

    /**
     * [출발]. 출발 기록을 먼저 저장하고(출발 직후 동결돼도 남게) 순정 내비 안내를 요청한 뒤 홈으로.
     * 시연 모드면 시연 데이터로 출발 기록을 만든다(내비 호출 없음).
     * @param seats 좌석 센서로 감지한 인원 → 나눠 낼 인원(인원 확인 알림 값)
     * @return 출발 기록을 새로 열었으면 true
     */
    fun depart(seats: Int = 1): Boolean {
        val ui = departFlow.value
        val sel = ui.selected ?: return false
        if (!ui.canDepart) return false
        // 시연: 주행 숫자는 시연 장면 그대로, 목적지 이름 · 나눠 낼 인원만 화면에서 고른 값(내비 호출 없음)
        source.demoTrip()?.let { demo ->
            val opened = flow.depart(demo.copy(destName = sel.dest.name, n = payersFor(seats)))
            refresh(); home()
            departFlow.value = DepartUi()
            return opened
        }
        val trip = departTrip(newTripId(), source.departedAt(), sel.dest, payersFor(seats), ui.preview, weatherDep ?: WEATHER_UNKNOWN, here)
        val opened = flow.depart(trip)
        tripKeeper?.takeGuideEnd()                   // 지난 주행에서 남은 안내 끝은 버린다
        tripKeeper?.takeRouteStart()                 // 지난 주행에서 남은 목적지 변경도 버린다
        clearGuideEnd()
        syncTrip(fresh = true)                       // 내비가 덮기 전에(앱이 보이는 동안) 서비스를 띄운다
        lastRoute = sel.dest
        departSnapshot = ui.copy(routeFailed = false)   // 경로 요청이 실패하면 이 출발 화면으로 되돌린다(#132)
        departTripId = trip.tripId
        sendRoute(sel.dest)
        refresh(); home()
        departFlow.value = DepartUi()
        return opened
    }

    /** 경로 요청 실패 때 되돌릴 출발 화면(고른 목적지 · 미리보기)과 그 출발 기록. 메모리만. */
    private var departSnapshot: DepartUi? = null
    private var departTripId: String? = null

    /** 보내기 전에 내비가 이미 안내 중인지 읽고(이전 안내 취소를 실패로 보지 않게, #132) 길을 보낸다. 읽기는 1.5초 안. */
    private fun sendRoute(d: Destination) {
        val n = navi ?: return
        n.guide { g ->
            val replacing = g?.routeGuiding == true
            tripKeeper?.expectOldCancel(replacing)
            n.route(d, replacing)
        }
    }

    /**
     * 내비로 길을 보내지 못함(#132 · #118 E1 depart-route-fail, F20 예외). 방금 남긴 출발 기록을 지우고(기록 없음)
     * 출발 화면으로 돌아가 같은 목적지 · 미리보기에 "내비로 길을 보내지 못했어요"와 [다시 시도]를 보인다.
     * 내비가 오류 창으로 노고를 덮어도, 다시 열면 이 화면이다.
     */
    fun onRouteFailed() {
        val trip = flow.state.openTrip
        if (flow.phase != EffortPhase.DRIVING || trip == null || trip.demo) return
        // 이번 실행에서 보낸 출발이거나(뷰모델이 새로 만들어졌으면) 막 출발한 기록만(2분 안)
        val id = departTripId
        if (if (id != null) trip.tripId != id else epochS(isoNow()) - epochS(trip.departedAt) > ROUTE_FAIL_WINDOW_S) return
        flow.discardTrip(); clearGuideEnd(); syncTrip()
        departTripId = null
        departFlow.value = (departSnapshot ?: DepartUi()).copy(routeFailed = true)
        askFlow.value = false
        saved[KEY_STACK] = listOf(EffortScreen.HOME.name, EffortScreen.DEPART.name)
        refresh()
        ChagokLog.event("effort_route_failed")
    }

    /** 경로 요청이 실패했을 때 홈 진행 중 카드의 [다시 시도]. 이번 실행에서 보낸 목적지가 있을 때만. */
    fun retryRoute(): Boolean {
        val d = lastRoute ?: return false
        if (flow.phase != EffortPhase.DRIVING) return false
        sendRoute(d)
        return true
    }

    // ------------------------------------------------------------ 도착(F22) → 영수증(F23)
    /** 화면이 다시 보였으니 정차 상태가 확인되면 1번 도착 여부를 본다(기본 배치에서는 도착 순간 차곡이 멈춰 있음, F22). */
    private var resumeCheck = false
    private var checking = false

    /** 화면이 다시 보인 횟수. 기어·문 값이 그대로여도 도착 확인을 다시 돌리려고 화면 효과의 키로 쓴다. */
    private val shownFlow = MutableStateFlow(0)
    val shown: StateFlow<Int> = shownFlow.asStateFlow()

    /** F20 ② 나란히 배치에서 받은 정체 구간 목록(NaviRepository.onCongestion). 진행 중 실제 출발에만 붙는다. */
    fun onCongestionList(c: CongestionList) {
        if (flow.setCongestionList(c)) refresh()
    }

    /** 마지막으로 받은 정차 여부(기어 P). 안내 끝 이벤트가 왔을 때 바로 볼지 정한다. */
    private var lastParked = false
    // 안내 끝 · 질문 상태(#145)는 화면(뷰모델)이 아니라 앱 프로세스에 둔다([ArrivalHold]).
    // Pleos 실행기에서 아이콘을 누르면 MainActivity가 새로 하나 더 만들어져 뷰모델도 새로 생긴다(11/13 에뮬) — 그때도 이어지게.
    // 프로세스가 끝나면 사라지지만, 그때는 서비스도 없어 다시 열 때 끊김으로 다시 묻는다
    /** 내비 목적지 도착 신호를 받은 시각. 기어 P가 되면 이 시각으로 도착 처리한다(#145 1). */
    private var pendingArrival: String?
        get() = ArrivalHold.pendingArrival
        set(v) { ArrivalHold.pendingArrival = v }
    /** 주행이 끊겼다 이어졌다(서비스 없음). 기어 P에서 내비 상태를 1번 보고 묻는다(#145, 11/13 에뮬). */
    private var interruptCheck: Boolean
        get() = ArrivalHold.interruptCheck
        set(v) { ArrivalHold.interruptCheck = v }
    /** 홈 "도착했나요?"(#145 2). 답하기 전까지 다른 자동 도착은 보지 않는다. */
    private var arriveAsk: ArriveAsk?   // 화면 생성 중(uiOf)에도 읽으므로 위임 대신 직접 접근
        get() = ArrivalHold.arriveAsk
        set(v) { ArrivalHold.arriveAsk = v }
    /**
     * "도착했나요?"에 [아직이에요]로 답했다. 내비 안내가 없을 수 있어(끊김 뒤 · 안내 종료 뒤) 다시 보임(RESUME)으로는 자동 도착하지 않는다.
     * 새 안내가 시작되거나 출발 기록이 바뀌면 푼다. [도착했어요] · 내비 안내 끝은 그대로.
     */
    private var notYet: Boolean
        get() = ArrivalHold.notYet
        set(v) { ArrivalHold.notYet = v }

    private fun clearGuideEnd() { pendingArrival = null; interruptCheck = false; arriveAsk = null; notYet = false }

    /** MainActivity onStart. 시연 모드는 [도착했어요]로만 도착한다(DECISIONS 11/13 '도착 계기: 운전석 문 빼기'). */
    fun onShown() {
        resumeCheck = true; shownFlow.value += 1
        // 진행 중 실제 출발이면 보이는 동안 내비 안내 끝을 이벤트로 받는다(나란히 배치). onStop 때 리스너를 놓는다
        val trip = flow.state.openTrip
        val live = flow.phase == EffortPhase.DRIVING && trip != null && !trip.demo && !source.demo
        val keeper = tripKeeper
        // 안 보이는 동안 서비스가 받은 안내 끝(기본 배치). 서비스를 다시 띄우기(syncTrip) 전에 본다
        // 안 보이는 동안 목적지를 바꿨으면 출발 기록부터 다시 잡는다(F22 · AC-F22-10). 안내 끝보다 먼저(도착이 새 기준으로 계산되게)
        keeper?.takeRouteStart()?.let { if (live) applyRouteStart(it) }
        val ended = keeper?.takeGuideEnd()
        // 경로 요청 실패(#132): 노고가 가려진 뒤 서비스가 알아챔 → 출발 화면으로
        if (live && ended?.kind == GUIDE_ROUTE_FAILED) { ChagokLog.event("effort_guide_end", "kind" to ended.kind, "from" to "service"); onRouteFailed(); return }
        // 촬영용 도착 신호(debug 빌드만, 시연 영상 5번 컷): 에뮬은 내비 도착 신호를 못 내서 debug 훅(src/debug ShootArriveReceiver)이
        // 서비스와 같은 자리에 도착 신호를 남긴다. 시연 주행이면 실제 도착 신호처럼 기어 P에서 도착한다(도착 시각은 arrive()가 시연 값으로).
        // release에서는 이 분기가 없고 시연은 [도착했어요]로만 도착한다(DECISIONS 11/13 '도착 계기: 운전석 문 빼기')
        if (BuildConfig.DEBUG && !live && ended != null && trip != null && flow.phase == EffortPhase.DRIVING) {
            demoShootArrival(trip, ended)?.let { at ->
                pendingArrival = at; resumeCheck = false
                ChagokLog.event("effort_guide_end", "kind" to ended.kind, "from" to "debug_shoot")
                refresh()
            }
        }
        val interrupted = live && keeper != null && !keeper.running
        if (live && ended != null) {
            ChagokLog.event("effort_guide_end", "kind" to ended.kind, "from" to "service", "interrupted" to interrupted)
            onGuideEnd(trip!!, ended)
        } else if (interrupted && arriveAsk == null && pendingArrival == null && !notYet) {
            interruptCheck = true; resumeCheck = false
            ChagokLog.event("effort_interrupted")
        }
        if (live) navi?.listen()
        syncTrip()                                   // 프로세스가 다시 만들어졌거나 출발 기록이 바뀌었으면 맞춘다
    }

    /** 안내 끝 → 신호 시각으로 도착 대기 · 묻기 · 무시(#145). 다시 보임(RESUME)은 보지 않는다(같은 일을 두 번 보지 않게). */
    private fun onGuideEnd(trip: OpenTrip, e: GuideEnd) {
        resumeCheck = false; interruptCheck = false
        when (val a = guideEndAction(trip, e.kind, e.at)) {
            is GuideEndAction.Arrive -> { pendingArrival = a.at; arriveAsk = null }
            is GuideEndAction.Ask -> if (pendingArrival == null) arriveAsk = ArriveAsk(a.at, e.kind)
            is GuideEndAction.Skip -> ChagokLog.event("effort_auto_arrive_skip", "cue" to "guide_${e.kind}", "why" to a.why)
        }
        refresh()
    }

    /**
     * 내비가 안내를 끝냄(NaviRepository: onDestinationArrived = "arrived", onRouteCancelled = "cancelled").
     * 앱이 보일 때만 온다(나란히 배치). 받은 시각을 남겨 두고 기어 P면 바로, 아니면 P가 될 때 처리한다.
     */
    fun onGuideEnded(kind: String) {
        val trip = flow.state.openTrip
        if (flow.phase != EffortPhase.DRIVING || trip == null || trip.demo || source.demo) return
        ChagokLog.event("effort_guide_end", "kind" to kind, "parked" to lastParked)
        onGuideEnd(trip, GuideEnd(kind, isoNow()))
        if (lastParked) onCar(true)
    }

    /**
     * 내비가 새 안내를 시작함(목적지를 바꿈 · 다시 안내 · 경유지 변경) → 앞서 받은 안내 끝과 질문은 버린다.
     * [rs]는 `onRouteStarted`의 새 안내(경유지 변경은 null). 목적지 이름이 다르면 출발 기록을 다시 잡는다(F22 · AC-F22-10).
     */
    fun onGuideStarted(rs: RouteStart? = null) {
        if (pendingArrival != null || arriveAsk != null) ChagokLog.event("effort_guide_restart")
        clearGuideEnd()
        if (rs != null) applyRouteStart(rs)
        refresh()
    }

    /** 주행 중 목적지 변경 반영. 실제 주행 중일 때만, 새 이름이 다를 때만(EffortFlow.retarget). 바뀌면 상주 알림 제목도 맞춘다. */
    private fun applyRouteStart(rs: RouteStart) {
        if (flow.phase != EffortPhase.DRIVING || source.demo) return
        if (flow.retarget(rs)) { syncTrip(); refresh() }
    }

    /**
     * 화면이 보이는 동안 기어가 바뀔 때마다, 그리고 다시 보일 때(이벤트, 폴링 없음 — AC-C-2·AC-F21-4).
     * 진행 중 출발이 있고 정차(기어 P)일 때:
     * 내비 도착 신호를 받았으면 그 시각으로 도착, 주행이 끊겼다 이어졌으면 내비 상태를 보고 묻기,
     * 그 밖에는 다시 보인 뒤 처음이면 도착인지 본다. 묻는 중이면 답을 기다린다. 운전석 문은 보지 않는다(DECISIONS 11/13).
     */
    fun onCar(parked: Boolean) {
        lastParked = parked
        if (!parked || flow.phase != EffortPhase.DRIVING) return
        val trip = flow.state.openTrip ?: return
        pendingArrival?.let { at -> pendingArrival = null; resumeCheck = false; arrive("navi_arrived", at); return }
        if (arriveAsk != null) { resumeCheck = false; return }
        if (interruptCheck) { interruptCheck = false; resumeCheck = false; checkInterrupted(trip); return }
        val resume = resumeCheck && !source.demo && !trip.demo && !notYet
        resumeCheck = false
        if (!resume) return
        val c = ArrivalCue.RESUME
        if (!autoArrivalAllowed(trip, c, isoNow())) { ChagokLog.event("effort_auto_arrive_skip", "cue" to c.name, "eta" to (trip.etaS > 0)); return }
        checkArrival(c)
    }

    /** 주행이 끊겼다 이어진 뒤: 내비가 아직 안내 중이면 그대로, 아니면 "도착했나요?". */
    private fun checkInterrupted(trip: OpenTrip) {
        val decide = { g: GuideState? ->
            val cur = flow.state.openTrip
            if (cur?.tripId == trip.tripId && flow.phase == EffortPhase.DRIVING) {
                when (val a = interruptedAction(cur, g, isoNow())) {
                    is GuideEndAction.Ask -> { arriveAsk = ArriveAsk(a.at, ASK_INTERRUPTED); refresh() }
                    is GuideEndAction.Skip -> ChagokLog.event("effort_auto_arrive_skip", "cue" to ASK_INTERRUPTED, "why" to a.why)
                    is GuideEndAction.Arrive -> Unit
                }
            }
        }
        val n = navi
        if (n == null) decide(null) else n.guide { decide(it) }
    }

    /**
     * 홈 "도착했나요?"에 답함(#145 2). 예 → 물을 때 정한 시각([안내 종료]를 누른 시각 · 끊김을 알아챈 시각)으로 도착.
     * 아니요 → 계속 가는 중. 이번 실행에서 보낸 목적지가 있으면 내비 안내를 다시 요청한다.
     */
    fun answerArrive(yes: Boolean) {
        val ask = arriveAsk ?: return
        arriveAsk = null
        ChagokLog.event("effort_arrive_ask", "kind" to ask.kind, "yes" to yes)
        if (yes) { arrive("asked_${ask.kind}", ask.at); return }
        notYet = true
        if (lastRoute != null) retryRoute()
        refresh()
    }

    private fun checkArrival(cue: ArrivalCue, retried: Boolean = false) {
        val reason = cue.name.lowercase()
        if (checking) return
        val trip = flow.state.openTrip ?: return
        if (trip.demo) return                                     // 시연: 자동 도착 없음([도착했어요]로만)
        val n = navi ?: return
        checking = true
        n.guide { g ->
            checking = false
            when {
                isMidStop(g) -> ChagokLog.event("effort_mid_stop", "reason" to reason)   // 휴게소 정차: 도착 아님(PRD 8장)
                arrivalByGuide(g) -> arrive(reason)
                // 다시 보인 직후 내비 연결이 늦어 못 읽음 → 한 번만 다시 묻는다(연결은 이제 맺어짐). 그래도 모르면 도착으로 보지 않음
                !retried -> checkArrival(cue, retried = true)
                else -> ChagokLog.event("effort_auto_arrive_skip", "cue" to cue.name, "guide" to false)
            }
        }
    }

    /**
     * 도착 기록 → 영수증을 바로 띄우고(AC-F22-1), 도착 날씨·일출일몰은 받는 대로 채운다.
     * [at]: 내비 도착 신호·[안내 종료]를 받은 시각(#145). 없으면 지금.
     */
    private fun arrive(reason: String, at: String? = null) {
        val trip = flow.state.openTrip ?: return
        val demo = trip.demo
        clearGuideEnd()
        val arrivedAt = if (demo) demoSource?.arrivedAt() ?: isoNow() else at ?: isoNow()
        val weather = if (demo) demoSource?.demoWeatherArr() ?: WEATHER_UNKNOWN else null
        if (!flow.arrive(arrivedAt, weather)) return
        syncTrip()                                   // 도착했으니 서비스·상주 알림을 내린다
        ChagokLog.event("effort_arrived", "reason" to reason, "demo" to demo)
        refresh()
        // 오늘 운전 돌아보기(F29) → [운전자 챙겨주기] → 영수증. 정리하지 못함은 셀 것이 없어 바로 안내로
        open(if (ui.value.unsettled) EffortScreen.RECEIPT else EffortScreen.REVIEW)
        if (ui.value.unsettled) ChagokLog.event("effort_unsettled", "reason" to reason)   // 날씨를 받아도 영수증이 안 나오니 부르지 않는다
        else if (weather == null) fetchArrivalWeather(arrivedAt)
    }

    /**
     * 홈 "○○ 가는 중" 카드의 [도착했어요](#68 5번). 내비가 안내를 끝내지 않아도 사용자가 직접 도착 처리한다.
     * 누른 시각이 도착 시각이고, 상한 판정(정리하지 못함)도 그대로 탄다. 시연이면 시연 도착 시각.
     */
    fun arriveNow() { if (flow.phase == EffortPhase.DRIVING) arrive("button") }

    /** 도착 지점 날씨(GET /v1/weather, 3초) + 출발 때 못 구한 일출·일몰(도착 좌표, 명세 F23). 좌표는 메모리에서만 쓴다. */
    private fun fetchArrivalWeather(arrivedAt: String) {
        val n = navi ?: run { flow.completeArrival(WEATHER_UNKNOWN); refresh(); return }
        n.location { loc ->
            if (loc == null) { flow.completeArrival(WEATHER_UNKNOWN); refresh(); return@location }
            val sun = sunTimes(loc.first, loc.second, arrivedAt.take(10))
            val trip = flow.state.openTrip
            // #63: 출발 기록의 반올림 좌표(없으면 도착 좌표)로 시간대별 강수를 같이 부른다. 1회 관측(/v1/weather)은 실패 때 대체용
            val dep = trip?.depLat?.let { lat -> trip.depLng?.let { lat to it } } ?: loc
            viewModelScope.launch {
                val hourly = async { withTimeoutOrNull(TRIP_TIMEOUT_MS) { withContext(Dispatchers.IO) { tripApi.weatherTrip(dep, loc, trip?.departedAt ?: arrivedAt, arrivedAt) } } }
                val once = async { withTimeoutOrNull(NET_TIMEOUT_MS) { withContext(Dispatchers.IO) { api.weather(loc.first, loc.second) } } ?: WEATHER_UNKNOWN }
                val w = once.await()
                val wt = hourly.await()
                flow.completeArrival(w, hhmm(sun.first), hhmm(sun.second), weatherTrip = wt)   // 출발 좌표도 여기서 지움
                refresh()
                ChagokLog.event("effort_weather", "at" to "arr", "precip" to w, "hourly" to (wt != null), "hours" to (wt?.dep?.size ?: 0))
            }
        }
    }

    // ------------------------------------------------------------ 중간 정산(F27, 명준 Q-08-02 · 효성 #97)
    private var midway: MidwayUi? = null

    /**
     * 가는 중 홈 내 차 그림 [지금까지의 노고 보기]. 누른 시각이 '지금'이다.
     * 내비 남은 거리(안내 중일 때만)와 출발 좌표~현재 위치 시간대별 날씨(4초, 실패면 없이)를 받아 엔진으로 센다.
     * 좌표는 날씨 조회에만 쓰고(소수 둘째 자리, AC-N-4) 저장·로그하지 않는다.
     */
    fun startMidway() {
        val trip = flow.state.openTrip ?: return
        if (trip.arrived) return
        if (trip.demo) { startDemoMidway(trip); return }
        val now = isoNow()
        midway = MidwayUi(now)
        refresh(); open(EffortScreen.MIDWAY)
        val n = navi ?: run { midwayFailed(now, "no_navi"); return }
        n.guide { g ->
            val remain = g?.remainM?.takeIf { g.routeGuiding == true }
            if (remain == null) { midwayFailed(now, "no_remain"); return@guide }
            n.location { loc ->
                val dep = trip.depLat?.let { lat -> trip.depLng?.let { lat to it } } ?: loc
                viewModelScope.launch {
                    val wt = if (loc != null && dep != null)
                        withTimeoutOrNull(TRIP_TIMEOUT_MS) { withContext(Dispatchers.IO) { tripApi.weatherTrip(dep, loc, trip.departedAt, now) } } else null
                    val cur = flow.state.openTrip?.takeIf { it.tripId == trip.tripId && !it.arrived } ?: return@launch
                    if (midway?.at != now) return@launch            // 그사이 닫았거나 다시 누름
                    val r = midwayReceiptOf(cur, now, remain, wt, effortConstants) ?: run { midwayFailed(now, "no_route"); return@launch }
                    val view = if (cur.n >= 2 && !isUnsettled(r)) proposalViewOf(r, settingsFlow.value.proposalMethod, effortConstants?.items.orEmpty()) else null
                    midway = MidwayUi(now, pending = false, receipt = r, proposal = view, remainM = remain, weatherTrip = wt)
                    refresh()
                    ChagokLog.event("effort_midway", "hourly" to (wt != null), "unsettled" to isUnsettled(r), "offer" to (view != null), "count" to cur.settlements.size)
                }
            }
        }
    }

    /** 시연(#105 demo.json midway): 17:05 · 남은 52.3km · 그 시간대 날씨로 R27과 같은 영수증. 내비·서버를 부르지 않는다(AC-C-3). */
    private fun startDemoMidway(trip: OpenTrip) {
        val m = demoScene?.midway ?: return
        val r = midwayReceiptOf(trip, m.at, m.remainM, m.weatherTrip, effortConstants) ?: return
        val view = if (trip.n >= 2 && !isUnsettled(r)) proposalViewOf(r, settingsFlow.value.proposalMethod, effortConstants?.items.orEmpty()) else null
        midway = MidwayUi(m.at, pending = false, receipt = r, proposal = view, remainM = m.remainM, weatherTrip = m.weatherTrip)
        refresh(); open(EffortScreen.MIDWAY)
        ChagokLog.event("effort_midway", "demo" to true, "offer" to (view != null), "count" to trip.settlements.size)
    }

    private fun midwayFailed(at: String, reason: String) {
        if (midway?.at != at) return
        midway = MidwayUi(at, pending = false, failed = true)
        refresh()
        ChagokLog.event("effort_midway_fail", "reason" to reason)
    }

    /** 중간 정산에서 고름: A [좋아요] · B 항목. 그 영수증의 남은 금액(원 단위)을 출발 기록에 더한다. 도착 제안은 이만큼 뺀 남은 몫으로. */
    /**
     * 중간 정산 쉰 시간 답(#118 10-3 ⑥, 명준 11/12). 고르면 그 값으로 지금까지 영수증 · 제안을 다시 센다.
     * 여기서 [좋아요] · 항목을 고르면 이 영수증의 남은 몫이 정산 기록(remainKrw)에 남아 최종 영수증의 중간 정산 몫이 된다.
     */
    fun answerMidwayRest(min: Int) {
        val m = midway ?: return
        if (m.choice != null || m.pending || m.failed) return
        val trip = flow.state.openTrip?.takeIf { !it.arrived } ?: return
        val remain = m.remainM ?: return
        val r = midwayReceiptOf(trip, m.at, remain, m.weatherTrip, effortConstants, min) ?: return
        val view = if (trip.n >= 2 && !isUnsettled(r)) proposalViewOf(r, settingsFlow.value.proposalMethod, effortConstants?.items.orEmpty()) else null
        midway = m.copy(receipt = r, proposal = view, restMin = min)
        refresh()
        ChagokLog.event("effort_midway_rest", "min" to min)
    }

    fun midwayPick(choice: String) {
        val m = midway ?: return
        if (m.choice != null || m.needsRest) return
        val remain = m.receipt?.proposal?.remainKrw ?: return
        val view = m.proposal ?: return
        val (method, suggestion) = when (view) {
            is ProposalView.A -> Settings.METHOD_A to view.screen.mainText
            is ProposalView.B -> Settings.METHOD_B to choice
        }
        if (!flow.settle(Settlement(m.at, remain, method, choice, suggestion))) return
        midway = m.copy(choice = choice)
        refresh()
    }

    /** 중간 정산 [계속 가기] · 뒤로. 고르지 않았으면 아무것도 남기지 않는다. */
    fun closeMidway() { midway = null; refresh(); home() }

    /** 영수증 화면의 쉰 시간 답(F21). 고르면 바로 다시 계산한다(#55). */
    fun answerRest(min: Int) { flow.answerRest(min); refresh(); ChagokLog.event("effort_rest", "min" to min) }

    // ------------------------------------------------------------ 오늘 운전 돌아보기(F29)
    private fun reviewWords(): ReviewWords {
        val a = getApplication<Application>()
        return ReviewWords(
            none = a.getString(com.mist.pleos_app.R.string.effort_calc_none),
            unknown = a.getString(com.mist.pleos_app.R.string.effort_calc_unknown),
            whole = a.getString(com.mist.pleos_app.R.string.effort_review_whole),
            depOnly = a.getString(com.mist.pleos_app.R.string.effort_review_dep_only),
            longOver = a.getString(com.mist.pleos_app.R.string.effort_review_long_over),
        )
    }

    /** 홈 "도착" 표시 → [운전자 챙겨주기]를 눌렀으면 영수증, 아니면 다 센 돌아보기(세는 연출은 다시 보지 않음, F29 '다시 열 때'). */
    fun openArrived() {
        val trip = flow.state.openTrip
        open(if (trip?.consented == true || ui.value.unsettled || ui.value.reviewItems.isEmpty()) EffortScreen.RECEIPT else EffortScreen.REVIEW)
    }

    /** 다 셈(또는 [건너뛰기]). 다시 열면 세지 않고 다 센 모습으로. */
    fun reviewCounted() { if (flow.markReview(seen = true)) { ChagokLog.event("effort_review_counted"); refresh() } }

    /**
     * [운전자 챙겨주기](혼자 · 짧은 주행은 [영수증 보기]) → 영수증 + 제안 A(AC-F29-2). 스택의 돌아보기 자리를 영수증으로 바꿔 뒤로 가면 홈.
     */
    fun consent() {
        flow.markReview(seen = true, consented = true)
        ChagokLog.event("effort_review_consent", "solo" to (ui.value.proposal == null))
        val names = saved.get<List<String>>(KEY_STACK) ?: listOf(EffortScreen.HOME.name)
        saved[KEY_STACK] = names.filter { it != EffortScreen.REVIEW.name && it != EffortScreen.RECEIPT.name } + EffortScreen.RECEIPT.name
        refresh()
    }

    /**
     * [다음에](AC-F29-2): 영수증 · 금액을 보이지 않고 기록 1건(choice = later)을 남긴 뒤 홈.
     * 쉰 시간은 묻지 않고 '없음'(0)으로 센다(명세 v0.6 F29 [제안], PRD v2.0 7-5).
     */
    fun later() {
        val trip = flow.state.openTrip ?: return
        if (!trip.arrived) return
        if (trip.restAnswerMin == null) flow.answerRest(0)
        val cur = flow.state.openTrip ?: return
        val r = receiptOf(cur, effortConstants)
        if (r != null && !isUnsettled(r) && flow.finish(recordOf(cur, r, ui.value.proposal, settingsFlow.value.proposalMethod, CHOICE_LATER))) {
            justFinished = cur.tripId; notifySaved(cur.tripId)
        }
        ChagokLog.event("effort_review_later")
        refresh(); home()
    }

    // ------------------------------------------------------------ 제안(F24·F25) → 선택·기록(F26)
    /**
     * 제안 카드에서 고름(F26): A [좋아요], B 항목. 기록 1건을 바로 남기고, 같은 화면에 "오늘 기록에 남겼어요"를 보여 준다.
     * 그 뒤로는 아무것도 요구하지 않는다(알림·재촉·전송 없음, AC-F26-1). [닫기]로 홈.
     */
    fun pick(choice: String) {
        if (done != null) return
        val trip = flow.state.openTrip ?: return
        val r = receiptOf(trip, effortConstants) ?: return
        if (isUnsettled(r)) return
        val view = ui.value.proposal
        val method = settingsFlow.value.proposalMethod
        if (!flow.finish(recordOf(trip, r, view, method, choice))) return
        notifySaved(trip.tripId)
        done = ArriveDone(trip, r, view, choice)
        refresh()
    }

    /**
     * 도착 화면 [닫기]. 이미 골랐으면 홈으로만. 정리하지 못함이면 기록 없이 출발 기록을 닫는다(#71 June).
     * 고르지 않고 닫으면 "닫기"로 기록 1건(제안이 없을 때 포함).
     */
    fun closeArrive() {
        when {
            done != null -> { justFinished = done?.trip?.tripId; done = null; refresh(); home() }
            ui.value.unsettled -> closeUnsettled()
            else -> {
                val trip = flow.state.openTrip
                val r = receiptOf(trip, effortConstants)
                if (trip != null && r != null && flow.finish(recordOf(trip, r, ui.value.proposal, settingsFlow.value.proposalMethod, CHOICE_CLOSE))) { justFinished = trip.tripId; notifySaved(trip.tripId) }
                refresh(); home()
            }
        }
    }

    /** 정리하지 못함(#68): 영수증·제안 없이 "정리하지 못함" 기록 1건을 남기고 닫는다(명준 #75 11/9, #71의 '기록 없음'을 바꿈). */
    private fun closeUnsettled() {
        val trip = flow.state.openTrip
        val r = receiptOf(trip, effortConstants)
        if (trip != null && r != null && flow.finish(recordOf(trip, r, null, settingsFlow.value.proposalMethod, CHOICE_UNSETTLED))) { justFinished = trip.tripId; notifySaved(trip.tripId) }
        else flow.discardTrip()
        refresh(); home()
        ChagokLog.event("effort_unsettled_close")
    }

    // ------------------------------------------------------------ 나눠 낼 인원(인원 확인 알림, PR #34·#40 재사용)
    private val payers = PayerFlow(PrefsPayerStore(app))
    private val payerFlow = MutableStateFlow(payers.state)
    val payerState: StateFlow<PayerState> = payerFlow.asStateFlow()

    /**
     * 인원 확인 알림(June 11/9): 평소 · 시연 모두 출발 화면에 들어올 때마다 좌석 센서 감지 인원으로 바로 띄운다.
     * 센서를 못 읽으면(0명) "몇 명이 함께 타나요?". [확인] · [닫기]로 내리고, 다시 들어오면 또 뜬다.
     */
    private val askFlow = MutableStateFlow(false)
    val askPayersOnEnter: StateFlow<Boolean> = askFlow.asStateFlow()
    fun payersFor(seats: Int): Int = payers.payersFor(seats)

    fun confirmPayers(seats: Int, n: Int) {
        payers.confirm(seats, n); payerFlow.value = payers.state; askFlow.value = false
        ChagokLog.event("payers_confirm", "seats" to seats, "payers" to payers.state.payers)
    }

    fun dismissPayers(seats: Int) {
        payers.dismiss(seats); payerFlow.value = payers.state; askFlow.value = false
        ChagokLog.event("payers_dismiss", "seats" to seats)
    }

    /** 진행 중인 출발을 그만둔다. */
    fun discardTrip() { flow.discardTrip(); clearGuideEnd(); syncTrip(); refresh() }

    /** 기록 초기화(S20). PT 리허설 전. */
    fun resetRecords() { flow.reset(); clearGuideEnd(); syncTrip(); refresh(); ChagokLog.event("effort_reset") }

    private companion object {
        const val NET_TIMEOUT_MS = 3_000L                   // 날씨·검색 전체 마감(AC-C-6, #57)
        const val TRIP_TIMEOUT_MS = 4_000L                  // 시간대별 강수 마감(#66)
        const val KEY_STACK = "effort_stack"
        const val KEY_RECORD = "effort_record"
        const val DEMO_ASSET = "data/effort/demo.json"
        const val ASK_INTERRUPTED = "interrupted"

        fun readAsset(app: Application, path: String): String? =
            runCatching { app.assets.open(path).bufferedReader().use { it.readText() } }
                .onFailure { ChagokLog.event("asset_missing", "path" to path) }.getOrNull()
    }
}

/** 도착 판정 중간 상태(#145). 앱 프로세스에 하나. 출발 기록이 하나뿐이라 출발 · 도착 · 그만두기 때 함께 비운다. */
private object ArrivalHold {
    @Volatile var pendingArrival: String? = null
    @Volatile var interruptCheck = false
    @Volatile var arriveAsk: ArriveAsk? = null
    @Volatile var notYet = false
}
