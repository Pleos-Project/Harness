package com.mist.pleos_app.effort

import com.mist.pleos_app.engine.EffortConstants
import com.mist.pleos_app.engine.EffortInput
import com.mist.pleos_app.engine.EffortMidway
import com.mist.pleos_app.engine.EffortReceipt
import com.mist.pleos_app.engine.EffortSettlement
import com.mist.pleos_app.engine.effort
import com.mist.pleos_app.engine.epochS
import com.mist.pleos_app.engine.parseHhmm

/**
 * 도착인지 중간 정차인지 가르는 내비 상태(명세 v0.5 F22): getDestinationInfo의 남은 거리 + getRouteStateInfo의 안내 상태.
 * 못 읽은 값은 null.
 */
data class GuideState(val remainM: Int?, val routeGuiding: Boolean?)

/**
 * 기어 P여도 경로 안내 중이고 남은 거리 > 0이면 도착이 아니라 중간 정차다(명세 F22·PRD 8장).
 * 내비 상태를 못 읽었으면(null) 도착으로 본다 — 도착을 놓치는 것보다 낫고, 시연·에뮬은 안내가 없다.
 */
fun isMidStop(g: GuideState?): Boolean = g != null && (g.remainM ?: 0) > 0 && g.routeGuiding == true

/**
 * 내비 상태로 도착을 볼 수 있는지. 다시 보일 때(RESUME)는 내비 연결을 새로 맺느라 상태를 못 읽을 수 있어
 * (에뮬 11/9: 출발 1분 뒤 다시 열자 `navi_guide got:false` → 2분 주행이 도착으로 잡힘) 못 읽으면(null) 도착으로 보지 않는다.
 * 그때는 홈 [도착했어요]로 직접.
 */
fun arrivalByGuide(g: GuideState?): Boolean = g != null && !isMidStop(g)

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
        // F27: 앞서 한 중간 정산(정산한 순서). 합(남은 몫)과 장시간 구간 경계(정산 시각)는 엔진이 만든다(#105). 없으면 빈 목록(출력 그대로)
        settlements = trip.settlements.map { EffortSettlement(it.at, it.remainKrw) },
    )
}

/**
 * 중간 정산 엔진 입력(F27, 효성 #97). 출발~[nowIso]를 도착과 같은 규칙으로 센다.
 * - 지금까지 거리는 엔진이 경로 거리 − [remainM]으로 만든다(화면에서 계산하지 않음)
 * - 날씨는 출발 좌표~현재 위치 시간대별 강수([weatherTrip], 실패면 null) · 도착 1회 관측은 부르지 않고 unknown(June #97 답)
 * - 쉰 시간은 도착과 같은 시트로 물은 답([restMin], #118 10-3 ⑥ 명준 11/12). 고르기 전에는 0. 앞서 한 정산은 그대로(엔진이 합과 정산 시각을 씀, #105)
 * 경로 거리를 모르거나 이미 도착했으면 null(중간 정산 없음).
 */
fun midwayInputOf(trip: OpenTrip, nowIso: String, remainM: Int, weatherTrip: com.mist.pleos_app.engine.WeatherTrip?, restMin: Int = 0): EffortInput? {
    if (trip.arrived) return null
    val route = trip.routeM?.takeIf { it > 0 } ?: return null
    val base = effortInputOf(trip.copy(arrivedAt = nowIso, weatherArr = WEATHER_UNKNOWN, restAnswerMin = restMin, weatherTrip = weatherTrip)) ?: return null
    return base.copy(midway = EffortMidway(route, remainM.toDouble()))
}

/** 중간 정산 영수증(엔진 effort). 계산은 엔진에서만. */
fun midwayReceiptOf(trip: OpenTrip, nowIso: String, remainM: Int, weatherTrip: com.mist.pleos_app.engine.WeatherTrip?, c: EffortConstants?, restMin: Int = 0): EffortReceipt? {
    if (c == null) return null
    return midwayInputOf(trip, nowIso, remainM, weatherTrip, restMin)?.let { effort(it, c) }
}

/** 영수증(엔진 effort). 도착 전이면 null. 계산은 엔진에서만 한다(CLAUDE.md). */
fun receiptOf(trip: OpenTrip?, c: EffortConstants?): EffortReceipt? {
    if (trip == null || c == null) return null
    return effortInputOf(trip)?.let { effort(it, c) }
}

/**
 * 자동 도착 판정의 계기(F22). 다시 보였을 때(기어 P) 내비 안내 상태로 판정하는 것(RESUME) 하나다.
 * 운전석 문 열기는 계기에서 뺐다(DECISIONS 11/13 '도착 계기: 운전석 문 빼기'). 내비 안내 끝은 [guideEndAction]으로 따로 다룬다(#145).
 */
enum class ArrivalCue { RESUME }

/** 내비가 안내를 끝낸 종류(NaviRepository·TripService). */
const val GUIDE_ARRIVED = "arrived"       // onDestinationArrived
const val GUIDE_CANCELLED = "cancelled"   // onRouteCancelled([안내 종료] 등)
const val GUIDE_ROUTE_FAILED = "route_failed"   // 출발 뒤 우리 안내가 시작되기 전에 온 취소 = 경로 요청 실패(#132, 서비스가 판단)

/** 내비 안내 끝을 받은 뒤 할 일(#145, DECISIONS 11/13 '도착 판정: 내비 도착 신호'). */
sealed interface GuideEndAction {
    /** 기어 P에서 [at](신호를 받은 시각)으로 바로 도착 처리. 내비 상태를 다시 묻지 않는다. */
    data class Arrive(val at: String) : GuideEndAction
    /** "도착했나요?"를 한 번 묻고, 예면 [at]으로 도착 처리. */
    data class Ask(val at: String) : GuideEndAction
    data class Skip(val why: String) : GuideEndAction
}

/**
 * 내비 안내 끝([kind])을 [at]에 받았을 때.
 * - 목적지 도착 신호는 그 자체가 도착 근거라 내비 상태를 다시 읽지 않고, 받은 시각을 도착 시각으로 쓴다.
 *   다시 읽으면 도착 직후 "안내 중 · 남은 거리 있음"이 잠깐 와도 중간 정차로 멈추고, 판정이 늦을수록 운전 시간이 늘었다.
 * - [안내 종료]는 중간에 안내만 끈 것일 수 있어 묻는다. 예면 [안내 종료]를 누른 시각.
 * - 출발 뒤 [EARLY_RESUME_S] 안의 신호와 시연은 보지 않는다. ETA를 못 받은 주행은 도착 신호여도 묻는다(경로 요청 실패와 겹칠 수 있음).
 */
fun guideEndAction(trip: OpenTrip, kind: String, at: String): GuideEndAction = when {
    trip.demo || trip.arrived -> GuideEndAction.Skip("not_live")
    epochS(at) - epochS(trip.departedAt) < EARLY_RESUME_S -> GuideEndAction.Skip("early")
    kind == GUIDE_ARRIVED && trip.etaS > 0 -> GuideEndAction.Arrive(at)
    kind == GUIDE_ARRIVED || kind == GUIDE_CANCELLED -> GuideEndAction.Ask(at)
    else -> GuideEndAction.Skip("unknown")
}

/**
 * 주행이 끊겼다 이어진 뒤(출발 기록은 있는데 주행 중 서비스가 없음 — 시동이 완전히 꺼졌다 켜짐 등, 11/13 에뮬)의 판정.
 * 내비 안내도 사라져 "안내 안 함"으로 답하므로 다시 보임(RESUME)으로 보면 휴게소가 도착이 된다. 그래서 묻는다.
 * 내비가 아직 안내 중이고 남은 거리가 있으면 중간 정차라 묻지 않는다. 출발 직후·시연도 묻지 않는다.
 */
fun interruptedAction(trip: OpenTrip, g: GuideState?, now: String): GuideEndAction = when {
    trip.demo || trip.arrived -> GuideEndAction.Skip("not_live")
    epochS(now) - epochS(trip.departedAt) < EARLY_RESUME_S -> GuideEndAction.Skip("early")
    isMidStop(g) -> GuideEndAction.Skip("mid_stop")
    else -> GuideEndAction.Ask(now)
}

/**
 * 이 계기로 자동 도착을 봐도 되는지.
 * 출발 때 ETA를 못 받은 실제 주행(경로 미리보기 실패 — 오프라인 등)은 내비 경로 요청도 실패했을 가능성이 커서,
 * 다시 열었을 때 "안내 안 함"을 도착으로 읽으면 출발 직후 영수증이 뜬다(에뮬 오프라인 11/8). 이때는 홈 [도착했어요](#68)로 직접.
 * 시연은 자동 도착이 없다(시연 기록은 [도착했어요]로만 도착).
 */
@Suppress("UNUSED_PARAMETER")
fun autoArrivalAllowed(trip: OpenTrip, cue: ArrivalCue, nowIso: String? = null): Boolean {
    if (trip.demo) return false
    if (trip.etaS <= 0) return false
    // 출발 직후 다시 열면 내비가 아직 안내를 시작하기 전이라 "남은 거리 0 · 안내 안 함"으로 답한다(에뮬 11/9 실측: 경로 시작까지 몇 초).
    // 이걸 도착으로 읽지 않도록 출발 뒤 EARLY_RESUME_S 안에는 다시 보임으로 자동 도착하지 않는다
    if (nowIso != null && epochS(nowIso) - epochS(trip.departedAt) < EARLY_RESUME_S) return false
    return true
}

/** 출발 뒤 이 시간(초) 안에 다시 보이면 자동 도착을 보지 않는다. 1분 안에 끝나는 주행은 없다고 본다. */
const val EARLY_RESUME_S = 60

private const val MINUTES_PER_DAY = 24 * 60

/** 엔진 영수증 상태(#68·#71). 도착이 너무 늦게 잡혀(경과 > min(max(ETA × 2, ETA + 60분), 12시간)) 영수증 대신 안내만. */
const val RECEIPT_UNSETTLED = "unsettled"

fun isUnsettled(r: EffortReceipt?): Boolean = r?.status == RECEIPT_UNSETTLED
