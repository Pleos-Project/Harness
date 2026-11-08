package com.mist.pleos_app.effort

/*
 * F20 ② 나란히 배치일 때의 정체 구간 목록(명세 v0.5 F20 처리 ②, DECISIONS 11/7 '정체 수집').
 * 기본 배치에서는 [출발] 뒤 앱이 멈춰 못 받고(#51), 내비와 나란히 띄워 앱이 살아 있을 때만 온다.
 * SDK 타입(TrafficCongestionInfo)은 navi 패키지에서 이 타입으로 바꿔 넘긴다(여기는 순수 로직).
 */

/** 구간 하나. [distanceM] 미터, [avrSpeedKmh] km/h, [timeS] SDK의 구간 시간(분 단위 올림이라 속도가 없을 때만 쓴다). */
data class CongestionSection(val distanceM: Double, val avrSpeedKmh: Double, val timeS: Long, val jam: Boolean)

/** 목록으로 잰 정체: [seconds] = SLOW·HEAVY 구간마다 거리 ÷ 평균 속도의 합, [meters] = 그 거리 합, [sections] = 받은 구간 수. */
data class CongestionList(val seconds: Double, val meters: Double, val sections: Int)

/**
 * 목록이 비었으면 null(첫 응답은 0개라 다시 묻는다, AC-F20-2). 구간이 있으면 SLOW·HEAVY만 더한다(없으면 0).
 * `trafficCongestionTime`은 분 단위 올림이라(짧은 구간도 60초) 합이 부풀어 쓰지 않고, 평균 속도가 0 이하인 구간만 그 값으로 대신한다.
 */
/** 목록 지문. 같은 목록인지(직전 경로 값이 남은 것인지) 가리는 데만 쓴다. */
fun congestionSignature(items: List<CongestionSection>): String =
    items.joinToString("|") { "${it.distanceM.toLong()}:${it.avrSpeedKmh.toLong()}:${if (it.jam) 1 else 0}" }

/** [congestionStep]의 결과: 이 목록을 쓴다 / 잠시 뒤 다시 묻는다 / 그만둔다(requestTrip 비율 그대로). */
sealed interface CongestionStep {
    data class Take(val list: CongestionList) : CongestionStep
    data object Retry : CongestionStep
    data object GiveUp : CongestionStep
}

/**
 * [attempt]번째(1부터) 응답을 어떻게 할지. 첫 응답은 기준으로만 둔다: onRouteStarted 직후에는 안내 중이어도 **직전 경로 목록**이
 * 남아 있다(11/8 나란히 배치 실측: 직후 4구간·290m → 2초 뒤 새 경로 19구간·1,530m, 그 뒤 그대로. #51의 SAFE_GUIDING 13건과 같은 문제).
 * 그 뒤 응답이 비어 있지 않고 첫 응답과 다르면 쓴다. 끝까지 같거나 비어 있으면 그만둔다(같은 경로를 다시 받은 경우도 비율로 남음).
 */
fun congestionStep(attempt: Int, maxTries: Int, items: List<CongestionSection>, baseline: String): CongestionStep {
    val c = if (attempt > 1) congestionFromList(items) else null
    return when {
        c != null && congestionSignature(items) != baseline -> CongestionStep.Take(c)
        attempt < maxTries -> CongestionStep.Retry
        else -> CongestionStep.GiveUp
    }
}

fun congestionFromList(items: List<CongestionSection>): CongestionList? {
    if (items.isEmpty()) return null
    val jam = items.filter { it.jam }
    val seconds = jam.sumOf { if (it.avrSpeedKmh > 0) it.distanceM * 3.6 / it.avrSpeedKmh else it.timeS.toDouble() }
    return CongestionList(seconds = seconds, meters = jam.sumOf { it.distanceM }, sections = items.size)
}
