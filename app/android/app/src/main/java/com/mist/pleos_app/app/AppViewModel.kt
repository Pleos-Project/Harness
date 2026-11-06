package com.mist.pleos_app.app

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope

/** 정차 화면(명세 3절). 정산 카드는 홈 맨 위 카드라 화면이 아니다(DECISIONS '정산 카드 위치'). */
enum class Screen { HOME, RANGE, CARD, RECORD, SETTINGS }

/**
 * 화면 이동과 복원(AC-C-11·AC-N-2). 화면 스택을 SavedStateHandle에 두어
 * 다른 앱(내비)에 갔다 오거나 프로세스가 다시 만들어져도 직전 화면으로 돌아온다.
 */
class AppViewModel(private val saved: SavedStateHandle) : ViewModel() {

    val stack: StateFlow<List<Screen>> = saved.getStateFlow(KEY_STACK, listOf(Screen.HOME.name))
        .map { names -> names.mapNotNull { n -> Screen.entries.firstOrNull { it.name == n } }.ifEmpty { listOf(Screen.HOME) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, listOf(Screen.HOME))

    val current: Screen get() = stack.value.last()

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

    fun home() { saved[KEY_STACK] = listOf(Screen.HOME.name) }

    private companion object { const val KEY_STACK = "screen_stack" }
}
