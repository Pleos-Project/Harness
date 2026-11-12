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
 * 기어 P·운전석 문이어도 경로 안내 중이고 남은 거리 > 0이면 도착이 아니라 중간 정차다(명세 F22·PRD 8장).
 * 내비 상태를 못 읽었으면(null) 도착으로 본다 — 도착을 놓치는 것보다 낫고, 시연·에뮬은 안내가 없다.
 */
fun isMidStop(g: GuideState?): Boolean = g != null && (g.remainM ?: 0) > 0 && g.routeGuiding == true

/**
 * 내비 상태로 도착을 볼 수 있는지. 다시 보일 때(RESUME·DOOR_ON_SHOW)는 내비 연결을 새로 맺느라 상태를 못 읽을 수 있어
 * (에뮬 11/9: 출발 1분 뒤 다시 열자 `navi_guide got:false` → 2분 주행이 도착으로 잡힘) 못 읽으면(null) 도착으로 보지 않는다.
 * 보이는 동안 문이 열린 순간(DOOR)은 문 신호 자체가 근거라 지금처럼 못 읽어도 도착. 그 밖에는 홈 [도착했어요].
 */
fun arrivalByGuide(g: GuideState?, cue: ArrivalCue): Boolean = when {
    isMidStop(g) -> false
    g == null -> cue == ArrivalCue.DOOR
    else -> true
}

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
 * - 쉰 시간은 묻지 않아 0, 앞서 한 정산은 그대로(엔진이 합과 정산 시각을 씀, #105)
 * 경로 거리를 모르거나 이미 도착했으면 null(중간 정산 없음).
 */
fun midwayInputOf(trip: OpenTrip, nowIso: String, remainM: Int, weatherTrip: com.mist.pleos_app.engine.WeatherTrip?): EffortInput? {
    if (trip.arrived) return null
    val route = trip.routeM?.takeIf { it > 0 } ?: return null
    val base = effortInputOf(trip.copy(arrivedAt = nowIso, weatherArr = WEATHER_UNKNOWN, restAnswerMin = 0, weatherTrip = weatherTrip)) ?: return null
    return base.copy(midway = EffortMidway(route, remainM.toDouble()))
}

/** 중간 정산 영수증(엔진 effort). 계산은 엔진에서만. */
fun midwayReceiptOf(trip: OpenTrip, nowIso: String, remainM: Int, weatherTrip: com.mist.pleos_app.engine.WeatherTrip?, c: EffortConstants?): EffortReceipt? {
    if (c == null) return null
    return midwayInputOf(trip, nowIso, remainM, weatherTrip)?.let { effort(it, c) }
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
    GUIDE_END,      // 보이는 동안(나란히 배치) 내비가 안내를 끝냄(도착·안내 종료) → 기어 P에서 내비 안내 상태로 판정(DECISIONS 11/12)
}

/**
 * 이번 기어·문 변화에서 볼 도착 계기. 없으면 null.
 * 문이 열리는 순간이 가장 앞서고, 다시 보인 뒤 처음(RESUME), 그다음 보이는 동안 받은 안내 끝(GUIDE_END) 순이다.
 * 안내 끝은 기본 배치에서는 오지 않는다(앱이 동결돼 있고 onStop 때 리스너를 놓음, 11/12 실측). 나란히 배치에서만 온다.
 */
fun arrivalCueOf(doorOpen: Boolean?, lastDoorOpen: Boolean?, resume: Boolean, guideEnded: Boolean): ArrivalCue? = when {
    doorOpen == true && lastDoorOpen == false -> ArrivalCue.DOOR
    doorOpen == true && lastDoorOpen == null -> ArrivalCue.DOOR_ON_SHOW
    resume -> ArrivalCue.RESUME
    guideEnded -> ArrivalCue.GUIDE_END
    else -> null
}

/**
 * 이 계기로 자동 도착을 봐도 되는지.
 * 출발 때 ETA를 못 받은 실제 주행(경로 미리보기 실패 — 오프라인 등)은 내비 경로 요청도 실패했을 가능성이 커서,
 * 다시 열었을 때 "안내 안 함"을 도착으로 읽으면 출발 직후 영수증이 뜬다(에뮬 오프라인 11/8).
 * 이때는 보이는 동안 문이 실제로 열리는 순간만 도착으로 본다. 그 밖에는 홈 [도착했어요](#68)로 직접.
 */
fun autoArrivalAllowed(trip: OpenTrip, cue: ArrivalCue, nowIso: String? = null): Boolean {
    if (trip.demo || cue == ArrivalCue.DOOR) return true
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
