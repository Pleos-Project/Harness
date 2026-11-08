package com.mist.pleos_app.effort

import com.mist.pleos_app.app.Settings
import com.mist.pleos_app.engine.EffortItem
import com.mist.pleos_app.engine.EffortReceipt
import com.mist.pleos_app.engine.EffortScreenA
import com.mist.pleos_app.engine.EffortScreenB

/**
 * 영수증 다음 제안 화면(명세 v0.5 F24·F25). 방식은 설정(S20)에서 고른 하나만 보여 주고 서로 넘어가지 않는다.
 * 글자와 금액은 모두 엔진(effortProposal)이 만든 것을 그대로 쓴다.
 */
sealed interface ProposalView {
    /** 방식 A(F24): 제목·큰 숫자·근거 두 줄, [받아들이기]·[닫기]. */
    data class A(val screen: EffortScreenA) : ProposalView

    /** 방식 B(F25): 제안 문장, 세 항목 전부(기본값만 올림)와 [닫기]. 금액은 없다(AC-F25-3). */
    data class B(val screen: EffortScreenB, val items: List<EffortItem>) : ProposalView
}

const val CHOICE_ACCEPT = "accept"
const val CHOICE_CLOSE = "close"

/**
 * 영수증과 설정 방식으로 제안 화면을 고른다. null이면 제안 없이 영수증만(F25 '제안 없음', 혼자 탄 주행 n = 1).
 * @param method Settings.METHOD_A | METHOD_B
 */
fun proposalViewOf(r: EffortReceipt?, method: String, items: List<EffortItem>): ProposalView? {
    val p = r?.proposal ?: return null
    return if (method == Settings.METHOD_B) p.b?.let { ProposalView.B(it, items) } else ProposalView.A(p.a)
}

/**
 * B 문장에서 항목 말을 예시로 바꿔 끼운다(공동 경비 회전, 명세 F25·12절 디자인 기본값).
 * 예시가 없거나 첫 예시(문장 안의 말)가 문장에 없으면 원래 문장.
 */
fun bTextAt(b: EffortScreenB, index: Int): String {
    val ex = b.itemExamples
    if (ex.isEmpty() || !b.text.contains(ex[0])) return b.text
    return b.text.replace(ex[0], ex[Math.floorMod(index, ex.size)])
}

/**
 * 선택(F26) → 기록 1건. 좌표 없이 영수증 값과 고른 것만 남긴다.
 * - suggestion: A면 큰 숫자 문구("한 사람 약 9천 원"), B면 기본으로 올린 항목 key, 제안이 없으면 null
 * - choice: accept | close | 고른 항목 key(coffee·meal·shared)
 */
fun recordOf(trip: OpenTrip, r: EffortReceipt, view: ProposalView?, method: String, choice: String): EffortRecord =
    EffortRecord(
        tripId = trip.tripId,
        date = trip.departedAt.take(10),
        destName = trip.destName,
        driveMin = r.driveMin,
        multiplier = r.multiplier,
        effortMin = r.effortMin,
        method = method,
        suggestion = when (view) {
            is ProposalView.A -> view.screen.mainText
            is ProposalView.B -> view.screen.item
            null -> null
        },
        choice = choice,
        headlineText = r.headlineText,
    )
