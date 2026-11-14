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

/**
 * 기록 상세에서 합계를 금액(총액)까지 보여 줄지(효성 11/15, #181 · DECISIONS '기록 상세의 합계 금액').
 * [다음에]를 누른 주행은 배수까지만 — 누르지 않았는데 기록에서 금액이 보이면 "누른 뒤에만 금액"(PRD 7-5)이 깨진다.
 * 혼자 탄 주행 · 아주 짧은 주행 · [운전자 챙겨주기]를 누른 주행(금액을 본 뒤 [닫기] 포함)은 도착 때와 같이 총액까지.
 */
fun recordShowsAmount(r: EffortRecord): Boolean = r.choice != CHOICE_LATER

/** 정리하지 못한 주행의 목록·카드 표기(명준 #75 11/9: 목록과 상세에 "정리하지 못함"). */
const val UNSETTLED_LABEL = "정리하지 못함"

/**
 * 영수증 줄 근거 펼침에 보이는 글(#118 HANDOFF_v3 5-5): 엔진 근거 문구 "설명 · 출처"에서 첫 문장(설명)만. 출처(연구 · 법 조항)는 화면에 내지 않는다.
 */
fun basisFirst(basis: String?): String? = basis?.substringBefore(" · ")?.trim()?.ifEmpty { null }

/** 정리하지 못함 이유 한 줄의 경과(#118 arrive-unsettled "출발하고 14시간이 지나서 열었어요"): 1시간 이상은 시간만, 미만은 분. 엔진 elapsedMin(도착 − 출발)을 쓴다. */
fun unsettledElapsedText(r: com.mist.pleos_app.engine.EffortReceipt): String {
    val m = kotlin.math.floor(r.elapsedMin).toInt().coerceAtLeast(0)
    return if (m >= 60) "${m / 60}시간" else "${m}분"
}
