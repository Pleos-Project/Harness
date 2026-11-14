package com.mist.pleos_app.vehicle

import com.mist.pleos_app.util.ChagokLog

/*
 * 앰비언트 라이트(명세 v0.5 F28, 효성 11/9 · June #109 실측 · 권한 #111).
 * 앱이 보이는 동안(정차) 브랜드 색으로 켜 두고(June·효성·명준 구두 합의 11/9, 처음 안은 앱을 켤 때·정산할 때만),
 * 가려지거나 주행으로 바뀌면 켜기 전에 읽어 둔 값으로 되돌린다.
 * - 쓰기 API는 setAmbientLightSwitch · setAmbientLightBrightnessColor 두 개뿐(밝기·주행 모드 자동 색은 건드리지 않음)
 * - 주행 전환(X1)·onStop이면 그 자리에서 되돌리고 그 뒤로는 부르지 않는다(AC-C-2, AC-F28-3)
 * - 못 읽거나 실패하면 조명 없이 진행하고 로그 1줄(AC-F28-4). 설정에서 끄면 부르지 않는다(AC-F28-5)
 */

/** 조명 상태 한 벌. [switch]는 LightSwitch 이름(OFF·ON·AUTOMATIC…), [color]는 32비트 ARGB(다시 읽으면 알파가 빠진 RGB). */
data class LightValue(val switch: String, val color: Int)

/** 브랜드 색 = 컬러 v4 라임 #C8F04A(#162 HANDOFF_v4 3절, DECISIONS 11/14 '앰비언트 라이트 색: v4 라임'. 옛 값 #D9F77A). */
const val AMBIENT_BRAND_ARGB: Int = 0xFFC8F04A.toInt()
const val LIGHT_ON = "ON"

/** SDK와 맞닿는 곳. 대상 영역은 capabilities가 준 영역 전부(에뮬: 전 좌석 묶음 하나). 콜백은 한 번만. */
interface AmbientLightPort {
    fun read(done: (LightValue?) -> Unit)
    fun write(v: LightValue, done: (Boolean) -> Unit)
}

/** 켜기 전에 읽어 둔 값. 되돌리기 전에 앱이 끝나도 다음에 켤 때 되돌리려고 기기에 둔다(좌표 아님). */
interface AmbientStore {
    fun load(): LightValue?
    fun save(v: LightValue?)
}

class MemoryAmbientStore(private var v: LightValue? = null) : AmbientStore {
    override fun load() = v
    override fun save(v: LightValue?) { this.v = v }
}

/**
 * 켜 달라는 이유(지금은 앱 화면 "app" 하나)가 하나라도 있으면 켜 두고, 모두 놓으면 되돌린다.
 * [allowed]가 false면(설정 끔 · 화면이 가려짐 · 주행 중) 켜지 않는다.
 */
class AmbientLight(
    private val port: AmbientLightPort,
    private val store: AmbientStore,
    private val brand: Int = AMBIENT_BRAND_ARGB,
    private val allowed: () -> Boolean = { true },
) {
    private enum class State { OFF, READING, ON }
    private var state = State.OFF
    private val holders = mutableSetOf<String>()

    @Synchronized
    fun acquire(reason: String) {
        if (!allowed()) return
        holders += reason
        if (state != State.OFF) return
        state = State.READING
        port.read { v -> onRead(v, reason) }
    }

    @Synchronized
    private fun onRead(v: LightValue?, reason: String) {
        if (state != State.READING) return
        if (v == null) { state = State.OFF; holders.clear(); ChagokLog.event("ambient", "on" to false, "why" to "read"); return }
        if (holders.isEmpty() || !allowed()) { state = State.OFF; return }   // 읽는 사이 놓았거나 가려짐 → 켜지 않음
        store.save(v)
        state = State.ON
        port.write(LightValue(LIGHT_ON, brand)) { ok ->
            ChagokLog.event("ambient", "on" to ok, "reason" to reason)
            if (!ok) restoreSaved("fail")
        }
    }

    @Synchronized
    fun release(reason: String) {
        if (!holders.remove(reason)) return
        if (holders.isEmpty()) restoreSaved(reason)
    }

    /** 주행 전환(X1) · onStop · 설정 끔: 이유와 상관없이 바로 되돌린다. */
    @Synchronized
    fun restoreAll(reason: String) {
        holders.clear()
        restoreSaved(reason)
    }

    /** 다음에 앱을 켤 때: 지난번에 되돌리지 못하고 끝났으면 먼저 되돌린다(F28 '못 되돌린 경우'). */
    @Synchronized
    fun recover() {
        if (state == State.OFF && store.load() != null) restoreSaved("recover")
    }

    val isOn: Boolean @Synchronized get() = state == State.ON

    private fun restoreSaved(reason: String) {
        val saved = store.load()
        state = State.OFF
        if (saved == null) return                                      // 읽는 중이면 onRead가 켜지 않는다
        store.save(null)
        port.write(saved) { ok -> ChagokLog.event("ambient", "restore" to ok, "reason" to reason) }
    }
}

/** 기기 저장(SharedPreferences). 되돌리면 지운다. */
class PrefsAmbientStore(context: android.content.Context) : AmbientStore {
    private val p = context.applicationContext.getSharedPreferences("chagok_ambient", android.content.Context.MODE_PRIVATE)
    override fun load(): LightValue? {
        val sw = p.getString("switch", null) ?: return null
        return LightValue(sw, p.getInt("color", 0))
    }
    override fun save(v: LightValue?) {
        val e = p.edit()
        if (v == null) e.clear() else e.putString("switch", v.switch).putInt("color", v.color)
        e.apply()
    }
}
