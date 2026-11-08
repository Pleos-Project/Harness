package com.mist.pleos_app.effort

import com.mist.pleos_app.engine.EffortConstants
import com.mist.pleos_app.engine.EffortReceipt
import com.mist.pleos_app.engine.driveText
import com.mist.pleos_app.engine.kmText

/**
 * 계산 연출(Design v2 `design/screens/calc.html`, HANDOFF_v2 3-1)의 체크포인트 6개.
 * 값은 모두 엔진 영수증·출발 기록에서 온다(엔진 표기 함수 driveText·kmText·pctText 결과). 화면은 그리기만 한다.
 */
enum class CalcFx { RAIN, SNOW, LEAVES, JAM, ZIP, LATE, NIGHT, SUN, LONG, DIST, NONE }

/** 진행 줄 문구 종류(문구는 strings.xml, 명준 확정 전 임시). */
enum class CalcSay { RAIN, SNOW, WEATHER_NONE, WEATHER_CLEAR, JAM, JAM_NONE, LATE, LATE_NONE, NIGHT, NIGHT_NONE, LONG, LONG_NONE, DIST_GROUP, DIST_SOLO }

data class CalcItem(
    val key: String,          // weather | congestion | delay | night | fatigue | distance
    val label: String,        // 줄 이름(엔진 line_labels, 거리는 앱 문구)
    val value: String?,       // 원 아래 값 "비 +40%" · "38km +10%" · "25분 +5%". null이면 "없음"(앱 문구)
    val unknown: Boolean,     // 정보 없음(날씨·정체 조회 실패)
    val zero: Boolean,        // 가산 0 → 회색 원
    val fx: CalcFx,
    val say: CalcSay,
    val tag: String? = null,  // 그래픽 말풍선 "+25분"(예상보다 더) · "+10분"(장시간)
    val km: Int? = null,      // 거리 숫자 세기
)

/**
 * 영수증 → 체크포인트 6개(날씨 · 정체 구간 · 예상보다 더 · 밤길 · 장시간 · 거리). 프로토타입 calcItems와 같은 규칙.
 * @param distanceLabel 앱 문구 "거리"
 */
fun calcItems(trip: OpenTrip, r: EffortReceipt, c: EffortConstants, distanceLabel: String): List<CalcItem> {
    val lines = r.lines.associateBy { it.key }
    val label = { k: String -> c.copy.lineLabels[k] ?: k }
    val weatherUnknown = r.notes.contains(c.copy.text["weather_unknown_text"])
    val jamUnknown = r.notes.contains(c.copy.text["congestion_unknown_text"])

    val wKey = listOf("rain", "snow").firstOrNull { it in lines }
    val weather = if (wKey != null) {
        val l = lines.getValue(wKey)
        CalcItem("weather", label(wKey), "${c.copy.precipWords[wKey] ?: ""} ${l.pctText}".trim(), false, false,
            if (wKey == "snow") CalcFx.SNOW else CalcFx.RAIN, if (wKey == "snow") CalcSay.SNOW else CalcSay.RAIN)
    } else if (weatherUnknown) {
        CalcItem("weather", label("rain"), null, true, true, CalcFx.NONE, CalcSay.WEATHER_NONE)
    } else CalcItem("weather", label("rain"), null, false, true, CalcFx.LEAVES, CalcSay.WEATHER_CLEAR)

    val jam = lines["congestion"]?.let { l ->
        val km = trip.congestionM?.let { kmText(it) }
        CalcItem("congestion", l.label, listOfNotNull(km, l.pctText).joinToString(" "), false, false, CalcFx.JAM, CalcSay.JAM)
    } ?: CalcItem("congestion", label("congestion"), null, jamUnknown, true, if (jamUnknown) CalcFx.NONE else CalcFx.ZIP, CalcSay.JAM_NONE)

    val delay = lines["delay"]?.let { l ->
        val t = driveText(r.delayMin * 60)
        CalcItem("delay", l.label, "$t ${l.pctText}", false, false, CalcFx.LATE, CalcSay.LATE, tag = "+$t")
    } ?: CalcItem("delay", label("delay"), null, false, true, CalcFx.NONE, CalcSay.LATE_NONE)

    val night = lines["night"]?.let { l ->
        CalcItem("night", l.label, "${driveText(r.nightMin * 60)} ${l.pctText}", false, false, CalcFx.NIGHT, CalcSay.NIGHT)
    } ?: CalcItem("night", label("night"), null, false, true, CalcFx.SUN, CalcSay.NIGHT_NONE)

    val long = lines["fatigue"]?.let { l ->
        val t = driveText(r.fatigueExcessMin * 60)
        CalcItem("fatigue", l.label, "$t ${l.pctText}", false, false, CalcFx.LONG, CalcSay.LONG, tag = "+$t")
    } ?: CalcItem("fatigue", label("fatigue"), null, false, true, CalcFx.NONE, CalcSay.LONG_NONE)

    val km = trip.routeM?.let { com.mist.pleos_app.engine.roundHalfUp(it / 1000) }
    val dist = CalcItem(
        "distance", distanceLabel, r.distanceText, false, false, CalcFx.DIST,
        if (trip.n > 1) CalcSay.DIST_GROUP else CalcSay.DIST_SOLO, km = km,
    )
    return listOf(weather, jam, delay, night, long, dist)
}
