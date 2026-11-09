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
import com.mist.pleos_app.ui.components.CgReceiptSum
import com.mist.pleos_app.ui.components.CgSize
import com.mist.pleos_app.ui.components.CgTitleBar
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

@Composable
internal fun EffortArriveScreen(ui: EffortUi, onRest: (Int) -> Unit, onPick: (String) -> Unit, onClose: () -> Unit) {
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

    // 이 도착에서 처음 열 때만 출력 연출(다시 열거나 화면이 돌아오면 끝 상태로)
    var played by rememberSaveable(key) { mutableStateOf(reduce || ui.done != null) }
    val paper = remember(key) { Animatable(if (played) 0f else -1.05f) }
    val offer = remember(key) { Animatable(if (played) 1f else 0f) }
    val needsRest = !ui.unsettled && ui.done == null && trip.restAnswerMin == null
    var sheet by rememberSaveable(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        if (!played) {
            launch { paper.animateTo(0f, tween(1100, easing = EasePrint)) }
            launch { delay(900); offer.animateTo(1f, tween(450, easing = EaseRise)) }
            delay(1100)
            played = true
            if (needsRest) { delay(1300); sheet = true }
        } else if (needsRest) sheet = true
    }
    var open by rememberSaveable(key) { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (narrow) 16.gpx else 24.gpx)) {
            // v3 5-5: 위 제목 줄("○○에 도착했어요" · 인원)은 없앰. 인원은 영수증 머리 아래 줄로
            val receipt: @Composable (Modifier) -> Unit = { m ->
                if (!ui.unsettled) ReceiptColumn(
                    trip, r, ui, narrow, reduce, paperShift = paper.value, open = open,
                    onToggle = { k -> open = if (open == k) null else k }, onChangeRest = { sheet = true }, modifier = m,
                )
            }
            val card: @Composable (Modifier) -> Unit = { m ->
                OfferCard(ui, trip, narrow, onPick, onClose, m.graphicsLayer { alpha = offer.value; translationY = (1f - offer.value) * 16f * density })
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
        // 쉰 시간 시트(딤 없음, 고르면 닫힘). 처음엔 답을 묻고, [바꾸기]로 다시 연다. 고른 뒤·정리하지 못함에는 없다
        if (sheet && ui.done == null && !ui.unsettled) {
            CgBottomSheet(
                Modifier.align(if (narrow) Alignment.BottomCenter else Alignment.BottomEnd).then(if (narrow) Modifier.fillMaxWidth() else Modifier.width(640.gpx)),
                compact = narrow,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.gpx)) {
                    Text(stringResource(R.string.effort_rest_sheet_title), style = if (narrow) Chagok.type.bodyL else Chagok.type.titleM, color = Chagok.colors.textPrimary)
                    Text(stringResource(R.string.effort_rest_sheet_sub), style = Chagok.type.bodyM, color = Chagok.colors.textSecondary)
                }
                ui.restAnswers.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.gpx)) {
                        row.forEach { (min, label) ->
                            CgChip(label, selected = min == (trip.restAnswerMin ?: 0), onClick = { sheet = false; onRest(min) }, modifier = Modifier.weight(1f), size = CgSize.M)
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------ 영수증(CgReceipt 조립)
@Composable
internal fun ReceiptColumn(
    trip: OpenTrip, r: EffortReceipt, ui: EffortUi, narrow: Boolean, reduce: Boolean,
    paperShift: Float, open: String?, onToggle: (String) -> Unit, onChangeRest: () -> Unit, modifier: Modifier,
    editable: Boolean = true,   // 기록 상세(Q-08-01)는 쉰 시간 [바꾸기] 없이 근거 펼침만
    midwaySub: String? = null,  // 중간 정산(F27): 제목 "오늘 주행 영수증"(v3) + 이 줄("14:20 출발 · 아직 가는 중"), 쉰 시간 줄 없음
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
    CgReceipt(modifier, compact = narrow, shift = paperShift, fill = !narrow, scroll = !narrow) {
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
            style = if (narrow) t.labelM else TextStyle(fontSize = 30.gsp, lineHeight = 38.gsp), color = c.textTertiary, textAlign = TextAlign.Center,
        )
        CgDoubleRule(Modifier.padding(top = if (narrow) 8.gpx else 24.gpx, bottom = if (narrow) 4.gpx else 16.gpx))
        if (!narrow) Row(Modifier.fillMaxWidth().padding(top = 4.gpx, bottom = 12.gpx)) {
            Text(stringResource(R.string.effort_receipt_col_item), Modifier.weight(1f), style = TextStyle(fontSize = 32.gsp, lineHeight = 40.gsp, fontWeight = FontWeight.Bold), color = c.textPrimary)
            Text(stringResource(R.string.effort_receipt_col_add), style = TextStyle(fontSize = 32.gsp, lineHeight = 40.gsp), color = c.textSecondary)
        }
        if (r.lines.isEmpty() && r.notes.isEmpty()) {
            Text(stringResource(R.string.effort_receipt_plain), Modifier.padding(vertical = 14.gpx), style = if (narrow) t.bodyM else t.bodyL.copy(fontWeight = FontWeight.Normal), color = c.textTertiary)
        }
        r.lines.forEach { line ->
            CgReceiptRow(
                line.label, line.detailText.orEmpty(), line.pctText, basisFirst(line.basisText), expanded = open == line.key,
                onClick = { onToggle(line.key) }, compact = narrow,
                extra = if (line.key == "congestion") r.footnoteText else null, hi = hi(line.key),
            )
        }
        // 정보 없음(날씨·정체) — 더하지 않은 줄(시안 arrive-nodata). 문구는 엔진 notes 그대로
        r.notes.forEach { note -> CgReceiptRow(note, "", "—", basis = null, expanded = false, onClick = {}, compact = narrow) }
        if (editable && midwaySub == null && ui.weatherPending) Text(stringResource(R.string.effort_weather_pending), Modifier.padding(vertical = 8.gpx), style = t.labelM, color = c.textTertiary)

        // 소계: 운전 시간 · 중간에 쉰 시간 [바꾸기] · 거리 → 합계
        CgDashedRule(Modifier.padding(top = if (narrow) 4.gpx else 20.gpx, bottom = if (narrow) 4.gpx else 18.gpx))
        CgReceiptSum(stringResource(R.string.effort_receipt_drive), r.driveText, compact = narrow, hi = hi("drive"))
        val restLabel = ui.restAnswers.firstOrNull { it.first == (trip.restAnswerMin ?: 0) }?.second ?: ""
        // v3 5-5: [바꾸기] 버튼 대신 값을 누르면 쉰 시간 시트가 다시 열림
        if (midwaySub == null) CgReceiptSum(
            stringResource(R.string.effort_receipt_rest), restLabel, compact = narrow, hi = hi("rest"),
            onValue = if (editable && ui.done == null) onChangeRest else null, valueLabel = stringResource(R.string.effort_receipt_rest_change_label),
        )
        r.distanceText?.let { CgReceiptSum(stringResource(R.string.effort_receipt_distance), it, compact = narrow) }
        CgReceiptSum(stringResource(R.string.effort_receipt_total), r.multiplierText, compact = narrow, total = true, hi = hi("total"))
        if (!narrow && open == null) CgBarcode(receiptCode(trip, r), Modifier.padding(top = 22.gpx))
    }
}

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
private fun OfferCard(ui: EffortUi, trip: OpenTrip, narrow: Boolean, onPick: (String) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val c = Chagok.colors
    val t = Chagok.type
    val head = if (narrow) t.titleL else t.headlineS
    val big = if (narrow) t.headlineS else t.headlineL
    val why = if (narrow) t.labelM else t.bodyM
    val btn = if (narrow) CgSize.M else CgSize.L
    val close: @Composable (Boolean) -> Unit = { filled ->
        // 정리하지 못함은 [홈으로](명세 F22·시안 arrive-unsettled), 그 밖은 [닫기]
        CgButton(stringResource(if (ui.unsettled) R.string.effort_home else R.string.close), onClose, Modifier.fillMaxWidth(), kind = if (filled) CgButtonKind.Filled else CgButtonKind.Basic, size = btn)
    }
    CgOfferCard(modifier, compact = narrow) {
        val done = ui.done
        val view = ui.proposal
        when {
            ui.unsettled -> {
                Text(stringResource(R.string.effort_offer_plain_head), style = head, color = c.textTertiary)
                Text(ui.receipt?.statusText ?: "", style = head, color = c.textPrimary)
                Filler(narrow)
                close(true)
            }
            done != null -> DoneBody(done, ui.items, narrow) { close(true) }
            trip.n < 2 -> {
                Text(stringResource(R.string.effort_arrive_solo), style = head, color = c.textTertiary)
                Text(stringResource(R.string.effort_offer_solo), style = head, color = c.textPrimary)
                Text(stringResource(R.string.effort_offer_solo_why), style = why, color = c.textSecondary)
                Filler(narrow)
                close(false)
            }
            view is ProposalView.A -> {
                Text(view.screen.titleText, style = head, color = c.textPrimary)
                Text(view.screen.mainText, style = big, color = c.textPrimary, maxLines = 1)
                Column(verticalArrangement = Arrangement.spacedBy(4.gpx)) {
                    Text(view.screen.basisText, style = why, color = c.textSecondary)
                    Text(view.screen.wageText, style = why, color = c.textTertiary)
                }
                Filler(narrow)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.gpx)) {
                    CgButton(stringResource(R.string.effort_offer_like), { onPick(CHOICE_ACCEPT) }, Modifier.weight(1f), size = btn)
                    CgButton(stringResource(R.string.close), onClose, Modifier.weight(1f), kind = CgButtonKind.Basic, size = btn)
                }
            }
            view is ProposalView.B -> {
                Text(view.screen.text, style = head, color = c.textPrimary)
                view.items.firstOrNull { it.key == view.screen.item }?.let { Text(stringResource(R.string.effort_offer_b_sub, ieyo(it.label)), style = why, color = c.textSecondary) }
                Filler(narrow)
                val opt: @Composable (Modifier, EffortItem) -> Unit = { m, item ->
                    CgOptionButton(item.examples.ifEmpty { listOf(item.label) }, isDefault = item.key == view.screen.item, onClick = { onPick(item.key) }, modifier = m, compact = narrow)
                }
                if (narrow) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.gpx)) { view.items.forEach { opt(Modifier.weight(1f), it) } }
                else Column(verticalArrangement = Arrangement.spacedBy(12.gpx)) { view.items.forEach { opt(Modifier.fillMaxWidth(), it) } }
                close(false)
            }
            else -> {
                // 제안 없음(운전자 몫이 커피 1인분의 절반보다 작음, 명세 F25 · A·B 같음 Q-07-04)
                Text(stringResource(R.string.effort_offer_plain_head), style = head, color = c.textTertiary)
                Text(stringResource(R.string.effort_offer_none), style = head, color = c.textPrimary)
                Text(stringResource(R.string.effort_offer_none_why), style = why, color = c.textSecondary)
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
    val head = if (narrow) t.titleL else t.headlineS
    val why = if (narrow) t.labelM else t.bodyM
    when (val v = done.proposal) {
        is ProposalView.A -> {
            Text(v.screen.titleText, style = head, color = c.textTertiary)
            Text(stringResource(R.string.effort_offer_a_done), style = head, color = c.textPrimary)
            Text(v.screen.mainText, style = if (narrow) t.titleM else t.titleL, color = c.textSecondary)
        }
        else -> {
            val phrase = items.firstOrNull { it.key == done.choice }?.phraseText ?: done.choice
            Text(stringResource(R.string.effort_offer_b_done_head), style = head, color = c.textTertiary)
            Text(stringResource(R.string.effort_offer_b_done, phrase), style = head, color = c.textPrimary)
            Text(stringResource(R.string.effort_offer_b_done_sub), style = why, color = c.textSecondary)
        }
    }
    Filler(narrow)
    Text(stringResource(R.string.effort_offer_done_note), style = why, color = c.textTertiary)
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
