package com.mist.pleos_app.effort

import com.mist.pleos_app.engine.EffortConstants
import com.mist.pleos_app.engine.effortProposal
import com.mist.pleos_app.engine.driveText
import com.mist.pleos_app.engine.approxText
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
/** 정리하지 못함(#68)을 닫음: 영수증·제안 없이 기록만(명준 #75 11/9). */
const val CHOICE_UNSETTLED = "unsettled"

/**
 * 영수증과 설정 방식으로 제안 화면을 고른다. null이면 제안 없이 영수증만(F25 '제안 없음' — A·B 같음(Q-07-04), 혼자 탄 주행 n = 1).
 * @param method Settings.METHOD_A | METHOD_B
 */
fun proposalViewOf(r: EffortReceipt?, method: String, items: List<EffortItem>): ProposalView? {
    val p = r?.proposal ?: return null
    // Q-07-04(명준 11/8): 방식 A도 B와 같은 기준 — 운전자 몫이 커피 1인분의 절반보다 작으면(엔진 b = null) 제안 없이 기록만
    val b = p.b ?: return null
    return if (method == Settings.METHOD_B) ProposalView.B(b, items) else ProposalView.A(p.a)
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
        n = trip.n,
        trip = trip.copy(depLat = null, depLng = null),   // 기록 상세 영수증용 엔진 입력. 좌표는 남기지 않는다
    )

/**
 * 최종 영수증 하단 중간 정산 줄(#118 arrive-mid-a · arrive-mid-a-open · arrive-mid-b-open, 명준 11/9).
 * 가는 길에 중간 정산을 했을 때만 있다. 처음은 전체 모드(출발 → 도착 전부), 아래 작은 줄을 누르면 포함 모드(정산 몫을 뺀 남은 몫).
 * 값은 모두 엔진 결과다. A: 전체 = 노고 금액, 포함 = 남은 금액(천 원 어림은 엔진 approxText 한 번).
 * B: 전체 = 운전 시간, 포함 = 마지막 정산 뒤 운전 시간(엔진 fatigueSegmentsMin의 마지막 구간, 정산 시각으로 끊음).
 */
data class SettleFoldView(val amount: Boolean, val allValue: String, val incValue: String)

fun settleFoldOf(r: EffortReceipt?, method: String): SettleFoldView? {
    val p = r?.proposal ?: return null
    val settled = p.settledKrw ?: return null
    if (settled <= 0.0) return null
    val remain = p.remainKrw ?: return null
    return if (method == Settings.METHOD_B) {
        val last = r.fatigueSegmentsMin.lastOrNull() ?: return null
        SettleFoldView(false, r.driveText, driveText(last * 60))
    } else SettleFoldView(true, p.amountText, approxText(remain).second)
}

/**
 * 전체 모드의 제안: 같은 영수증 노고로 정산 몫을 빼지 않은 제안(엔진 effortProposal, settledKrw 없음).
 * 노고 %·초는 영수증 effortMin(= 노고 ÷ 6000)에서 되돌린다. 포함 모드는 엔진 영수증의 제안 그대로.
 */
fun receiptForSettleMode(r: EffortReceipt, included: Boolean, n: Int, c: EffortConstants?): EffortReceipt {
    if (included || c == null || settleFoldOf(r, Settings.METHOD_A) == null) return r
    return r.copy(proposal = effortProposal(r.effortMin * 6000, n, c, null))
}

/**
 * 영수증 합계 끝 글 "약 3만 6천 원"(#118 v92 · v94) = 제안 카드 근거의 "운전 노고 약 ○만 ○천 원"과 같은 엔진 값.
 * 혼자 탄 주행은 엔진이 제안을 만들지 않아서 같은 노고로 effortProposal(n = 1)의 금액 표기를 쓴다.
 */
fun receiptAmountText(r: EffortReceipt, c: EffortConstants?): String? =
    r.proposal?.amountText ?: c?.let { effortProposal(r.effortMin * 6000, 1, it, null).amountText }
