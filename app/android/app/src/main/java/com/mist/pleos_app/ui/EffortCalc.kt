package com.mist.pleos_app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import com.mist.pleos_app.R
import com.mist.pleos_app.effort.CalcFx
import com.mist.pleos_app.effort.CalcItem
import com.mist.pleos_app.effort.CalcSay
import com.mist.pleos_app.effort.EffortUi
import com.mist.pleos_app.engine.roundHalfUp
import com.mist.pleos_app.ui.components.CgButton
import com.mist.pleos_app.ui.components.CgButtonKind
import com.mist.pleos_app.ui.components.CgLoadingDots
import com.mist.pleos_app.ui.components.CgSize
import com.mist.pleos_app.ui.components.CgTitleBar
import com.mist.pleos_app.ui.theme.Chagok
import com.mist.pleos_app.ui.theme.ChagokColors
import com.mist.pleos_app.ui.theme.gpx
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.sin

/*
 * 계산 연출(Design v2 `design/screens/calc.html`, HANDOFF_v2 3-1, #75 명준): 도착하면 영수증 전에 내 차가 체크포인트 6개를
 * 차례로 들르며 오늘 운전을 센다. 시간·곡선은 가이드 그대로(줄인 안 없음). [건너뛰기]는 늘 보이고 누르면 바로 영수증.
 * 움직이는 건 graphicsLayer·Canvas 그리기 단계에서만 읽는다(HANDOFF 4절). 그림(차·구름·모래시계)은 시안 SVG 경로 그대로.
 */

private val Move = CubicBezierEasing(.45f, 0f, .25f, 1f)
private val Launch = CubicBezierEasing(.3f, 1.3f, .5f, 1f)
private val Pop = CubicBezierEasing(.175f, .885f, .32f, 1.275f)
private val Crouch = CubicBezierEasing(.3f, 0f, .3f, 1f)
private val Hop = CubicBezierEasing(.3f, 0f, .3f, 1f)
private val Jolt = CubicBezierEasing(.2f, .8f, .3f, 1f)
private val Rise = CubicBezierEasing(.2f, .7f, .2f, 1f)
private val Flip = CubicBezierEasing(.6f, 0f, .2f, 1f)
private val Tick = CubicBezierEasing(.4f, 0f, .6f, 1f)
private val EaseOutCss = CubicBezierEasing(0f, 0f, .58f, 1f)
private val EaseInOutCss = CubicBezierEasing(.42f, 0f, .58f, 1f)

/** CSS 키프레임처럼 구간마다 곡선을 다시 적용: p(0..1)가 [a, b] 안에서 몇 % 왔는지에 easing. */
private fun seg(p: Float, a: Float, b: Float, e: CubicBezierEasing): Float = e.transform(((p - a) / (b - a)).coerceIn(0f, 1f))

private const val FX_MS = 1750f

@Composable
internal fun EffortCalcScreen(ui: EffortUi, items: List<CalcItem>, onDone: () -> Unit) {
    val trip = ui.openTrip ?: return
    val r = ui.receipt ?: return
    val narrow = LocalNarrow.current
    val reduce = rememberReduceMotion()
    val done by rememberUpdatedState(onDone)
    val pending by rememberUpdatedState(ui.weatherPending)
    val n = items.size

    // 상태(모두 Animatable·State — 그리기 단계에서 읽음)
    var loading by remember { mutableStateOf(true) }
    var step by remember { mutableIntStateOf(-1) }            // 지금 선 체크포인트
    var moving by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    val car = remember { Animatable(-1f) }                     // 체크포인트 위치(0..n-1). 시작 자리는 -1 → 아래 st로
    val fill = remember { Animatable(0f) }
    val crouch = remember { Animatable(0f) }
    val jolt = remember { Animatable(0f) }
    val hop = remember { Animatable(0f) }
    val total = remember { Animatable(0f) }
    val pops = remember(n) { List(n) { Animatable(0f) } }
    val vals = remember(n) { List(n) { Animatable(0f) } }     // 값 글자 위치(팝)
    val valAs = remember(n) { List(n) { Animatable(0f) } }    // 값 글자 투명도
    val fx = remember(n) { List(n) { Animatable(0f) } }

    LaunchedEffect(trip.tripId) {
        // 도착 처리 중(1.2초) — 도착 날씨를 받는 중이면 조금 더 기다린다(최대 3초, AC-C-6)
        delay(1200)
        withTimeoutOrNull(3000) { snapshotFlow { pending }.first { !it } }
        loading = false
        if (reduce) {
            // 시안 playCalc: 움직임 줄이기면 원 6개를 다 채우고 차를 마지막 원에, 합계·끝 문구를 보인 뒤 1.5초 뒤 영수증
            pops.forEach { it.snapTo(1f) }; vals.forEach { it.snapTo(1f) }; valAs.forEach { it.snapTo(1f) }
            car.snapTo((n - 1).toFloat()); fill.snapTo((n - 1).toFloat()); total.snapTo(1f)
            finished = true
            delay(1500); done(); return@LaunchedEffect
        }
        delay(500)
        // 진행 줄 문구는 차가 그 체크포인트로 움직이기 시작할 때 바꾼다(시안 prog.textContent = items[i].say)
        step = 0
        // ① 웅크림 0.26초 → ③ 튀어 나감 0.46초(v3: 전기차라 연기 없음). 튀어 나감(cg-jolt)은 웅크린 끝 모양에서 이어 시작
        moving = true
        crouch.animateTo(1f, tween(260, easing = Crouch))
        crouch.snapTo(0f)
        jolt.snapTo(0f)
        launch { jolt.animateTo(1f, tween(460, easing = LinearEasing)) }   // 구간별 곡선은 그릴 때 적용
        car.animateTo(0f, tween(460, easing = Launch))
        moving = false
        for (i in 0 until n) {
            if (i > 0) {
                step = i
                moving = true
                launch { fill.animateTo(i.toFloat(), tween(550, easing = Move)) }
                car.animateTo(i.toFloat(), tween(550, easing = Move))
                moving = false
            }
            launch { pops[i].animateTo(1f, tween(380, easing = LinearEasing)) }
            // 값 글자: 투명도 .25초 ease-out · 위치 .35초 팝(시안 .cn .vl transition)
            launch { valAs[i].animateTo(1f, tween(250, easing = EaseOutCss)) }
            launch { vals[i].animateTo(1f, tween(350, easing = Pop)) }
            launch { fx[i].animateTo(FX_MS, tween(FX_MS.toInt(), easing = LinearEasing)) }
            delay(1400)                                          // ⑦ 그래픽이 사라지는 중에 다음 이동
        }
        // ⑧ 마지막: 통통 두 번 + 합계 팝, 1.9초 뒤 영수증
        finished = true
        launch { total.animateTo(1f, tween(500, easing = Pop)) }
        repeat(2) { hop.snapTo(0f); hop.animateTo(1f, tween(500, easing = LinearEasing)) }   // 구간(0–40–100%)별 곡선은 그릴 때
        delay(900)
        done()
    }

    val c = Chagok.colors
    val t = Chagok.type
    val say = when {
        loading -> stringResource(R.string.effort_calc_loading)
        finished -> stringResource(R.string.effort_calc_done)
        step < 0 -> stringResource(R.string.effort_calc_start)
        else -> sayText(items[step].say)
    }
    Column(Modifier.fillMaxSize()) {
        CgTitleBar(
            stringResource(R.string.effort_arrive_title, trip.destName), compact = narrow,
            trailing = { CgButton(stringResource(R.string.effort_calc_skip), { done() }, kind = CgButtonKind.Basic, size = CgSize.S) },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.gpx)) {
            Text(say, style = t.bodyL, color = c.textSecondary)   // 시안 .cprog = body-l(가로·분할 같음)
            if (loading) CgLoadingDots()
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val d = LocalDensity.current
            val w = with(d) { maxWidth.toPx() }
            val h = with(d) { maxHeight.toPx() }
            val px = { v: Int -> v.toFloat() }                  // 가이드 값 = 디스플레이 실제 픽셀(theme gpx와 같은 규칙)
            // 창(1700 × 1184) 기준 시안 좌표를 이 칸 기준으로 옮김(머리 줄 높이만큼 빼고, 앞뒤 여백은 그대로)
            val headH = px(80 + 40)
            val geo = if (!narrow) {
                val left = px(320 - 40); val right = w - px(240 - 40)
                Geo(false, left, right, px(620 - 32) - headH, 0f, 0f)
            } else {
                val top = px(352 - 32) - headH; val bottom = h - px(178 - 32)
                Geo(true, px(308 - 40), 0f, 0f, top, bottom)
            }
            Rail(geo, n, fill, px(4), c)
            items.forEachIndexed { i, it -> Node(geo, i, n, it, pops[i], vals[i], valAs[i], fx[i], px, c, t) }
            Car(geo, n, car, crouch, jolt, hop, moving, px, c)
            // 합계
            Column(
                Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(bottom = if (narrow) 0.gpx else (150 - 32).gpx)
                    .graphicsLayer { alpha = total.value.coerceIn(0f, 1f); translationY = (1f - total.value) * 24f; scaleX = .96f + .04f * total.value; scaleY = scaleX },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.gpx),
            ) {
                Text(stringResource(R.string.effort_calc_total, r.driveText), style = if (narrow) t.headlineS else t.headlineM, color = c.textPrimary)
                Text(r.multiplierText, style = if (narrow) t.bodyL else t.titleM, color = c.textSecondary)
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
        CalcSay.DIST_GROUP -> R.string.effort_calc_say_dist_group
        CalcSay.DIST_SOLO -> R.string.effort_calc_say_dist_solo
    },
)

/** 레일 위치: 가로는 left..right(y 고정), 세로는 top..bottom(x 고정). */
private data class Geo(val vertical: Boolean, val a: Float, val b: Float, val y: Float, val top: Float, val bottom: Float) {
    fun at(p: Float, n: Int): Offset = if (!vertical) Offset(a + (b - a) * p / (n - 1), y) else Offset(a, top + (bottom - top) * p / (n - 1))
}

@Composable
private fun Rail(g: Geo, n: Int, fill: Animatable<Float, *>, sw: Float, c: ChagokColors) {
    Canvas(Modifier.fillMaxSize()) {
        val s = g.at(0f, n); val e = g.at((n - 1).toFloat(), n); val f = g.at(fill.value, n)   // 채움은 그리기 단계에서 읽음
        // v3: 뉴트럴 트랙 + 지나온 선(야간 라임 · 주간 잉크)
        drawLine(c.progressTrack, s, e, strokeWidth = sw)
        drawLine(c.progressLine, s, f, strokeWidth = sw)
    }
}

/** 체크포인트: 원(빈 → 채움 팝) + 이름 + 값 + 위쪽 그래픽 칸. */
@Composable
private fun Node(
    g: Geo, i: Int, n: Int, it: CalcItem, pop: Animatable<Float, *>, value: Animatable<Float, *>, valueA: Animatable<Float, *>,
    fx: Animatable<Float, *>, px: (Int) -> Float, c: ChagokColors, t: com.mist.pleos_app.ui.theme.ChagokType,
) {
    val o = g.at(i.toFloat(), n)
    val dot = if (g.vertical) 52 else 60
    val zeroColor = c.textDisabled
    // 원
    Box(
        Modifier.offset { IntOffset((o.x - px(dot) / 2).toInt(), (o.y - px(dot) / 2).toInt()) }.size(dot.gpx)
            .graphicsLayer {
                val p = pop.value
                // cg-pop: 0–60% · 60–100% 두 구간 모두 같은 팝 곡선(CSS 키프레임 구간별 timing)
                val s = if (p <= 0f) 1f else if (p < .6f) .55f + (1.2f - .55f) * seg(p, 0f, .6f, Pop) else 1.2f - .2f * seg(p, .6f, 1f, Pop)
                scaleX = s; scaleY = s
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val on = pop.value > 0f
            val r = size.minDimension / 2
            // v3: 지나면 라임 원(테두리 4, 주간 잉크 · 야간 라임), 0인 항목은 꺼진 색, 아직이면 바탕색 + 트랙색 테두리 6(분할 5)
            val k = size.width / dot
            // 야간: 채운 원 둘레 8px 라임 halo(시안 .screen.dark .cn.on .dot box-shadow 0 0 0 8px #D9F77A26)
            if (on && !it.zero && c.isDark) drawCircle(Color(0x26D9F77A), r + 8 * k)
            when {
                on && it.zero -> drawCircle(zeroColor, r)
                on -> { val bw = 4 * k; drawCircle(c.progressIndicator, r - bw / 2); drawCircle(c.progressIndicatorBorder, r - bw / 2, style = Stroke(bw)) }
                else -> { val bw = (if (g.vertical) 5 else 6) * k; drawCircle(c.bgPrimary, r - bw / 2); drawCircle(c.progressTrack, r - bw / 2, style = Stroke(bw)) }
            }
        }
    }
    val lbStyle = if (g.vertical) t.bodyMB else t.bodyL
    val vlStyle = if (it.zero) (if (g.vertical) t.bodyM else t.bodyL) else (if (g.vertical) t.bodyL else t.titleS)
    // 값이 없을 때: 조회 실패 "정보 없음", 맑은 날씨 "맑음"(시안 calcItems), 그 밖 "없음"
    val noneText = when {
        it.unknown -> stringResource(R.string.effort_calc_unknown)
        it.key == "weather" && it.zero -> stringResource(R.string.effort_calc_clear)
        else -> stringResource(R.string.effort_calc_none)
    }
    val v = it.value ?: noneText
    val vlMod = Modifier.graphicsLayer { alpha = valueA.value.coerceIn(0f, 1f); translationY = (1f - value.value) * px(10) }
    val vlColor = if (it.zero) c.textTertiary else c.textPrimary
    if (!g.vertical) {
        // 시안 .cn .lb top 52 · .vl top 96(원 가운데 기준), 폭 300 가운데 정렬
        Text(it.label, Modifier.offset { IntOffset((o.x - px(150)).toInt(), (o.y + px(52)).toInt()) }.width(300.gpx),
            style = lbStyle, color = c.textSecondary, maxLines = 1, textAlign = TextAlign.Center)
        Text(v, Modifier.offset { IntOffset((o.x - px(150)).toInt(), (o.y + px(96)).toInt()) }.width(300.gpx).then(vlMod),
            style = vlStyle, color = vlColor, maxLines = 1, textAlign = TextAlign.Center)
        FxSlot(it, fx, Modifier.offset { IntOffset((o.x - px(150)).toInt(), (o.y - px(54) - px(240)).toInt()) }.size(300.gpx, 240.gpx), 1f, c)
    } else {
        // 시안 .calc.p .cn .lb left 52 · top -38, .vl left 52 · top 0
        Text(it.label, Modifier.offset { IntOffset((o.x + px(52)).toInt(), (o.y - px(38)).toInt()) },
            style = lbStyle, color = c.textSecondary, maxLines = 1)
        Text(v, Modifier.offset { IntOffset((o.x + px(52)).toInt(), o.y.toInt()) }.then(vlMod),
            style = vlStyle, color = vlColor, maxLines = 1)
        FxSlot(it, fx, Modifier.offset { IntOffset((o.x + px(250)).toInt(), (o.y - px(66)).toInt()) }.size(165.gpx, 132.gpx), .55f, c)
    }
}

/** 항목 그래픽 칸(300 × 240, 분할은 .55배). 1.75초: 나타남 14% · 머묾 · 80%부터 위로 흩어지며 사라짐(cg-fx). */
@Composable
private fun FxSlot(it: CalcItem, fx: Animatable<Float, *>, modifier: Modifier, k: Float, c: ChagokColors) {
    val tm = rememberTextMeasurer()
    val car = remember { NogoCarPaths() }
    val cloud = remember { PathParser().parsePathString(CLOUD).toPath() }
    val hg = remember { PathParser().parsePathString(HOURGLASS).toPath() }
    val sand = remember { PathParser().parsePathString(SAND).toPath() }
    val honk = stringResource(R.string.effort_calc_honk)
    val zip = stringResource(R.string.effort_calc_zip)
    val none = stringResource(R.string.effort_calc_none)
    Canvas(modifier) {
        val ms = fx.value
        if (ms <= 0f || ms >= FX_MS) return@Canvas
        val p = ms / FX_MS
        // 등장·퇴장 껍데기
        val (alpha, dy, sc) = when {
            p < .14f -> Triple(p / .14f, 18f * (1 - p / .14f), .86f + (1.04f - .86f) * (p / .14f))
            p < .22f -> Triple(1f, 0f, 1.04f - .04f * ((p - .14f) / .08f))
            p < .8f -> Triple(1f, 0f, 1f)
            else -> { val q = (p - .8f) / .2f; Triple(1f - q, -24f * q * q, 1f) }
        }
        val u = size.width / 300f                              // 칸 300 기준 → px
        withTransform({
            translate(0f, dy * u)
            scale(sc, sc, Offset(size.width / 2, size.height))
        }) {
            drawFx(it, ms, u, alpha, c, tm, car, cloud, hg, sand, honk, zip, none)
        }
    }
}

private fun DrawScope.drawFx(
    it: CalcItem, ms: Float, u: Float, a: Float, c: ChagokColors, tm: TextMeasurer,
    car: NogoCarPaths, cloud: Path, hg: Path, sand: Path, honk: String, zip: String, none: String,
) {
    val ink = c.textPrimary.copy(alpha = c.textPrimary.alpha * a)
    val face = c.surfaceCard.copy(alpha = c.surfaceCard.alpha * a)
    val dim = c.textTertiary.copy(alpha = c.textTertiary.alpha * a)
    fun drawCloud() {
        translate(65 * u, 0f) { scale(u, u, Offset.Zero) { drawPath(cloud, face); drawPath(cloud, ink, style = Stroke(5f, join = StrokeJoin.Round)) } }
    }
    fun tag(text: String, x: Float, y: Float, size: Float, appear: Float) {
        val q = ((ms - appear) / 500f).coerceIn(0f, 1f)
        if (q <= 0f) return
        val s = Pop.transform(q)
        val layout = tm.measure(text, TextStyle(fontSize = (size * u / density).sp, fontWeight = FontWeight.ExtraBold, color = ink))
        val pad = 10 * u
        val w = layout.size.width + pad * 2; val h = layout.size.height + pad * 1.4f
        scale(s, s, Offset(x + w / 2, y + h / 2)) {
            drawRoundRect(face, Offset(x, y), Size(w, h), androidx.compose.ui.geometry.CornerRadius(16 * u))
            drawRoundRect(ink, Offset(x, y), Size(w, h), androidx.compose.ui.geometry.CornerRadius(16 * u), style = Stroke(3 * u))
            drawText(layout, topLeft = Offset(x + pad, y + pad * .7f))
        }
    }
    when (it.fx) {
        CalcFx.RAIN -> {
            drawCloud()
            for (i in 0 until 9) {
                val ph = (((ms / 600f) + (i * 0.37f % 0.6f) / 0.6f) % 1f)
                val e = ph * ph                                        // ease-in
                val x = (72 + i * 19) * u; val y = (86 + 120 * e) * u
                drawLine(ink.copy(alpha = ink.alpha * (1 - ph * .6f)), Offset(x, y), Offset(x, y + 26 * u), strokeWidth = 5 * u, cap = StrokeCap.Round)
            }
        }
        CalcFx.SNOW -> {
            drawCloud()
            for (i in 0 until 8) {
                val ph = (((ms / 1400f) + (i * 0.53f % 1.4f) / 1.4f) % 1f)
                val x = (68 + i * 21 + 14 * sin(ph * 2 * PI).toFloat()) * u; val y = (84 + 128 * ph) * u
                drawCircle(face, 9 * u, Offset(x + 9 * u, y + 9 * u)); drawCircle(ink, 9 * u, Offset(x + 9 * u, y + 9 * u), style = Stroke(4 * u))
            }
        }
        CalcFx.LEAVES, CalcFx.SUN -> {
            val cx = 150 * u; val cy = 42 * u
            rotate(360f * (ms / 5000f), Offset(cx, cy)) {
                drawCircle(dim, 56 * u, Offset(cx, cy), style = Stroke(6 * u, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2 * u, 12 * u)), cap = StrokeCap.Round))
            }
            drawCircle(face, 32 * u, Offset(cx, cy)); drawCircle(ink, 32 * u, Offset(cx, cy), style = Stroke(5 * u))
            if (it.fx == CalcFx.LEAVES) for (i in 0 until 5) {
                val ph = (((ms / 1600f) + i * 0.29f) % 1f)
                val x = (36 + i * 48 + 20 * sin(ph * 2 * PI).toFloat()) * u; val y = (60 + 150 * ph) * u
                rotate(320f * ph, Offset(x + 16 * u, y + 9 * u)) { drawOval(dim, Offset(x, y), Size(32 * u, 18 * u)) }
            }
        }
        CalcFx.JAM -> {
            val crawl = 20 * (1 - (1 - ms / FX_MS).let { it * it })
            for (i in 0 until 3) {
                val jit = 3 * sin((ms / 160f + i * .3f) * PI).toFloat()
                drawNogoCar(car, Offset((12 + i * 96 + crawl) * u, (150 + jit) * u), 86 * u, otherCarTone(illustOf(c.isDark)), 0f, a)   // 정체: 다른 차
                tag(honk, (12 + i * 96 + 18) * u, (150 - 64) * u, 24f, 200f + i * 360f)
            }
        }
        CalcFx.ZIP -> {
            val q = (ms / 1100f).coerceIn(0f, 1f)
            // cg-zip: left 20에서 translateX(-20 → 170) = x 0 → 190, 말풍선은 .fx-zip .tg (110, 70)
            drawNogoCar(car, Offset((190 * Flip.transform(q)) * u, 150 * u), 86 * u, myCarTone(illustOf(c.isDark), c.isDark), 0f, a)
            tag(zip, 110 * u, 70 * u, 28f, 350f)
        }
        CalcFx.LATE -> {
            val cx = 150 * u; val cy = 85 * u
            drawCircle(face, 55 * u, Offset(cx, cy)); drawCircle(ink, 52 * u, Offset(cx, cy), style = Stroke(6 * u))
            // cg-spin .5초 × 3번, 바퀴마다 cubic-bezier(.4,0,.6,1)
            val turns = if (ms >= 1500f) 3f else (ms / 500f).toInt() + Tick.transform((ms % 500f) / 500f)
            rotate(360f * turns, Offset(cx, cy)) { drawLine(ink, Offset(cx, cy), Offset(cx, cy - 38 * u), strokeWidth = 6 * u, cap = StrokeCap.Round) }
            drawCircle(ink, 7 * u, Offset(cx, cy))
            it.tag?.let { s -> tag(s, 180 * u, 60 * u, 28f, 350f) }
        }
        CalcFx.NIGHT -> {
            // 달(.moon 96px, left 100 · top 34): box-shadow inset -26px 10px → 원에서 (-26, +10) 옮긴 원을 뺀 초승달(오른쪽 위가 밝음, 속은 투명)
            // cg-rise 1.2초 cubic-bezier(.2,.7,.2,1): translateY(40) rotate(-20°) → 제자리, 가운데 기준
            val q = Rise.transform((ms / 1200f).coerceIn(0f, 1f))
            val mc = Offset(148 * u, 82 * u); val mr = 48 * u
            translate(0f, 40 * u * (1 - q)) {
                rotate(-20f * (1 - q), mc) {
                    val disc = Path().apply { addOval(androidx.compose.ui.geometry.Rect(mc, mr)) }
                    val cut = Path().apply { addOval(androidx.compose.ui.geometry.Rect(mc + Offset(-26 * u, 10 * u), mr)) }
                    drawPath(Path().apply { op(disc, cut, PathOperation.Difference) }, ink)
                }
            }
            // 별(.st 20px, 네 갈래 clip-path): cg-twk .7초 ease-in-out 왕복, scale .4→1.15 · 투명도 .3→1, 시작 어긋남 i×0.23초
            listOf(44f to 40f, 226f to 30f, 70f to 150f, 236f to 136f).forEachIndexed { i, (x, y) ->
                val ph = ((ms / 1000f + i * .23f) % 1.4f) / .7f
                val e = EaseInOutCss.transform(if (ph > 1f) 2f - ph else ph)
                val s = .4f + .75f * e
                val cx = (x + 10) * u; val cy = (y + 10) * u; val h = 10 * u * s; val m = 2.4f * u * s   // 38% → 가운데에서 12% = 2.4px
                val star = Path().apply {
                    moveTo(cx, cy - h); lineTo(cx + m, cy - m); lineTo(cx + h, cy); lineTo(cx + m, cy + m)
                    lineTo(cx, cy + h); lineTo(cx - m, cy + m); lineTo(cx - h, cy); lineTo(cx - m, cy - m); close()
                }
                drawPath(star, ink.copy(alpha = ink.alpha * (.3f + .7f * e)))
            }
        }
        CalcFx.LONG -> {
            // cg-flip: 0–35% 그대로 · 35–65% 뒤집기(cubic-bezier(.6,0,.2,1)) · 65–100% 그대로
            val q = seg(ms / FX_MS, .35f, .65f, Flip)
            translate(110 * u, 24 * u) {
                rotate(180f * q, Offset(40 * u, 62 * u)) {
                    scale(u, u, Offset.Zero) {
                        drawPath(hg, ink, style = Stroke(6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        drawPath(sand, ink)
                    }
                }
            }
            it.tag?.let { s -> tag(s, 180 * u, 60 * u, 28f, 350f) }
        }
        CalcFx.DIST -> {
            val q = (ms / 900f).coerceIn(0f, 1f)
            val eased = 1 - (1 - q) * (1 - q) * (1 - q)
            val km = roundHalfUp((it.km ?: 0) * eased.toDouble())   // 시안 Math.round(반올림 규칙: roundHalfUp)
            val big = tm.measure("$km", TextStyle(fontSize = (56 * u / density).sp, fontWeight = FontWeight.ExtraBold, color = ink))
            val unit = tm.measure("km", TextStyle(fontSize = (32 * u / density).sp, fontWeight = FontWeight.Bold, color = c.textSecondary.copy(alpha = c.textSecondary.alpha * a)))   // small = txt2
            val wsum = big.size.width + 6 * u + unit.size.width
            drawText(big, topLeft = Offset((size.width / 1f - wsum) / 2, 46 * u))
            drawText(unit, topLeft = Offset((size.width - wsum) / 2 + big.size.width + 6 * u, 46 * u + big.size.height - unit.size.height - 6 * u))
            val shift = (ms / 350f % 1f) * 52 * u
            var x = 20 * u - shift
            while (x < size.width - 20 * u) {
                val x0 = x.coerceAtLeast(20 * u); val x1 = (x + 30 * u).coerceAtMost(size.width - 20 * u)
                if (x1 > x0) drawLine(dim, Offset(x0, 195 * u), Offset(x1, 195 * u), strokeWidth = 6 * u)
                x += 52 * u
            }
            val jit = 2 * sin((ms / 180f) * PI).toFloat()
            drawNogoCar(car, Offset(107 * u, (150 + jit) * u), 86 * u, myCarTone(illustOf(c.isDark), c.isDark), 0f, a)
        }
        CalcFx.NONE -> {
            val q = Pop.transform(((ms - 100f) / 450f).coerceIn(0f, 1f))
            val layout = tm.measure(none, TextStyle(fontSize = (32 * u / density).sp, fontWeight = FontWeight.Bold, color = dim))
            val w = layout.size.width + 48 * u; val h = layout.size.height + 20 * u
            scale(q, q, Offset(90 * u + w / 2, 110 * u + h / 2)) {
                drawRoundRect(face, Offset(90 * u, 110 * u), Size(w, h), androidx.compose.ui.geometry.CornerRadius(20 * u))
                drawRoundRect(c.borderStrong, Offset(90 * u, 110 * u), Size(w, h), androidx.compose.ui.geometry.CornerRadius(20 * u),
                    style = Stroke(3 * u, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8 * u, 6 * u))))
                drawText(layout, topLeft = Offset(114 * u, 120 * u))
            }
        }
    }
}

@Composable
private fun Car(
    g: Geo, n: Int, pos: Animatable<Float, *>, crouch: Animatable<Float, *>, jolt: Animatable<Float, *>, hop: Animatable<Float, *>,
    moving: Boolean, px: (Int) -> Float, c: ChagokColors,
) {
    val paths = remember { NogoCarPaths() }
    val tone = myCarTone(illustOf(c.isDark), c.isDark)
    val inf = rememberInfiniteTransition(label = "car")
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(300, easing = LinearEasing)), label = "wheel")
    val bob by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(320, easing = EaseInOutCss), RepeatMode.Reverse), label = "bob")
    // v97: 가로 창 105px × 1.275배(1.5배에서 15% 줄임, 앞 범퍼 · 바닥 기준) · 분할 77px × 0.85배(시안 .calc.p .cc-o)
    val k = if (g.vertical) .85f else 1.275f
    val carW = px(if (g.vertical) 77 else 105) * k; val carH = carW * 70f / 170f
    Canvas(Modifier.fillMaxSize()) {
        // 위치: 체크포인트 p의 원 앞(가로: 원 왼쪽 36px, 세로: 원 위 28px). 시작 자리(-1)는 첫 원 앞보다 60px 뒤(가로만)
        val p = pos.value
        val base = g.at(p.coerceAtLeast(0f), n)
        val back = if (p < 0f) -p * px(60) else 0f
        val cr = crouch.value
        val jl = jolt.value
        val jolting = jl > 0f && jl < 1f
        // cg-bob(위로 1.5px 왕복): 웅크림·튀어 나감 중에는 그 애니메이션이 덮어써서 없음
        val bobY = if (moving && cr == 0f && !jolting) -1.5f * bob else 0f
        // cg-hop: 40%에 -14px, 0–40% · 40–100% 구간마다 cubic-bezier(.3,0,.3,1)
        val hp = hop.value
        val hopY = when {
            hp <= 0f || hp >= 1f -> 0f
            hp < .4f -> -14f * seg(hp, 0f, .4f, Hop)
            else -> -14f * (1f - seg(hp, .4f, 1f, Hop))
        }
        // cg-jolt: 0% scale(1.04,.88) → 35% translateX(3px) scale(.97,1.05) → 100% 제자리, 구간마다 cubic-bezier(.2,.8,.3,1)
        var jsx = 1f; var jsy = 1f; var jtx = 0f
        if (jolting) {
            if (jl < .35f) { val q = seg(jl, 0f, .35f, Jolt); jsx = 1.04f + (.97f - 1.04f) * q; jsy = .88f + (1.05f - .88f) * q; jtx = 3f * q }
            else { val q = seg(jl, .35f, 1f, Jolt); jsx = .97f + .03f * q; jsy = 1.05f - .05f * q; jtx = 3f * (1f - q) }
        }
        val wheel = if (moving || cr > 0f) spin else 0f
        // 차 그림 틀 안(왼쪽 위 0,0 · carW × carH) 변형: 시안 svg 좌표의 px는 .cc-o 배율(k)을 곱해 옮김
        val body: DrawScope.() -> Unit = {
            // cg-rev(웅크림): translateX(-4px) scale(1.04,.88), 바닥 가운데 기준
            withTransform({ translate(-4f * k * cr, 0f); scale(1f + .04f * cr, 1f - .12f * cr, Offset(carW / 2, carH)) }) {
                // 튀어 나감은 가운데 기준(rev 클래스가 빠져 transform-origin 기본값)
                withTransform({ translate(jtx * k, (bobY + hopY) * k); scale(jsx, jsy, Offset(carW / 2, carH / 2)) }) {
                    drawNogoCar(paths, Offset.Zero, carW, tone, wheel)
                }
            }
        }
        if (!g.vertical) {
            // 시안 .cc-car top -4 · .cc-o translateY(5px) scale(1.275) 오른쪽 아래 기준 → 차 상자 바닥 = 레일 가운데 + 3px(바퀴가 선에 닿음)
            translate(base.x - back - px(36) - carW, base.y + px(3) - carH) { body() }
        } else {
            // 세로 레일: 시안 .calc.p .cc-o rotate(-90deg) scaleX(-1) → 앞이 아래, 바퀴가 오른쪽(레일)
            // 차 상자(32 × 77) 가운데 = 레일 가운데 왼쪽 18px · 원 위 28 + 38.5px
            val cx = base.x - px(18); val cy = base.y - px(28) - 38.5f
            rotate(-90f, Offset(cx, cy)) {
                scale(-1f, 1f, Offset(cx, cy)) { translate(cx - carW / 2, cy - carH / 2) { body() } }
            }
        }
    }
}

// 시안 SVG 경로(design/screens/calc.html, prototype)
private const val CLOUD = "M38 74 C14 74 8 50 26 42 C24 20 50 10 66 22 C76 2 112 2 120 26 C142 22 160 38 152 56 C162 66 152 76 140 74 Z"
private const val HOURGLASS = "M8 4 H72 M8 120 H72 M14 4 C14 40 40 50 40 62 C40 74 14 84 14 120 M66 4 C66 40 40 50 40 62 C40 74 66 84 66 120"
private const val SAND = "M22 112 Q40 92 58 112 Z"
