package com.mist.pleos_app.ui

import android.provider.Settings as AndroidSettings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
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
import com.mist.pleos_app.engine.EffortLine
import com.mist.pleos_app.engine.EffortReceipt
import com.mist.pleos_app.ui.components.CgButton
import com.mist.pleos_app.ui.components.CgButtonKind
import com.mist.pleos_app.ui.components.CgNotice
import com.mist.pleos_app.ui.theme.Chagok
import com.mist.pleos_app.ui.theme.gpx
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * 도착 화면(Design v2 `design/screens/arrive.html`, #75 명준): 왼쪽 영수증 + 오른쪽 제안 카드 640(가로),
 * 위 영수증 · 아래 제안(분할 840). 쉰 시간은 영수증이 나온 뒤 짧은 하단 시트로 묻는다.
 * 숫자·줄 문구는 모두 엔진 출력(effort.copy), 고정 문구는 strings.xml(임시, 명준 확정 전). 화면에서 계산하지 않는다.
 * 움직임 값은 HANDOFF_v2.md 3-2절: 영수증 출력 1.1초, 제안 카드 0.9초 뒤 0.45초, 시트 1.3초 뒤 0.38초, 바뀐 값 1.8초.
 */

private val EasePrint = CubicBezierEasing(.25f, .8f, .3f, 1f)
private val EaseRise = CubicBezierEasing(.2f, .7f, .2f, 1f)
private val EaseRot = CubicBezierEasing(.65f, 0f, .35f, 1f)

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
    val sheetY = remember(key) { Animatable(1.15f) }
    LaunchedEffect(key) {
        if (!played) {
            launch { paper.animateTo(0f, tween(1100, easing = EasePrint)) }
            launch { delay(900); offer.animateTo(1f, tween(450, easing = EaseRise)) }
            delay(1100)
            played = true
            if (needsRest) { delay(1300); sheet = true }
        } else if (needsRest) sheet = true
    }
    LaunchedEffect(sheet) {
        if (sheet) { if (reduce) sheetY.snapTo(0f) else { sheetY.snapTo(1.15f); sheetY.animateTo(0f, tween(380, easing = EaseRise)) } }
    }
    var open by rememberSaveable(key) { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (narrow) 16.gpx else 24.gpx)) {
            ArriveTitle(trip, narrow)
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
        // 처음엔 답을 묻고(needsRest), [바꾸기]로 다시 열 수 있다. 고른 뒤·정리하지 못함에는 없다
        if (sheet && ui.done == null && !ui.unsettled) {
            RestSheet(
                ui, current = trip.restAnswerMin ?: 0, narrow = narrow,
                modifier = Modifier
                    .align(if (narrow) Alignment.BottomCenter else Alignment.BottomEnd)
                    .then(if (narrow) Modifier.fillMaxWidth() else Modifier.width(640.gpx))
                    .graphicsLayer { translationY = sheetY.value * size.height },
                onPick = { min -> sheet = false; onRest(min) },
            )
        }
    }
}

/** 제목 줄(Title · Main 80): "○○에 도착했어요" + 함께 탄 사람(가로 창만). */
@Composable
private fun ArriveTitle(trip: OpenTrip, narrow: Boolean) {
    Row(Modifier.fillMaxWidth().height(80.gpx), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.gpx)) {
        Text(
            stringResource(R.string.effort_arrive_title, trip.destName), Modifier.weight(1f),
            style = if (narrow) Chagok.type.titleL else Chagok.type.headlineS, color = Chagok.colors.textPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (!narrow) Text(
            if (trip.n >= 2) stringResource(R.string.effort_arrive_people, trip.n) else stringResource(R.string.effort_arrive_solo),
            style = Chagok.type.labelM, color = Chagok.colors.textTertiary,
        )
    }
}

// ------------------------------------------------------------ 영수증 = 출력 종이(프린터 슬롯 + 지그재그 끝 + 이중선 + 바코드)
@Composable
private fun ReceiptColumn(
    trip: OpenTrip, r: EffortReceipt, ui: EffortUi, narrow: Boolean, reduce: Boolean,
    paperShift: Float, open: String?, onToggle: (String) -> Unit, onChangeRest: () -> Unit, modifier: Modifier,
) {
    val c = Chagok.colors
    val slotH = if (narrow) 40.gpx else 60.gpx
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
    Column(modifier) {
        // 프린터 슬롯
        Box(
            Modifier.fillMaxWidth().height(slotH).zIndex(2f)
                .shadow(6.gpx, RoundedCornerShape(if (narrow) 14.gpx else 20.gpx))
                .background(c.btnBasic, RoundedCornerShape(if (narrow) 14.gpx else 20.gpx))
                .background(c.bgPrimary.copy(alpha = 0.6f), RoundedCornerShape(if (narrow) 14.gpx else 20.gpx))
                .border(2.gpx, c.lineStrong, RoundedCornerShape(if (narrow) 14.gpx else 20.gpx))
                .padding(horizontal = if (narrow) 28.gpx else 44.gpx),
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.fillMaxWidth().height(if (narrow) 6.gpx else 8.gpx).background(c.textPrimary, RoundedCornerShape(4.gpx))) }
        // 종이: 슬롯 가운데에서 내려온다(슬롯 밖은 잘림)
        Box(
            Modifier.then(if (narrow) Modifier else Modifier.weight(1f))
                .padding(horizontal = if (narrow) 16.gpx else 36.gpx)
                .offset(y = -slotH / 2)
                .clipToBounds(),
        ) {
            Column(
                Modifier.graphicsLayer { translationY = paperShift * size.height }
                    .then(if (narrow) Modifier else Modifier.verticalScroll(rememberScrollState())),
            ) {
                Paper(trip, r, ui, narrow, open, onToggle, onChangeRest, changed, flash.value)
                Zigzag(Modifier.fillMaxWidth().height(12.gpx), c.surfaceCard)
            }
        }
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

@Composable
private fun Paper(
    trip: OpenTrip, r: EffortReceipt, ui: EffortUi, narrow: Boolean, open: String?,
    onToggle: (String) -> Unit, onChangeRest: () -> Unit, changed: Set<String>, flash: Float,
) {
    val c = Chagok.colors
    val t = Chagok.type
    val plainL = t.bodyL.copy(fontWeight = FontWeight.Normal)          // 시안: 400 30/38
    val rowK = if (narrow) t.bodyM else plainL
    val rowV = if (narrow) t.bodyM else plainL
    val pct = if (narrow) t.bodyL else t.titleS
    Column(
        Modifier.fillMaxWidth().background(c.surfaceCard)
            .padding(start = if (narrow) 28.gpx else 56.gpx, end = if (narrow) 28.gpx else 56.gpx, top = if (narrow) 24.gpx else 48.gpx, bottom = if (narrow) 10.gpx else 24.gpx),
    ) {
        Text(stringResource(R.string.effort_receipt_title), Modifier.fillMaxWidth(), style = if (narrow) t.titleS else t.titleL, color = c.textPrimary, textAlign = TextAlign.Center)
        if (!narrow) Text(
            stringResource(R.string.effort_receipt_sub, trip.destName, dayText(trip.departedAt)), Modifier.fillMaxWidth(),
            style = t.bodyM, color = c.textTertiary, textAlign = TextAlign.Center,
        )
        DoubleRule(Modifier.padding(top = if (narrow) 8.gpx else 24.gpx, bottom = if (narrow) 4.gpx else 16.gpx))
        if (!narrow) Row(Modifier.fillMaxWidth().padding(top = 4.gpx, bottom = 10.gpx)) {
            Text(stringResource(R.string.effort_receipt_col_item), Modifier.weight(1f), style = t.bodyL, color = c.textPrimary)
            Text(stringResource(R.string.effort_receipt_col_add), style = plainL, color = c.textSecondary)
        }
        if (r.lines.isEmpty() && r.notes.isEmpty()) {
            Text(stringResource(R.string.effort_receipt_plain), Modifier.padding(vertical = 14.gpx), style = rowK, color = c.textTertiary)
        }
        r.lines.forEach { line ->
            ReceiptRow(
                line, narrow, rowK, rowV, pct, expanded = open == line.key,
                extra = if (line.key == "congestion") r.footnoteText else null,
                hi = if (line.key in changed) flash else 0f, onClick = { onToggle(line.key) },
            )
        }
        // 정보 없음(날씨·정체) — 더하지 않은 줄(시안 arrive-nodata). 문구는 엔진 notes 그대로
        r.notes.forEach { note ->
            Row(Modifier.fillMaxWidth().heightIn(min = if (narrow) 48.gpx else 64.gpx), verticalAlignment = Alignment.CenterVertically) {
                Text(note, Modifier.weight(1f), style = rowV, color = c.textSecondary)
                Text("—", style = pct, color = c.textTertiary)
                Spacer(Modifier.width(if (narrow) 28.gpx else 42.gpx))
            }
        }
        if (ui.weatherPending) Text(stringResource(R.string.effort_weather_pending), Modifier.padding(vertical = 8.gpx), style = t.labelM, color = c.textTertiary)

        // 소계: 운전 시간 · 중간에 쉰 시간 [바꾸기] · 거리
        DashedRule(Modifier.padding(top = if (narrow) 4.gpx else 20.gpx, bottom = if (narrow) 4.gpx else 18.gpx))
        SumRow(stringResource(R.string.effort_receipt_drive), r.driveText, narrow, hi = if ("drive" in changed) flash else 0f)
        val restLabel = ui.restAnswers.firstOrNull { it.first == (trip.restAnswerMin ?: 0) }?.second ?: ""
        SumRow(stringResource(R.string.effort_receipt_rest), restLabel, narrow, hi = if ("rest" in changed) flash else 0f) {
            if (ui.done == null) SmallButton(stringResource(R.string.effort_receipt_rest_change), onChangeRest)
        }
        r.distanceText?.let { SumRow(stringResource(R.string.effort_receipt_distance), it, narrow) }
        SumRow(stringResource(R.string.effort_receipt_total), r.multiplierText, narrow, total = true, hi = if ("total" in changed) flash else 0f)
        if (!narrow && open == null) {
            Column(Modifier.fillMaxWidth().padding(top = 22.gpx), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.gpx)) {
                Barcode(Modifier.fillMaxWidth().height(56.gpx), c.textPrimary)
                Text(receiptCode(trip, r), style = t.labelM.copy(letterSpacing = t.labelM.fontSize * 0.1f), color = c.textPrimary)
            }
        }
    }
}

@Composable
private fun ReceiptRow(
    line: EffortLine, narrow: Boolean, k: TextStyle, v: TextStyle, pct: TextStyle,
    expanded: Boolean, extra: String?, hi: Float, onClick: () -> Unit,
) {
    val c = Chagok.colors
    val canOpen = line.basisText != null
    Column(Modifier.fillMaxWidth().background(c.placeholderStrong.copy(alpha = c.placeholderStrong.alpha * hi), RoundedCornerShape(8.gpx))) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = if (narrow) 54.gpx else 64.gpx)
                .then(if (canOpen) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.gpx),
        ) {
            Text(line.label, Modifier.width(if (narrow) 140.gpx else 230.gpx), style = k, color = c.textPrimary)
            Text(line.detailText.orEmpty(), Modifier.weight(1f), style = v, color = c.textSecondary)
            Text(line.pctText, Modifier.width(if (narrow) 84.gpx else 120.gpx), style = pct, color = c.textPrimary, textAlign = TextAlign.End, maxLines = 1)
            Chevron(Modifier.size(if (narrow) 18.gpx else 26.gpx), up = expanded, visible = canOpen, color = c.textTertiary)
        }
        if (expanded && canOpen) {
            Column(
                Modifier.fillMaxWidth().padding(bottom = 10.gpx).background(c.placeholder, RoundedCornerShape(16.gpx)).padding(horizontal = 20.gpx, vertical = 14.gpx),
                verticalArrangement = Arrangement.spacedBy(4.gpx),
            ) {
                // 정체 구간: 경로 기준 한 줄(굵게) + 근거. 그 밖: 근거 한 줄(AC-F23-4)
                extra?.let { Text(it, style = if (narrow) Chagok.type.bodyMB else Chagok.type.bodyL, color = c.textPrimary) }
                Text(line.basisText!!, style = if (extra != null) Chagok.type.labelM else Chagok.type.bodyM, color = if (extra != null) c.textTertiary else c.textSecondary)
            }
        }
    }
}

@Composable
private fun SumRow(label: String, value: String, narrow: Boolean, total: Boolean = false, hi: Float = 0f, trailing: @Composable () -> Unit = {}) {
    val c = Chagok.colors
    val t = Chagok.type
    val plain = (if (narrow) t.bodyM else t.bodyL.copy(fontWeight = FontWeight.Normal))
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (narrow) 46.gpx else 52.gpx)
            .background(c.placeholderStrong.copy(alpha = c.placeholderStrong.alpha * hi), RoundedCornerShape(8.gpx)),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.gpx),
    ) {
        Text(label, Modifier.weight(1f), style = if (total) (if (narrow) t.bodyL else t.titleS) else plain, color = c.textPrimary)
        Text(value, style = if (total) (if (narrow) t.titleS else t.titleM) else (if (narrow) t.bodyL else t.titleS), color = c.textPrimary, maxLines = 1)
        trailing()
    }
}

@Composable
private fun SmallButton(text: String, onClick: () -> Unit) {
    val c = Chagok.colors
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Box(
        Modifier.defaultMinSize(minHeight = 48.gpx).background(if (pressed) c.btnBasicPressed else c.btnBasic, RoundedCornerShape(16.gpx))
            .clickable(src, indication = null, role = Role.Button, onClick = onClick).padding(horizontal = 18.gpx, vertical = 8.gpx),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Chagok.type.labelM, color = c.textPrimary) }
}

@Composable
private fun DoubleRule(modifier: Modifier) {
    val c = Chagok.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.gpx)) {
        Box(Modifier.fillMaxWidth().height(2.gpx).background(c.textPrimary))
        Box(Modifier.fillMaxWidth().height(2.gpx).background(c.textPrimary))
    }
}

@Composable
private fun DashedRule(modifier: Modifier) {
    val color = Chagok.colors.lineStrong
    Canvas(modifier.fillMaxWidth().height(2.gpx)) {
        drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = size.height, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
    }
}

/** 영수증 끝 지그재그(24px 간격 톱니). */
@Composable
private fun Zigzag(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val step = size.height * 2
        val p = Path().apply {
            moveTo(0f, 0f)
            var x = 0f
            while (x < size.width) { lineTo(x + step / 2, size.height); lineTo(x + step, 0f); x += step }
            close()
        }
        drawPath(p, color)
    }
}

/** 바코드(장식). 23px마다 같은 막대 무늬. */
@Composable
private fun Barcode(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val unit = size.height / 56f
        val bars = listOf(0f to 3f, 6f to 7f, 11f to 15f, 17f to 18f)
        var x = 0f
        while (x < size.width) {
            bars.forEach { (a, b) -> if (x + a * unit < size.width) drawRect(color, Offset(x + a * unit, 0f), androidx.compose.ui.geometry.Size(((b - a) * unit).coerceAtMost(size.width - x - a * unit), size.height)) }
            x += 23f * unit
        }
    }
}

@Composable
private fun Chevron(modifier: Modifier, up: Boolean, visible: Boolean, color: Color) {
    Canvas(modifier) {
        if (!visible) return@Canvas
        val w = size.width; val h = size.height
        val sw = w / 6f
        val y0 = if (up) h * 0.65f else h * 0.35f
        val y1 = if (up) h * 0.35f else h * 0.65f
        drawLine(color, Offset(w * 0.15f, y0), Offset(w * 0.5f, y1), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.5f, y1), Offset(w * 0.85f, y0), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

// ------------------------------------------------------------ 제안 카드(640 고정 / 분할은 아래 전체)
@Composable
private fun OfferCard(ui: EffortUi, trip: OpenTrip, narrow: Boolean, onPick: (String) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val c = Chagok.colors
    val t = Chagok.type
    val shape = RoundedCornerShape(32.gpx)
    Column(
        modifier.background(c.surfaceCard, shape).border(2.gpx, c.lineStrong, shape)
            .padding(horizontal = if (narrow) 24.gpx else 40.gpx, vertical = if (narrow) 22.gpx else 40.gpx),
        verticalArrangement = Arrangement.spacedBy(if (narrow) 12.gpx else 20.gpx),
    ) {
        val head = if (narrow) t.titleL else t.headlineS
        val big = if (narrow) t.headlineS else t.headlineL
        val why = if (narrow) t.labelM else t.bodyM
        val done = ui.done
        val view = ui.proposal
        when {
            ui.unsettled -> {
                Text(stringResource(R.string.effort_offer_plain_head), style = head, color = c.textTertiary)
                Text(ui.receipt?.statusText ?: "", style = if (narrow) t.titleL else t.headlineS, color = c.textPrimary)
                Filler(narrow)
                OfferButton(stringResource(R.string.close), filled = true, onClick = onClose, modifier = Modifier.fillMaxWidth(), narrow = narrow)
            }
            done != null -> DoneBody(done, ui.items, narrow, onClose)
            trip.n < 2 -> {
                Text(stringResource(R.string.effort_arrive_solo), style = head, color = c.textTertiary)
                Text(stringResource(R.string.effort_offer_solo), style = if (narrow) t.titleL else t.headlineS, color = c.textPrimary)
                Text(stringResource(R.string.effort_offer_solo_why), style = why, color = c.textSecondary)
                Filler(narrow)
                OfferButton(stringResource(R.string.close), filled = false, onClick = onClose, modifier = Modifier.fillMaxWidth(), narrow = narrow)
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
                    OfferButton(stringResource(R.string.effort_offer_like), filled = true, onClick = { onPick(CHOICE_ACCEPT) }, modifier = Modifier.weight(1f), narrow = narrow)
                    OfferButton(stringResource(R.string.close), filled = false, onClick = onClose, modifier = Modifier.weight(1f), narrow = narrow)
                }
            }
            view is ProposalView.B -> {
                Text(view.screen.text, style = if (narrow) t.titleL else t.headlineS, color = c.textPrimary)
                val def = view.items.firstOrNull { it.key == view.screen.item }
                def?.let { Text(stringResource(R.string.effort_offer_b_sub, ieyo(it.label)), style = why, color = c.textSecondary) }
                Filler(narrow)
                val opts: @Composable (Modifier, EffortItem) -> Unit = { m, item ->
                    OptionButton(item, isDefault = item.key == view.screen.item, narrow = narrow, onClick = { onPick(item.key) }, modifier = m)
                }
                if (narrow) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.gpx)) { view.items.forEach { opts(Modifier.weight(1f), it) } }
                else Column(verticalArrangement = Arrangement.spacedBy(12.gpx)) { view.items.forEach { opts(Modifier.fillMaxWidth(), it) } }
                OfferButton(stringResource(R.string.close), filled = false, onClick = onClose, modifier = Modifier.fillMaxWidth(), narrow = narrow)
            }
            else -> {
                // 방식 B 제안 없음(운전자 몫이 커피 1인분의 절반보다 작음, 명세 F25)
                Text(stringResource(R.string.effort_offer_plain_head), style = head, color = c.textTertiary)
                Text(stringResource(R.string.effort_offer_none), style = if (narrow) t.titleL else t.headlineS, color = c.textPrimary)
                Text(stringResource(R.string.effort_offer_none_why), style = why, color = c.textSecondary)
                Filler(narrow)
                OfferButton(stringResource(R.string.close), filled = false, onClick = onClose, modifier = Modifier.fillMaxWidth(), narrow = narrow)
            }
        }
    }
}

/** 고른 뒤: "기록에 남겼어요" + [닫기]. 더 요구하지 않는다(AC-F26-1). */
@Composable
private fun ColumnScope.DoneBody(done: ArriveDone, items: List<EffortItem>, narrow: Boolean, onClose: () -> Unit) {
    val c = Chagok.colors
    val t = Chagok.type
    val head = if (narrow) t.titleL else t.headlineS
    val why = if (narrow) t.labelM else t.bodyM
    when (val v = done.proposal) {
        is ProposalView.A -> {
            Text(v.screen.titleText, style = head, color = c.textTertiary)
            Text(stringResource(R.string.effort_offer_a_done), style = if (narrow) t.titleL else t.headlineS, color = c.textPrimary)
            Text(v.screen.mainText, style = if (narrow) t.titleM else t.titleL, color = c.textSecondary)
        }
        else -> {
            val phrase = items.firstOrNull { it.key == done.choice }?.phraseText ?: done.choice
            Text(stringResource(R.string.effort_offer_b_done_head), style = head, color = c.textTertiary)
            Text(stringResource(R.string.effort_offer_b_done, phrase), style = if (narrow) t.titleL else t.headlineS, color = c.textPrimary)
            Text(stringResource(R.string.effort_offer_b_done_sub), style = why, color = c.textSecondary)
        }
    }
    Filler(narrow)
    Text(stringResource(R.string.effort_offer_done_note), style = why, color = c.textTertiary)
    OfferButton(stringResource(R.string.close), filled = true, onClick = onClose, modifier = Modifier.fillMaxWidth(), narrow = narrow)
}

/** 가로 창은 버튼을 카드 아래로 민다. 분할은 쌓기만. */
@Composable
private fun ColumnScope.Filler(narrow: Boolean) { if (!narrow) Spacer(Modifier.weight(1f)) }

/** 제안 카드 버튼 = Basic Button L 100(분할 80). */
@Composable
private fun OfferButton(text: String, filled: Boolean, onClick: () -> Unit, modifier: Modifier, narrow: Boolean) {
    val c = Chagok.colors
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val bg = if (filled) (if (pressed) c.btnFilledPressed else c.btnFilled) else (if (pressed) c.btnBasicPressed else c.btnBasic)
    val shape = RoundedCornerShape(if (narrow) 24.gpx else 32.gpx)
    Box(
        modifier.height(if (narrow) 80.gpx else 100.gpx).background(bg, shape)
            .clickable(src, indication = null, role = Role.Button, onClick = onClick).padding(horizontal = 38.gpx),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = if (narrow) Chagok.type.bodyL else Chagok.type.titleS, color = if (filled) c.btnFilledText else c.textPrimary, maxLines = 1) }
}

/** 방식 B 항목 = Basic Button L 100(고르면 바로 기록). 기본값만 채움. 공동 경비는 예시가 2초마다 바뀐다. */
@Composable
private fun OptionButton(item: EffortItem, isDefault: Boolean, narrow: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = Chagok.colors
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val bg = if (isDefault) (if (pressed) c.btnFilledPressed else c.btnFilled) else (if (pressed) c.btnBasicPressed else c.btnBasic)
    val fg = if (isDefault) c.btnFilledText else c.textPrimary
    val shape = RoundedCornerShape(if (narrow) 24.gpx else 32.gpx)
    val words = item.examples.ifEmpty { listOf(item.label) }
    var i by remember(item.key) { mutableIntStateOf(0) }
    if (words.size > 1) LaunchedEffect(item.key) { while (true) { delay(2000); i = (i + 1) % words.size } }
    Box(
        modifier.heightIn(min = if (narrow) 96.gpx else 100.gpx).background(bg, shape)
            .clickable(src, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (narrow) 20.gpx else 36.gpx, vertical = 8.gpx),
        contentAlignment = Alignment.CenterStart,
    ) {
        AnimatedContent(
            targetState = words[i % words.size],
            transitionSpec = {
                slideInVertically(tween(320, easing = EaseRot)) { it } togetherWith slideOutVertically(tween(320, easing = EaseRot)) { -it }
            },
            label = "item-word",
        ) { w -> Text(w, style = if (narrow) Chagok.type.bodyL else Chagok.type.titleS, color = fg, maxLines = 1) }
    }
}

// ------------------------------------------------------------ 쉰 시간 시트(딤 없음, 고르면 닫힘, 영수증을 가리지 않음)
@Composable
private fun RestSheet(ui: EffortUi, current: Int, narrow: Boolean, modifier: Modifier, onPick: (Int) -> Unit) {
    val c = Chagok.colors
    val t = Chagok.type
    val shape = RoundedCornerShape(32.gpx)
    Column(
        modifier.shadow(20.gpx, shape).background(c.surfaceCard, shape).border(2.gpx, c.lineStrong, shape)
            .padding(start = if (narrow) 24.gpx else 40.gpx, end = if (narrow) 24.gpx else 40.gpx, top = if (narrow) 14.gpx else 16.gpx, bottom = if (narrow) 24.gpx else 36.gpx),
        verticalArrangement = Arrangement.spacedBy(if (narrow) 14.gpx else 20.gpx),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).width(80.gpx).height(6.gpx).background(c.lineStrong, RoundedCornerShape(3.gpx)))
        Column(verticalArrangement = Arrangement.spacedBy(4.gpx)) {
            Text(stringResource(R.string.effort_rest_sheet_title), style = if (narrow) t.bodyL else t.titleM, color = c.textPrimary)
            Text(stringResource(R.string.effort_rest_sheet_sub), style = t.bodyM, color = c.textSecondary)
        }
        ui.restAnswers.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.gpx)) {
                row.forEach { (min, label) -> RestChip(label, selected = min == current, narrow = narrow, onClick = { onPick(min) }, modifier = Modifier.weight(1f)) }
            }
        }
    }
}

/** Toggle Button M 80. 켜짐 = 카드 면 + 테두리 3(흑백안). */
@Composable
private fun RestChip(text: String, selected: Boolean, narrow: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = Chagok.colors
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val shape = RoundedCornerShape(24.gpx)
    Box(
        modifier.height(if (narrow) 72.gpx else 80.gpx)
            .background(if (selected) c.surfaceCard else if (pressed) c.btnBasicPressed else c.btnBasic, shape)
            .then(if (selected) Modifier.border(3.gpx, c.textPrimary, shape) else Modifier)
            .clickable(src, indication = null, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = if (narrow) Chagok.type.bodyMB else Chagok.type.bodyL, color = c.textPrimary, maxLines = 1) }
}

/** 움직임 줄이기(개발자 옵션 애니메이션 배율 0): 연출을 끄고 끝 상태로(HANDOFF_v2 3-2). */
@Composable
internal fun rememberReduceMotion(): Boolean {
    val ctx = LocalContext.current
    return remember {
        runCatching { AndroidSettings.Global.getFloat(ctx.contentResolver, AndroidSettings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}
