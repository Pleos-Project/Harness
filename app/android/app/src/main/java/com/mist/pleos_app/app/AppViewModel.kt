package com.mist.pleos_app.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.mist.pleos_app.engine.Candidate
import com.mist.pleos_app.engine.Constants
import com.mist.pleos_app.engine.DemoServer
import com.mist.pleos_app.engine.parseCandidates
import com.mist.pleos_app.engine.parseConstants
import com.mist.pleos_app.util.ChagokLog
import com.mist.pleos_app.vehicle.VehicleSnapshot
import com.mist.pleos_app.vehicle.seatCount
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** 정차 화면(명세 3절). 정산 카드는 홈 맨 위 카드라 화면이 아니다(DECISIONS '정산 카드 위치'). */
enum class Screen { HOME, RANGE, CARD, RECORD, SETTINGS }

/**
 * 화면 이동·복원(AC-C-11·AC-N-2)과 범위·한 장 상태.
 * - 화면 스택·범위 조건은 SavedStateHandle → 다른 앱에 갔다 오거나 프로세스가 다시 만들어져도 복원
 * - 카드 세션은 메모리(DemoServer). 프로세스가 다시 만들어지면 같은 조건으로 다시 뽑는다(데모 seed라 같은 앞면)
 */
class AppViewModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {

    /** 차종 표·기본 단가·데모 후보(빌드 때 fixtures/ → assets/data/, syncChagokData). 없으면 null. */
    val constants: Constants? = readAsset(app, "data/constants.json")?.let { runCatching { parseConstants(it) }.getOrNull() }
    private val candidates: List<Candidate> = readAsset(app, "data/candidates_demo.json")?.let { runCatching { parseCandidates(it) }.getOrNull() }.orEmpty()
    private val flow: CardFlow? = constants?.let { CardFlow(it, DemoServer(candidates)) }?.also {
        it.recentVisits = saved.get<List<String>>(KEY_RECENT).orEmpty()
    }

    // ------------------------------------------------------------ 화면 스택
    val stack: StateFlow<List<Screen>> = saved.getStateFlow(KEY_STACK, listOf(Screen.HOME.name))
        .map { names -> names.mapNotNull { n -> Screen.entries.firstOrNull { it.name == n } }.ifEmpty { listOf(Screen.HOME) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, listOf(Screen.HOME))

    fun open(screen: Screen) {
        val names = saved.get<List<String>>(KEY_STACK) ?: listOf(Screen.HOME.name)
        // 같은 화면을 두 번 쌓지 않는다. 범위 → 한 장 → (교체 3회째) 범위처럼 되돌아가는 경우는 그 지점까지 접는다.
        val i = names.indexOf(screen.name)
        saved[KEY_STACK] = if (i >= 0) names.take(i + 1) else names + screen.name
    }

    /** 뒤로. 홈이면 false → Activity가 moveTaskToBack(true)로 숨긴다(CCP Back, Pleos 앱 개발 가이드). */
    fun back(): Boolean {
        val names = saved.get<List<String>>(KEY_STACK) ?: return false
        if (names.size <= 1) return false
        saved[KEY_STACK] = names.dropLast(1)
        return true
    }

    // ------------------------------------------------------------ 범위 조건(F2·F3)
    private val cond = MutableStateFlow(readConditions())
    val conditions: StateFlow<Conditions> = cond.asStateFlow()

    /** 범위 화면에 들어올 때: 저장된 조건이 없으면 추가 0원(안 되면 +1만, AC-F2-2). */
    fun conditionsFor(s: VehicleSnapshot, model: String): Conditions {
        if (saved.get<Int>(KEY_EXTRA) != null) return cond.value
        val v = engineVehicle(constants, model, s) ?: return cond.value
        return cond.value.copy(extraKrw = defaultExtra(v, cond.value.days))
    }

    private fun readConditions() = Conditions(
        extraKrw = saved.get<Int>(KEY_EXTRA) ?: 0,
        days = saved.get<Int>(KEY_DAYS) ?: 1,
        themes = saved.get<List<String>>(KEY_THEMES).orEmpty(),
    )

    fun setConditions(c: Conditions) {
        saved[KEY_DAYS] = c.days
        saved[KEY_THEMES] = c.themes
        saved[KEY_EXTRA] = c.extraKrw
        cond.value = c
    }

    // ------------------------------------------------------------ 한 장(F5)
    private val card = MutableStateFlow<CardUi?>(null)
    val cardUi: StateFlow<CardUi?> = card.asStateFlow()

    /** 오늘의 한 곳: 범위 화면 없이 한 장(자동 +1만·+3만). */
    fun openToday(s: VehicleSnapshot, model: String) {
        saved[KEY_CARD_FROM_TODAY] = true
        card.value = load(s, model, today = true)
        open(Screen.CARD)
    }

    /** 범위 화면 "한 장 뽑기". */
    fun openPicked(s: VehicleSnapshot, model: String) {
        saved[KEY_CARD_FROM_TODAY] = false
        card.value = load(s, model, today = false)
        open(Screen.CARD)
    }

    /** 한 장 화면인데 세션이 없음(프로세스 재생성) → 같은 경로로 다시 뽑는다. */
    fun ensureCard(s: VehicleSnapshot, model: String) {
        if (card.value == null) card.value = load(s, model, today = saved.get<Boolean>(KEY_CARD_FROM_TODAY) ?: true)
    }

    // ------------------------------------------------------------ 재계산(F5 유지·재계산, F11 1인 몫)
    private var lastKey: RecallKey? = null
    private var recallJob: Job? = null

    /**
     * 차량값이 바뀔 때마다 부른다. SOC 1%p·주행가능거리 1km·착석 수가 바뀌었고 한 장이 떠 있으면
     * 마지막 변화 1초 뒤에 같은 세션으로 다시 불러 배지·1인 몫만 갱신한다(AC-F5-2·AC-F11-1, 재호출 1건).
     */
    fun onVehicle(s: VehicleSnapshot, model: String) {
        val v = engineVehicle(constants, model, s) ?: return
        val seats = seatCount(s.seatsOccupied, invert = SEAT_INVERT_EMULATOR)
        val key = recallKey(v.levelPct.toInt(), s.rangeRemainingM, seats)
        val prev = lastKey
        lastKey = key
        if (prev == null || prev == key) return
        if (card.value !is CardUi.Shown) return
        recallJob?.cancel()
        recallJob = viewModelScope.launch {
            delay(RECALL_DEBOUNCE_MS)
            val cur = card.value as? CardUi.Shown ?: return@launch
            val f = flow ?: return@launch
            card.value = f.recall(cur, v, seats)
            ChagokLog.event("card_recall", "seats" to seats, "zero" to (card.value as? CardUi.Shown)?.card?.display?.zeroExtra)
        }
    }

    /** 출발(F8): 최근 방문(10곳)에 넣는다. 다음 뽑기에서 빠진다. 장부 trip은 P6. */
    fun recordDeparture(placeId: String) {
        val list = (listOf(placeId) + saved.get<List<String>>(KEY_RECENT).orEmpty().filter { it != placeId }).take(10)
        saved[KEY_RECENT] = list
        flow?.recentVisits = list
    }

    fun swap() {
        val cur = card.value as? CardUi.Shown ?: return
        card.value = flow?.swap(cur)
        ChagokLog.event("card_swap", "left" to (card.value as? CardUi.Shown)?.swapsLeft, "ask_theme" to (card.value is CardUi.AskTheme))
    }

    private fun load(s: VehicleSnapshot, model: String, today: Boolean): CardUi? {
        val f = flow ?: return null
        val v = engineVehicle(constants, model, s) ?: return null
        val seats = seatCount(s.seatsOccupied, invert = SEAT_INVERT_EMULATOR)
        val ui = if (today) f.today(v, seats) else f.pick(v, seats, conditions.value)
        ChagokLog.event("card_open", "today" to today, "shown" to (ui is CardUi.Shown), "raised" to (ui as? CardUi.Shown)?.raisedTo)
        return ui
    }

    private companion object {
        const val KEY_STACK = "screen_stack"
        const val KEY_EXTRA = "cond_extra"
        const val KEY_DAYS = "cond_days"
        const val KEY_THEMES = "cond_themes"
        const val KEY_CARD_FROM_TODAY = "card_from_today"
        const val KEY_RECENT = "recent_visits"
        /** 착석 반전(설정 seat_invert, 에뮬 ON). 설정 화면(S1)이 생기면 그 값을 쓴다. */
        const val SEAT_INVERT_EMULATOR = true

        fun readAsset(app: Application, path: String): String? =
            runCatching { app.assets.open(path).bufferedReader().use { it.readText() } }
                .onFailure { ChagokLog.event("asset_missing", "path" to path) }.getOrNull()
    }
}
