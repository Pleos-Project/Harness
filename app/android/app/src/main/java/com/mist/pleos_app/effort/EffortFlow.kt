package com.mist.pleos_app.effort

import com.mist.pleos_app.engine.epochS
import com.mist.pleos_app.util.ChagokLog

/** 홈에서 보는 노고 흐름 단계(명세 v0.5 3절). */
enum class EffortPhase {
    IDLE,       // 열린 출발 기록 없음
    DRIVING,    // 출발함, 아직 도착 전(홈에 "진행 중", X2)
    ARRIVED,    // 도착함, 영수증·제안·선택 전
}

/**
 * 노고 흐름 상태 기계(화면·SDK와 떨어진 순수 로직). 바꿀 때마다 저장소에 바로 쓴다.
 * - 출발 기록은 1건만 연다. 같은 trip_id가 두 번 와도 1건(AC-F20-1, X2 '출발 콜백 중복')
 * - 도착은 열린 기록에 한 번만 붙는다. 기록이 없으면 무시(AC-F22-2, X2 '출발 기록 없이 도착 신호')
 * - 선택(F26)을 하면 기록 1건을 남기고 출발 기록을 닫는다
 */
class EffortFlow(private val store: EffortStore) {

    var state: EffortState = store.load()
        private set

    val phase: EffortPhase
        get() = when {
            state.openTrip == null -> EffortPhase.IDLE
            state.openTrip?.arrived == true -> EffortPhase.ARRIVED
            else -> EffortPhase.DRIVING
        }

    fun recent(limit: Int = RECENT_HOME): List<EffortRecord> = state.records.take(limit)

    /** [출발]. 같은 trip_id면 무시(false). 도착 전 기록이 남아 있으면 새 출발로 바꾼다(앞 기록은 영수증 없이 버림). */
    fun depart(trip: OpenTrip): Boolean {
        val cur = state.openTrip
        if (cur?.tripId == trip.tripId) return false
        if (cur != null) ChagokLog.event("effort_trip_replaced", "arrived" to cur.arrived)
        put(state.copy(openTrip = trip))
        ChagokLog.event("effort_depart", "demo" to trip.demo, "n" to trip.n, "eta" to (trip.etaS > 0), "congestion" to (trip.congestionRatio != null))
        return true
    }

    /** 오늘 운전 돌아보기(F29)를 다 셈 · [운전자 챙겨주기]를 누름. 도착한 출발 기록에만. 바뀌었으면 true. */
    fun markReview(seen: Boolean = true, consented: Boolean = false): Boolean {
        val cur = state.openTrip?.takeIf { it.arrived } ?: return false
        val next = cur.copy(reviewSeen = cur.reviewSeen || seen, consented = cur.consented || consented)
        if (next == cur) return false
        put(state.copy(openTrip = next))
        return true
    }

    /** 주행 중 목적지 변경(F22, AC-F22-10). 새 이름이 다를 때만 출발 기록의 이름·기준 ETA를 바꾼다. 바꿨으면 true. */
    fun retarget(r: RouteStart): Boolean {
        val cur = state.openTrip ?: return false
        val next = retargetOf(cur, r) ?: return false
        put(state.copy(openTrip = next))
        ChagokLog.event("effort_retarget", "eta" to (r.durationS > 0), "elapsed_min" to com.mist.pleos_app.engine.roundHalfUp((epochS(r.at) - epochS(cur.departedAt)) / 60.0))   // 목적지 이름은 남기지 않는다(AC-C-10)
        return true
    }

    /**
     * F20 ② 나란히 배치에서 받은 정체 구간 목록으로 출발 기록의 정체 값을 바꾼다(AC-F20-2: `congestion_*`는 구간 ≥1 응답에서).
     * 분은 `congestion_s`(엔진이 min(congestion_s, T)로 씀), km·비율도 같은 목록에서 낸다. 시연·도착 뒤·이미 받았으면 무시(false).
     */
    fun setCongestionList(c: CongestionList): Boolean {
        val cur = state.openTrip ?: return false
        if (cur.demo || cur.arrived || cur.congestionS != null) return false
        val ratio = cur.routeM?.takeIf { it > 0 }?.let { (c.meters / it).coerceIn(0.0, 1.0) } ?: cur.congestionRatio
        put(state.copy(openTrip = cur.copy(congestionS = c.seconds, congestionM = c.meters, congestionRatio = ratio)))
        ChagokLog.event("effort_congestion_list", "sections" to c.sections, "min" to com.mist.pleos_app.engine.roundHalfUp(c.seconds / 60))
        return true
    }

    /**
     * 도착(F22). 열린 기록이 없거나 이미 도착했으면 false.
     * @param weatherArr null이면 "받는 중" → 영수증을 먼저 띄우고 [completeArrival]로 채운다(AC-F22-1: 1초 안에 영수증)
     */
    fun arrive(arrivedAt: String, weatherArr: String? = WEATHER_UNKNOWN): Boolean {
        val cur = state.openTrip ?: return false.also { ChagokLog.event("effort_arrive_ignored", "reason" to "no_trip") }
        if (cur.arrived) return false
        put(state.copy(openTrip = cur.copy(arrivedAt = arrivedAt, weatherArr = weatherArr)))
        ChagokLog.event("effort_arrive", "weather" to (weatherArr ?: "pending"))
        return true
    }

    /**
     * 도착 뒤 받은 도착 날씨, 그리고 출발 때 못 구한 일출·일몰(도착 좌표로 계산, 명세 F23)을 채운다.
     * 이미 채워진 값은 바꾸지 않는다.
     */
    fun completeArrival(weatherArr: String, sunrise: String? = null, sunset: String? = null, weatherTrip: com.mist.pleos_app.engine.WeatherTrip? = null) {
        val cur = state.openTrip ?: return
        if (!cur.arrived) return
        val next = cur.copy(
            weatherArr = cur.weatherArr ?: weatherArr,
            sunrise = cur.sunrise ?: sunrise,
            sunset = cur.sunset ?: sunset,
            weatherTrip = cur.weatherTrip ?: weatherTrip,
            depLat = null, depLng = null,              // #63: 도착 조회가 끝나면 출발 좌표는 성공·실패와 상관없이 지운다
        )
        if (next != cur) put(state.copy(openTrip = next))
    }

    /** 쉰 시간 답(F21). 정차는 이 답 하나로만 뺀다(자동 측정 없음, #62 명준·효성·June). */
    fun answerRest(min: Int) {
        val cur = state.openTrip ?: return
        if (!cur.arrived) return
        put(state.copy(openTrip = cur.copy(restAnswerMin = min)))
    }

    /** 선택(F26). 열린 기록과 같은 trip_id만 받고, 기록 1건을 앞에 넣은 뒤 출발 기록을 닫는다. */
    fun finish(record: EffortRecord): Boolean {
        val cur = state.openTrip ?: return false
        if (cur.tripId != record.tripId || !cur.arrived) return false
        val records = (listOf(record) + state.records.filter { it.tripId != record.tripId }).take(RECORDS_MAX)
        put(EffortState(openTrip = null, records = records))
        ChagokLog.event("effort_finish", "method" to record.method, "choice" to record.choice)
        return true
    }

    /** 중간 정산 한 번을 출발 기록에 더한다(F27). 도착 전 진행 중일 때만. */
    fun settle(s: Settlement): Boolean {
        val cur = state.openTrip ?: return false
        if (cur.arrived) return false
        put(state.copy(openTrip = cur.copy(settlements = cur.settlements + s)))
        ChagokLog.event("effort_settle", "method" to s.method, "choice" to s.choice, "count" to cur.settlements.size + 1)
        return true
    }

    /** 열린 출발 기록을 버린다(기록 초기화·출발 취소). 선택 기록은 그대로. */
    fun discardTrip() { if (state.openTrip != null) put(state.copy(openTrip = null)) }

    /** 기록 초기화(S20): 열린 기록·선택 기록을 모두 비운다. */
    fun reset() { store.clear(); state = EffortState() }

    private fun put(next: EffortState) { state = next; store.save(next) }

    companion object {
        const val RECENT_HOME = 3       // 홈 최근 기록(명세 3절)
        const val RECORDS_MAX = 100     // 기록 누적 화면은 2차라 오래된 것은 버린다
    }
}
