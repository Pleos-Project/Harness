package com.mist.pleos_app.effort

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

    /** 도착(F22). 열린 기록이 없거나 이미 도착했으면 false. */
    fun arrive(arrivedAt: String, weatherArr: String = WEATHER_UNKNOWN): Boolean {
        val cur = state.openTrip ?: return false.also { ChagokLog.event("effort_arrive_ignored", "reason" to "no_trip") }
        if (cur.arrived) return false
        put(state.copy(openTrip = cur.copy(arrivedAt = arrivedAt, weatherArr = weatherArr)))
        ChagokLog.event("effort_arrive", "weather" to weatherArr)
        return true
    }

    /** 쉰 시간 답(F21 기본). 답을 바꾸면 자동으로 잰 정차 목록은 버리고 답만 남긴다(#55 June·효성). */
    fun answerRest(min: Int) {
        val cur = state.openTrip ?: return
        if (!cur.arrived) return
        put(state.copy(openTrip = cur.copy(restAnswerMin = min, stops = null)))
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
