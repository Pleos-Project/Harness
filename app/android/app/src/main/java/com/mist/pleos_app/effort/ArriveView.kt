package com.mist.pleos_app.effort

import com.mist.pleos_app.engine.EffortReceipt
import com.mist.pleos_app.engine.roundHalfUp

/**
 * 중간 정산 화면(F27, Design v2 v41 `screens/midway.html`). 메모리에만 둔다(정산한 것은 출발 기록 settlements에 저장).
 * pending: 내비 남은 거리·시간대별 날씨를 받는 중 / failed: 남은 거리를 못 읽음 등으로 셀 수 없음
 */
data class MidwayUi(
    val at: String,                         // 중간 정산을 누른 시각 = 영수증의 '지금'
    val pending: Boolean = true,
    val failed: Boolean = false,
    val receipt: EffortReceipt? = null,
    val proposal: ProposalView? = null,     // null = 제안 없음(혼자 · 남은 몫이 작음 · 정리하지 못함)
    val choice: String? = null,             // 고른 뒤(accept | 항목 key) — 이미 settlements에 더함
    // #118 10-3 ⑥(명준 11/12): 중간 정산도 쉰 시간을 묻는다. 고르기 전(null)에는 영수증 · 제안 대신 "정리하는 중" · "확인 중"
    val restMin: Int? = null,
    val remainM: Int? = null,               // 다시 계산용 입력(내비 남은 거리 · 시간대별 날씨). 메모리만
    val weatherTrip: com.mist.pleos_app.engine.WeatherTrip? = null,
) {
    /** 쉰 시간을 물어야 하는지: 영수증이 나왔고(실패 · 정리하지 못함 아님) 아직 고르지 않음. */
    val needsRest: Boolean get() = !pending && !failed && receipt != null && !isUnsettled(receipt) && restMin == null && choice == null
}

/**
 * 도착 화면(Design v2 `screens/arrive.html`, #75)이 보는 값. 영수증과 제안을 한 화면에 둔다.
 * 고른 뒤에도 같은 화면에 "기록에 남겼어요"를 보여 주므로, 기록(F26)을 남긴 뒤의 영수증·제안을 여기 잠시 둔다(메모리만).
 */
data class ArriveDone(
    val trip: OpenTrip,
    val receipt: EffortReceipt,
    val proposal: ProposalView?,
    val choice: String,            // accept | close | 고른 항목 key
)

/**
 * 영수증 바코드 아래 번호 "#2026 1107 104 130"(연 · 월일 · km · 운전 분). 장식이지만 숫자는 출발 기록·엔진 값에서만 만든다.
 * km는 경로 거리(m)를 km로 반올림, 분은 엔진 운전 시간 T를 반올림(roundHalfUp, CLAUDE.md).
 */
fun receiptCode(trip: OpenTrip, r: EffortReceipt): String {
    val d = trip.departedAt.take(10)                       // "2026-11-17"
    val y = d.take(4)
    val md = d.drop(5).replace("-", "")
    val km = trip.routeM?.let { roundHalfUp(it / 1000) } ?: 0
    val min = roundHalfUp(r.driveMin)
    return "#$y $md ${pad3(km)} ${pad3(min)}"
}

private fun pad3(n: Int): String = n.coerceIn(0, 999).toString().padStart(3, '0')

/** "밥" → "밥이에요", "커피" → "커피예요". 받침이 있으면 "이에요"(한글이 아니면 "이에요"). */
fun ieyo(word: String): String {
    val last = word.trimEnd().lastOrNull() ?: return word
    val hangul = last in '가'..'힣'
    val batchim = hangul && (last - '가') % 28 != 0
    return word + if (!hangul || batchim) "이에요" else "예요"
}

/** "2026-11-17T…" → "11월 17일". 날짜 표기만(계산 없음). */
fun dayText(iso: String): String {
    val d = iso.take(10)
    val m = d.drop(5).take(2).toIntOrNull() ?: return d
    val day = d.drop(8).take(2).toIntOrNull() ?: return d
    return "${m}월 ${day}일"
}

/**
 * 중간 정산 B 문장(시안 mid-b "여기까지는 밥값에서 운전자 빼고 나누는 게 어때요"). 엔진 문장 앞의 "오늘은"만 바꾼다.
 * 중간 정산 전용 문구는 엔진에 아직 없어서(효성 #97) 화면 문구를 임시로 이렇게 둔다(명준 확정 전).
 */
fun midwayBText(text: String): String = if (text.startsWith("오늘은 ")) "여기까지는 " + text.removePrefix("오늘은 ") else text
