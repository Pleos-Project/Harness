package com.mist.pleos_app.effort

import android.content.Context
import com.mist.pleos_app.engine.parseObject
import com.mist.pleos_app.util.ChagokLog

/** 노고 흐름 저장소(열린 출발 기록 + 선택 기록). 테스트는 [MemoryEffortStore]. */
interface EffortStore {
    fun load(): EffortState
    fun save(state: EffortState)
    fun clear()
}

class MemoryEffortStore(private var state: EffortState = EffortState()) : EffortStore {
    override fun load() = state
    override fun save(state: EffortState) { this.state = state }
    override fun clear() { state = EffortState() }
}

/**
 * 기기 안 SharedPreferences에 JSON 한 덩어리로 둔다. 좌표가 없어서(목적지 이름·시각·결과만) 암호화하지 않는다(AC-N-4).
 * 앱을 다시 켜도 열린 출발 기록과 기록이 남는다(AC-C-11·AC-F26-2). 깨졌으면 빈 상태로 시작한다.
 */
class PrefsEffortStore(context: Context) : EffortStore {
    private val p = context.getSharedPreferences("chagok_effort", Context.MODE_PRIVATE)

    override fun load(): EffortState {
        val text = p.getString(KEY, null) ?: return EffortState()
        return runCatching { parseEffortState(parseObject(text)) }
            .onFailure { ChagokLog.event("effort_load_failed", "err" to it.javaClass.simpleName) }
            .getOrDefault(EffortState())
    }

    // commit: 출발·도착 직후 프로세스가 동결·종료돼도 기록이 남게 바로 쓴다(June #51: [출발] 뒤 약 11초에 동결)
    override fun save(state: EffortState) { p.edit().putString(KEY, state.toJson().toString()).commit() }

    override fun clear() { p.edit().remove(KEY).commit() }

    private companion object { const val KEY = "state" }
}
