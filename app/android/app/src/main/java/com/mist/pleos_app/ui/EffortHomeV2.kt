package com.mist.pleos_app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.mist.pleos_app.R
import com.mist.pleos_app.effort.CHOICE_ACCEPT
import com.mist.pleos_app.effort.CHOICE_CLOSE
import com.mist.pleos_app.effort.EffortPhase
import com.mist.pleos_app.effort.EffortRecord
import com.mist.pleos_app.effort.EffortUi
import com.mist.pleos_app.effort.OpenTrip
import com.mist.pleos_app.effort.clockText
import com.mist.pleos_app.effort.dayText
import com.mist.pleos_app.effort.etaClock
import com.mist.pleos_app.effort.euro
import com.mist.pleos_app.effort.recordSummary
import com.mist.pleos_app.ui.theme.Chagok
import com.mist.pleos_app.ui.theme.gpx
import kotlinx.coroutines.delay

/*
 * 홈(Design v2 `design/screens/home.html`, #75 명준): 위 바(왼쪽 아이콘 · 가운데 이름) / 인사 + 내 차 그림 + [출발하기 →]
 * 또는 진행 중 알약(누르면 짧은 하단 시트) / 최근 기록 3장(방금 끝난 기록은 맨 위 굵은 테두리).
 * 앱 시작 때 1.6초 시작 화면. 문구는 시안 글자 그대로(임시), 숫자는 저장된 기록·엔진 표기에서만.
 */

private val EaseIn = CubicBezierEasing(.32f, .72f, 0f, 1f)
private val Idle = CubicBezierEasing(.45f, 0f, .55f, 1f)

@Composable
internal fun EffortHomeScreen(
    ui: EffortUi,
    routeFailed: Boolean,
    onDepart: () -> Unit,
    onDiscard: () -> Unit,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
    onReceipt: () -> Unit,
    onArrive: () -> Unit,
) {
    val narrow = LocalNarrow.current
    val c = Chagok.colors
    val t = Chagok.type
    val trip = ui.openTrip
    var sheet by rememberSaveable { mutableStateOf(false) }
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(700, easing = EaseIn)) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // 위 바: 왼쪽 아이콘(설정 — 시안은 기록 전체, 기록 화면이 생기면 옮김) · 가운데 앱 이름
            Box(Modifier.fillMaxWidth().height(80.gpx)) {
                Box(
                    Modifier.align(Alignment.CenterStart).size(80.gpx).background(c.btnBasic, RoundedCornerShape(20.gpx))
                        .clickable(role = Role.Button, onClick = onSettings).semantics { contentDescription = "settings" },
                    contentAlignment = Alignment.Center,
                ) { DashedIcon(40.gpx) }
                Text(stringResource(R.string.effort_home_logo), Modifier.align(Alignment.Center),
                    style = (if (narrow) t.titleM else t.titleL).copy(fontWeight = FontWeight.ExtraBold, letterSpacing = t.titleL.fontSize * .08f), color = c.textPrimary)
            }
            // 인사 + 내 차 그림 + 출발하기 / 진행 중
            val first = ui.recent.isEmpty() && trip == null
            val (sub, head) = when {
                trip != null && trip.arrived -> stringResource(R.string.effort_home_arrived_sub, trip.destName) to stringResource(R.string.effort_home_arrived_head)
                trip != null -> stringResource(R.string.effort_home_progress_sub, euro(trip.destName)) to stringResource(R.string.effort_home_progress_head)
                first -> stringResource(R.string.effort_home_first_sub) to stringResource(R.string.effort_home_first_head)
                else -> stringResource(R.string.effort_home_sub) to stringResource(R.string.effort_home_head)
            }
            Row(
                Modifier.fillMaxWidth().then(if (narrow) Modifier.height(300.gpx) else Modifier.weight(1f))
                    .graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 24f * density },
                horizontalArrangement = Arrangement.spacedBy(if (narrow) 28.gpx else 64.gpx, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CarIllustration(if (narrow) 180.gpx else 300.gpx)
                Column(Modifier.widthIn(max = 820.gpx), verticalArrangement = Arrangement.spacedBy(14.gpx)) {
                    Text(sub, style = if (narrow) t.labelM else t.bodyM, color = c.textTertiary)
                    Text(head, style = if (narrow) t.headlineS else t.headlineL, color = c.textPrimary)
                    when {
                        trip == null -> Cta(stringResource(R.string.effort_home_go), narrow, onDepart)
                        trip.arrived -> Row(horizontalArrangement = Arrangement.spacedBy(12.gpx), verticalAlignment = Alignment.CenterVertically) {
                            Cta(stringResource(R.string.effort_receipt_open), narrow, onReceipt)
                            TextBtn(stringResource(R.string.effort_discard), primary = false, onClick = onDiscard)
                        }
                        else -> Column(verticalArrangement = Arrangement.spacedBy(8.gpx)) {
                            ProgressPill(trip, narrow) { sheet = true }
                            if (routeFailed) Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.effort_route_failed), style = t.bodyM, color = c.textSecondary)
                                TextBtn(stringResource(R.string.effort_route_retry), primary = true, onClick = onRetry)
                            }
                        }
                    }
                }
            }
            // 최근 기록
            Text(stringResource(R.string.effort_recent), Modifier.padding(start = 8.gpx, top = if (narrow) 28.gpx else 40.gpx, bottom = if (narrow) 10.gpx else 14.gpx),
                style = t.bodyS.copy(fontWeight = FontWeight.Normal), color = c.textTertiary)
            if (ui.recent.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().height(if (narrow) 200.gpx else 240.gpx).dashed(32.gpx, c.lineStrong).padding(horizontal = 32.gpx),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.gpx, Alignment.CenterVertically),
                ) {
                    Text(stringResource(R.string.effort_recent_empty), style = t.titleS, color = c.textPrimary)
                    Text(stringResource(R.string.effort_recent_empty_hint), style = t.bodyM, color = c.textTertiary)
                }
            } else if (narrow) {
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.gpx)) {
                    ui.recent.forEachIndexed { i, r -> RecordCardV2(r, ui, narrow, fresh = i == 0 && r.tripId == ui.justFinished, modifier = Modifier.fillMaxWidth()) }
                }
            } else {
                Row(Modifier.fillMaxWidth().height(240.gpx), horizontalArrangement = Arrangement.spacedBy(24.gpx)) {
                    ui.recent.forEachIndexed { i, r -> RecordCardV2(r, ui, narrow, fresh = i == 0 && r.tripId == ui.justFinished, modifier = Modifier.weight(1f).height(240.gpx)) }
                    repeat(3 - ui.recent.size.coerceAtMost(3)) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        // 진행 중 시트(딤 없음). 시안은 닫기만 — [도착했어요]·[그만두기](#68·#73)는 "확인 필요"로 같이 둔다
        if (sheet && trip != null && !trip.arrived) {
            ProgressSheet(
                trip, narrow,
                modifier = Modifier.align(Alignment.BottomCenter).then(if (narrow) Modifier.fillMaxWidth() else Modifier.width(760.gpx)),
                onArrive = { sheet = false; onArrive() }, onDiscard = { sheet = false; onDiscard() }, onClose = { sheet = false },
            )
        }
    }
}

/** 원 안의 내 차(1.8초 반복 위아래 5px). */
@Composable
internal fun CarIllustration(size: Dp) {
    val c = Chagok.colors
    val paths = remember { carPaths() }
    val inf = rememberInfiniteTransition(label = "idle")
    val y by inf.animateFloat(-1f, 1f, infiniteRepeatable(tween(1800, easing = Idle), RepeatMode.Reverse), label = "y")
    Box(Modifier.size(size).background(c.placeholder, CircleShape)) {
        Canvas(Modifier.fillMaxSize()) {
            val s = this.size.width / 300f
            val w = 200f * s
            val line = 4f * s
            drawRoundRect(c.lineStrong, Offset(60f * s, this.size.height - 88f * s - line), androidx.compose.ui.geometry.Size(this.size.width - 120f * s, line), CornerRadius(2f * s))
            val top = this.size.height - 92f * s - 82f * s + y * 5f * s
            drawCar(paths, Offset((this.size.width - w) / 2, top), w, c.textPrimary, c.surfaceCard, c.placeholderStrong, 0f)
        }
    }
}

/** [출발하기 →] = Basic Button L 채움(알약 + 화살표 원). */
@Composable
private fun Cta(text: String, narrow: Boolean, onClick: () -> Unit) {
    val c = Chagok.colors
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Row(
        Modifier.graphicsLayer { val s = if (pressed) .98f else 1f; scaleX = s; scaleY = s }
            .height(if (narrow) 80.gpx else 100.gpx).background(if (pressed) c.btnFilledPressed else c.btnFilled, RoundedCornerShape(999.gpx))
            .clickable(src, indication = null, role = Role.Button, onClick = onClick)
            .padding(start = if (narrow) 28.gpx else 40.gpx, end = if (narrow) 8.gpx else 12.gpx),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.gpx),
    ) {
        Text(text, style = if (narrow) Chagok.type.bodyL else Chagok.type.titleS, color = c.btnFilledText)
        Box(Modifier.size(if (narrow) 60.gpx else 76.gpx).background(c.btnFilledText.copy(alpha = .15f), CircleShape), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(32.gpx)) {
                val w = size.width
                drawLine(c.btnFilledText, Offset(w * .35f, w * .15f), Offset(w * .7f, w * .5f), strokeWidth = w * .12f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(c.btnFilledText, Offset(w * .7f, w * .5f), Offset(w * .35f, w * .85f), strokeWidth = w * .12f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
    }
}

/** 진행 중 알약: 깜빡이는 점(1.2초) · "진행 중 · 14:20 출발 · 함께 4명" ›. */
@Composable
private fun ProgressPill(trip: OpenTrip, narrow: Boolean, onClick: () -> Unit) {
    val c = Chagok.colors
    val inf = rememberInfiniteTransition(label = "dot")
    val a by inf.animateFloat(.25f, 1f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "a")
    Row(
        Modifier.height(if (narrow) 64.gpx else 80.gpx).background(c.surfaceCard, RoundedCornerShape(999.gpx))
            .border(2.gpx, c.textPrimary, RoundedCornerShape(999.gpx)).clickable(role = Role.Button, onClick = onClick)
            .padding(start = if (narrow) 20.gpx else 28.gpx, end = if (narrow) 18.gpx else 24.gpx),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.gpx),
    ) {
        Box(Modifier.size(14.gpx).graphicsLayer { alpha = a }.background(c.textPrimary, CircleShape))
        Text(stringResource(R.string.effort_home_progress_pill, clockText(trip.departedAt), trip.n), style = if (narrow) Chagok.type.bodyMB else Chagok.type.bodyL, color = c.textPrimary, maxLines = 1)
        Text("›", style = Chagok.type.bodyL, color = c.textTertiary)
    }
}

/** 진행 중 시트: 어디로 가는 중 · 출발 시각·인원·도착 예상 · 안내 · [도착했어요] [그만두기] · 닫기. */
@Composable
private fun ProgressSheet(trip: OpenTrip, narrow: Boolean, modifier: Modifier, onArrive: () -> Unit, onDiscard: () -> Unit, onClose: () -> Unit) {
    val c = Chagok.colors
    val t = Chagok.type
    val y = remember { Animatable(1.15f) }
    LaunchedEffect(Unit) { y.animateTo(0f, tween(380, easing = CubicBezierEasing(.2f, .7f, .2f, 1f))) }
    val shape = RoundedCornerShape(40.gpx)
    Column(
        modifier.graphicsLayer { translationY = y.value * size.height }
            .shadow(20.gpx, shape).background(c.surfaceCard, shape).border(2.gpx, c.lineStrong, shape)
            .padding(start = if (narrow) 26.gpx else 40.gpx, end = if (narrow) 26.gpx else 40.gpx, top = 16.gpx, bottom = 24.gpx),
        verticalArrangement = Arrangement.spacedBy(8.gpx),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).width(80.gpx).height(6.gpx).background(c.lineStrong, RoundedCornerShape(3.gpx)))
        Spacer(Modifier.height(8.gpx))
        Text(stringResource(R.string.effort_home_sheet_title, euro(trip.destName)), style = if (narrow) t.bodyL else t.titleM, color = c.textPrimary)
        val eta = etaClock(trip)
        Text(
            listOfNotNull(stringResource(R.string.effort_home_sheet_dep, clockText(trip.departedAt)), stringResource(R.string.effort_home_sheet_people, trip.n),
                eta?.let { stringResource(R.string.effort_home_sheet_eta, it) }).joinToString(" · "),
            style = t.bodyM, color = c.textSecondary,
        )
        Text(stringResource(R.string.effort_home_sheet_note), style = t.labelM, color = c.textTertiary)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextBtn(stringResource(R.string.effort_arrive_now), primary = true, onClick = onArrive)
            TextBtn(stringResource(R.string.effort_discard), primary = false, onClick = onDiscard)
            Spacer(Modifier.weight(1f))
            TextBtn(stringResource(R.string.close), primary = false, onClick = onClose)
        }
    }
}

/** 최근 기록 카드: 날짜(방금) · 목적지 · "2시간 10분 · 1.57배 · 4명" · 고른 것. 방금 끝난 기록은 굵은 테두리 3. */
@Composable
private fun RecordCardV2(r: EffortRecord, ui: EffortUi, narrow: Boolean, fresh: Boolean, modifier: Modifier) {
    val c = Chagok.colors
    val t = Chagok.type
    val shape = RoundedCornerShape(32.gpx)
    val rise = remember(r.tripId) { Animatable(if (fresh) 0f else 1f) }
    LaunchedEffect(r.tripId) { if (fresh) rise.animateTo(1f, tween(700, easing = EaseIn)) }
    Column(
        modifier.graphicsLayer { alpha = rise.value; translationY = (1f - rise.value) * 24f * density }
            .shadow(6.gpx, shape).background(c.surfaceCard, shape)
            .border(if (fresh) 3.gpx else 2.gpx, if (fresh) c.textPrimary else c.line, shape)
            .padding(horizontal = if (narrow) 28.gpx else 30.gpx, vertical = if (narrow) 18.gpx else 26.gpx),
        verticalArrangement = Arrangement.spacedBy(if (narrow) 6.gpx else 4.gpx),
    ) {
        Text(if (fresh) stringResource(R.string.effort_home_just_now) else dayText(r.date + "T"),
            style = if (fresh) t.bodyS.copy(fontWeight = FontWeight.ExtraBold) else t.bodyS.copy(fontWeight = FontWeight.Normal),
            color = if (fresh) c.textPrimary else c.textTertiary)
        if (!narrow) Spacer(Modifier.weight(1f))
        Text(r.destName, style = if (narrow) t.titleS else t.titleM, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(recordSummary(r, ui.constants), style = t.bodyM, color = c.textSecondary, maxLines = 1)
        Text(choiceText(r, ui), style = t.labelM, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun choiceText(r: EffortRecord, ui: EffortUi): String = when {
    r.choice == CHOICE_ACCEPT -> listOfNotNull(r.suggestion?.removePrefix("한 사람 ")?.let { stringResource(R.string.effort_home_rec_per, it) }, stringResource(R.string.effort_offer_like)).joinToString(" · ")
    r.choice == CHOICE_CLOSE -> stringResource(R.string.effort_home_rec_closed)
    else -> stringResource(R.string.effort_home_rec_item, ui.items.firstOrNull { it.key == r.choice }?.phraseText ?: r.choice)
}

@Composable
private fun TextBtn(text: String, primary: Boolean, onClick: () -> Unit) {
    val c = Chagok.colors
    Box(Modifier.heightIn(min = 64.gpx).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 18.gpx), contentAlignment = Alignment.Center) {
        Text(text, style = Chagok.type.bodyL.copy(fontWeight = if (primary) FontWeight.ExtraBold else FontWeight.Bold), color = if (primary) c.textPrimary else c.textTertiary)
    }
}

/** 에셋에 없는 아이콘 자리(점선 상자, 시안 cg-ico). */
@Composable
private fun DashedIcon(size: Dp) {
    val c = Chagok.colors
    Canvas(Modifier.size(size).graphicsLayer { alpha = .45f }) {
        drawRoundRect(c.textPrimary, cornerRadius = CornerRadius(this.size.width * .3f), style = Stroke(this.size.width * .075f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))))
    }
}

private fun Modifier.dashed(radius: Dp, color: androidx.compose.ui.graphics.Color): Modifier = this.drawBehind {
    drawRoundRect(color, cornerRadius = CornerRadius(radius.toPx()), style = Stroke(3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
}

/** 앱 시작 화면(1.6초): 원 안의 내 차 · 이름 · "준비하고 있어요" + 점 세 개. */
@Composable
internal fun EffortSplash(onDone: () -> Unit) {
    val c = Chagok.colors
    LaunchedEffect(Unit) { delay(1600); onDone() }
    val inf = rememberInfiniteTransition(label = "dots")
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.gpx, Alignment.CenterVertically)) {
        CarIllustration(240.gpx)
        Text(stringResource(R.string.effort_home_logo), style = Chagok.type.headlineL.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = Chagok.type.headlineL.fontSize * .08f), color = c.textPrimary)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.gpx)) {
            Text(stringResource(R.string.effort_home_loading), style = Chagok.type.bodyM, color = c.textTertiary)
            repeat(3) { i ->
                val a by inf.animateFloat(.25f, 1f, infiniteRepeatable(keyframes { durationMillis = 1000; .25f at 0; 1f at 300 + i * 150; .25f at 1000 }), label = "d$i")
                Box(Modifier.size(10.gpx).graphicsLayer { alpha = a }.background(c.textTertiary, CircleShape))
            }
        }
    }
}
