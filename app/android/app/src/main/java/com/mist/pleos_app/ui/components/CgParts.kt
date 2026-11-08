package com.mist.pleos_app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import com.mist.pleos_app.ui.theme.Chagok
import com.mist.pleos_app.ui.theme.gpx
import kotlinx.coroutines.delay

/*
 * Design v2 부품(design/components의 html·css, #75 명준) — 조각 하나에 Compose 부품 하나(HANDOFF_v2 5절).
 * 크기는 Connect-S 실제 픽셀(gpx), 색·글자는 테마 토큰만. 화면 코드는 이 부품을 조립만 한다.
 * 문구는 부르는 쪽이 strings.xml·엔진 출력으로 넘긴다.
 */

private val EaseRise = CubicBezierEasing(.2f, .7f, .2f, 1f)
private val EaseIn = CubicBezierEasing(.32f, .72f, 0f, 1f)
private val EaseRot = CubicBezierEasing(.65f, 0f, .35f, 1f)

// ------------------------------------------------------------ 아이콘(에셋 4종: 뒤로 · + · − · 화살표) + 자리
enum class CgIcon { Back, Arrow, Plus, Minus, Chevron, Placeholder }

/** 에셋 아이콘을 선으로 그린다(design/icons/pleos). 에셋에 없는 아이콘은 점선 자리(Placeholder). */
@Composable
fun CgIconMark(icon: CgIcon, size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size).graphicsLayer { if (icon == CgIcon.Placeholder) alpha = .45f }) {
        val w = this.size.width
        val sw = w * .1f
        when (icon) {
            CgIcon.Back -> {
                drawLine(color, Offset(w * .62f, w * .18f), Offset(w * .3f, w * .5f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * .3f, w * .5f), Offset(w * .62f, w * .82f), sw, StrokeCap.Round)
            }
            CgIcon.Arrow -> {
                drawLine(color, Offset(w * .35f, w * .15f), Offset(w * .7f, w * .5f), w * .12f, StrokeCap.Round)
                drawLine(color, Offset(w * .7f, w * .5f), Offset(w * .35f, w * .85f), w * .12f, StrokeCap.Round)
            }
            CgIcon.Plus -> {
                drawLine(color, Offset(w * .2f, w * .5f), Offset(w * .8f, w * .5f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * .5f, w * .2f), Offset(w * .5f, w * .8f), sw, StrokeCap.Round)
            }
            CgIcon.Minus -> drawLine(color, Offset(w * .2f, w * .5f), Offset(w * .8f, w * .5f), sw, StrokeCap.Round)
            CgIcon.Chevron -> {
                drawLine(color, Offset(0f, w * .3f), Offset(w * .5f, w * .8f), w * .25f, StrokeCap.Round)
                drawLine(color, Offset(w * .5f, w * .8f), Offset(w, w * .3f), w * .25f, StrokeCap.Round)
            }
            CgIcon.Placeholder -> drawRoundRect(color, cornerRadius = CornerRadius(w * .3f), style = Stroke(w * .075f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))))
        }
    }
}

/** Icon Button 80 · 64(`components/icon-button`). plain = 면 없음(위 바 뒤로). */
@Composable
fun CgIconButton(label: String, icon: CgIcon, onClick: () -> Unit, modifier: Modifier = Modifier, small: Boolean = false, plain: Boolean = false, enabled: Boolean = true) {
    val c = Chagok.colors
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s = if (small) 64 else 80
    Box(
        modifier.size(s.gpx)
            .background(if (pressed) c.btnBasicPressed else if (plain) Color.Transparent else c.btnBasic, RoundedCornerShape((if (small) 16 else 20).gpx))
            .clickable(src, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { CgIconMark(icon, (if (small) 44 else 56).gpx * (if (icon == CgIcon.Placeholder) .7f else 1f), if (enabled) c.textPrimary else c.textTertiary) }
}

// ------------------------------------------------------------ cta · text-button
/** CTA(`components/cta`): Basic Button L 채움 변형 — 알약 + 화살표 원. busy면 꺼진 모양 + 글자만. */
@Composable
fun CgCta(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false, enabled: Boolean = true, busy: Boolean = false, fill: Boolean = false) {
    val c = Chagok.colors
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val on = enabled && !busy
    val shape = RoundedCornerShape(999.gpx)
    val fg = if (on) c.btnFilledText else c.textTertiary
    Row(
        modifier.graphicsLayer { val k = if (pressed && on) .98f else 1f; scaleX = k; scaleY = k }
            .height(if (compact) 80.gpx else 100.gpx)
            .background(if (!on) c.btnDisabled else if (pressed) c.btnFilledPressed else c.btnFilled, shape)
            .then(if (!on) Modifier.border(2.gpx, c.line, shape) else Modifier)
            .clickable(src, indication = null, enabled = on, role = Role.Button, onClick = onClick)
            .padding(start = if (compact) 28.gpx else 40.gpx, end = if (compact) 8.gpx else 12.gpx),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.gpx),
    ) {
        Text(text, Modifier.then(if (fill) Modifier.weight(1f) else Modifier), style = if (compact) Chagok.type.bodyL else Chagok.type.titleS, color = fg, maxLines = 1)
        Box(Modifier.size(if (compact) 60.gpx else 76.gpx).background(fg.copy(alpha = .15f), CircleShape), contentAlignment = Alignment.Center) {
            CgIconMark(CgIcon.Arrow, (if (compact) 32 else 40).gpx, fg)
        }
    }
}

/** 글자 버튼(`components/text-button`, 면 없음): primary = 진한 글자(확인·다시 시도), 아니면 옅은 글자(닫기). */
@Composable
fun CgTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, compact: Boolean = false) {
    val c = Chagok.colors
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Box(
        modifier.heightIn(min = 72.gpx).background(if (pressed) c.btnBasicPressed else Color.Transparent, RoundedCornerShape(36.gpx))
            .clickable(src, indication = null, role = Role.Button, onClick = onClick).padding(horizontal = if (compact) 12.gpx else 22.gpx),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = (if (compact) Chagok.type.bodyL else Chagok.type.titleS).copy(fontWeight = if (primary) FontWeight.ExtraBold else FontWeight.Bold),
            color = if (primary) c.textPrimary else c.textTertiary, maxLines = 1)
    }
}

// ------------------------------------------------------------ title-bar · pill
/**
 * 위 바(`components/title-bar`, Title Main · Depth 80).
 * centered = 가운데 제목(출발·홈), 아니면 왼쪽 큰 이름(도착·계산 연출). leading·trailing은 아이콘·알약·버튼 자리.
 */
@Composable
fun CgTitleBar(
    title: String,
    modifier: Modifier = Modifier,
    centered: Boolean = false,
    compact: Boolean = false,
    titleStyle: TextStyle? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val t = Chagok.type
    Box(modifier.fillMaxWidth().height(80.gpx)) {
        if (centered) {
            leading?.let { Box(Modifier.align(Alignment.CenterStart)) { it() } }
            Text(title, Modifier.align(Alignment.Center), style = titleStyle ?: (if (compact) t.titleM else t.titleL), color = Chagok.colors.textPrimary, maxLines = 1)
            trailing?.let { Box(Modifier.align(Alignment.CenterEnd)) { it() } }
        } else {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.gpx)) {
                leading?.invoke()
                Text(title, Modifier.weight(1f), style = titleStyle ?: (if (compact) t.titleL else t.headlineS), color = Chagok.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                trailing?.invoke()
            }
        }
    }
}

/** 알약(`components/pill`): 인원 "함께 4명 ⌄". */
@Composable
fun CgPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val c = Chagok.colors
    Row(
        modifier.height(if (compact) 56.gpx else 64.gpx).background(c.btnBasic, RoundedCornerShape(999.gpx))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = if (compact) 16.gpx else 22.gpx),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.gpx),
    ) {
        Text(text, style = if (compact) Chagok.type.bodyMB else Chagok.type.bodyL, color = c.textPrimary, maxLines = 1)
        CgIconMark(CgIcon.Chevron, 12.gpx, c.textTertiary)
    }
}

/** 진행 중 알약(`components/pill` cg-prog): 1.2초 깜빡이는 점 + 글자 + ›. */
@Composable
fun CgProgressPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val c = Chagok.colors
    val inf = rememberInfiniteTransition(label = "dot")
    val a by inf.animateFloat(.25f, 1f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "a")
    Row(
        modifier.height(if (compact) 64.gpx else 80.gpx).background(c.surfaceCard, RoundedCornerShape(999.gpx))
            .border(2.gpx, c.textPrimary, RoundedCornerShape(999.gpx)).clickable(role = Role.Button, onClick = onClick)
            .padding(start = if (compact) 20.gpx else 28.gpx, end = if (compact) 18.gpx else 24.gpx),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.gpx),
    ) {
        Box(Modifier.size(14.gpx).graphicsLayer { alpha = a }.background(c.textPrimary, CircleShape))
        Text(text, style = if (compact) Chagok.type.bodyMB else Chagok.type.bodyL, color = c.textPrimary, maxLines = 1)
        Text("›", style = Chagok.type.bodyL, color = c.textTertiary)
    }
}

// ------------------------------------------------------------ search-field
/** 검색 한 줄(`components/search-field`, Text Field 100 알약): 가로 1040 가운데, 입력 중이면 테두리 3 + [지우기]. */
@Composable
fun CgSearchField(query: String, hint: String, clearLabel: String, onQuery: (String) -> Unit, onSearch: () -> Unit, onClear: () -> Unit, compact: Boolean = false) {
    val c = Chagok.colors
    val active = query.isNotEmpty()
    val shape = RoundedCornerShape(50.gpx)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            (if (compact) Modifier.fillMaxWidth() else Modifier.width(1040.gpx))
                .height(if (compact) 88.gpx else 100.gpx)
                .shadow(6.gpx, shape).background(c.surfaceCard, shape)
                .border(if (active) 3.gpx else 2.gpx, if (active) c.textPrimary else c.line, shape)
                .padding(start = if (compact) 26.gpx else 32.gpx, end = 14.gpx),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.gpx),
        ) {
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text(hint, style = Chagok.type.bodyL.copy(fontWeight = FontWeight.Normal), color = c.textTertiary)
                BasicTextField(
                    value = query, onValueChange = { onQuery(it.take(100)) }, singleLine = true,
                    textStyle = Chagok.type.bodyL.copy(color = c.textPrimary), cursorBrush = SolidColor(c.textPrimary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint },
                )
            }
            if (active) CgButton(clearLabel, onClear, kind = CgButtonKind.Basic, size = CgSize.S)
        }
    }
}

// ------------------------------------------------------------ dest-card · record-card · preview-panel
/** 목적지 카드(`components/dest-card`): 바깥 자리 면 8 + 안쪽 카드. 고르면 바깥이 진해진다. */
@Composable
fun CgDestCard(tag: String, name: String, meta: String?, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val c = Chagok.colors
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val inner = RoundedCornerShape(32.gpx)
    Box(
        modifier.height(if (compact) 200.gpx else 320.gpx).background(if (selected) c.textPrimary else c.placeholder, RoundedCornerShape(40.gpx))
            .clickable(src, indication = null, role = Role.Button, onClick = onClick).padding(8.gpx),
    ) {
        Column(
            Modifier.fillMaxSize().graphicsLayer { val k = if (pressed) .985f else 1f; scaleX = k; scaleY = k }
                .shadow(4.gpx, inner).background(c.surfaceCard, inner)
                .padding(start = 28.gpx, end = 28.gpx, top = if (compact) 18.gpx else 28.gpx, bottom = if (compact) 14.gpx else 22.gpx),
            verticalArrangement = Arrangement.spacedBy(6.gpx),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.gpx)) {
                CgIconMark(CgIcon.Placeholder, 28.gpx, c.textTertiary)
                Text(tag, style = Chagok.type.bodyS.copy(fontWeight = FontWeight.Normal), color = c.textTertiary, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            Text(name, style = if (compact) Chagok.type.titleS else Chagok.type.titleL, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            meta?.let { Text(it, style = Chagok.type.bodyM, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** 기록 카드(`components/record-card`): 날짜(방금) · 목적지 · 요약 · 고른 것. fresh = 방금 끝난 기록(굵은 테두리 3, 0.7초 올라옴). */
@Composable
fun CgRecordCard(tag: String, title: String, summary: String, note: String, modifier: Modifier = Modifier, fresh: Boolean = false, compact: Boolean = false, key: Any = title) {
    val c = Chagok.colors
    val t = Chagok.type
    val shape = RoundedCornerShape(32.gpx)
    val rise = remember(key) { Animatable(if (fresh) 0f else 1f) }
    LaunchedEffect(key) { if (fresh) rise.animateTo(1f, tween(700, easing = EaseIn)) }
    Column(
        modifier.graphicsLayer { alpha = rise.value; translationY = (1f - rise.value) * 24f * density }
            .shadow(6.gpx, shape).background(c.surfaceCard, shape)
            .border(if (fresh) 3.gpx else 2.gpx, if (fresh) c.textPrimary else c.line, shape)
            .padding(horizontal = if (compact) 28.gpx else 30.gpx, vertical = if (compact) 18.gpx else 26.gpx),
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.gpx else 4.gpx),
    ) {
        Text(tag, style = t.bodyS.copy(fontWeight = if (fresh) FontWeight.ExtraBold else FontWeight.Normal), color = if (fresh) c.textPrimary else c.textTertiary)
        if (!compact) Spacer(Modifier.weight(1f))
        Text(title, style = if (compact) t.titleS else t.titleM, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(summary, style = t.bodyM, color = c.textSecondary, maxLines = 1)
        Text(note, style = t.labelM, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 미리보기 칸 상태(`components/preview-panel`). */
enum class CgPreviewState { Empty, Loading, Ready, Failed }

/**
 * 미리보기 칸(`components/preview-panel`): 빈 자리(점선) · 불러오는 중(회색 막대) · 준비(통계 3칸) · 실패(다시 시도).
 * 가로는 왼쪽 글 + 오른쪽 CTA, 분할은 위아래.
 */
@Composable
fun CgPreviewPanel(
    state: CgPreviewState, name: String?, stats: List<Pair<String, String>>, note: String, emptyText: String,
    failText: String, retryLabel: String, onRetry: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false,
    cta: @Composable (Modifier) -> Unit,
) {
    val c = Chagok.colors
    val t = Chagok.type
    val shape = RoundedCornerShape(40.gpx)
    if (state == CgPreviewState.Empty || name == null) {
        Box(modifier.heightIn(min = 240.gpx).cgDashed(40.gpx, c.lineStrong).padding(28.gpx), contentAlignment = Alignment.Center) {
            Text(emptyText, style = t.bodyL.copy(fontWeight = FontWeight.Normal), color = c.textTertiary)
        }
        return
    }
    val body: @Composable ColumnScope.() -> Unit = {
        Text(name, style = t.titleL, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        when (state) {
            CgPreviewState.Loading -> Row(horizontalArrangement = Arrangement.spacedBy(48.gpx)) { repeat(3) { CgSkeleton(Modifier.width(150.gpx).height(30.gpx)) } }
            CgPreviewState.Failed -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.gpx)) {
                Text(failText, style = t.bodyL.copy(fontWeight = FontWeight.Normal), color = c.textSecondary)
                CgTextButton(retryLabel, onRetry, primary = true)
            }
            else -> Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 20.gpx else 48.gpx)) {
                stats.forEach { (k, v) ->
                    Column(Modifier.widthIn(min = if (compact) 0.gpx else 150.gpx), verticalArrangement = Arrangement.spacedBy(2.gpx)) {
                        Text(k, style = t.labelM, color = c.textTertiary)
                        Text(v, style = if (compact) t.bodyL else t.titleS, color = c.textPrimary, maxLines = 1)
                    }
                }
            }
        }
        Text(note, style = if (compact) t.labelM else t.bodyM, color = c.textTertiary)
    }
    if (!compact) {
        Row(
            modifier.shadow(6.gpx, shape).background(c.surfaceCard, shape).border(2.gpx, c.line, shape).padding(start = 44.gpx, end = 28.gpx, top = 28.gpx, bottom = 28.gpx),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.gpx),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.gpx), content = body)
            cta(Modifier)
        }
    } else {
        Column(modifier.shadow(6.gpx, shape).background(c.surfaceCard, shape).border(2.gpx, c.line, shape).padding(24.gpx), verticalArrangement = Arrangement.spacedBy(10.gpx)) {
            body()
            Spacer(Modifier.height(12.gpx))
            cta(Modifier.fillMaxWidth())
        }
    }
}

// ------------------------------------------------------------ people-alert · stepper
/** 스테퍼(`components/stepper`, 알림 바 안 변형): [−] 값 [+]. */
@Composable
fun CgStepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit, minusLabel: String, plusLabel: String, minusEnabled: Boolean, plusEnabled: Boolean, compact: Boolean = false) {
    val c = Chagok.colors
    Row(Modifier.height(if (compact) 64.gpx else 80.gpx).background(c.btnBasic, RoundedCornerShape(40.gpx)), verticalAlignment = Alignment.CenterVertically) {
        CgIconButton(minusLabel, CgIcon.Minus, onMinus, small = compact, plain = true, enabled = minusEnabled)
        Text(value, Modifier.widthIn(min = if (compact) 64.gpx else 96.gpx), style = if (compact) Chagok.type.bodyL else Chagok.type.titleM, color = c.textPrimary, textAlign = TextAlign.Center)
        CgIconButton(plusLabel, CgIcon.Plus, onPlus, small = compact, plain = true, enabled = plusEnabled)
    }
}

/**
 * 인원 확인 알림 바(`components/people-alert`, 1120 × 120): 위 바 줄에 겹치는 얇은 알림. 저절로 안 사라짐.
 * 0.42초 (.32,.72,0,1) 위 16px · scale .98 → 제자리.
 */
@Composable
fun CgPeopleAlert(title: String, sub: String?, modifier: Modifier = Modifier, compact: Boolean = false, content: @Composable RowScope.() -> Unit) {
    val c = Chagok.colors
    val drop = remember { Animatable(0f) }
    LaunchedEffect(Unit) { drop.animateTo(1f, tween(420, easing = EaseIn)) }
    val shape = RoundedCornerShape(60.gpx)
    Row(
        modifier.then(if (compact) Modifier.fillMaxWidth().padding(start = 96.gpx) else Modifier.width(1120.gpx))
            .height(if (compact) 104.gpx else 120.gpx)
            .graphicsLayer { alpha = drop.value; translationY = -(1f - drop.value) * 16f * density; val k = .98f + .02f * drop.value; scaleX = k; scaleY = k }
            .shadow(16.gpx, shape).background(c.surfaceCard, shape).border(1.gpx, c.lineStrong, shape)
            .padding(start = if (compact) 26.gpx else 40.gpx, end = 10.gpx),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 12.gpx else 20.gpx),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.gpx)) {
            Text(title, style = if (compact) Chagok.type.bodyL else Chagok.type.titleM.copy(fontWeight = FontWeight.ExtraBold), color = c.textPrimary, maxLines = 1)
            if (!compact && sub != null) Text(sub, style = Chagok.type.labelS, color = c.textTertiary, maxLines = 1)
        }
        content()
    }
}

/** 알림 바 안 세로 구분선. */
@Composable
fun CgDivider() { Box(Modifier.width(2.gpx).height(56.gpx).background(Chagok.colors.line)) }

// ------------------------------------------------------------ bottom-sheet
/** 짧은 하단 시트(`components/bottom-sheet`): 딤·끌기 없음, 0.38초 (.2,.7,.2,1) 아래 115% → 제자리. 위에 손잡이. */
@Composable
fun CgBottomSheet(modifier: Modifier = Modifier, compact: Boolean = false, delayMs: Long = 0, content: @Composable ColumnScope.() -> Unit) {
    val c = Chagok.colors
    val y = remember { Animatable(1.15f) }
    LaunchedEffect(Unit) { delay(delayMs); y.animateTo(0f, tween(380, easing = EaseRise)) }
    val shape = RoundedCornerShape(32.gpx)
    Column(
        modifier.graphicsLayer { translationY = y.value * size.height }
            .shadow(20.gpx, shape).background(c.surfaceCard, shape).border(2.gpx, c.lineStrong, shape)
            .padding(start = if (compact) 24.gpx else 40.gpx, end = if (compact) 24.gpx else 40.gpx, top = if (compact) 14.gpx else 16.gpx, bottom = if (compact) 24.gpx else 32.gpx),
        verticalArrangement = Arrangement.spacedBy(if (compact) 14.gpx else 16.gpx),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).width(80.gpx).height(6.gpx).background(c.lineStrong, RoundedCornerShape(3.gpx)))
        content()
    }
}

// ------------------------------------------------------------ offer-card
/** 제안 카드 틀(`components/offer-card`): 카드 면 + 선 2 + 모서리 32. 가로 640 고정은 부르는 쪽이. */
@Composable
fun CgOfferCard(modifier: Modifier = Modifier, compact: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val c = Chagok.colors
    val shape = RoundedCornerShape(32.gpx)
    Column(
        modifier.background(c.surfaceCard, shape).border(2.gpx, c.lineStrong, shape)
            .padding(horizontal = if (compact) 24.gpx else 40.gpx, vertical = if (compact) 22.gpx else 40.gpx),
        verticalArrangement = Arrangement.spacedBy(if (compact) 12.gpx else 20.gpx),
        content = content,
    )
}

/** 방식 B 항목 버튼(`components/offer-card` cg-opt, Basic Button L 100): 기본값만 채움. words가 여럿이면 2초마다 다음 말. */
@Composable
fun CgOptionButton(words: List<String>, isDefault: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val c = Chagok.colors
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val bg = if (isDefault) (if (pressed) c.btnFilledPressed else c.btnFilled) else (if (pressed) c.btnBasicPressed else c.btnBasic)
    val fg = if (isDefault) c.btnFilledText else c.textPrimary
    var i by remember(words) { mutableIntStateOf(0) }
    if (words.size > 1) LaunchedEffect(words) { while (true) { delay(2000); i = (i + 1) % words.size } }
    Box(
        modifier.heightIn(min = if (compact) 96.gpx else 100.gpx).background(bg, RoundedCornerShape(if (compact) 24.gpx else 32.gpx))
            .clickable(src, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (compact) 20.gpx else 36.gpx, vertical = 8.gpx),
        contentAlignment = Alignment.CenterStart,
    ) {
        AnimatedContent(
            targetState = words[i % words.size],
            transitionSpec = { slideInVertically(tween(320, easing = EaseRot)) { it } togetherWith slideOutVertically(tween(320, easing = EaseRot)) { -it } },
            label = "opt",
        ) { w -> Text(w, style = if (compact) Chagok.type.bodyL else Chagok.type.titleS, color = fg, maxLines = 1) }
    }
}

// ------------------------------------------------------------ receipt
/**
 * 영수증 = 출력 종이(`components/receipt`): 프린터 슬롯 + 슬롯에서 내려오는 종이(슬롯 밖은 잘림) + 지그재그 끝.
 * shift = 종이 위치(-1.05 → 0, 출력 연출). scroll = 종이가 칸보다 길 때 안에서 넘김.
 */
@Composable
fun CgReceipt(modifier: Modifier = Modifier, compact: Boolean = false, shift: Float = 0f, fill: Boolean = true, scroll: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val c = Chagok.colors
    val slotH = if (compact) 40.gpx else 60.gpx
    val slotShape = RoundedCornerShape(if (compact) 14.gpx else 20.gpx)
    Column(modifier) {
        Box(
            Modifier.fillMaxWidth().height(slotH).zIndex(2f).shadow(6.gpx, slotShape)
                .background(c.btnBasic, slotShape).background(c.bgPrimary.copy(alpha = 0.6f), slotShape)
                .border(2.gpx, c.lineStrong, slotShape).padding(horizontal = if (compact) 28.gpx else 44.gpx),
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.fillMaxWidth().height(if (compact) 6.gpx else 8.gpx).background(c.textPrimary, RoundedCornerShape(4.gpx))) }
        Box(
            Modifier.then(if (fill) Modifier.weight(1f) else Modifier).padding(horizontal = if (compact) 16.gpx else 36.gpx)
                .offset(y = -slotH / 2).clipToBounds(),
        ) {
            Column(Modifier.graphicsLayer { translationY = shift * size.height }.then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
                Column(
                    Modifier.fillMaxWidth().background(c.surfaceCard)
                        .padding(start = if (compact) 28.gpx else 56.gpx, end = if (compact) 28.gpx else 56.gpx, top = if (compact) 24.gpx else 48.gpx, bottom = if (compact) 10.gpx else 24.gpx),
                    content = content,
                )
                CgZigzag(Modifier.fillMaxWidth().height(12.gpx), c.surfaceCard)
            }
        }
    }
}

/** 영수증 줄: 이름 · 값 · % · 꺾쇠. 누르면 아래에 근거 칸(extra는 굵은 첫 줄). hi = 바뀐 값 회색 강조(0..1). */
@Composable
fun CgReceiptRow(
    label: String, detail: String, pct: String, basis: String?, expanded: Boolean, onClick: () -> Unit,
    compact: Boolean = false, extra: String? = null, hi: Float = 0f,
) {
    val c = Chagok.colors
    val t = Chagok.type
    val plain = if (compact) t.bodyM else t.bodyL.copy(fontWeight = FontWeight.Normal)
    Column(Modifier.fillMaxWidth().background(c.placeholderStrong.copy(alpha = c.placeholderStrong.alpha * hi), RoundedCornerShape(8.gpx))) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = if (compact) 54.gpx else 64.gpx).then(if (basis != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.gpx),
        ) {
            Text(label, Modifier.width(if (compact) 140.gpx else 230.gpx), style = plain, color = c.textPrimary)
            Text(detail, Modifier.weight(1f), style = plain, color = c.textSecondary)
            Text(pct, Modifier.width(if (compact) 84.gpx else 120.gpx), style = if (compact) t.bodyL else t.titleS, color = c.textPrimary, textAlign = TextAlign.End, maxLines = 1)
            Box(Modifier.size(if (compact) 18.gpx else 26.gpx), contentAlignment = Alignment.Center) {
                if (basis != null) CgIconMark(CgIcon.Chevron, if (compact) 12.gpx else 14.gpx, c.textTertiary, Modifier.graphicsLayer { rotationZ = if (expanded) 180f else 0f })
            }
        }
        if (expanded && basis != null) {
            Column(
                Modifier.fillMaxWidth().padding(bottom = 10.gpx).background(c.placeholder, RoundedCornerShape(16.gpx)).padding(horizontal = 20.gpx, vertical = 14.gpx),
                verticalArrangement = Arrangement.spacedBy(4.gpx),
            ) {
                extra?.let { Text(it, style = if (compact) t.bodyMB else t.bodyL, color = c.textPrimary) }
                Text(basis, style = if (extra != null) t.labelM else t.bodyM, color = if (extra != null) c.textTertiary else c.textSecondary)
            }
        }
    }
}

/** 영수증 소계·합계 줄. trailing = [바꾸기] 같은 작은 버튼. */
@Composable
fun CgReceiptSum(label: String, value: String, compact: Boolean = false, total: Boolean = false, hi: Float = 0f, trailing: @Composable () -> Unit = {}) {
    val c = Chagok.colors
    val t = Chagok.type
    val plain = if (compact) t.bodyM else t.bodyL.copy(fontWeight = FontWeight.Normal)
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (compact) 46.gpx else 52.gpx).background(c.placeholderStrong.copy(alpha = c.placeholderStrong.alpha * hi), RoundedCornerShape(8.gpx)),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.gpx),
    ) {
        Text(label, Modifier.weight(1f), style = if (total) (if (compact) t.bodyL else t.titleS) else plain, color = c.textPrimary)
        Text(value, style = if (total) (if (compact) t.titleS else t.titleM) else (if (compact) t.bodyL else t.titleS), color = c.textPrimary, maxLines = 1)
        trailing()
    }
}

/** 영수증 이중선. */
@Composable
fun CgDoubleRule(modifier: Modifier = Modifier) {
    val c = Chagok.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.gpx)) {
        Box(Modifier.fillMaxWidth().height(2.gpx).background(c.textPrimary))
        Box(Modifier.fillMaxWidth().height(2.gpx).background(c.textPrimary))
    }
}

/** 영수증 점선(소계 위). */
@Composable
fun CgDashedRule(modifier: Modifier = Modifier) {
    val color = Chagok.colors.lineStrong
    Canvas(modifier.fillMaxWidth().height(2.gpx)) {
        drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = size.height, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
    }
}

/** 영수증 끝 지그재그(24px 간격 톱니). */
@Composable
fun CgZigzag(modifier: Modifier, color: Color) {
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

/** 바코드(장식) + 번호. */
@Composable
fun CgBarcode(code: String, modifier: Modifier = Modifier) {
    val c = Chagok.colors
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.gpx)) {
        Canvas(Modifier.fillMaxWidth().height(56.gpx)) {
            val unit = size.height / 56f
            val bars = listOf(0f to 3f, 6f to 7f, 11f to 15f, 17f to 18f)
            var x = 0f
            while (x < size.width) {
                bars.forEach { (a, b) -> if (x + a * unit < size.width) drawRect(c.textPrimary, Offset(x + a * unit, 0f), Size(((b - a) * unit).coerceAtMost(size.width - x - a * unit), size.height)) }
                x += 23f * unit
            }
        }
        Text(code, style = Chagok.type.labelM.copy(letterSpacing = Chagok.type.labelM.fontSize * 0.1f), color = c.textPrimary)
    }
}

// ------------------------------------------------------------ loading · empty · notice
/** 기다림 회색 막대(1.1초 깜빡, 투명 .45 ↔ 1). */
@Composable
fun CgSkeleton(modifier: Modifier) {
    val inf = rememberInfiniteTransition(label = "skel")
    val a by inf.animateFloat(.45f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "a")
    Box(modifier.graphicsLayer { alpha = a }.background(Chagok.colors.placeholderStrong, RoundedCornerShape(8.gpx)))
}

/** 기다림 점 세 개(1초, 0.15초씩 지연). */
@Composable
fun CgLoadingDots(color: Color = Chagok.colors.textSecondary) {
    val inf = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(6.gpx), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val a by inf.animateFloat(.25f, 1f, infiniteRepeatable(keyframes { durationMillis = 1000; .25f at 0; 1f at 300 + i * 150; .25f at 1000 }), label = "d$i")
            Box(Modifier.size(10.gpx).graphicsLayer { alpha = a }.background(color, CircleShape))
        }
    }
}

/** 빈 상태(`components/empty`): 점선 상자 + 제목 + 안내. */
@Composable
fun CgEmpty(title: String, hint: String, modifier: Modifier = Modifier) {
    val c = Chagok.colors
    Column(
        modifier.cgDashed(32.gpx, c.lineStrong).padding(horizontal = 32.gpx),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.gpx, Alignment.CenterVertically),
    ) {
        Text(title, style = Chagok.type.titleS, color = c.textPrimary, textAlign = TextAlign.Center)
        Text(hint, style = Chagok.type.bodyM, color = c.textTertiary, textAlign = TextAlign.Center)
    }
}

/** 알약 배너(`components/notice`): 에러·안내 한 줄. */
@Composable
fun CgBanner(text: String, modifier: Modifier = Modifier) {
    val c = Chagok.colors
    Box(modifier.fillMaxWidth().background(c.btnBasic, RoundedCornerShape(999.gpx)).padding(horizontal = 28.gpx, vertical = 16.gpx)) {
        Text(text, style = Chagok.type.bodyM, color = c.textSecondary)
    }
}

/** 점선 테두리 3px. */
fun Modifier.cgDashed(radius: Dp, color: Color): Modifier = this.drawBehind {
    drawRoundRect(color, cornerRadius = CornerRadius(radius.toPx()), style = Stroke(3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
}

/** 원 안의 그림 자리(홈·시작 화면 일러스트 틀). */
@Composable
fun CgIllustrationCircle(size: Dp, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.size(size).background(Chagok.colors.placeholder, CircleShape), content = content)
}
