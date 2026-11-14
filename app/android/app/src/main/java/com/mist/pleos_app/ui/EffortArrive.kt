package com.mist.pleos_app.ui

import android.provider.Settings as AndroidSettings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.mist.pleos_app.ui.components.CgBasisList
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import com.mist.pleos_app.effort.unsettledElapsedText
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.core.LinearEasing
import com.mist.pleos_app.effort.receiptAmountText
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.mist.pleos_app.R
import com.mist.pleos_app.effort.ArriveDone
import com.mist.pleos_app.effort.CHOICE_ACCEPT
import com.mist.pleos_app.effort.bigNumberSmall
import com.mist.pleos_app.effort.EffortUi
import com.mist.pleos_app.effort.OpenTrip
import com.mist.pleos_app.effort.ProposalView
import com.mist.pleos_app.effort.dayText
import com.mist.pleos_app.effort.ieyo
import com.mist.pleos_app.effort.receiptCode
import com.mist.pleos_app.engine.EffortItem
import com.mist.pleos_app.engine.EffortReceipt
import com.mist.pleos_app.ui.components.CgBarcode
import com.mist.pleos_app.ui.components.CgBottomSheet
import com.mist.pleos_app.ui.components.CgButton
import com.mist.pleos_app.ui.components.CgButtonKind
import com.mist.pleos_app.ui.components.CgChip
import com.mist.pleos_app.ui.components.CgDashedRule
import com.mist.pleos_app.ui.components.CgDoubleRule
import com.mist.pleos_app.ui.components.CgNotice
import com.mist.pleos_app.ui.components.CgOfferCard
import com.mist.pleos_app.ui.components.CgOptionButton
import com.mist.pleos_app.ui.components.CgReceipt
import com.mist.pleos_app.ui.components.CgReceiptRow
import com.mist.pleos_app.ui.components.CgIcon
import com.mist.pleos_app.ui.components.CgIconMark
import com.mist.pleos_app.ui.components.CgReceiptSum
import com.mist.pleos_app.ui.components.CgSize
import com.mist.pleos_app.ui.components.CgTitleBar
import com.mist.pleos_app.ui.components.CgSettleFold
import com.mist.pleos_app.ui.theme.Chagok
import com.mist.pleos_app.ui.theme.gpx
import androidx.compose.ui.text.TextStyle
import com.mist.pleos_app.ui.theme.gsp
import com.mist.pleos_app.effort.basisFirst
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * 도착 화면(Design v2 `design/screens/arrive.html`, #75 명준). 부품은 ui/components(Cg*)만 조립한다.
 * 왼쪽 영수증 + 오른쪽 제안 카드 640(가로), 위 영수증 · 아래 제안(분할 840). 쉰 시간은 영수증이 나온 뒤 짧은 하단 시트로 묻는다.
 * 숫자·줄 문구는 모두 엔진 출력(effort.copy), 고정 문구는 strings.xml(임시, 명준 확정 전). 화면에서 계산하지 않는다.
 * 움직임 값은 HANDOFF_v2.md 3-2절: 영수증 출력 1.1초, 제안 카드 0.9초 뒤 0.45초, 시트 1.3초 뒤 0.38초, 바뀐 값 1.8초.
 */

private val EasePrint = CubicBezierEasing(.25f, .8f, .3f, 1f)
private val EaseRise = CubicBezierEasing(.2f, .7f, .2f, 1f)

/**
 * 영수증 출력 연출(#118 v90~v94, HANDOFF_v3 5-5 표). 들어온 그 한 번만 재생, 정지 화면 = 다 나온 끝 상태(불 켜짐 · 합계 금액).
 * 0초 종이 1.1초 → 0.9초 제안 카드 0.45초 → 1.0초 출력구 불빛 0.8초 → 2.6초 배수가 위로 사라짐 0.34초
 * → 2.8초 금액이 아래에서 올라옴 0.42초 → 3.15초 마커가 다시 그어짐 0.4초. 움직임 줄이기면 끝 상태로 바로.
 */
@androidx.compose.runtime.Stable
internal class PrintSeq(val paper: Animatable<Float, *>, val offer: Animatable<Float, *>, val light: Animatable<Float, *>,
                        val out: Animatable<Float, *>, val inn: Animatable<Float, *>, val mark: Animatable<Float, *>) {
    val flip: () -> Triple<Float, Float, Float> = { Triple(out.value, inn.value, mark.value) }
}

@Composable
internal fun rememberPrintSeq(key: Any, play: Boolean, onPrinted: suspend () -> Unit = {}): PrintSeq {
    val end = !play
    val seq = remember(key) {
        PrintSeq(Animatable(if (end) 0f else -1.05f), Animatable(if (end) 1f else 0f), Animatable(if (end) 1f else 0f),
            Animatable(if (end) 1f else 0f), Animatable(if (end) 1f else 0f), Animatable(if (end) 1f else 0f))
    }
    LaunchedEffect(key) {
        if (!play) { onPrinted(); return@LaunchedEffect }
        launch { seq.paper.animateTo(0f, tween(1100, easing = EasePrint)) }
        launch { delay(900); seq.offer.animateTo(1f, tween(450, easing = EaseRise)) }
        launch { delay(1000); seq.light.animateTo(1f, tween(800, easing = LinearEasing)) }
        launch { delay(2600); seq.out.animateTo(1f, tween(340, easing = CubicBezierEasing(.55f, 0f, .75f, .2f))) }
        launch { delay(2800); seq.inn.animateTo(1f, tween(420, easing = CubicBezierEasing(.2f, .8f, .3f, 1f))) }
        launch { delay(3150); seq.mark.animateTo(1f, tween(400, easing = CubicBezierEasing(.3f, .7f, .3f, 1f))) }
        delay(1100)
        onPrinted()
    }
    return seq
}

@Composable
internal fun EffortArriveScreen(ui: EffortUi, onRest: (Int) -> Unit, onPick: (String) -> Unit, onClose: () -> Unit, onSettleSwap: () -> Unit = {}) {
    val trip = ui.openTrip
    val r = ui.receipt
    if (trip == null || r == null) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.gpx)) {
            CgNotice(stringResource(R.string.effort_receipt_none))
            CgButton(stringResource(R.string.effort_home), onClose, Modifier.fillMaxWidth(), kind = CgButtonKind.Basic)
        }
        return
    }
    val narrow = LocalNarrow.current
    val reduce = rememberReduceMotion()
    val key = trip.tripId
    if (ui.unsettled) { UnsettledArrive(trip, r, ui, narrow, onClose); return }
    // #118 10-3 ⑥: 쉰 시간을 고르기 전에는 영수증 · 제안 대신 "오늘 운전을 정리하는 중이에요" · "확인 중"과 시트만.
    // 고르면 그 값으로 센 영수증이 출력 연출과 함께 나온다
    if (trip.restAnswerMin == null && ui.done == null) {
        RestPending(stringResource(R.string.effort_rest_pending_arrive), ui.restAnswers, narrow, onRest)
        return
    }

    // 이 도착에서 처음 열 때만 출력 연출. 홈에서 [영수증 보기]로 다시 열거나 주행 중 가림이 풀려 돌아와도 끝 상태로
    // (화면을 바꾸면 rememberSaveable이 사라지므로 이 프로세스에서 이미 찍은 주행을 따로 기억)
    val first = remember(key) { !(reduce || ui.done != null || key in PrintedTrips) }
    val needsRest = !ui.unsettled && ui.done == null && trip.restAnswerMin == null
    var sheet by rememberSaveable(key) { mutableStateOf(false) }
    val seq = rememberPrintSeq(key, play = first) {
        PrintedTrips += key
        // 쉰 시간 시트: 처음이면 들어온 뒤 1.3초(.rsheet.late, 종이 1.1초 + 0.2초), 다시 열면 바로(openRcpt)
        if (needsRest) { if (first) delay(200); sheet = true }
    }
    val paper = seq.paper
    val offer = seq.offer
    var open by rememberSaveable(key) { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (narrow) 16.gpx else 24.gpx)) {
            // v3 5-5: 위 제목 줄("○○에 도착했어요" · 인원)은 없앰. 인원은 영수증 머리 아래 줄로
            val receipt: @Composable (Modifier) -> Unit = { m ->
                if (!ui.unsettled) ReceiptColumn(
                    trip, r, ui, narrow, reduce, paperShift = paper.value, open = open, print = seq, arrive = true,
                    onToggle = { k -> open = if (open == k) null else k }, onChangeRest = { sheet = true }, modifier = m, onSettleSwap = onSettleSwap,
                )
            }
            val card: @Composable (Modifier) -> Unit = { m ->
                OfferCard(ui, trip, narrow, onPick, onClose, m.graphicsLayer { alpha = offer.value; translationY = (1f - offer.value) * 16f * density }, offerPlayed = !first)
            }
            if (narrow) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.gpx)) {
                    receipt(Modifier.fillMaxWidth())
                    card(Modifier.fillMaxWidth())
                }
            } else {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.gpx)) {
                    if (ui.unsettled) Spacer(Modifier.weight(1f)) else receipt(Modifier.weight(1f).fillMaxHeight())
                    card(Modifier.width(640.gpx).fillMaxHeight())
                }
            }
        }
        // 쉰 시간 시트(딤 없음, 고르면 닫힘). 영수증의 "중간에 쉰 시간" 값을 누르면 다시 연다. 고른 뒤·정리하지 못함에는 없다
        if (sheet && ui.done == null && !ui.unsettled) {
            RestSheet(ui.restAnswers, trip.restAnswerMin, narrow, Modifier.align(if (narrow) Alignment.BottomCenter else Alignment.BottomEnd)) { min -> sheet = false; onRest(min) }
        }
    }
}

/**
 * 쉰 시간 시트(도착 · 중간 정산 공통, #118 10-3 ⑥). [selected]가 null이면(아직 안 고름) 아무 칩도 고르지 않은 모양.
 */
@Composable
internal fun RestSheet(answers: List<Pair<Int, String>>, selected: Int?, narrow: Boolean, modifier: Modifier = Modifier, onPick: (Int) -> Unit) {
    CgBottomSheet(modifier.then(if (narrow) Modifier.fillMaxWidth() else Modifier.width(640.gpx)), compact = narrow) {
        Column(verticalArrangement = Arrangement.spacedBy(4.gpx)) {
            Text(stringResource(R.string.effort_rest_sheet_title), style = if (narrow) Chagok.type.bodyL else Chagok.type.titleM, color = Chagok.colors.textPrimary)
            Text(stringResource(R.string.effort_rest_sheet_sub), style = Chagok.type.bodyM, color = Chagok.colors.textSecondary)
        }
        // 칩 두 줄은 한 묶음(줄 사이 12). 분할은 칩 높이 72 · Body M-B
        Column(verticalArrangement = Arrangement.spacedBy(12.gpx)) {
            answers.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.gpx)) {
                    row.forEach { (min, label) ->
                        CgChip(label, selected = min == selected, onClick = { onPick(min) }, modifier = Modifier.weight(1f), size = CgSize.M, compact = narrow)
                    }
                }
            }
        }
    }
}

/**
 * 쉰 시간을 고르기 전 화면(#118 10-3 ⑥): 영수증 자리 [text] · 제안 자리 "확인 중" + 쉰 시간 시트.
 * 가로는 왼쪽 영수증 자리 + 오른쪽 카드 자리 640, 분할은 위아래.
 */
@Composable
internal fun RestPending(text: String, answers: List<Pair<Int, String>>, narrow: Boolean, onPick: (Int) -> Unit, top: @Composable () -> Unit = {}) {
    val c = Chagok.colors
    val t = Chagok.type
    val slot: @Composable (Modifier, String, Boolean) -> Unit = { m, s, main ->
        Box(m.background(c.surfaceCard, RoundedCornerShape(32.gpx)).padding(32.gpx), contentAlignment = Alignment.Center) {
            Text(s, style = if (main) (if (narrow) t.bodyL else t.titleM) else (if (narrow) t.bodyM else t.bodyL), color = if (main) c.textSecondary else c.textTertiary, textAlign = TextAlign.Center)
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (narrow) 16.gpx else 24.gpx)) {
            top()
            if (narrow) {
                slot(Modifier.fillMaxWidth().height(420.gpx), text, true)
                slot(Modifier.fillMaxWidth().height(160.gpx), stringResource(R.string.effort_rest_pending_offer), false)
            } else {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.gpx)) {
                    slot(Modifier.weight(1f).fillMaxHeight(), text, true)
                    slot(Modifier.width(640.gpx).fillMaxHeight(), stringResource(R.string.effort_rest_pending_offer), false)
                }
            }
        }
        RestSheet(answers, null, narrow, Modifier.align(if (narrow) Alignment.BottomCenter else Alignment.BottomEnd), onPick)
    }
}

// ------------------------------------------------------------ 영수증(CgReceipt 조립)
@Composable
internal fun ReceiptColumn(
    trip: OpenTrip, r: EffortReceipt, ui: EffortUi, narrow: Boolean, reduce: Boolean,
    paperShift: Float, open: String?, onToggle: (String) -> Unit, onChangeRest: () -> Unit, modifier: Modifier,
    onSettleSwap: () -> Unit = {},
    editable: Boolean = true,   // 기록 상세(Q-08-01)는 쉰 시간 [바꾸기] 없이 근거 펼침만
    midwaySub: String? = null,  // 중간 정산(F27): 제목 "오늘 주행 영수증"(v3) + 이 줄("14:20 출발 · 아직 가는 중")
    showRest: Boolean = midwaySub == null,   // 쉰 시간 줄. 중간 정산도 묻게 되어(#118 10-3 ⑥) 중간 정산 화면이 true로 넘긴다
    print: PrintSeq? = null,    // 출력 연출(#118 v90~v94). null = 끝 상태(불 켜짐 · 합계 금액)
    arrive: Boolean = false,    // 도착 화면 가로만 종이 틀이 다르다(Design v2 .arrpg). 중간 정산 · 기록 상세는 그대로
) {
    val c = Chagok.colors
    val t = Chagok.type
    // 바뀐 값 1.8초 회색 강조(앞 40%는 그대로): 쉰 시간을 고른 뒤 영수증이 다시 계산될 때
    val prev = remember(trip.tripId) { mutableStateOf<EffortReceipt?>(null) }
    var changed by remember(trip.tripId) { mutableStateOf(emptySet<String>()) }
    val flash = remember(trip.tripId) { Animatable(0f) }
    LaunchedEffect(r) {
        val p = prev.value
        prev.value = r
        if (p != null && p != r && !reduce) {
            changed = changedKeys(p, r)
            flash.snapTo(1f); delay(720); flash.animateTo(0f, tween(1080, easing = LinearOutSlowInEasing))
        }
    }
    val hi = { k: String -> if (k in changed) flash.value else 0f }
    CgReceipt(modifier, compact = narrow, shift = paperShift, fill = !narrow, scroll = !narrow, light = { print?.light?.value ?: 1f }, arrive = arrive && !narrow) {
        // v3 5-5: 머리 700 42/54(분할 34/44) · 아래 줄 "장소 · 날짜 · n명"(혼자면 "혼자") 400 30/38
        Text(
            stringResource(if (midwaySub != null) R.string.effort_mid_receipt_title else R.string.effort_receipt_title), Modifier.fillMaxWidth(),
            style = TextStyle(fontSize = (if (narrow) 34 else 42).gsp, lineHeight = (if (narrow) 44 else 54).gsp, fontWeight = FontWeight.Bold),
            color = c.textPrimary, textAlign = TextAlign.Center,
        )
        Text(
            midwaySub ?: if (trip.n >= 2) stringResource(R.string.effort_receipt_sub_people, trip.destName, dayText(trip.departedAt), trip.n)
            else stringResource(R.string.effort_receipt_sub_solo, trip.destName, dayText(trip.departedAt)),
            Modifier.fillMaxWidth().padding(top = if (narrow) 4.gpx else 8.gpx),
            style = if (narrow) t.bodyM else TextStyle(fontSize = 30.gsp, lineHeight = 38.gsp), color = c.textTertiary, textAlign = TextAlign.Center,
        )
        CgDoubleRule(Modifier.padding(top = if (narrow) 8.gpx else if (arrive) 28.gpx else 24.gpx, bottom = if (narrow) 4.gpx else if (arrive) 18.gpx else 16.gpx))
        if (!narrow) Row(Modifier.fillMaxWidth().padding(top = 4.gpx, bottom = 12.gpx)) {
            Text(stringResource(R.string.effort_receipt_col_item), Modifier.weight(1f), style = TextStyle(fontSize = 32.gsp, lineHeight = 40.gsp, fontWeight = FontWeight.Bold), color = c.textPrimary)
            Text(stringResource(R.string.effort_receipt_col_add), style = TextStyle(fontSize = 32.gsp, lineHeight = 40.gsp), color = c.textSecondary)
        }
        // 정보 없음(날씨 · 정체): 목록 맨 위, 이름 · 값을 나누고 누르면 더하지 않은 까닭(아티팩트 calc terms.unshift)
        val cp = ui.constants?.copy?.text
        r.notes.forEach { note ->
            val jam = note == cp?.get("congestion_unknown_text")
            val k = "na:$note"
            CgReceiptRow(
                stringResource(if (jam) R.string.effort_receipt_na_jam_label else R.string.effort_receipt_na_weather_label),
                if (jam) stringResource(R.string.effort_receipt_na_jam) else if (note == cp?.get("weather_unknown_text")) stringResource(R.string.effort_receipt_na_weather) else note,
                "—", stringResource(if (jam) R.string.effort_receipt_na_jam_why else R.string.effort_receipt_na_weather_why),
                expanded = open == k, onClick = { onToggle(k) }, compact = narrow,
            )
        }
        if (r.lines.isEmpty() && r.notes.isEmpty()) {
            // 가산 0개(#118 v114, 명준 11/12 확정 문구): 해 아이콘 + 굵은 줄 + 작은 줄
            Row(Modifier.padding(vertical = 14.gpx), horizontalArrangement = Arrangement.spacedBy(if (narrow) 10.gpx else 16.gpx), verticalAlignment = Alignment.CenterVertically) {
                CgIconMark(CgIcon.Sun, if (narrow) 24.gpx else 36.gpx, c.textSecondary)
                Column(verticalArrangement = Arrangement.spacedBy(4.gpx)) {
                    Text(stringResource(R.string.effort_receipt_plain), style = if (narrow) t.labelM.copy(fontWeight = FontWeight.Bold) else t.bodyMB, color = c.textPrimary)
                    Text(stringResource(R.string.effort_receipt_plain_sub), style = if (narrow) t.labelS else t.labelM, color = c.textSecondary)
                }
            }
        }
        r.lines.forEach { line ->
            CgReceiptRow(
                line.label, line.detailText.orEmpty(), line.pctText, basisFirst(line.basisText), expanded = open == line.key,
                onClick = { onToggle(line.key) }, compact = narrow,
                extra = if (line.key == "congestion") r.footnoteText else null, hi = hi(line.key),
            )
        }
        if (editable && midwaySub == null && ui.weatherPending) Text(stringResource(R.string.effort_weather_pending), Modifier.padding(vertical = 8.gpx), style = t.labelM, color = c.textTertiary)

        // 소계: 운전 시간 · 중간에 쉰 시간 [바꾸기] · 거리 → 합계
        CgDashedRule(Modifier.padding(top = if (narrow) 4.gpx else 20.gpx, bottom = if (narrow) 4.gpx else 18.gpx))
        CgReceiptSum(stringResource(R.string.effort_receipt_drive), r.driveText, compact = narrow, hi = hi("drive"))
        val restLabel = ui.restAnswers.firstOrNull { it.first == (trip.restAnswerMin ?: 0) }?.second ?: ""
        // v3 5-5: [바꾸기] 버튼 대신 값을 누르면 쉰 시간 시트가 다시 열림
        if (showRest) CgReceiptSum(
            stringResource(R.string.effort_receipt_rest), restLabel, compact = narrow, hi = hi("rest"),
            onValue = if (editable && ui.done == null) onChangeRest else null, valueLabel = stringResource(R.string.effort_receipt_rest_change_label),
        )
        r.distanceText?.let { CgReceiptSum(stringResource(R.string.effort_receipt_distance), it, compact = narrow) }
        // v92 · v94: 처음 배수 → 불 켜지고 1.5초 뒤 금액으로 굴러 바뀜(끝 = 금액, 화면 읽기 = "약 3만 6천 원 · 평소보다 …")
        CgReceiptSum(stringResource(R.string.effort_receipt_total), r.multiplierText, compact = narrow, total = true, hi = hi("total"),
            amount = receiptAmountText(r, ui.constants), flip = print?.flip ?: { Triple(1f, 1f, 1f) })
        // #118: 중간 정산을 한 주행이면 합계 아래 "운전 노고 전체 · 약 3만 6천 원" + 작은 줄 "중간 정산 있음 ⇅"(누르면 자리 바꿈)
        val fold = ui.settleFold
        if (fold != null && midwaySub == null && editable) {
            val inc = ui.settleIncluded
            val all = stringResource(if (fold.amount) R.string.effort_settle_all_a else R.string.effort_settle_all_b)
            val incL = stringResource(if (fold.amount) R.string.effort_settle_inc_a else R.string.effort_settle_inc_b)
            CgSettleFold(
                main = if (inc) incL else all, alt = if (inc) all else stringResource(R.string.effort_settle_has),
                altLabel = stringResource(if (inc) R.string.effort_settle_to_all else R.string.effort_settle_to_inc),
                value = if (inc) fold.incValue else fold.allValue, swapKey = inc, onSwap = onSettleSwap,
                compact = narrow, reduce = reduce, enabled = ui.done == null,
            )
        }
        if (!narrow && open == null) CgBarcode(receiptCode(trip, r), Modifier.padding(top = 22.gpx))
    }
}

/** 이 프로세스에서 출력 연출을 이미 보여 준 주행(tripId). */
private val PrintedTrips = mutableSetOf<String>()

private fun changedKeys(a: EffortReceipt, b: EffortReceipt): Set<String> {
    val keys = mutableSetOf<String>()
    val am = a.lines.associateBy { it.key }
    val bm = b.lines.associateBy { it.key }
    (am.keys + bm.keys).forEach { k ->
        val x = am[k]; val y = bm[k]
        if (x == null || y == null || x.detailText != y.detailText || x.pctText != y.pctText) keys += k
    }
    if (a.driveText != b.driveText) keys += "drive"
    if (a.restMin != b.restMin) keys += "rest"
    if (a.multiplierText != b.multiplierText) keys += "total"
    return keys
}

// ------------------------------------------------------------ 제안 카드(CgOfferCard 조립, 640 고정 / 분할은 아래 전체)
@Composable
private fun OfferCard(ui: EffortUi, trip: OpenTrip, narrow: Boolean, onPick: (String) -> Unit, onClose: () -> Unit, modifier: Modifier, offerPlayed: Boolean = true) {
    val c = Chagok.colors
    val reduceMotion = rememberReduceMotion()
    val t = Chagok.type
    val head = if (narrow) t.titleL else t.headlineS
    val big = if (narrow) t.headlineS else t.headlineL
    val why = if (narrow) t.labelM else t.bodyM
    val cap = if (narrow) t.labelM else t.bodyM      // .offer .cap2 body-m · txt3(분할 label-m)
    val headWrap = head.copy(lineBreak = androidx.compose.ui.text.style.LineBreak.Heading)   // text-wrap:balance · keep-all
    val btn = if (narrow) CgSize.M else CgSize.L
    // 작은 머리 + 제목은 한 덩어리(.cap2 + .head margin-top -10 → 사이 10, 분할 6), 제목과 안내 사이 14(.head + .note -6)
    val capHead: @Composable (String, String, String?) -> Unit = { capT, headT, noteT ->
        Column(verticalArrangement = Arrangement.spacedBy(if (narrow) 6.gpx else 10.gpx)) {
            Text(capT, style = cap, color = c.textTertiary)
            Column(verticalArrangement = Arrangement.spacedBy(if (narrow) 8.gpx else 14.gpx)) {
                Text(headT, style = headWrap, color = c.textPrimary)
                noteT?.let { Text(it, style = why.copy(lineBreak = androidx.compose.ui.text.style.LineBreak.Heading), color = c.textTertiary) }
            }
        }
    }
    val close: @Composable (Boolean) -> Unit = { filled ->
        // 정리하지 못함은 [홈으로](명세 F22·시안 arrive-unsettled), 그 밖은 [닫기]
        CgButton(stringResource(if (ui.unsettled) R.string.effort_home else R.string.close), onClose, Modifier.fillMaxWidth(), kind = if (filled) CgButtonKind.Filled else CgButtonKind.Basic, size = btn)
    }
    CgOfferCard(modifier, compact = narrow, pad = 48) {   // 도착 .arrpg .offer padding 48
        val done = ui.done
        val view = ui.proposal
        when {
            ui.unsettled -> {
                capHead(stringResource(R.string.effort_offer_plain_head), ui.receipt?.statusText ?: "", null)
                Filler(narrow)
                close(true)
            }
            done != null -> DoneBody(done, ui.items, narrow) { close(true) }
            trip.n < 2 -> {
                capHead(stringResource(R.string.effort_arrive_solo), stringResource(R.string.effort_offer_solo), stringResource(R.string.effort_offer_solo_why))
                Filler(narrow)
                close(false)
            }
            view is ProposalView.A -> {
                // 11/9 명준 위계: 묻는 줄(작게, title-m · textSecondary) + 결과 숫자(크게)는 한 덩어리, 근거는 한 칸 띄움
                // 묻는 줄과 큰 숫자 사이 8(분할 4), 큰 숫자와 근거 사이 28(분할 16)
                Column(verticalArrangement = Arrangement.spacedBy(if (narrow) 4.gpx else 8.gpx)) {
                    Text(view.screen.titleText, style = if (narrow) t.bodyL else t.titleM, color = c.textSecondary)
                    // 큰 숫자 길이 규칙(#118 D4): 10만 원 이상 · 15자 이상이면 한 단계 작게(가로 Headline M · 분할 Title L).
                    // 그래도 카드 폭을 넘치면 "원"이 잘리지 않게 한 단계 더
                    val small = if (narrow) t.titleL else t.headlineM
                    var bigStyle by remember(view.screen.mainText) { mutableStateOf(if (bigNumberSmall(view.screen.mainText)) small else big) }
                    Text(view.screen.mainText, style = bigStyle, color = c.textPrimary, maxLines = 1, softWrap = false,
                        onTextLayout = { if (it.didOverflowWidth && bigStyle != t.titleM) bigStyle = if (bigStyle == big) small else t.titleM })
                }
                // 근거 = 별표 글머리 + 운전 노고 금액 밑줄(제안 카드가 올라온 뒤, 출력 연출이면 1.5초)
                Column(Modifier.padding(top = if (narrow) 4.gpx else 8.gpx)) {
                    CgBasisList(listOf(view.screen.basisText, view.screen.wageText), compact = narrow, underlineDelay = if (offerPlayed) 500 else 1500, animate = !reduceMotion)
                }
                Filler(narrow)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.gpx)) {
                    CgButton(stringResource(R.string.effort_offer_like), { onPick(CHOICE_ACCEPT) }, Modifier.weight(1f), size = btn)
                    CgButton(stringResource(R.string.close), onClose, Modifier.weight(1f), kind = CgButtonKind.Basic, size = btn)
                }
            }
            view is ProposalView.B -> {
                // 제목 + 안내는 한 덩어리(사이 14), 항목 버튼은 안내 바로 아래 16(.note + .opts), 닫기만 아래로
                Column(verticalArrangement = Arrangement.spacedBy(if (narrow) 8.gpx else 14.gpx)) {
                    Text(view.screen.text, style = headWrap, color = c.textPrimary)
                    view.items.firstOrNull { it.key == view.screen.item }?.let {
                        // #118 arrive-mid-b-open: 포함 모드면 끝에 "· 중간 정산 몫은 빼고 골랐어요"
                        val sub = stringResource(R.string.effort_offer_b_sub, ieyo(it.label))
                        Text(if (ui.settleFold != null && ui.settleIncluded) sub + stringResource(R.string.effort_settle_b_note) else sub,
                            style = why.copy(lineBreak = androidx.compose.ui.text.style.LineBreak.Heading), color = c.textTertiary)
                    }
                }
                val opt: @Composable (Modifier, EffortItem) -> Unit = { m, item ->
                    CgOptionButton(item.examples.ifEmpty { listOf(item.label) }, isDefault = item.key == view.screen.item, onClick = { onPick(item.key) }, modifier = m, compact = narrow)
                }
                // 분할 창도 세로(#118 D8): 가로 3칸이면 "바베큐장 이용료"처럼 긴 항목이 잘렸다
                Column(verticalArrangement = Arrangement.spacedBy(12.gpx)) { view.items.forEach { opt(Modifier.fillMaxWidth(), it) } }
                Filler(narrow)
                close(false)
            }
            else -> {
                // 제안 없음(운전자 몫이 커피 1인분의 절반보다 작음, 명세 F25 · A·B 같음 Q-07-04)
                val pre = if (ui.settleFold != null && ui.settleIncluded) stringResource(R.string.effort_offer_none_settled) else ""
                capHead(stringResource(R.string.effort_offer_plain_head), stringResource(R.string.effort_offer_none), pre + stringResource(R.string.effort_offer_none_why))
                Filler(narrow)
                close(false)
            }
        }
    }
}

/** 고른 뒤: "기록에 남겼어요" + [닫기]. 더 요구하지 않는다(AC-F26-1). */
@Composable
private fun ColumnScope.DoneBody(done: ArriveDone, items: List<EffortItem>, narrow: Boolean, close: @Composable () -> Unit) {
    val c = Chagok.colors
    val t = Chagok.type
    val head = (if (narrow) t.titleL else t.headlineS).copy(lineBreak = androidx.compose.ui.text.style.LineBreak.Heading)
    val why = if (narrow) t.labelM else t.bodyM
    val cap = if (narrow) t.labelM else t.bodyM
    // 작은 머리(.cap2) + 제목 한 덩어리 → .why(txt2) → .note "더 할 일은 없어요"(txt3) → 아래로 [닫기]
    val (capT, headT, whyT) = when (val v = done.proposal) {
        is ProposalView.A -> Triple(v.screen.titleText, stringResource(R.string.effort_offer_a_done), v.screen.mainText)
        else -> {
            val phrase = items.firstOrNull { it.key == done.choice }?.phraseText ?: done.choice
            Triple(stringResource(R.string.effort_offer_b_done_head), stringResource(R.string.effort_offer_b_done, phrase), stringResource(R.string.effort_offer_b_done_sub))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(if (narrow) 6.gpx else 10.gpx)) {
        Text(capT, style = cap, color = c.textTertiary)
        Text(headT, style = head, color = c.textPrimary)
    }
    Text(whyT, style = why, color = c.textSecondary)
    Text(stringResource(R.string.effort_offer_done_note), style = why, color = c.textTertiary)
    Filler(narrow)
    close()
}

/** 가로 창은 버튼을 카드 아래로 민다. 분할은 쌓기만. */
@Composable
internal fun ColumnScope.Filler(narrow: Boolean) { if (!narrow) Spacer(Modifier.weight(1f)) }

/** 움직임 줄이기(개발자 옵션 애니메이션 배율 0): 연출을 끄고 끝 상태로(HANDOFF_v2 3-2). */
@Composable
internal fun rememberReduceMotion(): Boolean {
    val ctx = LocalContext.current
    return remember {
        runCatching { AndroidSettings.Global.getFloat(ctx.contentResolver, AndroidSettings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}

/**
 * 정리하지 못함(#118 arrive-unsettled, #68 · #71): 경과가 상한을 넘으면 계산 연출 없이 바로. 숫자 · 쉰 시간 · 제안 없이 [홈으로]만.
 * 위 바 "○○에 도착했어요" + "함께 탄 사람 n명" · 빈 영수증 종이(제목 흐리게 · "장소 · 날짜") · 엔진 문구 · 이유 두 줄 · 회색 빈 줄 3개 · 안내.
 */
@Composable
private fun UnsettledArrive(trip: OpenTrip, r: EffortReceipt, ui: EffortUi, narrow: Boolean, onClose: () -> Unit) {
    val c = Chagok.colors
    val t = Chagok.type
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (narrow) 16.gpx else 24.gpx)) {
        CgTitleBar(
            stringResource(R.string.effort_arrive_title, trip.destName), compact = narrow, titlePlace = trip.destName,
            trailing = if (narrow) null else ({
                Text(if (trip.n >= 2) stringResource(R.string.effort_arrive_people, trip.n) else stringResource(R.string.effort_arrive_solo),
                    style = t.labelM, color = c.textTertiary)
            }),
        )
        Column(
            Modifier.fillMaxWidth().weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (narrow) 20.gpx else 32.gpx, if (narrow) Alignment.Top else Alignment.CenterVertically),
        ) {
            val shape = RoundedCornerShape(32.gpx)
            Column(
                Modifier.widthIn(max = 960.gpx).fillMaxWidth().shadow(10.gpx, shape).background(c.surfaceCard, shape).border(2.gpx, c.lineStrong, shape)
                    .padding(start = if (narrow) 26.gpx else 64.gpx, end = if (narrow) 26.gpx else 64.gpx, top = if (narrow) 30.gpx else 52.gpx, bottom = if (narrow) 24.gpx else 40.gpx),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.effort_receipt_title), style = if (narrow) t.bodyL else t.titleL, color = c.textDisabled)
                Text(stringResource(R.string.effort_receipt_sub_place, trip.destName, dayText(trip.departedAt)), style = t.labelM, color = c.textDisabled)
                CgDoubleRule(Modifier.padding(top = if (narrow) 12.gpx else 22.gpx, bottom = if (narrow) 20.gpx else 34.gpx))
                Text(r.statusText.orEmpty(), style = if (narrow) t.titleM else t.headlineS, color = c.textPrimary, textAlign = TextAlign.Center)
                Spacer(Modifier.height(14.gpx))
                Text(stringResource(R.string.effort_unsettled_why_late, unsettledElapsedText(r)), style = if (narrow) t.labelM else t.bodyL, color = c.textSecondary, textAlign = TextAlign.Center)
                Text(stringResource(R.string.effort_unsettled_why_time), style = if (narrow) t.labelM else t.bodyL, color = c.textSecondary, textAlign = TextAlign.Center)
                Column(Modifier.fillMaxWidth().padding(top = if (narrow) 24.gpx else 40.gpx, bottom = if (narrow) 20.gpx else 32.gpx), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (narrow) 12.gpx else 16.gpx)) {
                    floatArrayOf(.72f, .54f, .64f).forEach { w -> Box(Modifier.fillMaxWidth(w).height(if (narrow) 12.gpx else 16.gpx).background(c.placeholder, RoundedCornerShape(8.gpx))) }
                }
                Text(stringResource(R.string.effort_unsettled_hint), style = t.labelM, color = c.textTertiary, textAlign = TextAlign.Center)
            }
            CgButton(stringResource(R.string.effort_home), onClose, if (narrow) Modifier.fillMaxWidth() else Modifier, size = if (narrow) CgSize.M else CgSize.L)
        }
    }
}
