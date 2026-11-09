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
