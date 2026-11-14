package com.mist.pleos_app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
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
import com.mist.pleos_app.effort.ReviewFx
import com.mist.pleos_app.effort.ReviewIcon
import com.mist.pleos_app.effort.ReviewItem
import com.mist.pleos_app.effort.ReviewMotion
import com.mist.pleos_app.effort.ReviewPose
import com.mist.pleos_app.engine.driveText
import com.mist.pleos_app.ui.components.CgIcon
import com.mist.pleos_app.ui.components.CgIconMark
import com.mist.pleos_app.ui.components.CgLoadingDots
import com.mist.pleos_app.ui.components.CgPlaceLine
import com.mist.pleos_app.ui.theme.MinTouch
import com.mist.pleos_app.ui.theme.Chagok
import com.mist.pleos_app.ui.theme.ChagokColors
import com.mist.pleos_app.ui.theme.gpx
import com.mist.pleos_app.ui.theme.gsp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/*
 * 오늘 운전 돌아보기(명세 v0.6 F29, PRD v2.0 7-5, 명준 11/14 아티팩트 '도착 · 오늘 운전 돌아보기' rv-start ~ rv-solo).
 * 왼쪽 노고 캐릭터(표정 4장 · BX 'NOGO 캐릭터 모션'), 오른쪽 배경 없는 기준 5줄을 약 10초 동안 하나씩 센다. 금액 · 배수 · 퍼센트 없음.
 * 다 세면 [운전자 챙겨주기] · [다음에](혼자 · 짧은 주행은 [영수증 보기] 하나, AC-F29-3). 타이밍 · 곡선은 시안 playReview · CSS 그대로.
 */

private val Lime = Color(0xFFC8F04A)
private val LimeInk = Color(0xFF242135)
private val EaseOut = CubicBezierEasing(0f, 0f, .58f, 1f)
private val EaseInOut = CubicBezierEasing(.42f, 0f, .58f, 1f)
private val Std = CubicBezierEasing(.2f, 0f, 0f, 1f)
private val Rise = CubicBezierEasing(.05f, .7f, .1f, 1f)
private val PopE = CubicBezierEasing(.2f, .7f, .3f, 1f)
private val JumpE = CubicBezierEasing(.3f, .7f, .4f, 1f)
private val JamE = CubicBezierEasing(.2f, .7f, .2f, 1f)
private val FillE = CubicBezierEasing(.4f, 0f, .2f, 1f)

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
    var pose by remember { mutableStateOf(if (already) ReviewPose.PROUD else ReviewPose.FOCUS) }
    var body by remember { mutableStateOf(Body.BREATHE) }
    val bodyP = remember { Animatable(0f) }
    val states = remember { mutableStateListOf(*Array(items.size) { if (already) (if (items[it].on) RowState.ON else RowState.OFF) else RowState.WAIT }) }
    val shown = remember { mutableStateListOf(*Array(items.size) { if (already) items[it].value else "" }) }
    val ring = remember { List(items.size) { Animatable(if (already) 1f else 0f) } }
    val pop = remember { List(items.size) { Animatable(1f) } }
    val fx = remember { List(items.size) { Animatable(0f) } }
    val rain = remember { Animatable(0f) }
    val glow = remember { Animatable(if (already) 1f else 0f) }
    val stars = remember { Animatable(0f) }
    val btn = remember { List(2) { Animatable(if (already) 1f else 0f) } }

    suspend fun motion(b: Body) {
        body = b; bodyP.snapTo(0f)
        val ms = when (b) { Body.SLUMP -> 450; Body.DIP -> 500; Body.JUMP -> 700; Body.BREATHE -> 0 }
        if (ms > 0) bodyP.animateTo(1f, tween(ms, easing = LinearEasing))
        if (b == Body.DIP || b == Body.JUMP) body = Body.BREATHE   // slump는 끝 모양으로 남는다(시안 forwards)
    }

    fun finishNow() {
        // [건너뛰기] · 움직임 줄이기: 바로 다 센 모습
        items.indices.forEach { states[it] = if (items[it].on) RowState.ON else RowState.OFF; shown[it] = items[it].value }
        k = items.size; pose = ReviewPose.PROUD; body = Body.BREATHE; done = true; loading = false
    }

    LaunchedEffect(trip.tripId) {
        if (already) return@LaunchedEffect
        // 도착 날씨를 받는 중이면 조금 기다린다(최대 3초, AC-C-6) — 날씨 줄이 바뀌기 때문
        withTimeoutOrNull(3000) { snapshotFlow { pending }.first { !it } }
        loading = false
        if (reduce) { delay(600); finishNow(); launch { glow.snapTo(1f) }; btn.forEach { it.snapTo(1f) }; counted(); return@LaunchedEffect }
        delay(600)
        items.forEachIndexed { i, it ->
            if (done) return@LaunchedEffect
            k = i; states[i] = RowState.NOW
            val dur = if (it.on) REVIEW_ON_MS else REVIEW_OFF_MS
            launch { ring[i].animateTo(1f, tween(dur.toInt(), easing = LinearEasing)) }
            if (it.on) {
                pose = it.pose
                launch { motion(if (it.motion == ReviewMotion.SLUMP) Body.SLUMP else Body.DIP) }
                launch { fx[i].animateTo(1f, tween(1200, easing = LinearEasing)) }
                if (i == 0 && rainy) launch { rain.animateTo(1f, tween(700)) }
                launch {
                    if (it.countTo > 0) {
                        val a = Animatable(0f)
                        a.animateTo(1f, tween(1000, easing = LinearEasing)) {
                            val q = value; val v = kotlin.math.round(it.countTo * (1 - (1 - q) * (1 - q) * (1 - q))).toInt()
                            shown[i] = if (q < 1f) (if (it.countKm) "${v}km" else driveText(v * 60.0)) else it.value
                        }
                    } else { delay(700); shown[i] = it.value }
                }
            } else {
                pose = ReviewPose.FOCUS; body = Body.BREATHE; shown[i] = ""
            }
            delay(dur)
            if (done) return@LaunchedEffect
            states[i] = if (it.on) RowState.ON else RowState.OFF
            shown[i] = it.value
            if (it.on) launch { pop[i].snapTo(1f); pop[i].animateTo(1.14f, tween(128, easing = PopE)); pop[i].animateTo(1f, tween(192, easing = PopE)) }
            if (i == 0 && rainy) launch { rain.animateTo(.45f, tween(900)) }
            delay(REVIEW_GAP_MS)
        }
        if (done) return@LaunchedEffect
        // 마무리: 비 그침 + 땀 닦기(찌그러졌다 펴기) → 0.9초 뒤 뿌듯(점프 + 반짝이 + 라임 빛) · 버튼 켜짐
        k = items.size
        launch { rain.animateTo(0f, tween(800)) }
        pose = ReviewPose.RELIEF
        launch { motion(Body.DIP) }
        delay(900)
        if (done) return@LaunchedEffect
        pose = ReviewPose.PROUD; done = true
        launch { motion(Body.JUMP) }
        launch { glow.animateTo(1f, tween(800)) }
        launch { stars.snapTo(0f); stars.animateTo(1f, tween(800 + 180, easing = LinearEasing)) }
        btn.forEachIndexed { i, a -> launch { delay(i * 70L); a.animateTo(1f, tween(420, easing = Rise)) } }
        counted()
    }

    val say = when {
        loading -> stringResource(R.string.effort_calc_loading)
        done -> stringResource(R.string.effort_review_done, r.driveText,
            if (trip.n <= 1) stringResource(R.string.effort_review_solo) else stringResource(R.string.effort_review_people, trip.n))
        k < 0 -> stringResource(R.string.effort_calc_start)
        else -> sayText(items[k.coerceAtMost(items.lastIndex)].say)
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val skip = {
        if (!done) {
            finishNow()
            scope.launch { glow.snapTo(1f); btn.forEach { it.snapTo(1f) }; rain.snapTo(0f) }
            counted()
        }
    }

    Box(Modifier.fillMaxSize()) {
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
                        Crossfade(say, animationSpec = tween(280, easing = Std), label = "say") { s ->
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
                // 왼쪽: 노고(가로 820 · 분할 360)
                Character(
                    Modifier.width((if (narrow) 360 else 820).gpx).fillMaxHeight(), narrow, reduce, pose, body, { bodyP.value },
                    { glow.value }, { stars.value }, c,
                )
                // 오른쪽: 기준 5줄 + 버튼
                Column(
                    Modifier.weight(1f).fillMaxHeight().padding(
                        start = (if (narrow) 16 else 40).gpx, top = (if (narrow) 16 else 40).gpx,
                        end = (if (narrow) 40 else 64).gpx, bottom = (if (narrow) 48 else 96).gpx,
                    ),
                ) {
                    val gap = (if (narrow) 6 else 8).gpx
                    Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(gap)) {
                        items.forEachIndexed { i, it ->
                            // 다 센 줄은 지금 영수증 값으로 그린다: 도착 날씨가 3초 뒤에 오면 세는 동안의 값("출발할 때 비")이 남아
                            // 영수증("비, 주행 내내")과 어긋났다(11/14 에뮬). 세는 중인 줄만 세는 값
                            val settled = states[i] == RowState.ON || states[i] == RowState.OFF
                            ReviewRow(
                                Modifier.fillMaxWidth().weight(1f).heightIn(min = (if (narrow) 84 else 112).gpx, max = 170.gpx),
                                it, if (settled) (if (it.on) RowState.ON else RowState.OFF) else states[i], if (settled) it.value else shown[i],
                                { ring[i].value }, { pop[i].value }, { fx[i].value }, i == items.lastIndex, gap, narrow, c,
                            )
                        }
                    }
                    // 버튼(세는 동안 숨김 · 자리는 남김) — 다 세면 아래에서 올라오며 나타남(0.42초, 0.07초 차이)
                    Row(Modifier.padding(top = (if (narrow) 24 else 40).gpx), horizontalArrangement = Arrangement.spacedBy((if (narrow) 12 else 16).gpx)) {
                        val lift = { a: Animatable<Float, *> -> Modifier.graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 24f } }
                        ReviewButton(
                            stringResource(if (solo) R.string.effort_review_receipt else R.string.effort_review_consent),
                            if (solo) ReviewBtnIcon.RECEIPT else ReviewBtnIcon.HAND_HEART, filled = true, nudge = done && !reduce,
                            enabled = done, onClick = onConsent, narrow = narrow, modifier = lift(btn[0]),
                        )
                        if (!solo) ReviewButton(
                            stringResource(R.string.effort_review_later), null, filled = false, nudge = false,
                            enabled = done, onClick = onLater, narrow = narrow, modifier = lift(btn[1]),
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
        Modifier.defaultMinSize(minWidth = 120.gpx, minHeight = 80.gpx.coerceAtLeast(MinTouch))
            .background(if (pressed) c.btnTertiaryPressed else Color.Transparent, RoundedCornerShape(24.gpx))
            .clickable(src, indication = null, role = Role.Button, onClick = onClick).padding(horizontal = 30.gpx),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Chagok.type.bodyL, color = c.btnTertiaryText, maxLines = 1) }
}

private val Stars = listOf(Triple(.16f, .30f, Offset(-.6f, -.8f)), Triple(.78f, .24f, Offset(.7f, -.7f)), Triple(.84f, .56f, Offset(.9f, .2f)), Triple(.12f, .58f, Offset(-.8f, .3f)))
private const val STAR_D = "M17 0C18.5 11 23 15.5 34 17C23 18.5 18.5 23 17 34C15.5 23 11 18.5 0 17C11 15.5 15.5 11 17 0Z"

/** 노고 상반신(높이 840 · 분할 480, 아래 9% 흐림) · 표정 0.22초 바뀜 · 몸짓 · 라임 빛 · 반짝이 4개. */
@Composable
private fun Character(
    modifier: Modifier, narrow: Boolean, reduce: Boolean, pose: ReviewPose, body: Body, bodyP: () -> Float,
    glow: () -> Float, stars: () -> Float, c: ChagokColors,
) {
    // 숨쉬기 3.2초(늘). 움직임 줄이기면 멈춤(시안 prefers-reduced-motion)
    val breathe = if (reduce) null else rememberInfiniteTransition(label = "breathe").animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart), label = "b")
    val starPath = remember { PathParser().parsePathString(STAR_D).toPath() }
    val glowColor = if (c.isDark) Color(0x40C8F04A) else Color(0x66C8F04A)
    BoxWithConstraints(modifier) {
        val d = LocalDensity.current
        val gs = (if (narrow) 460 else 760).gpx
        // 라임 빛(다 세면 0.8초에 퍼짐)
        Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = (if (narrow) 250 else 140).gpx).size(gs)
                .graphicsLayer { alpha = glow() }
                .background(Brush.radialGradient(listOf(glowColor, Color.Transparent))),
        )
        val maxH = with(d) { maxHeight.toPx() }
        val want = with(d) { (if (narrow) 480 else 840).gpx.toPx() }
        val bottomPad = if (narrow) 170.gpx else 0.gpx
        val h = with(d) { min(want, maxH - bottomPad.toPx()).coerceAtLeast(0f).toDp() }
        Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPad).height(h).aspectRatio(700f / 754f)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(.5f, .92f)
                    val p = bodyP()
                    when (body) {
                        Body.BREATHE -> if (breathe != null) {
                            val s = ((1 - cos(2 * PI * breathe.value)) / 2).toFloat()
                            val e = EaseInOut.transform(s)
                            translationY = -.009f * size.height * e; scaleX = 1 + .005f * e; scaleY = 1 - .005f * e
                        }
                        Body.SLUMP -> rotationZ = keyframes(p, listOf(0f to 0f, .4f to -3f, .7f to 1.5f, 1f to -1f), EaseOut)
                        Body.DIP -> {
                            translationY = size.height * keyframes(p, listOf(0f to 0f, .35f to .015f, .7f to -.006f, 1f to 0f), EaseOut)
                            scaleX = keyframes(p, listOf(0f to 1f, .35f to 1.035f, .7f to .99f, 1f to 1f), EaseOut)
                            scaleY = keyframes(p, listOf(0f to 1f, .35f to .95f, .7f to 1.02f, 1f to 1f), EaseOut)
                        }
                        Body.JUMP -> {
                            translationY = size.height * keyframes(p, listOf(0f to 0f, .18f to .01f, .48f to -.07f, .78f to 0f, 1f to 0f), JumpE)
                            scaleX = keyframes(p, listOf(0f to 1f, .18f to 1.05f, .48f to .96f, .78f to 1.04f, 1f to 1f), JumpE)
                            scaleY = keyframes(p, listOf(0f to 1f, .18f to .93f, .48f to 1.05f, .78f to .96f, 1f to 1f), JumpE)
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
            Crossfade(pose, animationSpec = tween(220), label = "pose") { p ->
                val (res, alt) = when (p) {
                    ReviewPose.FOCUS -> R.drawable.nogo_v_focus to R.string.effort_review_alt_focus
                    ReviewPose.SHY -> R.drawable.nogo_v_shy to R.string.effort_review_alt_shy
                    ReviewPose.RELIEF -> R.drawable.nogo_v_relief to R.string.effort_review_alt_relief
                    ReviewPose.PROUD -> R.drawable.nogo_v_proud to R.string.effort_review_alt_proud
                }
                Image(painterResource(res), stringResource(alt), Modifier.fillMaxSize(), alignment = Alignment.BottomCenter, contentScale = ContentScale.Fit)
            }
            // 반짝이 4개(0.8초, 0.06초 차이) — 너비 6%, 자기 크기만큼 비율로 날아감
            Canvas(Modifier.fillMaxSize()) {
                val sp = stars()
                if (sp <= 0f || sp >= 1f) return@Canvas
                val sw = size.width * .06f
                Stars.forEachIndexed { i, (l, tp, dv) ->
                    val q = ((sp * 980f - i * 60f) / 800f).coerceIn(0f, 1f)
                    if (q <= 0f || q >= 1f) return@forEachIndexed
                    val e = EaseOut.transform(q)
                    val a = if (q < .3f) q / .3f else 1f - (q - .3f) / .7f
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
    val circle = (if (narrow) 64 else 100).gpx
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
            style = if (narrow) t.bodyL.copy(fontWeight = FontWeight.Bold, fontSize = 24.gsp, lineHeight = 32.gsp) else t.titleM,
            color = if (st == RowState.NOW || st == RowState.ON) c.textPrimary else c.textTertiary, maxLines = 1, softWrap = false,
        )
        // 작은 움직임(세는 줄 · 있음일 때만)
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (st == RowState.NOW && it.on) RowFx(it, fx, narrow, c)
        }
        // 값
        Text(
            value, Modifier.widthIn(min = (if (narrow) 128 else 220).gpx),
            style = (if (narrow) t.bodyL.copy(fontWeight = FontWeight.Bold, fontSize = 26.gsp, lineHeight = 34.gsp) else t.titleL)
                .let { s -> if (st == RowState.OFF) s.copy(fontWeight = FontWeight.Normal) else s },
            color = if (st == RowState.OFF) c.textTertiary else c.textPrimary,
            textAlign = TextAlign.End, maxLines = 1, softWrap = false,
        )
    }
}

/** 줄 안 작은 움직임(시안 rvFx · CSS): 비 9줄 · 눈 · 차 4대 막힘 · 시간 막대 · 별 6개. */
@Composable
private fun RowFx(it: ReviewItem, fx: () -> Float, narrow: Boolean, c: ChagokColors) {
    val loop = rememberInfiniteTransition(label = "fx").animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "l")
    val star = remember { Path().apply { moveTo(.5f, 0f); lineTo(.62f, .38f); lineTo(1f, .5f); lineTo(.62f, .62f); lineTo(.5f, 1f); lineTo(.38f, .62f); lineTo(0f, .5f); lineTo(.38f, .38f); close() } }
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        val u = 1f
        when (it.fx) {
            ReviewFx.RAIN, ReviewFx.SNOW -> {
                val snow = it.fx == ReviewFx.SNOW
                val period = if (snow) 1f else .5f                       // 비 0.7초 · 눈 1.4초(루프 1.4초 기준)
                repeat(9) { i ->
                    val ph = ((loop.value / period) + ((i * .23f) % .7f) / .7f) % 1f
                    val x = w * (.06f + i * .11f) - 22 * u * ph
                    val y = -34 * u + 180 * u * ph
                    if (snow) drawCircle(c.textTertiary, 7 * u, Offset(x, y))
                    else rotate(12f, Offset(x, y)) { drawRoundRect(c.textTertiary, Offset(x, y), Size(4 * u, 30 * u), CornerRadius(2 * u)) }
                }
            }
            ReviewFx.JAM -> {
                val cg = (if (narrow) 17 else 45) * u
                val pw = (if (narrow) 16 else 44) * u; val ph = (if (narrow) 12 else 24) * u
                val p = JamE.transform(fx().coerceIn(0f, 1f))
                repeat(4) { i ->
                    val x0 = w * i * .24f
                    val x1 = w - (4 - i) * cg
                    val x = x0 + (x1 - x0) * p
                    drawRoundRect(if (i == 3) c.textPrimary else c.textTertiary, Offset(x, h / 2 - ph / 2), Size(pw, ph), CornerRadius(ph / 2))
                }
            }
            ReviewFx.BAR -> {
                val right = w - 8 * u; val bh = 14 * u; val top = h / 2 - bh / 2
                val m = right * it.barMark
                drawRoundRect(c.dividerDefault, Offset(0f, top), Size(right, bh), CornerRadius(bh / 2))
                val reveal = right * FillE.transform(fx().coerceIn(0f, 1f) / (1200f / 1200f))
                if (reveal > 0f) {
                    drawRoundRect(c.textTertiary, Offset(0f, top), Size(min(reveal, m), bh), CornerRadius(bh / 2))
                    if (reveal > m) drawRoundRect(Lime, Offset(m - bh / 2, top), Size(reveal - m + bh / 2, bh), CornerRadius(bh / 2))
                }
                drawRect(c.textPrimary, Offset(m, h / 2 - 22 * u), Size(3 * u, 44 * u))
            }
            ReviewFx.NIGHT -> {
                val s = (if (narrow) 16 else 22) * u
                val tops = listOf(.18f, .44f, .24f, .50f, .14f, .38f)
                repeat(6) { i ->
                    val ph = (loop.value * 2f + i * .17f / .7f) % 2f
                    val a = if (ph < 1f) .35f + .65f * ph else .35f + .65f * (2f - ph)
                    translate(w * (.06f + i * .16f), h * tops[i]) { scale(s, s, Offset.Zero) { drawPath(star, c.textPrimary, alpha = a) } }
                }
            }
        }
    }
}

/** 화면 비(시안 THX_DROPS): 굵기 3 · 길이 40 빗줄기가 비스듬히 떨어진다. 세기는 [strength](0..1). */
@Composable
private fun ScreenRain(strength: () -> Float, c: ChagokColors) {
    val loop = rememberInfiniteTransition(label = "rain").animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "r")
    Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = strength() }) {
        val u = 1f
        val col = c.textTertiary.copy(alpha = .35f)
        repeat(42) { i ->
            val seed = (i * 7919) % 100 / 100f
            val ph = (loop.value + seed) % 1f
            val x = size.width * ((i * .0238f + seed * .02f) % 1f) - 60 * u * ph
            val y = -60 * u + (size.height + 120 * u) * ph
            drawLine(col, Offset(x, y), Offset(x - 10 * u, y + 40 * u), 3 * u, StrokeCap.Round)
        }
    }
}

private enum class ReviewBtnIcon { HAND_HEART, RECEIPT }

// [운전자 챙겨주기] 아이콘(시안 HH_* · Phosphor hand-heart를 손 · 하트로 나눔): 하트가 손 실루엣 뒤에서 3.2초마다 잠깐 떴다 내려앉음
private const val HH_HAND = "M232.76,137.88A28.39,28.39,0,0,0,208.13,133L172,141.26c0-.42,0-.84,0-1.26a32,32,0,0,0-32-32H89.94a35.76,35.76,0,0,0-25.45,10.54L43,140H20A20,20,0,0,0,0,160v40a20,20,0,0,0,20,20H120a11.89,11.89,0,0,0,2.91-.36l64-16a11.4,11.4,0,0,0,1.79-.6l38.82-16.54c.23-.09.45-.19.67-.3a28.61,28.61,0,0,0,4.57-48.32ZM36,196H24V164H36Zm181.68-31.39-37.51,16L118.52,196H60V157l21.46-21.46A11.93,11.93,0,0,1,89.94,132H140a8,8,0,0,1,0,16H112a12,12,0,0,0,0,24h32a12.19,12.19,0,0,0,2.69-.3l67-15.41.47-.12a4.61,4.61,0,0,1,5.82,4.44A4.58,4.58,0,0,1,217.68,164.61Z"
private const val HH_SIL = "M232.76,137.88A28.39,28.39,0,0,0,208.13,133L172,141.26c0-.42,0-.84,0-1.26a32,32,0,0,0-32-32H89.94a35.76,35.76,0,0,0-25.45,10.54L43,140H20A20,20,0,0,0,0,160v40a20,20,0,0,0,20,20H120a11.89,11.89,0,0,0,2.91-.36l64-16a11.4,11.4,0,0,0,1.79-.6l38.82-16.54c.23-.09.45-.19.67-.3a28.61,28.61,0,0,0,4.57-48.32Z"
private const val HH_HEART = "M156,42.85A51.77,51.77,0,0,1,192.54,28C220.91,28,244,51.33,244,80C244,97.37,236.19,114.94,220.76,132.49C205.33,150.04,176,170,164.4,178.5Q156,184,147.6,178.5C136,170,106.67,150.04,91.24,132.49C75.81,114.94,68,97.37,68,80C68,51.33,91.09,28,119.46,28A51.77,51.77,0,0,1,156,42.85ZM119.46,52A27.18,27.18,0,0,1,144.9,68.56a12,12,0,0,0,22.2,0A27.18,27.18,0,0,1,192.54,52C207.42,52,220,64.82,220,80c0,18.06-15,38.84-43.38,60.19C168.5,146.3,161,151.5,156,154.5C151,151.5,143.5,146.3,135.38,140.19C107,118.84,92,98.06,92,80C92,64.82,104.58,52,119.46,52Z"

/** 들썩(시안 rv-lift, 3.2초 주기): 0 → 16% 떠오름 → 26% 거의 그대로 → 44% 내려앉음. 0..1 비율. */
private fun liftAt(p: Float): Float = when {
    p < .16f -> CubicBezierEasing(.22f, .6f, .3f, 1f).transform(p / .16f)
    p < .26f -> 1f - .08f * EaseInOut.transform((p - .16f) / .10f)
    p < .44f -> .92f * (1f - CubicBezierEasing(.45f, 0f, .4f, 1f).transform((p - .26f) / .18f))
    else -> 0f
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
            .defaultMinSize(minWidth = 120.gpx, minHeight = (if (narrow) 80 else 100).gpx.coerceAtLeast(MinTouch))
            .drawBehind {
                if (!active()) return@drawBehind
                // 라임 물결(box-shadow 0 → 18px 투명, 앞 28%)
                val q = ((cycle?.value ?: 0f) / .28f).coerceIn(0f, 1f)
                if (q >= 1f) return@drawBehind
                val spread = (if (narrow) 12f else 18f) * PopE.transform(q)
                drawRoundRect(nudgeColor.copy(alpha = nudgeColor.alpha * (1f - q)), Offset(-spread, -spread), Size(size.width + spread * 2, size.height + spread * 2), CornerRadius(radius.toPx() + spread))
            }
            .background(bg, RoundedCornerShape(radius))
            .clickable(src, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = (if (narrow) 20 else 38).gpx),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy((if (narrow) 10 else 12).gpx, Alignment.CenterHorizontally),
    ) {
        val isz = (if (narrow) 28 else 32).gpx
        when (icon) {
            ReviewBtnIcon.HAND_HEART -> Canvas(Modifier.size(isz).graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
                val s = size.width / 256f
                val lift = if (active()) liftAt((cycle?.value ?: 0f)) else 0f
                scale(s, s, Offset.Zero) {
                    translate(0f, -34f * lift) { drawPath(hh.third, fg) }
                    drawPath(hh.second, Color.Black, blendMode = BlendMode.Clear)
                    drawPath(hh.first, fg)
                }
            }
            ReviewBtnIcon.RECEIPT -> Box(Modifier.graphicsLayer { translationY = if (active()) -3f * liftAt((cycle?.value ?: 0f)) else 0f }) {
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
