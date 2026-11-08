package com.mist.pleos_app.effort

import com.mist.pleos_app.engine.EffortConstants
import com.mist.pleos_app.engine.EffortInput
import com.mist.pleos_app.engine.EffortReceipt
import com.mist.pleos_app.engine.effort
import com.mist.pleos_app.engine.parseHhmm

/**
 * 도착인지 중간 정차인지 가르는 내비 상태(명세 v0.5 F22): getDestinationInfo의 남은 거리 + getRouteStateInfo의 안내 상태.
 * 못 읽은 값은 null.
 */
data class GuideState(val remainM: Int?, val routeGuiding: Boolean?)

/**
 * 기어 P·운전석 문이어도 경로 안내 중이고 남은 거리 > 0이면 도착이 아니라 중간 정차다(명세 F22·PRD 8장).
 * 내비 상태를 못 읽었으면(null) 도착으로 본다 — 도착을 놓치는 것보다 낫고, 시연·에뮬은 안내가 없다.
 */
fun isMidStop(g: GuideState?): Boolean = g != null && (g.remainM ?: 0) > 0 && g.routeGuiding == true

/**
 * 출발 기록 → 엔진 입력(명세 v0.5 F23). 도착 전이면 null.
 * - 쉰 시간 답이 아직 없으면 엔진이 0으로 본다(영수증 화면에서 고르면 다시 계산)
 * - 도착 날씨를 아직 못 받았으면 unknown("날씨 정보 없음")
 * - 일출·일몰을 출발·도착 어느 쪽에서도 못 구했으면 밤길을 빼도록 [0, 24:00]을 넣는다(밤 구간 길이 0)
 * - 거리는 경로 거리(km, 표시만). 누적 주행거리 Δ는 다음 단계에서
 */
fun effortInputOf(trip: OpenTrip): EffortInput? {
    val arrivedAt = trip.arrivedAt ?: return null
    val sunrise = trip.sunrise?.let(::parseHhmm)
    val sunset = trip.sunset?.let(::parseHhmm)
    val known = sunrise != null && sunset != null
    return EffortInput(
        departedAt = trip.departedAt,
        arrivedAt = arrivedAt,
        etaS = trip.etaS,
        weatherDep = trip.weatherDep,
        weatherArr = trip.weatherArr ?: WEATHER_UNKNOWN,
        sunriseMin = if (known) sunrise!! else 0,
        sunsetMin = if (known) sunset!! else MINUTES_PER_DAY,
        n = trip.n,
        restAnswerMin = trip.restAnswerMin ?: 0, // 답하기 전(null)은 0분. #64 전 엔진의 `?: 0`과 같은 동작
        congestionRatio = trip.congestionRatio,
        congestionM = trip.congestionM,
        congestionS = trip.congestionS,
        distanceKm = trip.routeM?.let { it / 1000 },
        weatherTrip = trip.weatherTrip,                // #63: 도착 때 받은 조각별 강수. 없거나 전부 unknown이면 엔진이 1회 관측 규칙으로
    )
}

/** 영수증(엔진 effort). 도착 전이면 null. 계산은 엔진에서만 한다(CLAUDE.md). */
fun receiptOf(trip: OpenTrip?, c: EffortConstants?): EffortReceipt? {
    if (trip == null || c == null) return null
    return effortInputOf(trip)?.let { effort(it, c) }
}

/** 자동 도착 판정의 계기(F22). */
enum class ArrivalCue {
    DOOR,           // 화면이 보이는 동안 운전석 문이 닫힘 → 열림으로 바뀜
    DOOR_ON_SHOW,   // 다시 보였을 때 문이 이미 열려 있음
    RESUME,         // 다시 보였을 때(기어 P) 내비 안내 상태로 판정
}

/**
 * 이 계기로 자동 도착을 봐도 되는지.
 * 출발 때 ETA를 못 받은 실제 주행(경로 미리보기 실패 — 오프라인 등)은 내비 경로 요청도 실패했을 가능성이 커서,
 * 다시 열었을 때 "안내 안 함"을 도착으로 읽으면 출발 직후 영수증이 뜬다(에뮬 오프라인 11/8).
 * 이때는 보이는 동안 문이 실제로 열리는 순간만 도착으로 본다. 그 밖에는 홈 [도착했어요](#68)로 직접.
 */
fun autoArrivalAllowed(trip: OpenTrip, cue: ArrivalCue): Boolean =
    trip.demo || trip.etaS > 0 || cue == ArrivalCue.DOOR

private const val MINUTES_PER_DAY = 24 * 60

/** 엔진 영수증 상태(#68·#71). 도착이 너무 늦게 잡혀(경과 > min(max(ETA × 2, ETA + 60분), 12시간)) 영수증 대신 안내만. */
const val RECEIPT_UNSETTLED = "unsettled"

fun isUnsettled(r: EffortReceipt?): Boolean = r?.status == RECEIPT_UNSETTLED
