package com.mist.pleos_app.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import com.mist.pleos_app.util.ChagokLog

/**
 * Pleos Vehicle SDK `Light`로 앰비언트 스위치·색을 읽고 쓴다(F28, June #109 실측).
 * 읽기는 P CAR_LIGHTS, 쓰기는 P CONTROL_CAR_LIGHTS(문서와 달리 CAR_LIGHTS만이면 SecurityException).
 * 대상 영역은 checkAmbientLightBrightnessColorCapabilities가 준 영역 전부(에뮬: 전 좌석 묶음 하나).
 */
class PleosAmbientLightPort(private val vehicle: () -> Vehicle?) : AmbientLightPort {
    private var areas: List<SeatArea>? = null

    private fun failed(what: String, e: Throwable) =
        ChagokLog.event("ambient_failed", "call" to what, "kind" to if (e is SecurityException) "PERMISSION" else "UNSUPPORTED")

    /** 대상 영역(한 번 읽으면 기억). 못 읽으면 빈 목록. */
    private fun areasOf(light: ai.pleos.playground.vehicle.api.Light, done: (List<SeatArea>) -> Unit) {
        areas?.let { return done(it) }
        runCatching {
            light.checkAmbientLightBrightnessColorCapabilities({ _, list -> areas = list; done(list) }, { e -> failed("caps", e); done(emptyList()) })
        }.onFailure { failed("caps", it); done(emptyList()) }
    }

    override fun read(done: (LightValue?) -> Unit) {
        val light = runCatching { vehicle()?.getLight() }.getOrNull() ?: return done(null)
        areasOf(light) { list ->
            val a = list.firstOrNull()
            if (a == null) done(null)
            else runCatching {
                light.getAmbientLightSwitch(a, { sw ->
                    runCatching {
                        light.getAmbientLightBrightnessColor(a, { col ->
                            done(if (sw != null && col != null) LightValue(sw.name, col) else null)
                        }, { e -> failed("get_color", e); done(null) })
                    }.onFailure { failed("get_color", it); done(null) }
                }, { e -> failed("get_switch", e); done(null) })
            }.onFailure { failed("get_switch", it); done(null) }
        }
    }

    override fun write(v: LightValue, done: (Boolean) -> Unit) {
        val light = runCatching { vehicle()?.getLight() }.getOrNull()
        val sw = runCatching { LightSwitch.valueOf(v.switch) }.getOrNull()
        if (light == null || sw == null) return done(false)
        areasOf(light) { list -> if (list.isEmpty()) done(false) else writeAll(light, list, sw, v.color, done) }
    }

    private fun writeAll(light: ai.pleos.playground.vehicle.api.Light, list: List<SeatArea>, sw: LightSwitch, color: Int, done: (Boolean) -> Unit) {
        // 켤 때는 스위치 → 색, 되돌릴 때는 색 → 스위치(마지막에 원래 스위치 상태로 남게)
        val calls = list.size * 2
        var left = calls
        var ok = true
        val one: (Boolean) -> Unit = { r -> synchronized(this) { ok = ok && r; left -= 1; if (left == 0) done(ok) } }
        list.forEach { a ->
            val setSwitch = { runCatching { light.setAmbientLightSwitch(a, sw, { _, _ -> one(true) }, { e -> failed("set_switch", e); one(false) }) }.onFailure { failed("set_switch", it); one(false) } }
            val setColor = { runCatching { light.setAmbientLightBrightnessColor(a, color, { _, _ -> one(true) }, { e -> failed("set_color", e); one(false) }) }.onFailure { failed("set_color", it); one(false) } }
            if (sw == LightSwitch.ON) { setSwitch(); setColor() } else { setColor(); setSwitch() }
        }
    }
}
