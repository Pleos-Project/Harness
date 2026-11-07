package com.mist.pleos_app.effort

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.mist.pleos_app.app.PrefsSettingsStore
import com.mist.pleos_app.app.Settings
import com.mist.pleos_app.util.ChagokLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** 노고 흐름 화면(명세 v0.5 3절). 영수증·제안은 3~5단계에서 붙인다. */
enum class EffortScreen { HOME, DEPART, SETTINGS }

/** 홈·출발 화면이 보는 상태. */
data class EffortUi(
    val phase: EffortPhase,
    val openTrip: OpenTrip?,
    val recent: List<EffortRecord>,
    val demo: Boolean,              // 지금 쓰는 출처가 시연 데이터인지(AC-C-7)
    val demoAvailable: Boolean,     // 시연 데이터(assets/data/effort/demo.json)가 들어 있는지
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
    private val ui = MutableStateFlow(uiOf())
    val state: StateFlow<EffortUi> = ui.asStateFlow()

    private fun uiOf() = EffortUi(
        phase = flow.phase,
        openTrip = flow.state.openTrip,
        recent = flow.recent(),
        demo = source.demo,
        demoAvailable = demoSource != null,
    )

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
        return true
    }

    private fun home() { saved[KEY_STACK] = listOf(EffortScreen.HOME.name) }

    // ------------------------------------------------------------ 출발(F20)
    /**
     * [출발]. 지금은 시연 데이터로만 출발 기록을 만든다. 실제 출발(목적지·requestTrip·인원 확인·requestRoute)은 2단계에서 붙인다.
     * @return 출발 기록을 새로 열었으면 true
     */
    fun depart(): Boolean {
        val trip = source.demoTrip() ?: return false.also { ChagokLog.event("effort_depart_skipped", "reason" to "live_not_ready") }
        val opened = flow.depart(trip)
        refresh()
        home()
        return opened
    }

    /** 진행 중인 출발을 그만둔다. */
    fun discardTrip() { flow.discardTrip(); refresh() }

    /** 기록 초기화(S20). PT 리허설 전. */
    fun resetRecords() { flow.reset(); refresh(); ChagokLog.event("effort_reset") }

    private companion object {
        const val KEY_STACK = "effort_stack"
        const val DEMO_ASSET = "data/effort/demo.json"

        fun readAsset(app: Application, path: String): String? =
            runCatching { app.assets.open(path).bufferedReader().use { it.readText() } }
                .onFailure { ChagokLog.event("asset_missing", "path" to path) }.getOrNull()
    }
}
