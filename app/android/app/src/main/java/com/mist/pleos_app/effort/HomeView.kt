package com.mist.pleos_app.effort

import com.mist.pleos_app.engine.EffortConstants
import com.mist.pleos_app.engine.driveText
import com.mist.pleos_app.engine.roundHalfUp
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * 홈(Design v2 `design/screens/home.html`, #75)에 쓰는 표기. 숫자는 엔진 표기 함수(driveText)와 저장된 값에서만 만든다.
 */

/** "태안 꽃지해수욕장" → "태안 꽃지해수욕장으로", "서울" → "서울로"(받침 없음·ㄹ받침은 "로"). */
fun euro(word: String): String {
    val last = word.trimEnd().lastOrNull() ?: return word
    if (last !in '가'..'힣') return word + "으로"
    val jong = (last - '가') % 28
    return word + if (jong == 0 || jong == 8) "로" else "으로"
}

/** 기록 카드 가운데 줄 "2시간 10분 · 1.57배 · 4명". 배수 1.00은 엔진 문구 multiplier_same_text 그대로. */
fun recordSummary(r: EffortRecord, c: EffortConstants?): String {
    if (r.choice == CHOICE_UNSETTLED) return listOfNotNull(UNSETTLED_LABEL, r.n?.let { "${it}명" }).joinToString(" · ")
    val drive = driveText(r.driveMin * 60)
    // 저장된 배수는 엔진이 이미 소수 둘째 자리로 반올림한 값(m100/100)이라 두 자리로 적기만 한다
    val mult = if (roundHalfUp(r.multiplier * 100) == 100) c?.copy?.text?.get("multiplier_same_text") ?: "1.00배"
    else String.format(Locale.ROOT, "%.2f배", roundHalfUp(r.multiplier * 100) / 100.0)
    return listOfNotNull(drive, mult, r.n?.let { "${it}명" }).joinToString(" · ")
}

/** "2026-11-17T14:20:00+09:00" → "14:20". */
fun clockText(iso: String): String = iso.drop(11).take(5)

/** 도착 예상 시각 = 출발 + ETA(초, 반올림). ETA를 모르면 null. */
fun etaClock(trip: OpenTrip): String? {
    if (trip.etaS <= 0) return null
    return runCatching {
        OffsetDateTime.parse(trip.departedAt).plusSeconds(roundHalfUp(trip.etaS).toLong()).format(DateTimeFormatter.ofPattern("HH:mm"))
    }.getOrNull()
}

/**
 * 홈 기록 카드(#118 Design v3 HANDOFF_v3 5-2). 배수 = 저장된 영수증 배수, 길 조건 칩 = 그날 영수증에서 더해진 항목(정보 없음 제외)을
 * 더한 몫이 큰 순서로 최대 3개 + 남은 수. 하나도 없으면 "맑은 길". 값은 엔진 결과(기록에 남긴 입력으로 다시 계산)만 쓴다.
 */
data class RecordCardView(
    val multiplier: String?,      // "1.57"(뒤에 "배"는 화면이 붙임). null이면 [flatText]만
    val flatText: String?,        // "평소와 같은 길" · "정리하지 못함"
    val chips: List<String>,      // 영수증 줄 key(rain · snow · congestion · delay · night · fatigue) 또는 CHIP_CLEAR
    val more: Int,
    val meta: String,             // "2시간 10분 · 4명"
)

const val CHIP_CLEAR = "clear"
private const val CHIPS_MAX = 3

fun recordCardOf(r: EffortRecord, c: EffortConstants?): RecordCardView {
    val people = r.n?.let { if (it >= 2) "${it}명" else "혼자" }
    if (r.choice == CHOICE_UNSETTLED) return RecordCardView(null, UNSETTLED_LABEL, emptyList(), 0, listOfNotNull(people).joinToString(" · "))
    val m100 = roundHalfUp(r.multiplier * 100)
    val flat = m100 == 100
    val receipt = recordReceiptOf(r, c)
    val keys = receipt?.lines.orEmpty().filter { it.addMin > 0 }.sortedByDescending { it.addMin }.map { it.key }
    val chips = when {
        receipt == null -> emptyList()               // 예전 기록(입력 없음): 칩 없이
        keys.isEmpty() -> listOf(CHIP_CLEAR)
        else -> keys.take(CHIPS_MAX)
    }
    return RecordCardView(
        multiplier = if (flat) null else String.format(Locale.ROOT, "%d.%02d", m100 / 100, m100 % 100),
        flatText = if (flat) c?.copy?.text?.get("multiplier_same_text") ?: "평소와 같은 길" else null,
        chips = chips,
        more = (keys.size - CHIPS_MAX).coerceAtLeast(0),
        meta = listOfNotNull(driveText(r.driveMin * 60), people).joinToString(" · "),
    )
}
