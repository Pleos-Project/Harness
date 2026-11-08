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
    fun route(d: Destination)
    /** 도착인지 중간 정차인지(F22). */
    fun guide(done: (GuideState?) -> Unit)
}

/** 노고 흐름 화면(명세 v0.5 3절). */
/** 도착 화면(RECEIPT)은 영수증과 제안을 한 화면에 둔다(Design v2 arrive, #75). */
enum class EffortScreen { HOME, DEPART, SETTINGS, CALC, RECEIPT }

/** 홈·출발·영수증 화면이 보는 상태. */
data class EffortUi(
    val phase: EffortPhase,
    val openTrip: OpenTrip?,
    val recent: List<EffortRecord>,
    val demo: Boolean,              // 지금 쓰는 출처가 시연 데이터인지(AC-C-7)
    val demoAvailable: Boolean,     // 시연 데이터(assets/data/effort/demo.json)가 들어 있는지
    val receipt: EffortReceipt? = null,                 // 도착했으면 엔진 영수증(F23)
    val weatherPending: Boolean = false,                // 도착 날씨를 받는 중
    val restQuestion: String? = null,                   // effort.copy rest_question_text("중간에 쉰 시간")
    val restAnswers: List<Pair<Int, String>> = emptyList(),   // (분, 문구): 없음·15분·30분·1시간 이상
    val proposal: ProposalView? = null,                 // 설정 방식(A/B)의 제안 화면. null이면 영수증만(F25 '제안 없음'·혼자 탄 주행)
    val unsettled: Boolean = false,                     // 정리하지 못함(#68): 영수증 대신 안내 문구(receipt.statusText)와 [닫기]만
    val items: List<com.mist.pleos_app.engine.EffortItem> = emptyList(),   // 방식 B 항목(공동 경비 예시 포함)
    val done: ArriveDone? = null,                       // 고른 뒤: 기록은 남겼고 같은 화면에 "남겼어요"를 보여 주는 중
    val calcItems: List<CalcItem> = emptyList(),        // 계산 연출 체크포인트 6개(도착 뒤, 정리하지 못함이 아닐 때)
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

    /** 노고 상수·문구(constants.json effort, 빌드 때 fixtures/에서 복사). 없으면 미리보기 문구 없이 진행. */
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
            demo = source.demo || trip?.demo == true,
            demoAvailable = demoSource != null,
            receipt = receipt,
            weatherPending = trip?.arrived == true && trip.weatherArr == null,
            restQuestion = effortConstants?.copy?.text?.get("rest_question_text"),
            restAnswers = effortConstants?.copy?.restAnswers.orEmpty(),
            proposal = d?.proposal ?: proposalViewOf(receipt, settingsFlow.value.proposalMethod, effortConstants?.items.orEmpty()),
            unsettled = isUnsettled(receipt),
            items = effortConstants?.items.orEmpty(),
            done = d,
            calcItems = if (trip?.arrived == true && receipt != null && !isUnsettled(receipt) && effortConstants != null)
                calcItems(trip, receipt, effortConstants, getApplication<Application>().getString(com.mist.pleos_app.R.string.effort_receipt_distance)) else emptyList(),
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
    fun back(): Boolean {
        val names = saved.get<List<String>>(KEY_STACK) ?: return false
        if (names.size <= 1) return false
        saved[KEY_STACK] = names.dropLast(1)
        if (done != null) { done = null; refresh() }   // 고른 뒤 뒤로 = [닫기]와 같음(기록은 이미 저장됨)
        return true
    }

    private fun home() { saved[KEY_STACK] = listOf(EffortScreen.HOME.name) }

    // ------------------------------------------------------------ 출발(F20)
    /** MainActivity가 넣는다(NaviRepository). 없으면(미리보기·테스트) 내비 후보·미리보기·경로 없이 진행. */
    var navi: DepartNavi? = null

    private val api = EffortApi(BuildConfig.API_BASE_URL)
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
        open(EffortScreen.DEPART)
        departFlow.value = DepartUi()
        here = null; weatherDep = null
        if (source.demo) return
        val n = navi ?: return
        departFlow.update { it.copy(naviLoading = true) }
        n.places { list -> departFlow.update { it.copy(naviOptions = list.map { p -> p.toOption() }, naviLoading = false) } }
        n.location { loc ->
            here = loc
            if (loc == null) { weatherDep = WEATHER_UNKNOWN; return@location }
            viewModelScope.launch {
                weatherDep = withTimeoutOrNull(NET_TIMEOUT_MS) { withContext(Dispatchers.IO) { api.weather(loc.first, loc.second) } } ?: WEATHER_UNKNOWN
                ChagokLog.event("effort_weather", "at" to "dep", "precip" to weatherDep)
            }
        }
    }

    fun setQuery(q: String) { departFlow.update { it.copy(query = q) } }

    /** 키보드 [검색]·검색 버튼을 눌렀을 때만 1번(자동 완성 없음, #57 June). 3초 안에 못 받으면 unavailable. */
    fun search() {
        val q = departFlow.value.query.trim()
        if (q.isEmpty()) return
        searchJob?.cancel()
        departFlow.update { it.copy(searching = true, searchStatus = null, searchResults = emptyList()) }
        searchJob = viewModelScope.launch {
            val r = withTimeoutOrNull(NET_TIMEOUT_MS) { withContext(Dispatchers.IO) { api.places(q, here) } }
                ?: PlacesResult(PlacesStatus.UNAVAILABLE, emptyList())
            departFlow.update { it.copy(searching = false, searchStatus = r.status, searchResults = r.places.map { p -> p.toOption() }) }
            ChagokLog.event("effort_search", "status" to r.status.name, "count" to r.places.size, "here" to (here != null))
        }
    }

    /** 목적지 고르기 → requestTrip으로 ETA·경로 거리·정체 비율을 미리 받는다(출발 뒤에는 차곡이 멈춰 못 받음, #51). */
    fun select(o: DestOption) {
        departFlow.update { it.copy(selected = o, preview = null, previewText = null, previewing = navi != null) }
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
        source.demoTrip()?.let { trip ->
            val opened = flow.depart(trip)
            refresh(); home()
            return opened
        }
        val ui = departFlow.value
        val sel = ui.selected ?: return false
        if (!ui.canDepart) return false
        val trip = departTrip(newTripId(), source.departedAt(), sel.dest, payersFor(seats), ui.preview, weatherDep ?: WEATHER_UNKNOWN, here)
        val opened = flow.depart(trip)
        lastRoute = sel.dest
        navi?.route(sel.dest)
        refresh(); home()
        departFlow.value = DepartUi()
        return opened
    }

    /** 경로 요청이 실패했을 때 홈 진행 중 카드의 [다시 시도]. 이번 실행에서 보낸 목적지가 있을 때만. */
    fun retryRoute(): Boolean {
        val d = lastRoute ?: return false
        if (flow.phase != EffortPhase.DRIVING) return false
        navi?.route(d)
        return true
    }

    val canRetryRoute: Boolean get() = lastRoute != null

    // ------------------------------------------------------------ 도착(F22) → 영수증(F23)
    private var lastDoorOpen: Boolean? = null
    /** 화면이 다시 보였으니 정차 상태가 확인되면 1번 도착 여부를 본다(기본 배치에서는 도착 순간 차곡이 멈춰 있음, F22). */
    private var resumeCheck = false
    private var checking = false

    /** 화면이 다시 보인 횟수. 기어·문 값이 그대로여도 도착 확인을 다시 돌리려고 화면 효과의 키로 쓴다. */
    private val shownFlow = MutableStateFlow(0)
    val shown: StateFlow<Int> = shownFlow.asStateFlow()

    /** MainActivity onStart. 시연 모드는 문 열기로만 도착한다(12절 시연 순서). */
    fun onShown() { resumeCheck = true; lastDoorOpen = null; shownFlow.value += 1 }

    /**
     * 화면이 보이는 동안 기어·문 값이 바뀔 때마다(이벤트, 폴링 없음 — AC-C-2·AC-F21-4).
     * 진행 중 출발이 있고 정차(기어 P)일 때: 운전석 문이 열리는 순간, 또는 다시 보인 뒤 처음이면 도착인지 본다.
     */
    fun onCar(parked: Boolean, doorOpen: Boolean?) {
        val cue = when {
            doorOpen == true && lastDoorOpen == false -> ArrivalCue.DOOR
            doorOpen == true && lastDoorOpen == null -> ArrivalCue.DOOR_ON_SHOW
            else -> null
        }
        lastDoorOpen = doorOpen
        if (!parked || flow.phase != EffortPhase.DRIVING) return
        val trip = flow.state.openTrip ?: return
        val resume = resumeCheck && !source.demo && !trip.demo
        resumeCheck = false
        val c = cue ?: (if (resume) ArrivalCue.RESUME else return)
        if (!autoArrivalAllowed(trip, c)) { ChagokLog.event("effort_auto_arrive_skip", "cue" to c.name, "eta" to false); return }
        checkArrival(c.name.lowercase())
    }

    private fun checkArrival(reason: String) {
        if (checking) return
        val trip = flow.state.openTrip ?: return
        if (trip.demo) { arrive(reason); return }                 // 시연: 내비 안내가 없다
        val n = navi ?: run { arrive(reason); return }
        checking = true
        n.guide { g ->
            checking = false
            if (isMidStop(g)) ChagokLog.event("effort_mid_stop", "reason" to reason)   // 휴게소 정차: 도착 아님(PRD 8장)
            else arrive(reason)
        }
    }

    /** 도착 기록 → 영수증을 바로 띄우고(AC-F22-1), 도착 날씨·일출일몰은 받는 대로 채운다. */
    private fun arrive(reason: String) {
        val trip = flow.state.openTrip ?: return
        val demo = trip.demo
        val arrivedAt = if (demo) demoSource?.arrivedAt() ?: isoNow() else isoNow()
        val weather = if (demo) demoSource?.demoWeatherArr() ?: WEATHER_UNKNOWN else null
        if (!flow.arrive(arrivedAt, weather)) return
        ChagokLog.event("effort_arrived", "reason" to reason, "demo" to demo)
        refresh()
        // 계산 연출(Design v2 #75) → 영수증. 정리하지 못함은 셀 것이 없어 바로 안내로
        open(if (ui.value.unsettled) EffortScreen.RECEIPT else EffortScreen.CALC)
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
            viewModelScope.launch {
                val w = withTimeoutOrNull(NET_TIMEOUT_MS) { withContext(Dispatchers.IO) { api.weather(loc.first, loc.second) } } ?: WEATHER_UNKNOWN
                flow.completeArrival(w, hhmm(sun.first), hhmm(sun.second))
                refresh()
                ChagokLog.event("effort_weather", "at" to "arr", "precip" to w)
            }
        }
    }

    /** 영수증 화면의 쉰 시간 답(F21). 고르면 바로 다시 계산한다(#55). */
    fun answerRest(min: Int) { flow.answerRest(min); refresh(); ChagokLog.event("effort_rest", "min" to min) }

    /** 계산 연출이 끝나거나 [건너뛰기]·뒤로 → 영수증. 스택에서 연출 자리를 영수증으로 바꿔 뒤로 가면 홈. */
    fun calcDone() {
        val names = saved.get<List<String>>(KEY_STACK) ?: listOf(EffortScreen.HOME.name)
        saved[KEY_STACK] = names.filter { it != EffortScreen.CALC.name && it != EffortScreen.RECEIPT.name } + EffortScreen.RECEIPT.name
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
        done = ArriveDone(trip, r, view, choice)
        refresh()
    }

    /**
     * 도착 화면 [닫기]. 이미 골랐으면 홈으로만. 정리하지 못함이면 기록 없이 출발 기록을 닫는다(#71 June).
     * 고르지 않고 닫으면 "닫기"로 기록 1건(제안이 없을 때 포함).
     */
    fun closeArrive() {
        when {
            done != null -> { done = null; refresh(); home() }
            ui.value.unsettled -> closeUnsettled()
            else -> {
                val trip = flow.state.openTrip
                val r = receiptOf(trip, effortConstants)
                if (trip != null && r != null) flow.finish(recordOf(trip, r, ui.value.proposal, settingsFlow.value.proposalMethod, CHOICE_CLOSE))
                refresh(); home()
            }
        }
    }

    /** 정리하지 못함(#68·#71 June): 선택 기록(F26) 없이 출발 기록만 닫는다. 최근 기록에는 영수증만 남긴다. */
    private fun closeUnsettled() {
        flow.discardTrip()
        refresh(); home()
        ChagokLog.event("effort_unsettled_close")
    }

    // ------------------------------------------------------------ 나눠 낼 인원(인원 확인 알림, PR #34·#40 재사용)
    private val payers = PayerFlow(PrefsPayerStore(app))
    private val payerFlow = MutableStateFlow(payers.state)
    val payerState: StateFlow<PayerState> = payerFlow.asStateFlow()

    fun shouldAskPayers(seats: Int): Boolean = payers.shouldAsk(seats)
    fun payersFor(seats: Int): Int = payers.payersFor(seats)

    fun confirmPayers(seats: Int, n: Int) {
        payers.confirm(seats, n); payerFlow.value = payers.state
        ChagokLog.event("payers_confirm", "seats" to seats, "payers" to payers.state.payers)
    }

    fun dismissPayers(seats: Int) {
        payers.dismiss(seats); payerFlow.value = payers.state
        ChagokLog.event("payers_dismiss", "seats" to seats)
    }

    /** 진행 중인 출발을 그만둔다. */
    fun discardTrip() { flow.discardTrip(); refresh() }

    /** 기록 초기화(S20). PT 리허설 전. */
    fun resetRecords() { flow.reset(); refresh(); ChagokLog.event("effort_reset") }

    private companion object {
        const val NET_TIMEOUT_MS = 3_000L                   // 날씨·검색 전체 마감(AC-C-6, #57)
        const val KEY_STACK = "effort_stack"
        const val DEMO_ASSET = "data/effort/demo.json"

        fun readAsset(app: Application, path: String): String? =
            runCatching { app.assets.open(path).bufferedReader().use { it.readText() } }
                .onFailure { ChagokLog.event("asset_missing", "path" to path) }.getOrNull()
    }
}
