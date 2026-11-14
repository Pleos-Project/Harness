package com.mist.pleos_app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.mist.pleos_app.R
import com.mist.pleos_app.effort.CalcSay
import com.mist.pleos_app.effort.EffortUi
import com.mist.pleos_app.effort.REVIEW_GAP_MS
import com.mist.pleos_app.effort.REVIEW_OFF_MS
import com.mist.pleos_app.effort.REVIEW_ON_MS
import com.mist.pleos_app.effort.ReviewCast
import com.mist.pleos_app.effort.ReviewFx
import com.mist.pleos_app.effort.ReviewIcon
import com.mist.pleos_app.effort.ReviewItem
import com.mist.pleos_app.effort.ReviewMotion
import com.mist.pleos_app.effort.ReviewPose
import com.mist.pleos_app.effort.hhmm
import com.mist.pleos_app.engine.driveText
import com.mist.pleos_app.engine.roundHalfUp
import com.mist.pleos_app.ui.components.CgIcon
import com.mist.pleos_app.ui.components.CgIconMark
import com.mist.pleos_app.ui.components.CgLoadingDots
import com.mist.pleos_app.ui.components.CgPlaceLine
import com.mist.pleos_app.ui.theme.Chagok
import com.mist.pleos_app.ui.theme.ChagokColors
import com.mist.pleos_app.ui.theme.ChagokMotion
import com.mist.pleos_app.ui.theme.MinTouch
import com.mist.pleos_app.ui.theme.gpx
import com.mist.pleos_app.ui.theme.gsp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min

/*
 * 오늘 운전 돌아보기(명세 v0.6 F29, PRD v2.0 7-5, #162 HANDOFF_v4 6절 · 9절, 아티팩트 v188 rv-start ~ rv-solo).
 * 왼쪽 노고 캐릭터(표정 4장 · BX 'NOGO 캐릭터 모션'), 오른쪽 배경 없는 기준 5줄을 약 10초 동안 하나씩 센다. 금액 · 배수 · 퍼센트 없음.
 * 다 세면 [운전자 챙겨주기] · [다음에](혼자 · 짧은 주행은 [영수증 보기] 하나, AC-F29-3). 시간표는 6-4, 곡선은 9절:
 * 나타남 · 자리 잡음 = ChagokMotion.Out(rv-out), 화면 안 오감 · 반복 = ChagokMotion.InOut(rv-io), 진행 표시 = linear.
 * 상태는 줄 차례(코루틴 delay)와 애니메이션 API로만 바꾼다(폴링 · 주기 타이머 없음). 값은 엔진 결과, 숫자가 올라가는 사이 값만 여기서 그린다.
 */

private val Lime = Color(0xFFC8F04A)
private val LimeInk = Color(0xFF242135)
private val Out = ChagokMotion.Out
private val InOut = ChagokMotion.InOut

/** 몸짓 한 가지(시안 .rvc 클래스): 숨쉬기(늘) · 갸웃(slump, 끝 모양 유지) · 찌그러졌다 펴기(dip) · 점프(jump). */
private enum class Body { BREATHE, SLUMP, DIP, JUMP }

/** 줄 상태(시안 .rvw): 아직 · 세는 중(now) · 있음(on) · 없음(off). */
private enum class RowState { WAIT, NOW, ON, OFF }

/** CSS 키프레임처럼 구간마다 곡선을 다시 적용해 값을 낸다. stops = (위치 0..1, 값). */
private fun keyframes(p: Float, stops: List<Pair<Float, Float>>, e: Easing): Float {
    if (p <= stops.first().first) return stops.first().second
    for (i in 1 until stops.size) {
        val (a, va) = stops[i - 1]; val (b, vb) = stops[i]
        if (p <= b) return va + (vb - va) * e.transform(((p - a) / (b - a)).coerceIn(0f, 1f))
    }
    return stops.last().second
}

/** 끝에서 느려짐(cubic out, 시안 `1 - (1 - q)^3`). 값 세기 · 예상 시각 넘어감. */
private fun cubicOut(q: Float): Float { val r = 1f - q.coerceIn(0f, 1f); return 1f - r * r * r }

private fun bodyOf(m: ReviewMotion) = when (m) { ReviewMotion.SLUMP -> Body.SLUMP; ReviewMotion.DIP -> Body.DIP; ReviewMotion.JUMP -> Body.JUMP }

/** 반복 움직임 시계(ms). 애니메이션 시계에 붙어 돌고, 화면에 있을 때만 만든다. 60초마다 처음으로(그 사이 한 번 끊김). */
@Composable
private fun rememberLoopMs(label: String): State<Float> =
    rememberInfiniteTransition(label = label).animateFloat(0f, 60_000f, infiniteRepeatable(tween(60_000, easing = LinearEasing)), label = label)

@Composable
internal fun EffortReviewScreen(
    ui: EffortUi, items: List<ReviewItem>,
    onCounted: () -> Unit, onConsent: () -> Unit, onLater: () -> Unit,
) {
    val trip = ui.openTrip ?: return
    val r = ui.receipt ?: return
    val narrow = LocalNarrow.current
    val reduce = rememberReduceMotion()
    val c = Chagok.colors
    val t = Chagok.type
    val counted by rememberUpdatedState(onCounted)
    val pending by rememberUpdatedState(ui.weatherPending)
    val solo = ui.proposal == null
    val rainy = items.first().on && items.first().fx == ReviewFx.RAIN

    // 상태. 다시 열면(이미 다 셈) 다 센 모습 그대로 — 세는 연출은 다시 보지 않는다(F29 '다시 열 때')
    val already = trip.reviewSeen
    var loading by remember { mutableStateOf(!already) }
    var k by remember { mutableIntStateOf(if (already) items.size else -1) }          // 지금 세는 줄(-1 시작 전, size = 다 셈)
    var done by remember { mutableStateOf(already) }
    var pose by remember { mutableStateOf(if (already) ReviewCast.done.first else ReviewCast.idle) }
    var body by remember { mutableStateOf(Body.BREATHE) }
    val bodyP = remember { Animatable(0f) }
    val states = remember { mutableStateListOf(*Array(items.size) { if (already) (if (items[it].on) RowState.ON else RowState.OFF) else RowState.WAIT }) }
    val shown = remember { mutableStateListOf(*Array(items.size) { if (already) items[it].value else "" }) }
    val ring = remember { List(items.size) { Animatable(if (already) 1f else 0f) } }
    val pop = remember { List(items.size) { Animatable(1f) } }
    val fx = remember { List(items.size) { Animatable(0f) } }       // 줄 안 작은 움직임 경과(ms)
    val rain = remember { Animatable(0f) }
    val glow = remember { Animatable(if (already) 1f else 0f) }
    val stars = remember { Animatable(0f) }
    val btn = remember { List(2) { Animatable(if (already) 1f else 0f) } }

    suspend fun motion(b: Body) {
        body = b; bodyP.snapTo(0f)
        val ms = when (b) { Body.SLUMP -> 450; Body.DIP -> 500; Body.JUMP -> 700; Body.BREATHE -> 0 }
        if (ms > 0) bodyP.animateTo(1f, tween(ms, easing = LinearEasing))   // 곡선은 키프레임 구간마다(rv-out)
        if (b == Body.DIP || b == Body.JUMP) body = Body.BREATHE   // slump는 끝 모양으로 남는다(시안 forwards)
    }

    fun finishNow() {
        // [건너뛰기] · 움직임 줄이기: 바로 다 센 모습
        items.indices.forEach { states[it] = if (items[it].on) RowState.ON else RowState.OFF; shown[it] = items[it].value }
        k = items.size; pose = ReviewCast.done.first; body = Body.BREATHE; done = true; loading = false
    }

    LaunchedEffect(trip.tripId) {
        if (already) return@LaunchedEffect
        // 도착 날씨를 받는 중이면 조금 기다린다(최대 3초, AC-C-6) — 날씨 줄이 바뀌기 때문
        withTimeoutOrNull(3000) { snapshotFlow { pending }.first { !it } }
        loading = false
        // 움직임 줄이기: 0.6초 뒤 다 센 모습(6-4)
        if (reduce) { delay(600); finishNow(); glow.snapTo(1f); btn.forEach { it.snapTo(1f) }; counted(); return@LaunchedEffect }
        delay(600)
        items.forEachIndexed { i, it ->
            if (done) return@LaunchedEffect
            k = i; states[i] = RowState.NOW
            val dur = if (it.on) REVIEW_ON_MS else REVIEW_OFF_MS
            // 원 테두리가 셈 시간만큼 차오름(linear)
            launch { ring[i].animateTo(1f, tween(dur.toInt(), easing = LinearEasing)) }
            if (it.on) {
                pose = it.pose
                launch { motion(bodyOf(it.motion)) }
                launch { fx[i].animateTo(dur.toFloat(), tween(dur.toInt(), easing = LinearEasing)) }
                if (i == 0 && rainy) launch { rain.animateTo(1f, tween(700)) }      // 화면 비 세게 0.7초
                launch {
                    if (it.countTo > 0) {
                        // 0 → n 1초, 끝에서 느려짐. 사이 값만 여기서 그리고 끝은 엔진 값
                        val a = Animatable(0f)
                        a.animateTo(1f, tween(1000, easing = LinearEasing)) {
                            val v = roundHalfUp(it.countTo * cubicOut(value).toDouble())
                            shown[i] = if (value < 1f) (if (it.countKm) "${v}km" else driveText(v * 60.0)) else it.value
                        }
                    } else { delay(700); shown[i] = it.value }
                }
            } else {
                pose = ReviewCast.idle; body = Body.BREATHE; shown[i] = ""
            }
            delay(dur)
            if (done) return@LaunchedEffect
            states[i] = if (it.on) RowState.ON else RowState.OFF
            shown[i] = it.value
            // 다 차면 딱 라임으로 바뀌며 0.32초 뿅(40%에 1.14배, rv-out)
            if (it.on) launch { pop[i].snapTo(1f); pop[i].animateTo(1.14f, tween(128, easing = Out)); pop[i].animateTo(1f, tween(192, easing = Out)) }
            if (i == 0 && rainy) launch { rain.animateTo(.45f, tween(900)) }       // 그 뒤 약하게 0.9초
            delay(REVIEW_GAP_MS)
        }
        if (done) return@LaunchedEffect
        // 마무리: 비 그침 0.8초 + 땀 닦기(relief · dip 0.5초) → 0.9초 뒤 뿌듯(점프 0.7초 + 반짝이 0.8초 + 라임 빛 0.8초) · 버튼 0.42초(0.07초 차이)
        k = items.size
        launch { rain.animateTo(0f, tween(800)) }
        pose = ReviewCast.finish.first
        launch { motion(bodyOf(ReviewCast.finish.second)) }
        delay(900)
        if (done) return@LaunchedEffect
        pose = ReviewCast.done.first; done = true
        launch { motion(bodyOf(ReviewCast.done.second)) }
        launch { glow.animateTo(1f, tween(800)) }
        launch { stars.snapTo(0f); stars.animateTo(1f, tween(800 + 180, easing = LinearEasing)) }
        btn.forEachIndexed { i, a -> launch { delay(i * 70L); a.animateTo(1f, tween(420, easing = Out)) } }
        counted()
    }

    val say = when {
        loading -> stringResource(R.string.effort_calc_loading)
        done -> stringResource(R.string.effort_review_done, r.driveText,
            if (trip.n <= 1) stringResource(R.string.effort_review_solo) else stringResource(R.string.effort_review_people, trip.n))
        k < 0 -> stringResource(R.string.effort_calc_start)
        else -> sayText(items[k.coerceAtMost(items.lastIndex)].say)
    }
    val sayRise = with(LocalDensity.current) { 10.gpx.roundToPx() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val skip = {
        if (!done) {
            // [건너뛰기] → 바로 다 센 모습(움직임 없이, 6-4)
            finishNow()
            scope.launch { glow.snapTo(1f); btn.forEach { it.snapTo(1f) }; rain.snapTo(0f) }
            counted()
        }
    }

    // 시안 .thx는 .pg 안에서 position:absolute; inset:0이라 바깥 여백 40을 받지 않는다(창 전체가 판).
    // 앱 EffortFrame은 사방 40을 두므로 이 화면만 그만큼 넓혀 시안과 같은 폭으로 그린다(줄 안 연출 칸이 80 좁아 '예상 17:45'가 잘렸음)
    val frame = with(LocalDensity.current) { 40.gpx.roundToPx() }
    Box(
        Modifier.fillMaxSize().layout { m, cs ->
            val pl = m.measure(cs.copy(minWidth = cs.minWidth + frame * 2, maxWidth = cs.maxWidth + frame * 2, minHeight = cs.minHeight + frame * 2, maxHeight = cs.maxHeight + frame * 2))
            layout(cs.maxWidth, cs.maxHeight) { pl.place(-frame, -frame) }
        },
    ) {
        // 화면 비(날씨 줄을 셀 때 세게 → 약하게 → 다 세면 그침)
        if (rainy && !done && !reduce) ScreenRain({ rain.value }, c)   // 다 세면 그친 비는 만들지도 않는다(반복이 계속 돌지 않게)
        Column(Modifier.fillMaxSize()) {
            // 머리: "○○에 도착했어요"(이름만 말줄임) + 아래 줄(노고 문구 → 오늘 운전 ○시간 ○분 · N명) · 오른쪽 건너뛰기(세는 동안만)
            Row(
                Modifier.fillMaxWidth().padding(start = (if (narrow) 24 else 40).gpx, end = (if (narrow) 16 else 40).gpx, top = (if (narrow) 24 else 40).gpx),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.gpx)) {
                    CgPlaceLine(stringResource(R.string.effort_arrive_title, trip.destName), trip.destName, if (narrow) t.titleL else t.headlineM, c.textPrimary)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.gpx)) {
                        // 아래 줄 문구 전환: 아래에서 10 올라오며 바뀜 0.28초(시안 rv-say)
                        AnimatedContent(
                            say, label = "say",
                            transitionSpec = {
                                (fadeIn(tween(280, easing = Out)) + slideInVertically(tween(280, easing = Out)) { sayRise }) togetherWith
                                    fadeOut(tween(280, easing = Out))
                            },
                        ) { s ->
                            Text(
                                s, Modifier.heightIn(min = (if (narrow) 38 else 44).gpx).semantics { liveRegion = LiveRegionMode.Polite },
                                style = if (narrow) t.bodyL else t.titleM, color = if (done) c.textSecondary else c.textPrimary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (loading) CgLoadingDots()
                    }
                }
                if (!done) GhostButton(stringResource(R.string.effort_calc_skip), skip)
            }
            Row(Modifier.fillMaxWidth().weight(1f)) {
                // 왼쪽: 노고(가로 820). 분할은 캐릭터를 그리지 않고 오른쪽 열이 전체 폭(6-1: 캐릭터가 잘리기만 해서 뺌)
                if (!narrow) Character(
                    Modifier.width(820.gpx).fillMaxHeight(), reduce, pose, body, { bodyP.value },
                    { glow.value }, { stars.value }, c,
                )
                // 오른쪽: 기준 5줄 + 버튼
                Column(
                    Modifier.weight(1f).fillMaxHeight().padding(
                        start = 40.gpx, top = (if (narrow) 16 else 40).gpx,
                        end = (if (narrow) 40 else 64).gpx, bottom = (if (narrow) 40 else 96).gpx,
                    ),
                ) {
                    val gap = (if (narrow) 6 else 8).gpx
                    Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(gap)) {
                        items.forEachIndexed { i, it ->
                            // 다 센 줄은 지금 영수증 값으로 그린다: 도착 날씨가 3초 뒤에 오면 세는 동안의 값("출발할 때 비")이 남아
                            // 영수증("비, 주행 내내")과 어긋났다(11/14 에뮬). 세는 중인 줄만 세는 값
                            val settled = states[i] == RowState.ON || states[i] == RowState.OFF
                            ReviewRow(
                                // 줄 112~170(분할 84 이상, 6-1)
                                Modifier.fillMaxWidth().weight(1f).heightIn(min = (if (narrow) 84 else 112).gpx, max = 170.gpx),
                                it, if (settled) (if (it.on) RowState.ON else RowState.OFF) else states[i], if (settled) it.value else shown[i],
                                { ring[i].value }, { pop[i].value }, { fx[i].value }, i == items.lastIndex, gap, narrow, c,
                            )
                        }
                    }
                    // 버튼(세는 동안 숨김 · 자리는 남김) — 다 세면 아래 24에서 올라오며 나타남(0.42초, 0.07초 차이, rv-out)
                    // 분할: 창 너비로 늘려 [챙겨주기] 2 : [다음에] 1(6-1). 혼자는 [영수증 보기] 하나가 전체 폭
                    Row(
                        (if (narrow) Modifier.fillMaxWidth() else Modifier).padding(top = (if (narrow) 24 else 40).gpx),
                        horizontalArrangement = Arrangement.spacedBy((if (narrow) 12 else 16).gpx),
                    ) {
                        val lift = { a: Animatable<Float, *> -> Modifier.graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 24f } }
                        ReviewButton(
                            stringResource(if (solo) R.string.effort_review_receipt else R.string.effort_review_consent),
                            if (solo) ReviewBtnIcon.RECEIPT else ReviewBtnIcon.HAND_HEART, filled = true, nudge = done && !reduce,
                            enabled = done, onClick = onConsent, narrow = narrow,
                            modifier = (if (narrow) Modifier.weight(if (solo) 1f else 2f) else Modifier).then(lift(btn[0])),
                        )
                        if (!solo) ReviewButton(
                            stringResource(R.string.effort_review_later), null, filled = false, nudge = false,
                            enabled = done, onClick = onLater, narrow = narrow,
                            modifier = (if (narrow) Modifier.weight(1f) else Modifier).then(lift(btn[1])),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun sayText(s: CalcSay): String = stringResource(
    when (s) {
        CalcSay.RAIN -> R.string.effort_calc_say_rain
        CalcSay.SNOW -> R.string.effort_calc_say_snow
        CalcSay.WEATHER_NONE -> R.string.effort_calc_say_weather_none
        CalcSay.WEATHER_CLEAR -> R.string.effort_calc_say_clear
        CalcSay.JAM -> R.string.effort_calc_say_jam
        CalcSay.JAM_NONE -> R.string.effort_calc_say_jam_none
        CalcSay.LATE -> R.string.effort_calc_say_late
        CalcSay.LATE_NONE -> R.string.effort_calc_say_late_none
        CalcSay.NIGHT -> R.string.effort_calc_say_night
        CalcSay.NIGHT_NONE -> R.string.effort_calc_say_night_none
        CalcSay.LONG -> R.string.effort_calc_say_long
        CalcSay.LONG_NONE -> R.string.effort_calc_say_long_none
        CalcSay.DIST_GROUP, CalcSay.DIST_SOLO -> R.string.effort_calc_start   // 돌아보기에는 거리 줄이 없다
    },
)

/** 건너뛰기(시안 Basic Button M · Ghost): 면 없이 글자만, 누르면 옅은 면. */
@Composable
private fun GhostButton(text: String, onClick: () -> Unit) {
    val c = Chagok.colors
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Box(
        Modifier.defaultMinSize(minWidth = 0.gpx, minHeight = 80.gpx.coerceAtLeast(MinTouch))
            .background(if (pressed) c.btnTertiaryPressed else Color.Transparent, RoundedCornerShape(24.gpx))
            .clickable(src, indication = null, role = Role.Button, onClick = onClick).padding(horizontal = 16.gpx),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Chagok.type.bodyL, color = c.btnTertiaryText, maxLines = 1) }
}

private val Stars = listOf(Triple(.16f, .30f, Offset(-.6f, -.8f)), Triple(.78f, .24f, Offset(.7f, -.7f)), Triple(.84f, .56f, Offset(.9f, .2f)), Triple(.12f, .58f, Offset(-.8f, .3f)))
private const val STAR_D = "M17 0C18.5 11 23 15.5 34 17C23 18.5 18.5 23 17 34C15.5 23 11 18.5 0 17C11 15.5 15.5 11 17 0Z"

/** 노고 상반신(높이 840, 아래 9% 흐림) · 표정 0.22초 바뀜 · 몸짓 · 라임 빛 · 반짝이 4개. 분할 창에서는 그리지 않는다(6-1). */
@Composable
private fun Character(
    modifier: Modifier, reduce: Boolean, pose: ReviewPose, body: Body, bodyP: () -> Float,
    glow: () -> Float, stars: () -> Float, c: ChagokColors,
) {
    // 숨쉬기 3.2초(늘, rv-io). 움직임 줄이기면 멈춤
    val breathe = if (reduce) null else rememberInfiniteTransition(label = "breathe").animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart), label = "b")
    val starPath = remember { PathParser().parsePathString(STAR_D).toPath() }
    val glowColor = if (c.isDark) Color(0x40C8F04A) else Color(0x66C8F04A)
    BoxWithConstraints(modifier) {
        val d = LocalDensity.current
        // 라임 빛(다 세면 0.8초에 퍼짐)
        Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 140.gpx).size(760.gpx)
                .graphicsLayer { alpha = glow() }
                .background(Brush.radialGradient(listOf(glowColor, Color.Transparent))),
        )
        val maxH = with(d) { maxHeight.toPx() }
        val want = with(d) { 840.gpx.toPx() }
        val h = with(d) { min(want, maxH).coerceAtLeast(0f).toDp() }
        Box(
            Modifier.align(Alignment.BottomCenter).height(h).aspectRatio(700f / 754f)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(.5f, .92f)
                    val p = bodyP()
                    when (body) {
                        // rv-breathe: 0 → 50% → 100%, 구간마다 rv-io
                        Body.BREATHE -> if (breathe != null) {
                            val e = keyframes(breathe.value, listOf(0f to 0f, .5f to 1f, 1f to 0f), InOut)
                            translationY = -.009f * size.height * e; scaleX = 1 + .005f * e; scaleY = 1 - .005f * e
                        }
                        Body.SLUMP -> rotationZ = keyframes(p, listOf(0f to 0f, .4f to -3f, .7f to 1.5f, 1f to -1f), Out)
                        Body.DIP -> {
                            translationY = size.height * keyframes(p, listOf(0f to 0f, .35f to .015f, .7f to -.006f, 1f to 0f), Out)
                            scaleX = keyframes(p, listOf(0f to 1f, .35f to 1.035f, .7f to .99f, 1f to 1f), Out)
                            scaleY = keyframes(p, listOf(0f to 1f, .35f to .95f, .7f to 1.02f, 1f to 1f), Out)
                        }
                        Body.JUMP -> {
                            translationY = size.height * keyframes(p, listOf(0f to 0f, .18f to .01f, .48f to -.07f, .78f to 0f, 1f to 0f), Out)
                            scaleX = keyframes(p, listOf(0f to 1f, .18f to 1.05f, .48f to .96f, .78f to 1.04f, 1f to 1f), Out)
                            scaleY = keyframes(p, listOf(0f to 1f, .18f to .93f, .48f to 1.05f, .78f to .96f, 1f to 1f), Out)
                        }
                    }
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithContent {
                    drawContent()
                    // 아래 9% 흐림(시안 mask: linear-gradient(to top, transparent 0, #000 9%))
                    drawRect(Brush.verticalGradient(0f to Color.Black, .91f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                },
        ) {
            // 표정 0.22초 바뀜. 그림 파일은 ReviewPose 한곳에서(6-5)
            Crossfade(pose, animationSpec = tween(220), label = "pose") { p ->
                Image(painterResource(p.res), stringResource(p.alt), Modifier.fillMaxSize(), alignment = Alignment.BottomCenter, contentScale = ContentScale.Fit)
            }
            // 반짝이 4개(0.8초 rv-out, 0.06초 차이) — 너비 6%, 자기 크기만큼 비율로 날아감
            Canvas(Modifier.fillMaxSize()) {
                val sp = stars()
                if (sp <= 0f || sp >= 1f) return@Canvas
                val sw = size.width * .06f
                Stars.forEachIndexed { i, (l, tp, dv) ->
                    val q = ((sp * 980f - i * 60f) / 800f).coerceIn(0f, 1f)
                    if (q <= 0f || q >= 1f) return@forEachIndexed
                    val e = Out.transform(q)
                    val a = keyframes(q, listOf(0f to 0f, .3f to 1f, 1f to 0f), Out)
                    val sc = .2f + .9f * e
                    translate(size.width * l + dv.x * sw * e, size.height * tp + dv.y * sw * e) {
                        rotate(40f * e, Offset(sw / 2, sw / 2)) {
                            scale(sw / 34f * sc, sw / 34f * sc, Offset.Zero) {
                                drawPath(starPath, Lime, alpha = a)
                                if (!c.isDark) drawPath(starPath, LimeInk, alpha = a, style = Stroke(1.5f))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 기준 한 줄(시안 .rvw): 원 100(분할 64) · 이름 · 작은 움직임 · 값. 원 사이 세로 선은 센 만큼 진해짐. */
@Composable
private fun ReviewRow(
    modifier: Modifier, it: ReviewItem, st: RowState, value: String,
    ring: () -> Float, pop: () -> Float, fx: () -> Float, last: Boolean, gap: Dp, narrow: Boolean, c: ChagokColors,
) {
    val t = Chagok.type
    // 6-1: 가로 원 100(아이콘 50) · 이름 Title M · 값 Title L / 분할 원 64(아이콘 32) · 이름 700 24/32 · 값 700 26/34
    val circle = (if (narrow) 64 else 100).gpx
    val nameStyle = if (narrow) t.bodyL.copy(fontSize = 24.gsp, lineHeight = 32.gsp, fontWeight = FontWeight.Bold) else t.titleM
    val valueStyle = if (narrow) t.bodyL.copy(fontSize = 26.gsp, lineHeight = 34.gsp, fontWeight = FontWeight.Bold) else t.titleL
    val d = LocalDensity.current
    val gapPx = with(d) { gap.toPx() }
    val lineColor = if (st == RowState.ON || st == RowState.OFF) c.textPrimary else c.dividerDefault
    Row(
        modifier.drawBehind {
            if (last) return@drawBehind
            val r = circle.toPx() / 2
            val x = r
            val cy = size.height / 2
            // 다음 줄 원 위까지(줄 높이가 같다고 보고 cy + 높이 + 사이 − r)
            drawLine(lineColor, Offset(x, cy + r), Offset(x, cy + size.height + gapPx - r), 2f)
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy((if (narrow) 12 else 24).gpx),
    ) {
        // 원
        Box(Modifier.size(circle).graphicsLayer { val s = pop(); scaleX = s; scaleY = s }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2
                when (st) {
                    RowState.ON -> drawCircle(Lime, r)
                    RowState.OFF -> drawCircle(c.dividerDefault, r - 1f, style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))))
                    else -> {
                        drawCircle(c.bgPrimary, r)
                        drawCircle(c.dividerDefault, r - 1f, style = Stroke(2f))
                    }
                }
                if (st == RowState.NOW) {
                    val sw = 3f
                    drawArc(c.textPrimary, -90f, 360f * ring(), false, topLeft = Offset(sw / 2, sw / 2), size = Size(size.width - sw, size.height - sw), style = Stroke(sw, cap = StrokeCap.Round))
                }
            }
            val ic = when (it.icon) {
                ReviewIcon.RAIN -> CgIcon.Rain; ReviewIcon.SNOW -> CgIcon.Snow; ReviewIcon.TRAFFIC -> CgIcon.Traffic
                ReviewIcon.DELAY -> CgIcon.Delay; ReviewIcon.MOON -> CgIcon.Moon; ReviewIcon.HOURGLASS -> CgIcon.Hourglass
            }
            CgIconMark(ic, (if (narrow) 32 else 50).gpx, when (st) { RowState.ON -> LimeInk; RowState.NOW -> c.textPrimary; else -> c.textTertiary })
        }
        // 이름
        Text(
            it.label, Modifier.width((if (narrow) 124 else 200).gpx),
            style = nameStyle,
            color = if (st == RowState.NOW || st == RowState.ON) c.textPrimary else c.textTertiary, maxLines = 1, softWrap = false,
        )
        // 작은 움직임(세는 줄 · 있음일 때만). 줄 밖으로 그리지 않는다(시안 .rvf overflow:hidden), 0.25초에 나타남
        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds().graphicsLayer { alpha = (fx() / 250f).coerceIn(0f, 1f) }) {
            if (st == RowState.NOW && it.on) RowFx(it, fx, narrow, c)
        }
        // 값
        Text(
            value, Modifier.widthIn(min = (if (narrow) 128 else 220).gpx),
            style = valueStyle.let { s -> if (st == RowState.OFF) s.copy(fontWeight = FontWeight.Normal) else s },
            color = if (st == RowState.OFF) c.textTertiary else c.textPrimary,
            textAlign = TextAlign.End, maxLines = 1, softWrap = false,
        )
    }
}

/** 줄 안 작은 움직임(시안 rvFx · review.css). [ms] = 이 줄을 센 지 지난 시간. */
@Composable
private fun RowFx(it: ReviewItem, ms: () -> Float, narrow: Boolean, c: ChagokColors) {
    when (it.fx) {
        ReviewFx.LATE -> LateFx(it, ms, narrow, c)
        ReviewFx.JAM -> JamFx(ms, narrow, c)
        ReviewFx.BAR -> Canvas(Modifier.fillMaxSize()) { drawBar(it.barMark, ms(), c) }
        ReviewFx.RAIN, ReviewFx.SNOW, ReviewFx.NIGHT -> {
            val loop by rememberLoopMs("fx")
            val star = remember { Path().apply { moveTo(.5f, 0f); lineTo(.62f, .38f); lineTo(1f, .5f); lineTo(.62f, .62f); lineTo(.5f, 1f); lineTo(.38f, .62f); lineTo(0f, .5f); lineTo(.38f, .38f); close() } }
            Canvas(Modifier.fillMaxSize()) {
                if (it.fx == ReviewFx.NIGHT) drawStars(star, loop, narrow, c) else drawRowRain(it.fx == ReviewFx.SNOW, loop, narrow, c)
            }
        }
    }
}

/** 비 9줄(4 × 30 · 분할 3 × 22, 12° 기울임, 0.7초 linear로 (−22, 180) 떨어짐) · 눈은 14 원 1.4초. 색 reviewRain(--rc). */
private fun DrawScope.drawRowRain(snow: Boolean, loopMs: Float, narrow: Boolean, c: ChagokColors) {
    val period = if (snow) 1400f else 700f
    val dw = if (snow) 14f else if (narrow) 3f else 4f
    val dh = if (snow) 14f else if (narrow) 22f else 30f
    repeat(9) { i ->
        val delay = ((i * .23f) % .7f) * 1000f                       // 시안 --dl: −((i·.23) % .7)초
        val ph = ((loopMs + delay) % period) / period
        val x = size.width * (.06f + i * .11f) - 22f * ph
        val y = -34f + 180f * ph
        if (snow) drawCircle(c.reviewRain, dw / 2, Offset(x + dw / 2, y + dh / 2))
        else rotate(12f, Offset(x + dw / 2, y + dh / 2)) { drawRoundRect(c.reviewRain, Offset(x, y), Size(dw, dh), CornerRadius(2f)) }
    }
}

/** 별 6개(22 · 분할 16): 0.7초 왕복 rv-io로 크기 .4 ↔ 1.15 · 투명도 .3 ↔ 1, 지연 −i·0.17초(시안 cg-twk). */
private fun DrawScope.drawStars(star: Path, loopMs: Float, narrow: Boolean, c: ChagokColors) {
    val s = if (narrow) 16f else 22f
    val tops = listOf(.18f, .44f, .24f, .50f, .14f, .38f)
    repeat(6) { i ->
        val ph = (loopMs + i * 170f) % 1400f
        val e = InOut.transform(if (ph < 700f) ph / 700f else 2f - ph / 700f)
        val sc = .4f + .75f * e
        // 별 자리(left 6+i·16% · top) 가운데를 기준으로 크기만 바뀜
        translate(size.width * (.06f + i * .16f) + s / 2, size.height * tops[i] + s / 2) {
            scale(s * sc, s * sc, Offset.Zero) { translate(-.5f, -.5f) { drawPath(star, c.textPrimary, alpha = .3f + .7f * e) } }
        }
    }
}

/** 장시간 시간 막대: 트랙 14(모서리 7, 오른쪽 8 띄움) · 1.2초 linear로 채움(눈금 뒤는 라임) · 눈금 3 × 44 at 70%. */
private fun DrawScope.drawBar(mark: Float, ms: Float, c: ChagokColors) {
    val w = size.width; val h = size.height
    val right = w - 8f; val bh = 14f; val top = h / 2 - bh / 2
    val split = right * mark                     // 채움 그라데이션 경계(채움 요소 폭 기준)
    drawRoundRect(c.dividerDefault, Offset(0f, top), Size(right, bh), CornerRadius(bh / 2))
    val reveal = right * (ms / 1200f).coerceIn(0f, 1f)
    if (reveal > 0f) {
        drawRoundRect(c.textTertiary, Offset(0f, top), Size(min(reveal, split), bh), CornerRadius(bh / 2))
        if (reveal > split) drawRoundRect(Lime, Offset(split - bh / 2, top), Size(reveal - split + bh / 2, bh), CornerRadius(bh / 2))
    }
    drawRect(c.textPrimary, Offset(w * mark, h / 2 - 22f), Size(3f, 44f))   // 눈금 left: var(--m)(줄 안 폭 기준)
}

// 정체 구간 차 옆모습(시안 RV_JCAR, viewBox 60 × 24, 앞 = 오른쪽)
private const val JCAR_BODY = "M4.5 19 C2.6 19 2 18 2 16.4 L2 12.6 C2 10.9 3 10.1 4.8 9.8 L13.6 8.4 L20.4 3.4 C21.6 2.5 22.8 2.2 24.6 2.2 L37.2 2.2 C39.6 2.2 41.2 2.9 42.8 4.5 L47.6 9.2 L54.6 10.2 C57 10.6 58 11.6 58 13.6 L58 16.4 C58 18 57.2 19 55.4 19 Z"
private const val JCAR_WIN = "M21.6 4.9 C22.4 4.4 23.3 4.2 24.6 4.2 L37 4.2 C38.6 4.2 39.6 4.6 40.8 5.8 L44 9 L17.8 9 Z"

/**
 * 정체 구간(6-4, review.css rv-jme · rv-jcreep · rv-jbk): 앞차 2대(브레이크등) · 내 차가 0~0.42초 달려와 급정거 →
 * 0.5초 앞이 2.5° 숙여짐 → 0.52초부터 맨 앞부터 0.09초 간격으로 10px(분할 12px)씩 0.26초 갔다 섬. transform만.
 */
@Composable
private fun JamFx(ms: () -> Float, narrow: Boolean, c: ChagokColors) {
    val body = remember { PathParser().parsePathString(JCAR_BODY).toPath() }
    val win = remember { PathParser().parsePathString(JCAR_WIN).toPath() }
    val il = illustOf(c.isDark)
    Canvas(Modifier.fillMaxSize()) {
        val cw = if (narrow) 66f else 50f; val ch = if (narrow) 26.4f else 20f
        val gap = if (narrow) 6f else 5f; val crp = if (narrow) 12f else 10f; val cin = if (narrow) -160f else -96f
        val t = ms()
        for (k in 0..2) {
            val me = k == 2
            val x0 = size.width - (k + 1) * (cw + gap)
            val y0 = size.height / 2 - ch / 2
            val tx: Float; val rot: Float; val bk: Float
            if (me) {
                val p = (t / 960f).coerceIn(0f, 1f)
                tx = when {
                    p < .4375f -> cin + (-crp - cin) * Out.transform(p / .4375f)
                    p < .729f -> -crp
                    else -> -crp + crp * InOut.transform((p - .729f) / (1f - .729f))
                }
                rot = when {
                    p < .4375f -> 0f
                    p < .52f -> 2.5f * Out.transform((p - .4375f) / (.52f - .4375f))
                    p < .656f -> 2.5f * (1f - Out.transform((p - .52f) / (.656f - .52f)))
                    else -> 0f
                }
                bk = keyframes(p, listOf(0f to .15f, .39f to 1f, .729f to 1f, .77f to .15f, .97f to .15f, 1f to 1f), LinearEasing)
            } else {
                val p = ((t - (520f + k * 90f)) / 260f).coerceIn(0f, 1f)
                tx = -crp + crp * InOut.transform(p)
                rot = 0f
                bk = keyframes(p, listOf(0f to 1f, .12f to .15f, .85f to .15f, 1f to 1f), LinearEasing)
            }
            translate(x0 + tx, y0) {
                rotate(rot, Offset(cw * .26f, ch)) {
                    val col = if (me) c.textPrimary else c.textTertiary
                    val s = ch / 24f
                    scale(s, s, Offset.Zero) {
                        drawPath(body, col)
                        drawPath(win, c.bgPrimary, alpha = .55f)
                        listOf(15.5f, 46f).forEach { cx ->
                            drawCircle(col, 4.4f, Offset(cx, 19f))
                            drawCircle(c.bgPrimary, 4.4f, Offset(cx, 19f), style = Stroke(1.6f))
                        }
                    }
                    // 브레이크등(차 뒤 왼쪽, 9% × 17%, illust-tail-hi + tail 번짐 5px)
                    val bx = -1f; val by = ch * .44f; val bw = cw * .09f; val bh = ch * .17f
                    drawRoundRect(il.tail, Offset(bx - 3f, by - 3f), Size(bw + 6f, bh + 6f), CornerRadius(5f), alpha = .35f * bk)
                    drawRoundRect(il.tailHi, Offset(bx, by), Size(bw, bh), CornerRadius(2f), alpha = bk)
                }
            }
        }
    }
}

/**
 * 예상보다 더(6-4): "예상 16:05"(회색 24 · 분할 22) 위 0.3초 뒤 가로줄(0.28초) · 큰 숫자 16:05 → 16:30 1분씩(0.8초, 끝에서 느려짐) ·
 * 16:30 아래 라임 마커(0.32초, 숫자가 끝난 뒤). 예상 시각 · 늦어진 분은 엔진 · 출발 기록 값(Review.kt), 사이 시각만 여기서.
 */
@Composable
private fun LateFx(it: ReviewItem, ms: () -> Float, narrow: Boolean, c: ChagokColors) {
    val from = it.lateFrom ?: return
    val t = Chagok.type
    val small = TextStyle(fontFamily = t.bodyL.fontFamily, fontWeight = FontWeight.Normal, fontSize = (if (narrow) 22 else 24).gsp, lineHeight = (if (narrow) 28 else 32).gsp, fontFeatureSettings = "tnum")
    val big = (if (narrow) t.bodyL.copy(fontSize = 30.gsp, lineHeight = 38.gsp, fontWeight = FontWeight.Bold) else t.titleM).copy(fontFeatureSettings = "tnum")
    val marker = if (c.isDark) Color(0x59C8F04A) else Lime
    val now = ms()
    val go = now - 300f
    val q = (go / 800f).coerceIn(0f, 1f)
    val minute = it.lateFromMin + roundHalfUp(it.countTo * cubicOut(q).toDouble())
    Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.gpx, Alignment.CenterVertically)) {
        Text(
            stringResource(R.string.effort_review_late_eta, from),
            Modifier.drawWithContent {
                drawContent()
                val sx = Out.transform((go / 280f).coerceIn(0f, 1f))
                if (sx > 0f) drawRect(c.textTertiary, Offset(0f, size.height / 2 - 1f), Size(size.width * sx, 2f))
            },
            style = small, color = c.textTertiary, maxLines = 1, softWrap = false,
        )
        Text(
            hhmm(minute),
            Modifier.drawBehind {
                val mx = Out.transform(((go - 800f) / 320f).coerceIn(0f, 1f))
                if (mx > 0f) {
                    val mh = size.height * .4f
                    drawRoundRect(marker, Offset(-4f, size.height - 4f - mh), Size((size.width + 8f) * mx, mh), CornerRadius(4f))
                }
            },
            style = big, color = lerp(c.textTertiary, c.textPrimary, (go / 200f).coerceIn(0f, 1f)), maxLines = 1, softWrap = false,
        )
    }
}

/**
 * 화면 비(시안 THX_DROPS · .cg-rain): 46줄, 굵기 3 · 길이 36~72(위가 투명한 그라데이션), 길이별 0.75~1.2초에 (−290, 1360) 떨어짐,
 * 위 10% · 아래 18% 흐림. 색 reviewRain(--rc). 세기는 [strength](0..1).
 */
@Composable
private fun ScreenRain(strength: () -> Float, c: ChagokColors) {
    val loop by rememberLoopMs("rain")
    Canvas(
        Modifier.fillMaxSize().graphicsLayer { alpha = strength(); compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(0f to Color.Transparent, .10f to Color.Black, .82f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
            },
    ) {
        repeat(46) { i ->
            val left = size.width * (((i * 61) % 97) / 97f * 1.2f - .05f)
            val len = 36f + (i * 11) % 5 * 9f
            val dur = (.75f + (i * 7) % 10 / 22f) * 1000f
            val delay = ((i * 13) % 23) / 10f * 1000f
            val ph = ((loop + delay) % dur) / dur
            val x = left - 290f * ph
            val y = -80f + 1360f * ph
            rotate(12f, Offset(x + 1.5f, y + len / 2)) {
                drawRoundRect(Brush.verticalGradient(listOf(Color.Transparent, c.reviewRain), startY = y, endY = y + len), Offset(x, y), Size(3f, len), CornerRadius(2f))
            }
        }
    }
}

private enum class ReviewBtnIcon { HAND_HEART, RECEIPT }

// [운전자 챙겨주기] 움직임용 손 · 하트(시안 HH_* · Phosphor hand-heart를 손 · 하트로 나눔). 가만히 있을 때는 drawable ic_ph_hand_heart 원본
private const val HH_HAND = "M232.76,137.88A28.39,28.39,0,0,0,208.13,133L172,141.26c0-.42,0-.84,0-1.26a32,32,0,0,0-32-32H89.94a35.76,35.76,0,0,0-25.45,10.54L43,140H20A20,20,0,0,0,0,160v40a20,20,0,0,0,20,20H120a11.89,11.89,0,0,0,2.91-.36l64-16a11.4,11.4,0,0,0,1.79-.6l38.82-16.54c.23-.09.45-.19.67-.3a28.61,28.61,0,0,0,4.57-48.32ZM36,196H24V164H36Zm181.68-31.39-37.51,16L118.52,196H60V157l21.46-21.46A11.93,11.93,0,0,1,89.94,132H140a8,8,0,0,1,0,16H112a12,12,0,0,0,0,24h32a12.19,12.19,0,0,0,2.69-.3l67-15.41.47-.12a4.61,4.61,0,0,1,5.82,4.44A4.58,4.58,0,0,1,217.68,164.61Z"
private const val HH_SIL = "M232.76,137.88A28.39,28.39,0,0,0,208.13,133L172,141.26c0-.42,0-.84,0-1.26a32,32,0,0,0-32-32H89.94a35.76,35.76,0,0,0-25.45,10.54L43,140H20A20,20,0,0,0,0,160v40a20,20,0,0,0,20,20H120a11.89,11.89,0,0,0,2.91-.36l64-16a11.4,11.4,0,0,0,1.79-.6l38.82-16.54c.23-.09.45-.19.67-.3a28.61,28.61,0,0,0,4.57-48.32Z"
private const val HH_HEART = "M156,42.85A51.77,51.77,0,0,1,192.54,28C220.91,28,244,51.33,244,80C244,97.37,236.19,114.94,220.76,132.49C205.33,150.04,176,170,164.4,178.5Q156,184,147.6,178.5C136,170,106.67,150.04,91.24,132.49C75.81,114.94,68,97.37,68,80C68,51.33,91.09,28,119.46,28A51.77,51.77,0,0,1,156,42.85ZM119.46,52A27.18,27.18,0,0,1,144.9,68.56a12,12,0,0,0,22.2,0A27.18,27.18,0,0,1,192.54,52C207.42,52,220,64.82,220,80c0,18.06-15,38.84-43.38,60.19C168.5,146.3,161,151.5,156,154.5C151,151.5,143.5,146.3,135.38,140.19C107,118.84,92,98.06,92,80C92,64.82,104.58,52,119.46,52Z"

/** 들썩(시안 rv-lift, 3.2초 주기): 0 → 16% 떠오름(rv-out) → 26% 92%(rv-io) → 44% 내려앉음(rv-io). (뜬 비율, 크기 비율 0..1). */
private fun liftAt(p: Float): Pair<Float, Float> = when {
    p < .16f -> Out.transform(p / .16f).let { it to it }
    p < .26f -> (1f - .08f * InOut.transform((p - .16f) / .10f)) to 1f
    p < .44f -> InOut.transform((p - .26f) / .18f).let { .92f * (1f - it) to 1f - it }
    else -> 0f to 0f
}

/** Basic Button L(높이 100 · 모서리 32, 분할 80 · 24) + 글자 왼쪽 아이콘. 채움은 라임 물결이 3.2초마다(나타난 1.2초 뒤부터). */
@Composable
private fun ReviewButton(
    text: String, icon: ReviewBtnIcon?, filled: Boolean, nudge: Boolean, enabled: Boolean,
    onClick: () -> Unit, narrow: Boolean, modifier: Modifier = Modifier,
) {
    val c = Chagok.colors
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val bg = if (filled) (if (pressed) c.btnFilledPressed else c.btnFilled) else (if (pressed) c.btnBasicPressed else c.btnBasic)
    val fg = if (filled) c.btnFilledText else c.btnBasicText
    val radius = (if (narrow) 24 else 32).gpx
    // 물결 · 들썩 주기(3.2초)는 켜졌을 때만 만든다(꺼진 버튼 · [다음에]에서 반복이 돌지 않게)
    val cycle = if (nudge) rememberInfiniteTransition(label = "nudge").animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "n") else null
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(nudge) { started = false; if (nudge) { delay(1200); started = true } }
    val active = { nudge && started && !pressed }
    val nudgeColor = if (c.isDark) Color(0x8CC8F04A) else Color(0xB3C8F04A)
    val hh = remember { Triple(PathParser().parsePathString(HH_HAND).toPath(), PathParser().parsePathString(HH_SIL).toPath(), PathParser().parsePathString(HH_HEART).toPath().apply { fillType = PathFillType.EvenOdd }) }
    Row(
        modifier
            .defaultMinSize(minWidth = if (narrow) 0.gpx else 140.gpx, minHeight = (if (narrow) 80 else 100).gpx.coerceAtLeast(MinTouch))
            .drawBehind {
                if (!active()) return@drawBehind
                // 라임 물결(box-shadow 0 → 18px · 분할 12px 투명으로, 앞 28%, rv-out)
                val q = ((cycle?.value ?: 0f) / .28f).coerceIn(0f, 1f)
                if (q >= 1f) return@drawBehind
                val e = Out.transform(q)
                val spread = (if (narrow) 12f else 18f) * e
                drawRoundRect(nudgeColor.copy(alpha = nudgeColor.alpha * (1f - e)), Offset(-spread, -spread), Size(size.width + spread * 2, size.height + spread * 2), CornerRadius(radius.toPx() + spread))
            }
            .background(bg, RoundedCornerShape(radius))
            .clickable(src, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = (if (narrow) 20 else 38).gpx),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy((if (narrow) 10 else 16).gpx, Alignment.CenterHorizontally),
    ) {
        val isz = (if (narrow) 28 else 32).gpx
        when (icon) {
            ReviewBtnIcon.HAND_HEART -> Box(Modifier.size(isz)) {
                val (lift, grow) = if (active()) liftAt(cycle?.value ?: 0f) else (0f to 0f)
                if (lift == 0f && grow == 0f) {
                    // 가만히: Phosphor hand-heart 원본(drawable)
                    Icon(painterResource(R.drawable.ic_ph_hand_heart), null, Modifier.fillMaxSize(), tint = fg)
                } else Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
                    // 하트가 손 실루엣 뒤에서 −64(256 단위) 뜨고 1.12배(하트 아래 70% 기준)
                    val s = size.width / 256f
                    scale(s, s, Offset.Zero) {
                        translate(0f, -64f * lift) { scale(1f + .12f * grow, 1f + .12f * grow, Offset(156f, 137.2f)) { drawPath(hh.third, fg) } }
                        drawPath(hh.second, Color.Black, blendMode = BlendMode.Clear)
                        drawPath(hh.first, fg)
                    }
                }
            }
            ReviewBtnIcon.RECEIPT -> Box(Modifier.graphicsLayer { translationY = if (active()) -3f * liftAt(cycle?.value ?: 0f).first else 0f }) {
                CgIconMark(CgIcon.Receipt, isz, fg)
            }
            null -> Unit
        }
        Text(
            text, style = if (narrow) Chagok.type.bodyL else Chagok.type.titleS, color = fg, maxLines = 1,
            modifier = Modifier.semantics { contentDescription = text },
        )
    }
}
