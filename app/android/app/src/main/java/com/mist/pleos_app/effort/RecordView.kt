package com.mist.pleos_app.effort

import com.mist.pleos_app.engine.EffortConstants
import com.mist.pleos_app.engine.EffortReceipt
import com.mist.pleos_app.engine.driveText
import com.mist.pleos_app.engine.roundHalfUp
import java.util.Locale

/*
 * 주행 기록 목록 · 기록 상세(Design v2 v41 `screens/record.html`, 명준 Q-08-01 11/8). 숫자는 엔진과 저장된 값에서만 만든다.
 */

/** 목록 줄 "2시간 10분 · 평소보다 1.57배 · 함께 4명"(배수 1.00은 엔진 문구 multiplier_same_text, 예전 기록은 인원 없음). */
fun recordLogLine(r: EffortRecord, c: EffortConstants?): String {
    if (r.choice == CHOICE_UNSETTLED) return listOfNotNull(UNSETTLED_LABEL, r.n?.let { "함께 ${it}명" }).joinToString(" · ")
    val m100 = roundHalfUp(r.multiplier * 100)
    val mult = if (m100 == 100) c?.copy?.text?.get("multiplier_same_text") ?: "1.00배"
    else String.format(Locale.ROOT, "평소보다 %d.%02d배", m100 / 100, m100 % 100)
    return listOfNotNull(driveText(r.driveMin * 60), mult, r.n?.let { "함께 ${it}명" }).joinToString(" · ")
}

/**
 * 기록 상세의 그날 영수증. 기록에 남긴 엔진 입력(좌표 없는 출발 기록)을 지금 상수로 다시 계산한다.
 * 이 기능 전에 남긴 기록은 입력이 없어 null(상세는 요약만).
 */
fun recordReceiptOf(r: EffortRecord, c: EffortConstants?): EffortReceipt? =
    if (r.choice == CHOICE_UNSETTLED) null else r.trip?.let { receiptOf(it, c) }

/** 정리하지 못한 주행의 목록·카드 표기(명준 #75 11/9: 목록과 상세에 "정리하지 못함"). */
const val UNSETTLED_LABEL = "정리하지 못함"

/**
 * 영수증 줄 근거 펼침에 보이는 글(#118 HANDOFF_v3 5-5): 엔진 근거 문구 "설명 · 출처"에서 첫 문장(설명)만. 출처(연구 · 법 조항)는 화면에 내지 않는다.
 */
fun basisFirst(basis: String?): String? = basis?.substringBefore(" · ")?.trim()?.ifEmpty { null }
