package com.mist.pleos_app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.mist.pleos_app.R
import com.mist.pleos_app.effort.CHOICE_ACCEPT
import com.mist.pleos_app.effort.CHOICE_CLOSE
import com.mist.pleos_app.effort.EffortRecord
import com.mist.pleos_app.effort.EffortUi
import com.mist.pleos_app.effort.OpenTrip
import com.mist.pleos_app.effort.clockText
import com.mist.pleos_app.effort.dayText
import com.mist.pleos_app.effort.etaClock
import com.mist.pleos_app.effort.euro
import com.mist.pleos_app.effort.recordSummary
import com.mist.pleos_app.ui.components.CgBottomSheet
import com.mist.pleos_app.ui.components.CgCta
import com.mist.pleos_app.ui.components.CgEmpty
import com.mist.pleos_app.ui.components.CgIcon
import com.mist.pleos_app.ui.components.CgIconButton
import com.mist.pleos_app.ui.components.CgIllustrationCircle
import com.mist.pleos_app.ui.components.CgLoadingDots
import com.mist.pleos_app.ui.components.CgProgressPill
import com.mist.pleos_app.ui.components.CgRecordCard
import com.mist.pleos_app.ui.components.CgTextButton
import com.mist.pleos_app.ui.components.CgTitleBar
import com.mist.pleos_app.ui.theme.Chagok
import com.mist.pleos_app.ui.theme.gpx
import kotlinx.coroutines.delay

/*
 * 홈(Design v2 `design/screens/home.html`, #75 명준). 부품은 ui/components(Cg*)만 조립한다.
 * 위 바(왼쪽 아이콘 · 가운데 이름) / 인사 + 내 차 그림 + [출발하기 →] 또는 진행 중 알약(누르면 짧은 하단 시트) /
 * 최근 기록 3장(방금 끝난 기록은 맨 위 굵은 테두리). 앱 시작 때 1.6초 시작 화면. 숫자는 저장된 기록·엔진 표기에서만.
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
            CgTitleBar(
                stringResource(R.string.effort_home_logo), centered = true,
                titleStyle = (if (narrow) t.titleM else t.titleL).copy(fontWeight = FontWeight.ExtraBold, letterSpacing = t.titleL.fontSize * .08f),
                leading = { CgIconButton(stringResource(R.string.screen_settings), CgIcon.Placeholder, onSettings) },
            )
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
                        trip == null -> CgCta(stringResource(R.string.effort_home_go), onDepart, compact = narrow)
                        trip.arrived -> Row(horizontalArrangement = Arrangement.spacedBy(12.gpx), verticalAlignment = Alignment.CenterVertically) {
                            CgCta(stringResource(R.string.effort_receipt_open), onReceipt, compact = narrow)
                            CgTextButton(stringResource(R.string.effort_discard), onDiscard, compact = true)
                        }
                        else -> Column(verticalArrangement = Arrangement.spacedBy(8.gpx)) {
                            CgProgressPill(stringResource(R.string.effort_home_progress_pill, clockText(trip.departedAt), trip.n), { sheet = true }, compact = narrow)
                            if (routeFailed) Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.effort_route_failed), style = t.bodyM, color = c.textSecondary)
                                CgTextButton(stringResource(R.string.effort_route_retry), onRetry, primary = true, compact = true)
                            }
                        }
                    }
                }
            }
            // 최근 기록
            Text(stringResource(R.string.effort_recent), Modifier.padding(start = 8.gpx, top = if (narrow) 28.gpx else 40.gpx, bottom = if (narrow) 10.gpx else 14.gpx),
                style = t.bodyS.copy(fontWeight = FontWeight.Normal), color = c.textTertiary)
            if (ui.recent.isEmpty()) {
                CgEmpty(stringResource(R.string.effort_recent_empty), stringResource(R.string.effort_recent_empty_hint), Modifier.fillMaxWidth().height(if (narrow) 200.gpx else 240.gpx))
            } else if (narrow) {
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.gpx)) {
                    ui.recent.forEachIndexed { i, r -> RecordCard(r, ui, narrow, fresh = i == 0 && r.tripId == ui.justFinished, modifier = Modifier.fillMaxWidth()) }
                }
            } else {
                Row(Modifier.fillMaxWidth().height(240.gpx), horizontalArrangement = Arrangement.spacedBy(24.gpx)) {
                    ui.recent.forEachIndexed { i, r -> RecordCard(r, ui, narrow, fresh = i == 0 && r.tripId == ui.justFinished, modifier = Modifier.weight(1f).height(240.gpx)) }
                    repeat(3 - ui.recent.size.coerceAtMost(3)) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        // 진행 중 시트(딤 없음). 시안은 닫기만 — [도착했어요]·[그만두기](#68·#73)는 "확인 필요"로 같이 둔다
        if (sheet && trip != null && !trip.arrived) {
            CgBottomSheet(
                Modifier.align(Alignment.BottomCenter).then(if (narrow) Modifier.fillMaxWidth() else Modifier.width(760.gpx)), compact = narrow,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.gpx)) {
                    Text(stringResource(R.string.effort_home_sheet_title, euro(trip.destName)), style = if (narrow) t.bodyL else t.titleM, color = c.textPrimary)
                    val eta = etaClock(trip)
                    Text(
                        listOfNotNull(stringResource(R.string.effort_home_sheet_dep, clockText(trip.departedAt)), stringResource(R.string.effort_home_sheet_people, trip.n),
                            eta?.let { stringResource(R.string.effort_home_sheet_eta, it) }).joinToString(" · "),
                        style = t.bodyM, color = c.textSecondary,
                    )
                    Text(stringResource(R.string.effort_home_sheet_note), style = t.labelM, color = c.textTertiary)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    CgTextButton(stringResource(R.string.effort_arrive_now), { sheet = false; onArrive() }, primary = true, compact = true)
                    CgTextButton(stringResource(R.string.effort_discard), { sheet = false; onDiscard() }, compact = true)
                    Spacer(Modifier.weight(1f))
                    CgTextButton(stringResource(R.string.close), { sheet = false }, compact = true)
                }
            }
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
    CgIllustrationCircle(size) {
        Canvas(Modifier.fillMaxSize()) {
            val s = this.size.width / 300f
            val w = 200f * s
            val line = 4f * s
            drawRoundRect(c.lineStrong, Offset(60f * s, this.size.height - 88f * s - line), Size(this.size.width - 120f * s, line), CornerRadius(2f * s))
            val top = this.size.height - 92f * s - 82f * s + y * 5f * s
            drawCar(paths, Offset((this.size.width - w) / 2, top), w, c.textPrimary, c.surfaceCard, c.placeholderStrong, 0f)
        }
    }
}

/** 최근 기록 카드: 날짜(방금) · 목적지 · "2시간 10분 · 1.57배 · 4명" · 고른 것. */
@Composable
private fun RecordCard(r: EffortRecord, ui: EffortUi, narrow: Boolean, fresh: Boolean, modifier: Modifier) {
    val note = when {
        r.choice == CHOICE_ACCEPT -> listOfNotNull(r.suggestion?.removePrefix("한 사람 ")?.let { stringResource(R.string.effort_home_rec_per, it) }, stringResource(R.string.effort_offer_like)).joinToString(" · ")
        r.choice == CHOICE_CLOSE -> stringResource(R.string.effort_home_rec_closed)
        else -> stringResource(R.string.effort_home_rec_item, ui.items.firstOrNull { it.key == r.choice }?.phraseText ?: r.choice)
    }
    CgRecordCard(
        if (fresh) stringResource(R.string.effort_home_just_now) else dayText(r.date + "T"),
        r.destName, recordSummary(r, ui.constants), note, modifier, fresh = fresh, compact = narrow, key = r.tripId,
    )
}

/** 앱 시작 화면(1.6초): 원 안의 내 차 · 이름 · "준비하고 있어요" + 점 세 개. */
@Composable
internal fun EffortSplash(onDone: () -> Unit) {
    val c = Chagok.colors
    LaunchedEffect(Unit) { delay(1600); onDone() }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.gpx, Alignment.CenterVertically)) {
        CarIllustration(240.gpx)
        Text(stringResource(R.string.effort_home_logo), style = Chagok.type.headlineL.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = Chagok.type.headlineL.fontSize * .08f), color = c.textPrimary)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.gpx)) {
            Text(stringResource(R.string.effort_home_loading), style = Chagok.type.bodyM, color = c.textTertiary)
            CgLoadingDots(c.textTertiary)
        }
    }
}
