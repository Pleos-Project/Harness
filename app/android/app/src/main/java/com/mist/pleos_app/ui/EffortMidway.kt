package com.mist.pleos_app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.mist.pleos_app.ui.components.CgBasisList
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.mist.pleos_app.R
import com.mist.pleos_app.effort.CHOICE_ACCEPT
import com.mist.pleos_app.effort.EffortUi
import com.mist.pleos_app.effort.MidwayUi
import com.mist.pleos_app.effort.OpenTrip
import com.mist.pleos_app.effort.ProposalView
import com.mist.pleos_app.effort.clockText
import com.mist.pleos_app.effort.ieyo
import com.mist.pleos_app.effort.isUnsettled
import com.mist.pleos_app.effort.midwayBText
import com.mist.pleos_app.engine.EffortItem
import com.mist.pleos_app.ui.components.CgButton
import com.mist.pleos_app.ui.components.CgButtonKind
import com.mist.pleos_app.ui.components.CgOfferCard
import com.mist.pleos_app.ui.components.CgOptionButton
import com.mist.pleos_app.ui.components.CgSize
import com.mist.pleos_app.ui.theme.Chagok
import com.mist.pleos_app.ui.theme.gpx

/*
 * 지금까지의 노고(중간 정산, F27) — Design v2 v41 `screens/midway.html` mid-a · mid-b · *-done.
 * 왼쪽 "오늘 주행 영수증"(v3)(도착과 같은 줄, 쉰 시간 줄 없음) / 오른쪽 제안 카드: A [좋아요] · B 항목 → 여기까지 기록 · [계속 가기].
 * 숫자는 엔진(midwayReceiptOf)에서만 온다.
 */
/** 이 프로세스에서 출력 연출을 이미 보여 준 중간 정산(at). */
private val MidwayPrinted = mutableSetOf<String>()

@Composable
internal fun EffortMidwayScreen(ui: EffortUi, onPick: (String) -> Unit, onClose: () -> Unit, onRest: (Int) -> Unit = {}) {
    val m = ui.midway
    val trip = ui.openTrip
    if (m == null || trip == null) { LaunchedEffect(Unit) { onClose() }; return }   // 프로세스가 다시 만들어지면 메모리 상태가 없다 → 홈
    val narrow = LocalNarrow.current
    val r = m.receipt
    val showPaper = r != null && !isUnsettled(r)
    var open by rememberSaveable(m.at) { mutableStateOf<String?>(null) }
    // "14:20 출발 · 아직 가는 중 · 4명"(혼자면 "· 혼자", 아티팩트 receipt 2061)
    val since = if (trip.n >= 2) stringResource(R.string.effort_mid_receipt_sub, clockText(trip.departedAt), trip.n)
    else stringResource(R.string.effort_mid_receipt_sub_solo, clockText(trip.departedAt))
    // #118 10-3 ⑥(명준 11/12): 중간 정산도 도착과 같은 시트로 쉰 시간을 묻는다. 고르기 전에는 "지금까지 운전을 정리하는 중이에요" · "확인 중"
    // HANDOFF_v4 7-1: 쉰 시간을 고르기 전에도 도착과 같이 영수증(쉰 시간 '없음' 기준)과 카드를 먼저 보여 주고, 출력이 끝난 뒤 시트가 늦게 올라온다
    var sheet by rememberSaveable(m.at) { mutableStateOf(false) }
    // 위 바("지금까지의 노고")는 없앴다(Design v2). 시스템 뒤로는 그대로 닫기
    BackHandler { onClose() }
    // 출력 연출: 도착과 같은 순서(종이 → 제안 카드 → 불빛 → 금액). 이 중간 정산을 처음 열 때만
    val reduce = rememberReduceMotion()
    val first = remember(m.at) { !reduce && m.choice == null && !MidwayPrinted.contains(m.at) }
    val needsRest = m.needsRest
    val seq = rememberPrintSeq(key = m.at, play = first) {
        MidwayPrinted += m.at
        // 쉰 시간 시트: 처음이면 출력 뒤 0.2초(도착 .rsheet.late와 같음), 다시 열면 바로
        if (needsRest) { if (first) delay(200); sheet = true }
    }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (narrow) 16.gpx else 24.gpx)) {
        val receipt: @Composable (Modifier) -> Unit = { mod ->
            // 쉰 시간 줄도 보인다(값을 누르면 시트 다시, 고른 뒤에는 못 바꿈). 줄 값은 중간 정산에서 고른 답
            if (r != null && showPaper) ReceiptColumn(
                trip.copy(restAnswerMin = m.restMin), r, ui, narrow, reduce = reduce, paperShift = seq.paper.value, open = open, print = seq,
                onToggle = { k -> open = if (open == k) null else k }, onChangeRest = { sheet = true }, modifier = mod, editable = m.choice == null,
                midwaySub = since, showRest = m.restMin != null,
            )
        }
        val card: @Composable (Modifier) -> Unit = { mod -> MidwayCard(m, ui.items, narrow, onPick, onClose, mod.graphicsLayer { alpha = seq.offer.value; translationY = (1f - seq.offer.value) * 16f * density }, solo = trip.n < 2, settledBefore = trip.settlements.isNotEmpty()) }
        if (narrow) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.gpx)) {
                receipt(Modifier.fillMaxWidth())
                card(Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.gpx)) {
                if (showPaper) receipt(Modifier.weight(1f).fillMaxHeight()) else Spacer(Modifier.weight(1f))
                card(Modifier.width(640.gpx).fillMaxHeight())
            }
        }
    }
    if (sheet && m.choice == null) RestSheet(ui.restAnswers, m.restMin, narrow, Modifier.align(if (narrow) Alignment.BottomCenter else Alignment.BottomEnd)) { min -> sheet = false; onRest(min) }
    }
}

@Composable
private fun MidwayCard(
    m: MidwayUi, items: List<EffortItem>, narrow: Boolean, onPick: (String) -> Unit, onClose: () -> Unit, modifier: Modifier,
    solo: Boolean = false, settledBefore: Boolean = false,
) {
    val c = Chagok.colors
    val t = Chagok.type
    val head = (if (narrow) t.titleL else t.headlineS).copy(lineBreak = androidx.compose.ui.text.style.LineBreak.Heading)
    val big = if (narrow) t.headlineS else t.headlineL
    val why = if (narrow) t.labelM else t.bodyM
    val cap = if (narrow) t.labelM else t.bodyM
    // 작은 머리(.cap2 body-m · txt3) + 제목 한 덩어리(사이 10, 분할 6), 제목과 안내(.note txt3) 사이 14
    val capHead: @Composable (String?, String, String?) -> Unit = { capT, headT, noteT ->
        Column(verticalArrangement = Arrangement.spacedBy(if (narrow) 6.gpx else 10.gpx)) {
            capT?.let { Text(it, style = cap, color = c.textTertiary) }
            Column(verticalArrangement = Arrangement.spacedBy(if (narrow) 8.gpx else 14.gpx)) {
                Text(headT, style = head, color = c.textPrimary)
                noteT?.let { Text(it, style = why.copy(lineBreak = androidx.compose.ui.text.style.LineBreak.Heading), color = c.textTertiary) }
            }
        }
    }
    val reduce = rememberReduceMotion()
    val btn = if (narrow) CgSize.M else CgSize.L
    val keep: @Composable (Boolean) -> Unit = { filled ->
        CgButton(stringResource(R.string.effort_mid_keep), onClose, Modifier.fillMaxWidth(), kind = if (filled) CgButtonKind.Filled else CgButtonKind.Basic, size = btn)
    }
    CgOfferCard(modifier, compact = narrow, pad = 48) {   // 도착 카드와 같은 여백
        val view = m.proposal
        val r = m.receipt
        when {
            m.pending -> {
                Text(stringResource(R.string.effort_mid_pending), style = head, color = c.textPrimary)
                Filler(narrow)
                keep(false)
            }
            m.failed || r == null -> {
                Text(stringResource(R.string.effort_mid_failed), style = head, color = c.textPrimary)
                Text(stringResource(R.string.effort_mid_failed_why), style = why, color = c.textSecondary)
                Filler(narrow)
                keep(true)
            }
            isUnsettled(r) -> {
                Text(r.statusText.orEmpty(), style = head, color = c.textPrimary)
                Text(stringResource(R.string.effort_mid_none_why), style = why, color = c.textSecondary)
                Filler(narrow)
                keep(true)
            }
            m.choice != null -> DoneMid(m, view, items, narrow) { keep(true) }
            view is ProposalView.A -> {
                // v104 mid-a: (묻는 줄 · 큰 숫자 한 덩어리) → 근거 28 → 안내 → 아래로 버튼. 위 캡션("14:20 출발 · 지금까지")은 없앰
                Column {
                    Column(verticalArrangement = Arrangement.spacedBy(if (narrow) 4.gpx else 8.gpx)) {
                        // 머리 줄 "지금까지를 챙긴다면"은 그리지 않는다(DECISIONS 11/14 '금액 카드 머리 줄', effort_mid_a_title 안 씀)
                        // 큰 숫자 길이 규칙(#118 D4), 그래도 넘치면 한 단계 더
                        val small = if (narrow) t.titleL else t.headlineM
                        var bigStyle by remember(view.screen.mainText) { mutableStateOf(if (com.mist.pleos_app.effort.bigNumberSmall(view.screen.mainText)) small else big) }
                        Text(view.screen.mainText, style = bigStyle, color = c.textPrimary, maxLines = 1, softWrap = false,
                            onTextLayout = { if (it.didOverflowWidth && bigStyle != t.titleM) bigStyle = if (bigStyle == big) small else t.titleM })
                    }
                }
                Column(Modifier.padding(top = if (narrow) 4.gpx else 8.gpx)) {
                    CgBasisList(listOf(view.screen.basisText, view.screen.wageText), compact = narrow, underlineDelay = 1500, animate = !reduce)
                }
                Text(stringResource(R.string.effort_mid_note), style = why.copy(lineBreak = androidx.compose.ui.text.style.LineBreak.Heading), color = c.textTertiary)
                Filler(narrow)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.gpx)) {
                    CgButton(stringResource(R.string.effort_offer_like), { onPick(CHOICE_ACCEPT) }, Modifier.weight(1f), size = btn)
                    CgButton(stringResource(R.string.effort_mid_keep), onClose, Modifier.weight(1f), kind = CgButtonKind.Basic, size = btn)
                }
            }
            view is ProposalView.B -> {
                // 제목 + 안내 한 덩어리 → 항목 버튼은 바로 아래 16 → 아래로 [계속 가기]
                capHead(null, midwayBText(view.screen.text), view.items.firstOrNull { it.key == view.screen.item }?.let { stringResource(R.string.effort_mid_b_sub, ieyo(it.label)) })
                val opt: @Composable (Modifier, EffortItem) -> Unit = { mod, item ->
                    CgOptionButton(item.examples.ifEmpty { listOf(item.label) }, isDefault = item.key == view.screen.item, onClick = { onPick(item.key) }, modifier = mod, compact = narrow)
                }
                // 분할 창도 세로(#118 D8)
                Column(verticalArrangement = Arrangement.spacedBy(12.gpx)) { view.items.forEach { opt(Modifier.fillMaxWidth(), it) } }
                Filler(narrow)
                keep(false)
            }
            // 제안 없음 · 혼자(아티팩트 midScr n<=1): "지금까지" + 배수 + "혼자 운전이라 영수증만 보여 드려요"
            solo -> {
                capHead(stringResource(R.string.effort_mid_cap_short), r.multiplierText, stringResource(R.string.effort_mid_solo_note))
                Filler(narrow)
                keep(true)
            }
            // 제안 없음 · 아직 짧음(def<0). 앞서 중간 정산을 해서 남은 몫이 작은 경우는 기존 문구
            !settledBefore -> {
                capHead(stringResource(R.string.effort_mid_cap_short), stringResource(R.string.effort_mid_short), stringResource(R.string.effort_mid_short_note))
                Filler(narrow)
                keep(true)
            }
            else -> {
                capHead(null, stringResource(R.string.effort_mid_none), stringResource(R.string.effort_mid_none_why))
                Filler(narrow)
                keep(true)
            }
        }
    }
}

/** 고른 뒤(mid-a-done · mid-b-done): 여기까지 기록해 뒀다 + [계속 가기]. */
@Composable
private fun ColumnScope.DoneMid(m: MidwayUi, view: ProposalView?, items: List<EffortItem>, narrow: Boolean, keep: @Composable () -> Unit) {
    val c = Chagok.colors
    val t = Chagok.type
    val head = (if (narrow) t.titleL else t.headlineS).copy(lineBreak = androidx.compose.ui.text.style.LineBreak.Heading)
    val why = if (narrow) t.labelM else t.bodyM
    // 캡션 + 제목 한 덩어리 → (A) "한 사람 약 ○"(.why body-m · txt2) → 안내(.note txt3) → 아래로 [계속 가기]
    Column(verticalArrangement = Arrangement.spacedBy(if (narrow) 6.gpx else 10.gpx)) {
        if (view is ProposalView.A) Text(stringResource(R.string.effort_mid_a_done), style = head, color = c.textPrimary)
        else {
            val phrase = items.firstOrNull { it.key == m.choice }?.phraseText ?: m.choice.orEmpty()
            Text(stringResource(R.string.effort_mid_b_done, phrase), style = head, color = c.textPrimary)
        }
    }
    if (view is ProposalView.A) Text(view.screen.mainText, style = why, color = c.textSecondary)
    Text(stringResource(R.string.effort_mid_done_note), style = why, color = c.textTertiary)
    Filler(narrow)
    keep()
}
