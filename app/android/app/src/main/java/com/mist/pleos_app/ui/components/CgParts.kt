package com.mist.pleos_app.ui.components

import androidx.compose.foundation.selection.toggleable
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.layout.layout
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
import com.mist.pleos_app.R
import com.mist.pleos_app.ui.theme.Chagok
import com.mist.pleos_app.ui.theme.ChagokMotion
import com.mist.pleos_app.ui.theme.ChagokRadius
import com.mist.pleos_app.ui.theme.cgElevation
import com.mist.pleos_app.ui.theme.gpx
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.runtime.mutableStateOf
import com.mist.pleos_app.ui.theme.gsp
import kotlinx.coroutines.delay

/*
 * Design v2 부품(design/components의 html·css, #75 명준) — 조각 하나에 Compose 부품 하나(HANDOFF_v2 5절).
 * 크기는 Connect-S 실제 픽셀(gpx), 색·글자는 테마 토큰만. 화면 코드는 이 부품을 조립만 한다.
 * 문구는 부르는 쪽이 strings.xml·엔진 출력으로 넘긴다.
 */

// 옛 private 곡선 EaseRise(.2,.7,.2,1) → ChagokMotion.Sheet, EaseIn(.32,.72,0,1) → ChagokMotion.Rise로 옮겼다(Design v2 PR-2)
private val EaseRot = CubicBezierEasing(.65f, 0f, .35f, 1f)

/**
 * 딥 잉크(주야 같음) + 투명도. 값 = `btnFilledText`(라임 위 잉크 글자, 주간 · 야간 모두 #242135, v4).
 * 사진 칸 그늘 · CTA 화살표 원처럼 테마와 상관없이 잉크인 자리에 쓴다(v3.1 하드코딩 `0x..242033` 자리, v4는 토큰을 따라 #242135).
 */
@Composable
private fun inkAlpha(alpha: Int): Color = Chagok.colors.btnFilledText.copy(alpha = alpha / 255f)

// ------------------------------------------------------------ 아이콘
/**
 * Pleos 에셋 4종(뒤로 · + · − · 화살표, Pleos design assets 2.1.5 `design/icons/pleos` → res/drawable/ic_pl_*.xml, Design v2 PR-3),
 * Phosphor Bold(#118 design/icons/phosphor, MIT → res/drawable/ic_ph_*.xml), 그리고 선으로 그리는 펼침 · 자리.
 * 벡터는 모두 색을 쓰는 자리 글자색으로 칠한다(HANDOFF_v3 3절).
 */
/** 워드마크 Nogo(`design/brand/nogo_wordmark_deepink.svg`, 152 × 48 기준). 색 = brandWordmark(주간 딥 잉크 · 야간 라임). */
@Composable
fun CgWordmark(width: Dp, modifier: Modifier = Modifier, label: String? = null) {
    androidx.compose.foundation.Image(
        androidx.compose.ui.res.painterResource(R.drawable.nogo_wordmark), contentDescription = label,
        modifier = modifier.size(width, width * 48f / 152f),
        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Chagok.colors.brandWordmark),
    )
}

/**
 * 앱 아이콘 타일(`design/brand/nogo_icon_*-gradient.svg`, 로고 v2 · 모서리 48에서 10.68): 주간 라임 · 야간 잉크 그라디언트. 시작 화면 192.
 * 그림자는 주간만(아티팩트 `.screen.dark .splash .appicon{filter:none}`).
 */
@Composable
fun CgAppIcon(size: Dp, modifier: Modifier = Modifier) {
    val c = Chagok.colors
    val shade = c.textPrimary.copy(alpha = 0x24 / 255f)   // 주간 textPrimary(v4 #242135)
    androidx.compose.foundation.Image(
        androidx.compose.ui.res.painterResource(if (c.isDark) R.drawable.nogo_icon_ink_gradient else R.drawable.nogo_icon_lime_gradient),
        contentDescription = null,
        modifier = modifier.size(size).then(
            if (c.isDark) Modifier else Modifier.shadow(8.gpx, RoundedCornerShape(size * 10.68f / 48f), ambientColor = shade, spotColor = shade),
        ),
    )
}

/** [pleos] = Pleos 에셋(아이콘 버튼 안 56 · 작으면 44). 아니면 Phosphor(48 · 40). res 없음 = 선으로 그림. */
enum class CgIcon(@androidx.annotation.DrawableRes val res: Int? = null, val pleos: Boolean = false) {
    Back(R.drawable.ic_pl_back_56, true), Arrow(R.drawable.ic_pl_arrow_40, true),
    Plus(R.drawable.ic_pl_plus_56, true), Minus(R.drawable.ic_pl_minus_44, true),
    Chevron, Placeholder,
    Receipt(R.drawable.ic_ph_receipt), Gear(R.drawable.ic_ph_gear_six), Users(R.drawable.ic_ph_users_three),
    Search(R.drawable.ic_ph_magnifying_glass), Mic(R.drawable.ic_ph_microphone),
    House(R.drawable.ic_ph_house), Briefcase(R.drawable.ic_ph_briefcase), Recent(R.drawable.ic_ph_clock_counter_clockwise),
    WifiSlash(R.drawable.ic_ph_wifi_slash), Warning(R.drawable.ic_ph_warning_circle), Map(R.drawable.ic_ph_map_trifold),
    Steering(R.drawable.ic_ph_steering_wheel), TurnRight(R.drawable.ic_ph_arrow_bend_up_right),
    Rain(R.drawable.ic_ph_cloud_rain), Snow(R.drawable.ic_ph_snowflake), Traffic(R.drawable.ic_ph_traffic_cone),
    Delay(R.drawable.ic_ph_clock_countdown), Moon(R.drawable.ic_ph_moon), Hourglass(R.drawable.ic_ph_hourglass_medium), Sun(R.drawable.ic_ph_sun),
    Check(R.drawable.ic_ph_check_circle), Info(R.drawable.ic_ph_info), Star(R.drawable.ic_ph_star),
    Bell(R.drawable.ic_ph_bell), BellRinging(R.drawable.ic_ph_bell_ringing), CarProfile(R.drawable.ic_ph_car_profile),
}

/** 벡터 아이콘(Pleos · Phosphor)은 painterResource + tint. 펼침(Chevron)과 점선 자리(Placeholder)만 선으로 그린다. */
@Composable
fun CgIconMark(icon: CgIcon, size: Dp, color: Color, modifier: Modifier = Modifier) {
    icon.res?.let { r ->
        androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(r), contentDescription = null, modifier = modifier.size(size), tint = color)
        return
    }
    Canvas(modifier.size(size).graphicsLayer { if (icon == CgIcon.Placeholder) alpha = .45f }) {
        val w = this.size.width
        when (icon) {
            CgIcon.Chevron -> {
                drawLine(color, Offset(0f, w * .3f), Offset(w * .5f, w * .8f), w * .25f, StrokeCap.Round)
                drawLine(color, Offset(w * .5f, w * .8f), Offset(w, w * .3f), w * .25f, StrokeCap.Round)
            }
            CgIcon.Placeholder -> drawRoundRect(color, cornerRadius = CornerRadius(w * .3f), style = Stroke(w * .075f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))))
            else -> Unit                                    // 벡터 아이콘은 위에서 그림
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
            .background(if (pressed) (if (plain) c.btnTertiaryPressed else c.btnBasicPressed) else if (plain) Color.Transparent else c.btnBasic, RoundedCornerShape((if (small) 16 else 20).gpx))
            .clickable(src, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        // Pleos 에셋(뒤로 · + · −)·선 아이콘은 56(작으면 44), Phosphor 벡터는 아티팩트 .btn.ib .ico.pi 48(작으면 40)
        val px = if (icon.res != null && !icon.pleos) (if (small) 40 else 48) else (if (small) 44 else 56)
        CgIconMark(icon, px.gpx * (if (icon == CgIcon.Placeholder) .7f else 1f), if (enabled) c.textPrimary else c.textTertiary)
    }
}

// ------------------------------------------------------------ cta · text-button
/** CTA(`components/cta`): Basic Button L 채움 변형 — 알약 + 화살표 원. busy면 꺼진 모양 + 글자만. */
@Composable
fun CgCta(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false, enabled: Boolean = true, busy: Boolean = false, fill: Boolean = false, hero: Boolean = false) {
    val c = Chagok.colors
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val on = enabled && !busy
    val shape = RoundedCornerShape(999.gpx)
    val fg = if (on) c.btnFilledText else c.btnDisabledText
    Row(
        modifier.graphicsLayer { val k = if (pressed && on) ChagokMotion.PRESS_SCALE else 1f; scaleX = k; scaleY = k }
            .height(if (hero) (if (compact) 104.gpx else 124.gpx) else if (compact) 80.gpx else 100.gpx)
            .background(if (!on) c.btnDisabled else if (pressed) c.btnFilledPressed else c.btnFilled, shape)   // 꺼짐도 테두리 없음(.cta.off)
            .clickable(src, indication = null, enabled = on, role = Role.Button, onClick = onClick)
            .padding(start = if (hero) (if (compact) 44.gpx else 52.gpx) else if (compact) 28.gpx else 40.gpx, end = if (hero) (if (compact) 12.gpx else 16.gpx) else if (compact) 8.gpx else 12.gpx),
        // busy = 글자–점 사이 12(아티팩트 .cta.busy), 아니면 글자–화살표 원 20(hero 26)
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (busy) 12.gpx else if (hero) 26.gpx else 20.gpx),
    ) {
        // hero = v104 홈 [출발하기]: 124(분할 104) · 700 40/52(34/44) · 화살표 원 92(80) · 아이콘 48
        val ts = if (hero) androidx.compose.ui.text.TextStyle(fontSize = (if (compact) 34 else 40).gsp, lineHeight = (if (compact) 44 else 52).gsp, fontWeight = FontWeight.Bold)
        else if (compact) Chagok.type.bodyL else Chagok.type.titleS
        Text(text, Modifier.then(if (fill) Modifier.weight(1f) else Modifier), style = ts, color = fg, maxLines = 1)
        // busy("내비로 보내는 중") = 화살표 원 대신 점 세 개(.cta.busy .dots)
        if (busy) Box(Modifier.padding(end = 24.gpx)) { CgLoadingDots(fg) }
        else Box(Modifier.size(if (hero) (if (compact) 80.gpx else 92.gpx) else if (compact) 60.gpx else 76.gpx).background(if (on) inkAlpha(0x14) else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
            CgIconMark(CgIcon.Arrow, (if (hero) (if (compact) 42 else 48) else if (compact) 32 else 40).gpx, fg)
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
        modifier.heightIn(min = if (compact) 64.gpx else 72.gpx).background(if (pressed) c.btnTertiaryPressed else Color.Transparent, RoundedCornerShape(36.gpx))
            .clickable(src, indication = null, role = Role.Button, onClick = onClick).padding(horizontal = if (compact) 12.gpx else 22.gpx),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = (if (compact) Chagok.type.bodyL else Chagok.type.titleS).copy(fontWeight = if (primary) FontWeight.ExtraBold else FontWeight.Bold),
            color = if (primary) c.textPrimary else c.btnTertiaryText, maxLines = 1)
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
    titleContent: (@Composable () -> Unit)? = null,   // 글자 대신 그림(홈 워드마크)
    titlePlace: String? = null,                        // 제목이 장소 이름으로 시작하면 이름만 말줄임(#118 D2)
) {
    val t = Chagok.type
    Box(modifier.fillMaxWidth().height(80.gpx)) {
        if (centered) {
            leading?.let { Box(Modifier.align(Alignment.CenterStart)) { it() } }
            if (titleContent != null) Box(Modifier.align(Alignment.Center)) { titleContent() }
            else Text(title, Modifier.align(Alignment.Center), style = titleStyle ?: (if (compact) t.titleM else t.titleL), color = Chagok.colors.textPrimary, maxLines = 1)
            trailing?.let { Box(Modifier.align(Alignment.CenterEnd)) { it() } }
        } else {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.gpx)) {
                // 아티팩트 .hd: 뒤로가 있으면 Depth 제목 .ttl(Title L · 분할 Title M), 없으면 큰 이름 .name(Headline S · 분할 Title L).
                // 면 없는 뒤로 버튼은 왼쪽으로 12 당긴다(.hd > .btn.ib.plain:first-child)
                val pull = 12.gpx
                leading?.let { Box(Modifier.layout { m, cs -> val pl = m.measure(cs); val d = pull.roundToPx(); layout(pl.width - d, pl.height) { pl.place(-d, 0) } }) { it() } }
                val auto = if (leading != null) (if (compact) t.titleM else t.titleL) else (if (compact) t.titleL else t.headlineS)
                Box(Modifier.weight(1f)) { CgPlaceLine(title, titlePlace, titleStyle ?: auto, Chagok.colors.textPrimary) }
                trailing?.invoke()
            }
        }
    }
}

/**
 * 문장 속 장소 이름만 말줄임(#118 D2 · 10-3 ③ `cg-pnl`): [full]이 [name]으로 시작하면 이름 칸만 줄이고
 * 뒤 문구("으로 가는 중 · 14:20 출발 · 함께 4명", "에 도착했어요", " 기록을 남겼어요")는 늘 다 보인다.
 * 이름으로 시작하지 않으면 줄 전체를 한 줄 말줄임.
 */
@Composable
fun CgPlaceLine(full: String, name: String?, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    if (name.isNullOrEmpty() || !full.startsWith(name) || full.length == name.length) {
        Text(full, modifier, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.weight(1f, fill = false), style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(full.substring(name.length), style = style, color = color, maxLines = 1, softWrap = false)
    }
}

/** 알약(`components/pill`): 인원 "함께 4명 ⌄". */
@Composable
fun CgPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false, icon: CgIcon? = null) {
    val c = Chagok.colors
    Row(
        modifier.height(if (compact) 56.gpx else 64.gpx).background(c.btnBasic, RoundedCornerShape(999.gpx))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = if (icon != null) (if (compact) 12.gpx else 18.gpx) else (if (compact) 16.gpx else 22.gpx), end = if (compact) 16.gpx else 22.gpx),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (icon != null) (if (compact) 8.gpx else 12.gpx) else 10.gpx),
    ) {
        // 아티팩트 .ppl: 앞 아이콘 34(분할 26), 글자와 같은 색
        icon?.let { CgIconMark(it, if (compact) 26.gpx else 34.gpx, c.textPrimary) }
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
/** 검색 한 줄(`components/search-field`, Text Field 100 알약): 가로 1040 가운데, 입력 중이면 테두리 3 + [지우기](v3: 면 없는 글자 버튼, 인원 알림 [닫기]와 같은 규칙). */
@Composable
fun CgSearchField(query: String, hint: String, clearLabel: String, onQuery: (String) -> Unit, onSearch: () -> Unit, onClear: () -> Unit, compact: Boolean = false, disabled: Boolean = false) {
    val c = Chagok.colors
    val active = query.isNotEmpty() && !disabled
    val shape = RoundedCornerShape(50.gpx)
    // 흐림(.hsearch.cg-dis): 투명도 .45 · 누르거나 입력할 수 없음(인터넷 없음, depart-offline)
    Box(Modifier.fillMaxWidth().alpha(if (disabled) 0.45f else 1f), contentAlignment = Alignment.Center) {
        Row(
            (if (compact) Modifier.fillMaxWidth() else Modifier.width(1040.gpx))
                .height(if (compact) 88.gpx else 100.gpx)
                .cgElevation(1, shape).background(c.fieldBackground, shape)
                .border(if (active) 3.gpx else 2.gpx, if (active) c.fieldCaret else c.fieldBorder, shape)
                .padding(start = if (compact) 26.gpx else 36.gpx, end = if (compact) 14.gpx else 18.gpx),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.gpx),
        ) {
            // 아티팩트 .hsearch: 돋보기 40(분할 34) · textTertiary
            CgIconMark(CgIcon.Search, if (compact) 34.gpx else 40.gpx, c.textTertiary)
            Box(Modifier.weight(1f)) {
                // .hsearch: 안내 · 입력 모두 title-s 700(분할 body-l 700)
                val fs = if (compact) Chagok.type.bodyL else Chagok.type.titleS
                if (query.isEmpty()) Text(hint, style = fs, color = c.textTertiary, maxLines = 1)
                BasicTextField(
                    value = query, onValueChange = { onQuery(it.take(100)) }, singleLine = true, enabled = !disabled,
                    textStyle = fs.copy(color = c.textPrimary), cursorBrush = SolidColor(c.fieldCaret),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint },
                )
            }
            if (active) CgTextButton(clearLabel, onClear, compact = compact)
            // 비어 있을 때만 음성 원 64(마이크 32). 음성 입력은 아직 없어 누르는 동작은 두지 않는다
            else Box(Modifier.size(if (compact) 56.gpx else 64.gpx).background(c.btnBasic, CircleShape), contentAlignment = Alignment.Center) {
                CgIconMark(CgIcon.Mic, if (compact) 28.gpx else 32.gpx, c.textPrimary)
            }
        }
    }
}

// ------------------------------------------------------------ dest-card · record-card · preview-panel
/**
 * 목적지 카드(`components/dest-card`): 바깥 자리 면 8 + 안쪽 카드(그림자 Level 1). 고르면 바깥이 라임 halo(아티팩트 .hcard.on), 안쪽은 라임 면.
 * 분할(compact, `.home.p .hcard`): 바깥 모서리 32 · 자리 면 6 · 안쪽 모서리 26 · 안 여백 20 22 16 · 태그 · 메타 Label M · 이름 줄 높이 40.
 */
@Composable
fun CgDestCard(tag: String, name: String, meta: String?, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false, icon: CgIcon? = null) {
    val c = Chagok.colors
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val inner = RoundedCornerShape(if (compact) 26.gpx else 32.gpx)
    Box(
        modifier.height(if (compact) 200.gpx else 300.gpx).background(if (selected) c.selectionHalo else c.placeholder, RoundedCornerShape(if (compact) 32.gpx else 40.gpx))
            .clickable(src, indication = null, role = Role.Button, onClick = onClick).padding(if (compact) 6.gpx else 8.gpx),
    ) {
        Column(
            Modifier.fillMaxSize().graphicsLayer { val k = if (pressed) ChagokMotion.PRESS_SCALE else 1f; scaleX = k; scaleY = k }
                .cgElevation(1, inner).background(if (selected) c.selectionBackground else c.surfaceCard, inner)
                .then(if (selected) Modifier.border(2.gpx, c.selectionBorder, inner) else Modifier)
                .padding(start = if (compact) 22.gpx else 28.gpx, end = if (compact) 22.gpx else 28.gpx, top = if (compact) 20.gpx else 28.gpx, bottom = if (compact) 16.gpx else 22.gpx),
            verticalArrangement = Arrangement.spacedBy(6.gpx),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.gpx)) {
                val tagIcon = icon ?: when { tag.contains("집") -> CgIcon.House; tag.contains("회사") -> CgIcon.Briefcase; else -> CgIcon.Recent }
                CgIconMark(tagIcon, 28.gpx, if (selected) c.selectionForeground else c.textTertiary)
                Text(tag, style = if (compact) Chagok.type.labelM else Chagok.type.bodyS, color = if (selected) c.selectionForeground else c.textTertiary, maxLines = 1)   // .hcard .tag body-s 700(분할 label-m)
            }
            Spacer(Modifier.weight(1f))
            // .hcard .nm(#118 D3 · 10-3 ① 한글 줄바꿈): 두 줄까지. 어절 안에서는 끊지 않고(WordBreak.Phrase) 두 줄 길이를 고르게(Balanced),
            // 넘치면 둘째 줄 끝 "…". 시안 700 40/48(`.home.dp .hcard .nm` 줄 높이 48) · 분할 titleS 32/40
            Text(
                name, style = (if (compact) Chagok.type.titleS.copy(lineHeight = 40.gsp) else Chagok.type.titleL.copy(lineHeight = 48.gsp)).copy(lineBreak = KoreanNameBreak),
                color = if (selected) c.selectionForeground else c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            meta?.let { Text(it, Modifier.graphicsLayer { alpha = if (selected) .72f else 1f }, style = if (compact) Chagok.type.labelM else Chagok.type.bodyM, color = if (selected) c.selectionForeground else c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/**
 * 한글 줄바꿈 `cg-kwrap`(#118 10-3 ①): 어절(띄어쓰기 단위) 안에서는 끊지 않고 두 줄이면 길이를 고르게.
 * 한 어절이 한 줄보다 길 때만 어절 안에서 끊긴다(Phrase가 못 맞추면 글자 단위로 내려감). Android 13+ 지원.
 */
val KoreanNameBreak = androidx.compose.ui.text.style.LineBreak(
    androidx.compose.ui.text.style.LineBreak.Strategy.Balanced,
    androidx.compose.ui.text.style.LineBreak.Strictness.Strict,
    androidx.compose.ui.text.style.LineBreak.WordBreak.Phrase,
)

/** 기록 카드(`components/record-card`): 날짜(방금) · 목적지 · 요약 · 고른 것. fresh = 방금 끝난 기록(굵은 테두리 3, 0.7초 올라옴). */

/** 미리보기 칸 상태(`components/preview-panel`). */
enum class CgPreviewState { Empty, Loading, Ready, Failed, RouteFailed }   // RouteFailed: 내비로 길을 보내지 못함(#132), [다시 시도]는 CTA 자리

/**
 * 미리보기 칸(`components/preview-panel`): 빈 자리(점선) · 불러오는 중(회색 막대) · 준비(통계 3칸) · 실패(다시 시도).
 * 가로는 왼쪽 글 + 오른쪽 CTA, 분할은 위아래.
 */
@Composable
fun CgPreviewPanel(
    state: CgPreviewState, name: String?, stats: List<Pair<String, String>>, note: String?, emptyText: String,
    failText: String, retryLabel: String, onRetry: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false,
    photo: Int? = null,          // 목적지 사진(res). null이면 딥 잉크 바탕
    onPhoto: Boolean = false,    // v3: 고른 목적지 미리보기 = 사진/딥 잉크 바탕 + 흰 글자(HANDOFF_v3 4-2)
    cta: @Composable (Modifier) -> Unit,
) {
    if (onPhoto && state != CgPreviewState.Empty && name != null) {
        CgPhotoPanel(photo, modifier, compact) {
            com.mist.pleos_app.ui.theme.ChagokOnPhoto {
                CgPreviewPanel(state, name, stats, note, emptyText, failText, retryLabel, onRetry, Modifier.fillMaxSize(), compact, onPhoto = false, bare = true, cta = cta)
            }
        }
        return
    }
    PreviewPanelBody(state, name, stats, note, emptyText, failText, retryLabel, onRetry, modifier, compact, bare = false, cta = cta)
}

/** 같은 이름으로 다시 부르는 사진 위 안쪽 칸(면 · 테두리 · 그림자 없음). */
@Composable
private fun CgPreviewPanel(
    state: CgPreviewState, name: String?, stats: List<Pair<String, String>>, note: String?, emptyText: String,
    failText: String, retryLabel: String, onRetry: () -> Unit, modifier: Modifier, compact: Boolean,
    onPhoto: Boolean, bare: Boolean, cta: @Composable (Modifier) -> Unit,
) = PreviewPanelBody(state, name, stats, note, emptyText, failText, retryLabel, onRetry, modifier, compact, bare, cta)

/** 사진 배경 + 그늘(가로: 왼쪽 → 오른쪽, 분할: 위 → 아래). 사진은 칸을 꽉 채우고 세로 기준점 60%. */
@Composable
private fun CgPhotoPanel(photo: Int?, modifier: Modifier, compact: Boolean, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(40.gpx)
    // 사진 위 그늘 · 바탕은 테마와 상관없이 딥 잉크(btnFilledText, v4 #242135)
    val shade = if (compact) Brush.verticalGradient(0f to inkAlpha(0x80), .45f to inkAlpha(0xCC), 1f to inkAlpha(0xF2))
    else Brush.horizontalGradient(0f to inkAlpha(0xF2), .34f to inkAlpha(0xD9), .62f to inkAlpha(0x80), 1f to inkAlpha(0x26))
    val glow = inkAlpha(0x33)
    Box(modifier.shadow(18.gpx, shape, ambientColor = glow, spotColor = glow).clip(shape).background(inkAlpha(0xFF))) {
        photo?.let {
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(it), contentDescription = null, modifier = Modifier.matchParentSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop, alignment = androidx.compose.ui.BiasAlignment(0f, .2f),
            )
            Box(Modifier.matchParentSize().background(shade))
        }
        content()
    }
}

@Composable
private fun PreviewPanelBody(
    state: CgPreviewState, name: String?, stats: List<Pair<String, String>>, note: String?, emptyText: String,
    failText: String, retryLabel: String, onRetry: () -> Unit, modifier: Modifier, compact: Boolean, bare: Boolean,
    cta: @Composable (Modifier) -> Unit,
) {
    val c = Chagok.colors
    val t = Chagok.type
    val shape = RoundedCornerShape(40.gpx)
    if (state == CgPreviewState.Empty || name == null) {
        Box(modifier.heightIn(min = 240.gpx).cgDashed(40.gpx, c.borderStrong).padding(28.gpx), contentAlignment = Alignment.Center) {
            Text(emptyText, style = if (compact) t.bodyM else t.bodyL, color = c.textTertiary)   // .pv.empty body-l 700(분할 body-m)
        }
        return
    }
    val body: @Composable ColumnScope.() -> Unit = {
        Text(name, style = if (compact) t.titleS else t.titleL, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        when (state) {
            // .pv .skel: 칸마다 이름 막대(18 · 60%) + 값 막대(30 · 80%). 사진 위는 움직임 없이 #FFFFFF33
            CgPreviewState.Loading -> Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 20.gpx else 48.gpx)) {
                repeat(3) {
                    Column(Modifier.width(if (compact) 120.gpx else 150.gpx), verticalArrangement = Arrangement.spacedBy(10.gpx)) {
                        CgSkeleton(Modifier.fillMaxWidth(.6f).height(18.gpx), onPhoto = bare)
                        CgSkeleton(Modifier.fillMaxWidth(.8f).height(30.gpx), onPhoto = bare)
                    }
                }
            }
            // depart-route-fail(#132): 경고 아이콘 + 오류색 한 줄. [다시 시도]는 오른쪽 CTA(Basic)
            CgPreviewState.RouteFailed -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.gpx)) {
                CgIconMark(CgIcon.Warning, if (compact) 30.gpx else 36.gpx, c.statusErrorForeground)
                Text(failText, Modifier.weight(1f, fill = false), style = t.bodyL, color = c.statusErrorForeground)
            }
            // .pvfail: 경고 아이콘 + 오류색 body-l 700 + [다시 시도]
            CgPreviewState.Failed -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.gpx)) {
                CgIconMark(CgIcon.Warning, if (compact) 30.gpx else 36.gpx, c.statusErrorForeground)
                Text(failText, Modifier.weight(1f, fill = false), style = t.bodyL, color = c.statusErrorForeground)
                CgTextButton(retryLabel, onRetry, primary = true, compact = compact)
            }
            else -> Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 20.gpx else 48.gpx)) {
                stats.forEach { (k, v) ->
                    Column(Modifier.widthIn(min = if (compact) 0.gpx else 150.gpx), verticalArrangement = Arrangement.spacedBy(2.gpx)) {
                        Text(k, style = t.labelM, color = c.textTertiary)
                        Text(v, style = t.titleS, color = c.textPrimary, maxLines = 1)   // .pv .st 값 title-s(분할도 같음)
                    }
                }
            }
        }
        note?.let { Text(it, style = if (compact) t.labelM else t.bodyM, color = c.textTertiary) }
    }
    if (!compact) {
        Row(
            (if (bare) modifier else modifier.cgElevation(1, shape).background(c.surfaceCard, shape).border(2.gpx, c.line, shape)).padding(start = 44.gpx, end = 28.gpx, top = 28.gpx, bottom = 28.gpx),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.gpx),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.gpx), content = body)
            cta(Modifier)
        }
    } else {
        // 분할 `.home.dp.p .pv`: 여백 위아래 20 · 좌우 24, 글 묶음과 CTA 사이 12, 글 묶음 안 14
        Column(
            (if (bare) modifier else modifier.cgElevation(1, shape).background(c.surfaceCard, shape).border(2.gpx, c.line, shape)).padding(horizontal = 24.gpx, vertical = 20.gpx),
            verticalArrangement = Arrangement.spacedBy(12.gpx),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.gpx), content = body)
            cta(Modifier.fillMaxWidth())
        }
    }
}

// ------------------------------------------------------------ people-alert · stepper
/**
 * 스테퍼(`components/stepper`, 알림 바 안 알약): [−] 값 [+]. 높이 80 · 버튼 80 · 값 칸 최소 96 · Title M(분할 64 · 64 · 64 · Body L).
 * [inBar] = 분할 인원 알림 바 안(`.a-p1 .pbar .pst`, compact와 같이 씀): 버튼 56 · 아이콘 40 · 값 칸 최소 56 · Body L.
 */
@Composable
fun CgStepper(
    value: String, onMinus: () -> Unit, onPlus: () -> Unit, minusLabel: String, plusLabel: String, minusEnabled: Boolean, plusEnabled: Boolean,
    compact: Boolean = false, inBar: Boolean = false,
) {
    val c = Chagok.colors
    val bar = compact && inBar
    Row(Modifier.height(if (compact) 64.gpx else 80.gpx).background(c.btnBasic, ChagokRadius.PILL), verticalAlignment = Alignment.CenterVertically) {
        if (bar) StepButton(minusLabel, CgIcon.Minus, onMinus, minusEnabled)
        else CgIconButton(minusLabel, CgIcon.Minus, onMinus, small = compact, plain = true, enabled = minusEnabled)
        Text(value, Modifier.widthIn(min = if (bar) 56.gpx else if (compact) 64.gpx else 96.gpx), style = if (compact) Chagok.type.bodyL else Chagok.type.titleM, color = c.textPrimary, textAlign = TextAlign.Center)
        if (bar) StepButton(plusLabel, CgIcon.Plus, onPlus, plusEnabled)
        else CgIconButton(plusLabel, CgIcon.Plus, onPlus, small = compact, plain = true, enabled = plusEnabled)
    }
}

/** 분할 알림 바 안 스테퍼 버튼 56(면 없음 · 눌림 tertiary-pressed · 아이콘 40). */
@Composable
private fun StepButton(label: String, icon: CgIcon, onClick: () -> Unit, enabled: Boolean) {
    val c = Chagok.colors
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Box(
        Modifier.size(56.gpx)
            .background(if (pressed) c.btnTertiaryPressed else Color.Transparent, ChagokRadius.PILL)
            .clickable(src, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { CgIconMark(icon, 40.gpx, if (enabled) c.textPrimary else c.textTertiary) }
}

/**
 * 인원 확인 알림 바(`components/people-alert`, 아티팩트 `.pbar` 1040 × 96 · 분할 좌우 40 안쪽 × 88, 그림자 Level 2):
 * 위 바 줄에 겹치는 얇은 알림. 저절로 안 사라짐. 0.42초 (.32,.72,0,1) 위 16px · scale .98 → 제자리.
 * (아티팩트 DS 카드 문구의 "1120 × 120"은 CSS와 다른 옛 값. 도착 확인 System Notification 700은 OS 알림이라 이 부품이 아니다.)
 * 분할 안 스테퍼는 `CgStepper(compact = true, inBar = true)`, 구분선은 `CgDivider(compact = true)`.
 */
@Composable
fun CgPeopleAlert(title: String, sub: String?, modifier: Modifier = Modifier, compact: Boolean = false, content: @Composable RowScope.() -> Unit) {
    val c = Chagok.colors
    val drop = remember { Animatable(0f) }
    LaunchedEffect(Unit) { drop.animateTo(1f, tween(420, easing = ChagokMotion.Rise)) }
    // v37~v38(명준 11/8): 폭 = 목적지 검색 창(1040, 분할은 좌우 40 안쪽), 높이 96(분할 88). 내려오는 효과는 처음 뜰 때만
    val shape = RoundedCornerShape(if (compact) 44.gpx else 48.gpx)
    Row(
        modifier.then(if (compact) Modifier.fillMaxWidth() else Modifier.width(1040.gpx))
            .height(if (compact) 88.gpx else 96.gpx)
            .graphicsLayer { alpha = drop.value; translationY = -(1f - drop.value) * 16f * density; val k = .98f + .02f * drop.value; scaleX = k; scaleY = k }
            // 아티팩트 .pbar: 면 surface-notification · 테두리 주간 border-subtle / 야간 없음 · 여백 0 20 0 48 · 사이 28(분할 0 10 0 26 · 12)
            .cgElevation(2, shape).background(c.surfaceNotification, shape).border(1.gpx, c.sheetBorder, shape)
            .padding(start = if (compact) 26.gpx else 48.gpx, end = if (compact) 10.gpx else 20.gpx),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 12.gpx else 28.gpx),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.gpx)) {
            Text(title, style = if (compact) Chagok.type.titleS else Chagok.type.titleM.copy(fontWeight = FontWeight.ExtraBold), color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!compact && sub != null) Text(sub, style = Chagok.type.labelS, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        content()
    }
}

/** 알림 바 안 세로 구분선 2 × 56(분할 44). */
@Composable
fun CgDivider(compact: Boolean = false) { Box(Modifier.width(2.gpx).height(if (compact) 44.gpx else 56.gpx).background(Chagok.colors.line)) }

// ------------------------------------------------------------ bottom-sheet
/**
 * 짧은 하단 시트(`components/bottom-sheet`): 딤·끌기 없음, 0.38초 (.2,.7,.2,1) 아래 115% → 제자리. 위에 손잡이.
 * 면 surface-modal · 테두리 주간 border-subtle / 야간 없음(아티팩트 1091·1093).
 * confirm = 확인 시트 .isheet(그만두기 · 기록 지우기): 모서리 40 · 여백 16 40 24 · 사이 8(분할 14 26 18).
 * 아니면 쉰 시간 시트 .rsheet: 모서리 32 · 여백 16 40 36 · 사이 20(분할 14 24 24 · 14). 그림자 Level 3.
 */
@Composable
fun CgBottomSheet(modifier: Modifier = Modifier, compact: Boolean = false, delayMs: Long = 0, confirm: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val c = Chagok.colors
    val y = remember { Animatable(1.15f) }
    LaunchedEffect(Unit) { delay(delayMs); y.animateTo(0f, tween(380, easing = ChagokMotion.Sheet)) }
    val shape = RoundedCornerShape((if (confirm) 40 else 32).gpx)
    val side = if (compact) (if (confirm) 26 else 24) else 40
    val bottom = if (compact) (if (confirm) 18 else 24) else (if (confirm) 24 else 36)
    Column(
        modifier.graphicsLayer { translationY = y.value * size.height }
            .cgElevation(3, shape).background(c.surfaceModal, shape).border(2.gpx, c.sheetBorder, shape)
            .padding(start = side.gpx, end = side.gpx, top = if (compact) 14.gpx else 16.gpx, bottom = bottom.gpx),
        verticalArrangement = Arrangement.spacedBy((if (confirm) 8 else if (compact) 14 else 20).gpx),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = if (confirm) 8.gpx else 0.gpx).width(80.gpx).height(6.gpx).background(c.lineStrong, RoundedCornerShape(3.gpx)))
        content()
    }
}

/**
 * 확인 시트 버튼 줄(.isheet .ibts): 오른쪽 정렬 · 내용 폭 · 사이 12 · 위 8. 분할은 S 64, 글자는 Body M 400 28/36
 * (아티팩트 `.a-p1 .isheet .btn`, G6 settings-wipe — S 기본 글자 Body S 26/34보다 크다).
 */
@Composable
fun CgSheetButtons(compact: Boolean, content: @Composable RowScope.(CgSize) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.gpx), horizontalArrangement = Arrangement.spacedBy(12.gpx, Alignment.End)) {
        if (compact) androidx.compose.runtime.CompositionLocalProvider(LocalButtonTextS provides Chagok.type.bodyM) { content(CgSize.S) }
        else content(CgSize.M)
    }
}

// ------------------------------------------------------------ offer-card
/** 제안 카드 틀(`components/offer-card`): 카드 면 + 선 2 + 모서리 32. 가로 640 고정은 부르는 쪽이. */
@Composable
fun CgOfferCard(modifier: Modifier = Modifier, compact: Boolean = false, pad: Int = 40, content: @Composable ColumnScope.() -> Unit) {
    val c = Chagok.colors
    val shape = RoundedCornerShape(32.gpx)
    Column(
        modifier.background(c.surfaceCard, shape).border(2.gpx, c.lineStrong, shape)
            .padding(horizontal = if (compact) 24.gpx else pad.gpx, vertical = if (compact) 22.gpx else pad.gpx),
        verticalArrangement = Arrangement.spacedBy(if (compact) 12.gpx else 20.gpx),
        content = content,
    )
}

/**
 * 근거 목록(11/9 명준, `.why.wl`): 줄마다 별표(*) 글머리(textTertiary, 왼쪽 4 · 글 28 안쪽, 분할 2 · 20), 줄 사이 6(분할 2).
 * 첫 줄의 "…원"까지(운전 노고 금액)는 진한 글자 + 밑줄 2px. 밑줄은 [underlineDelay] 뒤 0.6초 동안 왼쪽부터 그어진다(cg-ul).
 * 넘치면 어절 단위로 고르게 줄바꿈.
 */
@Composable
fun CgBasisList(lines: List<String>, compact: Boolean = false, underlineDelay: Int = 500, animate: Boolean = true) {
    val c = Chagok.colors
    val style = (if (compact) Chagok.type.labelM else Chagok.type.bodyM).copy(lineBreak = androidx.compose.ui.text.style.LineBreak.Heading)
    val k = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) { if (animate) { delay(underlineDelay.toLong()); k.animateTo(1f, tween(600, easing = CubicBezierEasing(.32f, .72f, 0f, 1f))) } }
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 2.gpx else 6.gpx)) {
        lines.forEachIndexed { i, line ->
            Box(Modifier.fillMaxWidth()) {
                Text("*", Modifier.padding(start = if (compact) 2.gpx else 4.gpx), style = style.copy(fontWeight = FontWeight.Bold), color = c.textTertiary)
                val end = if (i == 0) Regex("^.*?원").find(line)?.range?.last?.plus(1) ?: 0 else 0
                var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
                val text = androidx.compose.ui.text.buildAnnotatedString {
                    append(line)
                    if (end > 0) addStyle(androidx.compose.ui.text.SpanStyle(color = c.textPrimary), 0, end)
                }
                Text(
                    text,
                    Modifier.padding(start = if (compact) 20.gpx else 28.gpx).drawBehind {
                        val l = layout ?: return@drawBehind
                        if (end <= 0) return@drawBehind
                        val p = k.value
                        val l0 = l.getLineForOffset(0); val l1 = l.getLineForOffset(end - 1)
                        for (ln in l0..l1) {
                            val x0 = if (ln == l0) l.getHorizontalPosition(0, true) else l.getLineLeft(ln)
                            val x1 = if (ln == l1) l.getHorizontalPosition(end, true) else l.getLineRight(ln)
                            val y = l.getLineBaseline(ln) + 5f.gpxPx(this)
                            drawRect(c.textPrimary, Offset(x0, y), Size((x1 - x0) * p, 2f.gpxPx(this)))
                        }
                    },
                    style = style, color = c.textSecondary, onTextLayout = { layout = it },   // .why 모든 줄 txt2(별표만 txt3)
                )
            }
        }
    }
}

/** 방식 B 항목 버튼(`components/offer-card` cg-opt, Basic Button L 100): 기본값만 채움. words가 여럿이면 2초마다 다음 말. */
@Composable
fun CgOptionButton(words: List<String>, isDefault: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val c = Chagok.colors
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    // 기본 항목 = 선택 면(v3.1 selection-*: 라임 면 + 잉크 글자 + 테두리 2)
    val bg = if (isDefault) (if (pressed) c.selectionPressed else c.selectionBackground) else (if (pressed) c.btnBasicPressed else c.btnBasic)
    val fg = if (isDefault) c.selectionForeground else c.btnBasicText
    val optShape = RoundedCornerShape(if (compact) 24.gpx else 32.gpx)
    var i by remember(words) { mutableIntStateOf(0) }
    if (words.size > 1) LaunchedEffect(words) { while (true) { delay(2000); i = (i + 1) % words.size } }
    Box(
        modifier.heightIn(min = if (compact) 96.gpx else 100.gpx).background(bg, optShape)
            .then(if (isDefault) Modifier.border(2.gpx, c.selectionBorder, optShape) else Modifier)
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
/** cg-slot-on 키프레임(0.8초, linear): 투명도 0 → .55(8%) → 0(11%) → .35(20%) → .05(23%) → .7(31%) → 0(34%) → 0(52%) → 1(56%) → 1. */
private val SLOT_KF = floatArrayOf(0f, 0f, .08f, .55f, .11f, 0f, .20f, .35f, .23f, .05f, .31f, .7f, .34f, 0f, .52f, 0f, .56f, 1f, 1f, 1f)

internal fun slotAlpha(p: Float): Float {
    if (p >= 1f) return 1f
    var i = 0
    while (i + 2 < SLOT_KF.size && SLOT_KF[i + 2] < p) i += 2
    val (t0, v0, t1, v1) = listOf(SLOT_KF[i], SLOT_KF[i + 1], SLOT_KF[i + 2], SLOT_KF[i + 3])
    return if (t1 <= t0) v1 else v0 + (v1 - v0) * ((p - t0) / (t1 - t0)).coerceIn(0f, 1f)
}

/** 깜빡일 때 ±1px 떨림(8% · 20% · 31%). */
internal fun slotJitter(p: Float): Float = when {
    p in 0.05f..0.11f -> 1f
    p in 0.17f..0.23f -> -1f
    p in 0.28f..0.34f -> 1f
    else -> 0f
}

/** 켜지는 순간(56%) 빛이 크게 번졌다 100%에 가라앉음. */
internal fun slotBoost(p: Float): Float = if (p < .52f || p >= 1f) 0f else if (p < .56f) (p - .52f) / .04f else 1f - (p - .56f) / .44f

/**
 * 영수증 = 출력 종이(`components/receipt`): 프린터 슬롯(그림자 Level 1) + 슬롯에서 내려오는 종이(슬롯 밖은 잘림) + 지그재그 끝.
 * shift = 종이 위치(-1.05 → 0, 출력 연출). scroll = 종이가 칸보다 길 때 안에서 넘김.
 * [arrive] = 도착 화면 가로 전용 틀(아티팩트 `.arrpg:not(.p)`): 종이 좌우 40 · 슬롯 겹침 30 · 안 여백 56 64 26.
 * 기본 틀은 36 · 34 · 48 56 24. 분할(compact)에는 영향 없음. 이중선 위아래 여백(도착 28 · 18)은 부르는 쪽(CgDoubleRule padding)이 맞춘다.
 */
@Composable
fun CgReceipt(
    modifier: Modifier = Modifier, compact: Boolean = false, shift: Float = 0f, fill: Boolean = true, scroll: Boolean = true,
    light: () -> Float = { 1f },   // v91 출력구 불빛 진행(0 = 꺼짐 … 1 = 켜짐, 0.8초 깜빡임 키프레임). 정지 화면 = 1
    arrive: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Chagok.colors
    val arr = arrive && !compact
    val slotH = if (compact) 40.gpx else 68.gpx
    val slotShape = RoundedCornerShape(if (compact) 14.gpx else 22.gpx)
    Column(modifier) {
        Box(
            // v3: 출력구 = Satin Silver(금속은 여기만) + 어두운 슬롯
            Modifier.fillMaxWidth().height(slotH).zIndex(2f).cgElevation(1, slotShape)
                .background(Brush.verticalGradient(0f to c.receiptPrinterHighlight, .18f to c.receiptPrinterMidtone, .7f to c.receiptPrinterShade, 1f to c.receiptPrinterMidtone), slotShape)
                .border(1.gpx, c.receiptPrinterShade, slotShape).padding(horizontal = if (compact) 28.gpx else 44.gpx),
            contentAlignment = Alignment.Center,
        ) {
            // 슬롯 줄(검정) 위 라임 불빛: 다 나오면 지직 세 번 → 딱 켜지며 빛이 크게 번졌다 가라앉음(cg-slot-on)
            val dark = c.isDark
            Box(
                Modifier.fillMaxWidth().height(if (compact) 6.gpx else 8.gpx).background(c.receiptPrinterSlot, RoundedCornerShape(4.gpx))
                    .drawWithContent {
                        drawContent()
                        val p = light()
                        val a = slotAlpha(p)
                        if (a <= 0f) return@drawWithContent
                        val jit = slotJitter(p) * density
                        val boost = slotBoost(p)
                        val r = size.height / 2
                        translate(jit, 0f) {
                            // 글로우: 0 0 8 · 0 0 22 · 0 0 44(야간 10 · 26 · 56), 켜지는 순간 14 · 40 · 80
                            val blurs = if (dark) floatArrayOf(10f, 26f, 56f) else floatArrayOf(8f, 22f, 44f)
                            val alphas = if (dark) floatArrayOf(.9f, .6f, .25f) else floatArrayOf(.8f, .5f, .2f)
                            drawIntoCanvas { cv ->
                                for (i in blurs.indices) {
                                    val b = (blurs[i] + boost * (if (i == 0) 6f else if (i == 1) 18f else 36f)) * density
                                    val paint = androidx.compose.ui.graphics.Paint().apply { color = Color(0xFFC8F04A).copy(alpha = alphas[i] * a) }
                                    paint.asFrameworkPaint().maskFilter = android.graphics.BlurMaskFilter(b, android.graphics.BlurMaskFilter.Blur.NORMAL)
                                    cv.drawRoundRect(0f, 0f, size.width, size.height, r, r, paint)
                                }
                            }
                            drawRoundRect(
                                Brush.verticalGradient(0f to Color(0xFFE6F5B4), .45f to Color(0xFFC8F04A), 1f to Color(0xFFA6C72E)),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r), alpha = a,
                            )
                            if (boost > 0f) drawRoundRect(Color.White.copy(alpha = .25f * boost * a), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
                        }
                    },
            )
        }
        Box(
            Modifier.then(if (fill) Modifier.weight(1f) else Modifier).padding(horizontal = if (compact) 16.gpx else if (arr) 40.gpx else 36.gpx)
                .offset(y = if (arr) (-30).gpx else -slotH / 2).clipToBounds(),
        ) {
            Column(Modifier.graphicsLayer { translationY = shift * size.height }.then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
                Column(
                    Modifier.fillMaxWidth().background(c.receiptPaper)
                        .padding(
                            start = if (compact) 28.gpx else if (arr) 64.gpx else 56.gpx, end = if (compact) 28.gpx else if (arr) 64.gpx else 56.gpx,
                            top = if (compact) 24.gpx else if (arr) 56.gpx else 48.gpx, bottom = if (compact) 10.gpx else if (arr) 26.gpx else 24.gpx,
                        ),
                    content = content,
                )
                CgZigzag(Modifier.fillMaxWidth().height(12.gpx), c.receiptPaper)
            }
        }
    }
}

/**
 * 영수증 줄: 이름 · 값 · % · 꺾쇠. 누르면 아래에 근거 칸(extra는 굵은 첫 줄). hi = 바뀐 값 회색 강조(0..1, 색 = skeleton-highlight `--ph2`).
 * 칸 폭: 이름 240(분할 140) · % 130(분할 84) · 꺾쇠 28(분할 18). 근거 칸 설명은 Body M · text-secondary(분할 Label S),
 * 굵은 첫 줄은 Body L(분할 Label M 700).
 */
@Composable
fun CgReceiptRow(
    label: String, detail: String, pct: String, basis: String?, expanded: Boolean, onClick: () -> Unit,
    compact: Boolean = false, extra: String? = null, hi: Float = 0f,
    minHeight: Int = 70,                        // 가로 · 중간 정산 줄이 있는 영수증은 62(HANDOFF_v4 7-2)
) {
    val c = Chagok.colors
    val t = Chagok.type
    val plain = receiptPlain(compact)
    Column(Modifier.fillMaxWidth().background(c.skeletonHighlight.copy(alpha = c.skeletonHighlight.alpha * hi), RoundedCornerShape(8.gpx))) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = if (compact) 56.gpx else minHeight.gpx).then(if (basis != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.gpx),
        ) {
            Text(label, Modifier.width(if (compact) 140.gpx else 240.gpx), style = plain, color = c.textPrimary)
            Text(detail, Modifier.weight(1f), style = plain, color = c.textSecondary)
            // "+1% 미만"만 작은 글자(#118 D5 · S2), 칸 폭은 그대로
            val under = com.mist.pleos_app.effort.isUnderOnePct(pct)
            Text(pct, Modifier.width(if (compact) 84.gpx else 130.gpx), style = if (under) (if (compact) t.labelS else t.labelM) else receiptStrong(compact), color = c.textPrimary, textAlign = TextAlign.End, maxLines = 1)
            Box(Modifier.size(if (compact) 18.gpx else 28.gpx), contentAlignment = Alignment.Center) {
                if (basis != null) CgIconMark(CgIcon.Chevron, if (compact) 12.gpx else 14.gpx, c.textTertiary, Modifier.graphicsLayer { rotationZ = if (expanded) 180f else 0f })
            }
        }
        if (expanded && basis != null) {
            Column(
                Modifier.fillMaxWidth().padding(bottom = 10.gpx).background(c.receiptDetailBackground, RoundedCornerShape(16.gpx)).padding(horizontal = 20.gpx, vertical = 14.gpx),
                verticalArrangement = Arrangement.spacedBy(4.gpx),
            ) {
                // 아티팩트 .wy: 굵은 첫 줄 700 30/38(분할 24/28), 설명은 첫 줄이 있든 없든 28/36 txt2(분할 20/24)
                extra?.let { Text(it, style = if (compact) t.labelM.copy(fontWeight = FontWeight.Bold) else t.bodyL, color = c.textPrimary) }
                Text(basis, style = if (compact) t.labelS else t.bodyM, color = c.textSecondary)
            }
        }
    }
}

/**
 * 영수증 소계 · 합계 줄. onValue = 값을 누르면(쉰 시간: 시트 다시 열기, 값 아래 점선) · valueLabel = 그 접근성 이름.
 * 합계(v3 A안): "합계"는 기본 글자, 결과 문장만 800 + 글자 아래 40%에 라임 마커. 줄이 바뀌어도 줄마다 마커가 글자를 따라간다.
 */
@Composable
fun CgReceiptSum(
    label: String, value: String, compact: Boolean = false, total: Boolean = false, hi: Float = 0f,
    onValue: (() -> Unit)? = null, valueLabel: String? = null, trailing: @Composable () -> Unit = {},
    minHeight: Int = 60,                       // 가로 · 중간 정산 줄이 있는 영수증은 54(HANDOFF_v4 7-2)
    amount: String? = null,                    // v92 합계 끝 글 = "약 3만 6천 원"(엔진 값). null이면 배수만
    flip: () -> Triple<Float, Float, Float> = { Triple(1f, 1f, 1f) },   // (배수 나감, 금액 들어옴, 금액 마커) 0..1. 정지 화면 = 금액
) {
    val c = Chagok.colors
    val plain = receiptPlain(compact)
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (compact) 46.gpx else minHeight.gpx).padding(top = if (total && !compact) 6.gpx else 0.gpx)
            .background(c.skeletonHighlight.copy(alpha = c.skeletonHighlight.alpha * hi), RoundedCornerShape(8.gpx)),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.gpx),
    ) {
        Text(label, if (total) Modifier else Modifier.weight(1f), style = if (total) receiptStrong(compact) else plain, color = c.textPrimary)
        if (total && amount != null) {
            val style = totalValueStyle(compact)
            val pad = if (compact) 4.gpx else 6.gpx
            Box(
                Modifier.weight(1f).clipToBounds().clearAndSetSemantics { contentDescription = "$amount · $value" },
                contentAlignment = Alignment.CenterEnd,
            ) {
                MarkedText(value, style, pad, Modifier.graphicsLayer { val o = flip().first; alpha = 1f - o; translationY = -o * .7f * size.height }) { 1f }
                MarkedText(amount, style, pad, Modifier.graphicsLayer { val n = flip().second; alpha = n; translationY = (1f - n) * .7f * size.height }) { flip().third }
            }
        } else if (total) {
            var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
            val pad = if (compact) 4.gpx else 6.gpx
            Text(
                value,
                Modifier.weight(1f).drawBehind {
                    val l = layout ?: return@drawBehind
                    for (i in 0 until l.lineCount) {
                        val top = l.getLineTop(i); val bottom = l.getLineBottom(i)
                        val y = top + (bottom - top) * .6f
                        drawRect(c.receiptMarker, Offset(l.getLineLeft(i) - pad.toPx(), y), Size(l.getLineRight(i) - l.getLineLeft(i) + pad.toPx() * 2, bottom - y))
                    }
                },
                style = totalValueStyle(compact),
                color = c.textPrimary, textAlign = TextAlign.End, onTextLayout = { layout = it },
            )
        } else Text(
            value,
            if (onValue == null) Modifier else Modifier
                .clickable(role = Role.Button, onClickLabel = valueLabel, onClick = onValue)
                .drawBehind {   // 누를 수 있는 값 = 아래 점선
                    drawLine(c.lineStrong, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.5f.gpxPx(this), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)))
                }.padding(bottom = 2.gpx),
            style = receiptStrong(compact), color = c.textPrimary, maxLines = 1,
        )
        trailing()
    }
}

/**
 * 최종 영수증 하단 중간 정산 줄(#118 `receipt.css` cg-sfold): 위 점선 · 왼쪽 이름(크게) + 아래 작은 줄(점선 밑줄 · ⇅) · 오른쪽 값.
 * 작은 줄을 누르면 위 · 아래 이름이 0.38초 동안 엇갈려 자리를 바꾸고 값이 다시 올라온다(움직임 줄이기면 바로 바뀜).
 */
@Composable
fun CgSettleFold(
    main: String, alt: String, altLabel: String, value: String, swapKey: Boolean, onSwap: () -> Unit,
    compact: Boolean = false, reduce: Boolean = false, enabled: Boolean = true,
    topGap: Int = 28,                          // 합계와의 간격. 가로 영수증은 18(HANDOFF_v4 7-2)
) {
    val c = Chagok.colors
    val line = c.lineStrong
    val ease = ChagokMotion.Rise
    val dur = if (reduce) 0 else 380
    Row(
        Modifier.fillMaxWidth().padding(top = topGap.gpx)
            .drawBehind { drawLine(line, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 2f.gpxPx(this), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))) }
            .padding(top = 10.gpx),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.gpx),
    ) {
        AnimatedContent(
            swapKey, Modifier.weight(1f),
            transitionSpec = { (slideInVertically(tween(dur, easing = ease)) { it / 2 } + fadeIn(tween(dur))) togetherWith (slideOutVertically(tween(dur, easing = ease)) { -it / 2 } + fadeOut(tween(dur))) },
            label = "fold",
        ) { _ ->
            Column(verticalArrangement = Arrangement.spacedBy(2.gpx)) {
                // 아티팩트 cg-sfold 이름: 700 34/42(분할 700 28/36)
                Text(main, style = if (compact) TextStyle(fontSize = 28.gsp, lineHeight = 36.gsp, fontWeight = FontWeight.Bold) else receiptStrong(false), color = c.textPrimary, maxLines = 1)
                Row(
                    Modifier.then(if (enabled) Modifier.clickable(role = Role.Button, onClickLabel = altLabel, onClick = onSwap) else Modifier)
                        .drawBehind { drawLine(line, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.5f.gpxPx(this), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))) }
                        .padding(vertical = 4.gpx),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.gpx),
                ) {
                    Text(alt, style = if (compact) Chagok.type.labelS else Chagok.type.labelM, color = c.textTertiary, maxLines = 1)
                    Text("⇅", style = if (compact) Chagok.type.labelS else Chagok.type.labelM, color = c.textTertiary)
                }
            }
        }
        AnimatedContent(
            value,
            transitionSpec = { (slideInVertically(tween(if (reduce) 0 else 340)) { it / 3 } + fadeIn(tween(if (reduce) 0 else 340))) togetherWith fadeOut(tween(if (reduce) 0 else 120)) },
            label = "foldValue",
        ) { v -> Text(v, style = receiptStrong(compact), color = c.textPrimary, maxLines = 1) }
    }
}

/** 오른쪽 정렬 글 + 글자 아래 40% 라임 마커(줄마다, 폭 = mark() 0..1로 왼쪽부터 그어짐). */
@Composable
private fun MarkedText(text: String, style: TextStyle, pad: androidx.compose.ui.unit.Dp, modifier: Modifier, mark: () -> Float) {
    val c = Chagok.colors
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text,
        modifier.drawBehind {
            val l = layout ?: return@drawBehind
            val k = mark()
            for (i in 0 until l.lineCount) {
                val top = l.getLineTop(i); val bottom = l.getLineBottom(i)
                val y = top + (bottom - top) * .6f
                val x0 = l.getLineLeft(i) - pad.toPx()
                val w = (l.getLineRight(i) - l.getLineLeft(i) + pad.toPx() * 2) * k
                drawRect(c.receiptMarker, Offset(x0, y), Size(w, bottom - y))
            }
        },
        style = style, color = c.textPrimary, textAlign = TextAlign.End, onTextLayout = { layout = it },
    )
}

/** 영수증 합계 값(아티팩트: 800 38/48, 분할 700 32/40). */
@Composable
private fun totalValueStyle(compact: Boolean) = if (compact) TextStyle(fontSize = 32.gsp, lineHeight = 40.gsp, fontWeight = FontWeight.Bold)
else TextStyle(fontSize = 38.gsp, lineHeight = 48.gsp, fontWeight = FontWeight.ExtraBold)

/** 영수증 기본 글자(v3: 32/40, 분할 30/38, 보통 굵기). */
@Composable
private fun receiptPlain(compact: Boolean) = if (compact) TextStyle(fontSize = 30.gsp, lineHeight = 38.gsp, fontWeight = FontWeight.Normal)
else TextStyle(fontSize = 32.gsp, lineHeight = 40.gsp, fontWeight = FontWeight.Normal)

/** 영수증 굵은 값(v3: 700 34/42, 분할 700 30/38). */
@Composable
private fun receiptStrong(compact: Boolean) = if (compact) TextStyle(fontSize = 30.gsp, lineHeight = 38.gsp, fontWeight = FontWeight.Bold)
else TextStyle(fontSize = 34.gsp, lineHeight = 42.gsp, fontWeight = FontWeight.Bold)

private fun Float.gpxPx(d: androidx.compose.ui.unit.Density) = this * d.density

/** 영수증 이중선. */
@Composable
fun CgDoubleRule(modifier: Modifier = Modifier) {
    val c = Chagok.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.gpx)) {
        Box(Modifier.fillMaxWidth().height(2.gpx).background(c.textSecondary))   // .paper .rule2 = text-secondary
        Box(Modifier.fillMaxWidth().height(2.gpx).background(c.textSecondary))
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
        // 바코드 번호: 400 26/30 · 자간 .1em · text-secondary
        Text(code, style = TextStyle(fontSize = 26.gsp, lineHeight = 30.gsp, fontWeight = FontWeight.Normal, letterSpacing = 26.gsp * 0.1f), color = c.textSecondary)
    }
}

// ------------------------------------------------------------ loading · empty · notice
/** 기다림 막대: skeleton-base ↔ highlight 1.1초 번갈아(모서리 11). 사진 위(ChagokOnPhoto)는 #FFFFFF33 고정. */
@Composable
fun CgSkeleton(modifier: Modifier, onPhoto: Boolean = false) {
    val c = Chagok.colors
    if (onPhoto) { Box(modifier.background(c.placeholderStrong, RoundedCornerShape(11.gpx))); return }
    val inf = rememberInfiniteTransition(label = "skel")
    val a by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "a")
    Box(modifier.drawBehind { drawRoundRect(androidx.compose.ui.graphics.lerp(c.skeletonBase, c.skeletonHighlight, a), cornerRadius = CornerRadius(11f * density)) })
}

/** 기다림 점 세 개(1초, 0.15초씩 지연). */
@Composable
fun CgLoadingDots(color: Color = Chagok.colors.textSecondary) {
    val inf = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(6.gpx), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val a by inf.animateFloat(.25f, 1f, infiniteRepeatable(keyframes { durationMillis = 1000; .25f at 0; 1f at 300 + i * 150; .25f at 1000 }), label = "d$i")
            Box(Modifier.size(8.gpx).graphicsLayer { alpha = a }.background(color, CircleShape))
        }
    }
}

/** 빈 상태(`components/empty`): 점선 상자 + 제목 + 안내. */
@Composable
fun CgEmpty(title: String, hint: String, modifier: Modifier = Modifier) {
    val c = Chagok.colors
    Column(
        modifier.cgDashed(32.gpx, c.borderStrong).padding(horizontal = 32.gpx),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.gpx, Alignment.CenterVertically),
    ) {
        Text(title, style = Chagok.type.titleS, color = c.textPrimary, textAlign = TextAlign.Center)
        Text(hint, style = Chagok.type.bodyM, color = c.textTertiary, textAlign = TextAlign.Center)
    }
}

/** 알약 배너(`components/notice`): 에러·안내 한 줄. warn = 경고색(.offl · .notice.warn) + 앞 아이콘. */
@Composable
fun CgBanner(text: String, modifier: Modifier = Modifier, warn: Boolean = false, icon: CgIcon? = null) {
    val c = Chagok.colors
    val bg = if (warn) c.statusWarningBackground else c.btnBasic
    val fg = if (warn) c.statusWarningForeground else c.textSecondary
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.gpx).background(bg, RoundedCornerShape(999.gpx)).padding(horizontal = 28.gpx, vertical = 12.gpx),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.gpx),
    ) {
        icon?.let { CgIconMark(it, 32.gpx, fg) }
        Text(text, style = if (warn) Chagok.type.bodyMB else Chagok.type.bodyM, color = fg)
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

// ------------------------------------------------------------ segmented · switch (#75 v41 설정)
/** 둘로 나뉜 알약(`components/segmented`): 고른 쪽만 흰 면 + 굵게, 높이 76(분할 64). */
@Composable
fun CgSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val c = Chagok.colors
    Row(modifier.background(c.btnBasic, CircleShape).padding(6.gpx), horizontalArrangement = Arrangement.spacedBy(4.gpx)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.then(if (compact) Modifier.weight(1f) else Modifier).height(if (compact) 64.gpx else 76.gpx)
                    .then(if (on) Modifier.shadow(6.gpx, CircleShape).background(c.selectionBackground, CircleShape).border(2.gpx, c.selectionBorder, CircleShape) else Modifier)
                    .clickable(role = Role.RadioButton) { onSelect(i) }.padding(horizontal = if (compact) 12.gpx else 30.gpx),
                contentAlignment = Alignment.Center,
            ) {
                val st = if (compact) Chagok.type.bodyM else Chagok.type.bodyL
                Text(label, style = st.copy(fontWeight = FontWeight.Bold), color = if (on) c.selectionForeground else c.textTertiary, maxLines = 1)   // .sg body-l 700 양쪽
            }
        }
    }
}

/** 스위치 116 × 68(`components/switch`): 켜짐 = 라임 트랙 + 잉크 손잡이 오른쪽(v3.1 switch-*). 0.32초 (.32,.72,0,1). */
@Composable
fun CgSwitch(checked: Boolean, onToggle: (Boolean) -> Unit, label: String, modifier: Modifier = Modifier) {
    val c = Chagok.colors
    val x by animateFloatAsState(if (checked) 1f else 0f, tween(320, easing = ChagokMotion.Rise), label = "sw")
    Box(
        modifier.size(116.gpx, 68.gpx).background(if (checked) c.switchTrackOn else c.switchTrackOff, RoundedCornerShape(34.gpx))
            .semantics { contentDescription = label }
            .toggleable(checked, role = Role.Switch, onValueChange = onToggle).padding(6.gpx),
    ) {
        val shift = 48.gpx
        Box(Modifier.graphicsLayer { translationX = x * shift.toPx() }.size(56.gpx).shadow(2.gpx, CircleShape).background(if (checked) c.switchThumbOn else c.switchThumbOff, CircleShape))
    }
}

// ------------------------------------------------------------ noti-panel (v93 → v96)
/** 알림 한 줄: "○○ 기록을 남겼어요" + 때("방금" · 날짜). fresh = 아직 안 읽은 새 알림(굵게 · 라임 아이콘 원 · 빨간 점). */
data class CgNoti(val title: String, val time: String, val fresh: Boolean, val place: String? = null)   // place: 제목 앞 장소 이름(이름만 말줄임, #118 D2)

private val BELL_KF = floatArrayOf(0f, 0f, .15f, 14f, .30f, -12f, .45f, 8f, .60f, -5f, .75f, 2f, 1f, 0f)

internal fun bellAngle(p: Float): Float {
    if (p <= 0f || p >= 1f) return 0f
    var i = 0
    while (i + 2 < BELL_KF.size && BELL_KF[i + 2] < p) i += 2
    val t0 = BELL_KF[i]; val v0 = BELL_KF[i + 1]; val t1 = BELL_KF[i + 2]; val v1 = BELL_KF[i + 3]
    return v0 + (v1 - v0) * ((p - t0) / (t1 - t0)).coerceIn(0f, 1f)
}

/** 위 바 알림 종(Icon Button 80). dot = 새 기록이 있음(빨간 점 18, 0.3초 뒤 톡 튀어나옴) · open = 창이 열려 있음(눌린 면). */
@Composable
fun CgBell(label: String, dot: Boolean, open: Boolean, onClick: () -> Unit, compact: Boolean = false, reduce: Boolean = false) {
    val c = Chagok.colors
    val pop = remember { Animatable(if (dot && reduce) 1f else 0f) }
    LaunchedEffect(dot) {
        if (!dot) pop.snapTo(0f)
        else if (reduce) pop.snapTo(1f)
        else { delay(300); pop.animateTo(1f, tween(450, easing = CubicBezierEasing(.3f, 1.5f, .5f, 1f))) }
    }
    // 11/9 명준 2차: 읽기 전(창을 닫기 전)까지 = 활성 아이콘(bell-ringing) + 빨간 점. 0.5초 뒤 1.1초 한 번 흔들림
    val ring = remember { Animatable(if (dot) 1f else 0f) }
    LaunchedEffect(dot) {
        if (!dot || reduce) { ring.snapTo(if (dot) 1f else 0f); return@LaunchedEffect }
        ring.snapTo(0f); delay(500); ring.animateTo(1f, tween(1100, easing = CubicBezierEasing(.36f, .07f, .19f, .97f)))
    }
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Box {
        Box(
            Modifier.size(80.gpx)
                .background(if (pressed || open) c.btnBasicPressed else c.btnBasic, RoundedCornerShape(20.gpx))
                .clickable(src, indication = null, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.graphicsLayer {
                // cg-ring-bell: 15% 14° · 30% -12° · 45% 8° · 60% -5° · 75% 2° · 100% 0, 기준점 50% 12%
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(.5f, .12f)
                rotationZ = bellAngle(ring.value)
            }) { CgIconMark(if (dot) CgIcon.BellRinging else CgIcon.Bell, 48.gpx, c.textPrimary) }   // .btn.ib .ico.pi 48
        }
        if (dot) Box(
            // 점 18은 top 14 · right 16, 링 4는 바깥(box-shadow)이라 링 포함 상자는 4씩 바깥으로
            Modifier.align(Alignment.TopEnd).padding(top = if (compact) 8.gpx else 10.gpx, end = if (compact) 10.gpx else 12.gpx)
                .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
                .size(if (compact) 24.gpx else 26.gpx).background(c.bgPrimary, CircleShape).padding(4.gpx)
                .background(c.notificationDot, CircleShape),
        )
    }
}

/**
 * 알림 창(`components/noti-panel`): 페이지가 아니라 종 아래로 내려오는 창. 딤 없음, 바깥을 누르면 접힘.
 * 두 겹 테(바깥 테 62% + 안쪽 판) · 꼬리(종 가운데) · 머리 "알림" + "새 알림 n" · 줄(아이콘 원 + 제목 + 때) · 아래 안내.
 * 펼침 0.42초 → 줄이 0.05초 간격으로 12px 떠오름 · 접기 0.2초. 줄은 누르지 않는다(확인만).
 */
@Composable
fun BoxScope.CgNotiPanel(
    open: Boolean, items: List<CgNoti>, head: String, newLabel: String?, foot: String, onDismiss: () -> Unit,
    compact: Boolean = false, reduce: Boolean = false,
) {
    val c = Chagok.colors
    val shown = remember { androidx.compose.animation.core.MutableTransitionState(false) }
    shown.targetState = open
    if (!shown.currentState && !shown.targetState) return
    // 바깥 누르면 접힘(딤 없음)
    if (open) Box(Modifier.matchParentSize().clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss))
    val tr = androidx.compose.animation.core.rememberTransition(shown, label = "noti")
    val k by tr.animateFloat({ if (targetState) tween(if (reduce) 0 else 420, easing = CubicBezierEasing(.32f, .72f, 0f, 1f)) else tween(if (reduce) 0 else 200, easing = CubicBezierEasing(.4f, 0f, 1f, 1f)) }, label = "k") { if (it) 1f else 0f }
    val rowT = remember { Animatable(0f) }
    LaunchedEffect(open) { if (open) { rowT.snapTo(0f); rowT.animateTo(1f, tween(if (reduce) 0 else 800, easing = LinearEasing)) } }
    val outer = RoundedCornerShape(if (compact) 34.gpx else 40.gpx)
    val inner = RoundedCornerShape(if (compact) 28.gpx else 32.gpx)
    val w = if (compact) 520.gpx else 600.gpx
    // 꼬리 가운데 = 창 오른쪽에서 132(종 가운데: 설정 80 + 사이 12 + 종 반 40). .ncaret right 120 + 폭 24의 반
    val caretRight = 132.gpx
    // 아티팩트 .npanel top 132 · right 40은 화면 기준. 이 창은 바깥 여백 40 안에 있으므로 위 92 · 오른쪽 0
    Box(
        Modifier.align(Alignment.TopEnd).padding(top = 92.gpx).width(w)
            .graphicsLayer {
                alpha = k
                // 펼침: 위 -14 · 세로 .78 · 가로 .96 → 제자리(기준점 = 꼬리) / 접기: 위 -10 · 세로 .9
                val closing = !shown.targetState
                translationY = (1f - k) * (if (closing) -10f else -14f) * density
                scaleY = 1f - (1f - k) * (if (closing) .1f else .22f)
                scaleX = 1f - (1f - k) * (if (closing) 0f else .04f)
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin((w.toPx() - caretRight.toPx()) / w.toPx(), 0f)
            }
            .clickable(remember { MutableInteractionSource() }, indication = null) {},   // 창 안을 눌러도 접히지 않게
    ) {
        // 꼬리(24 마름모, 종 가운데)
        val rim = if (c.isDark) Color(0x0DFFFFFF) else c.surfaceRaised.copy(alpha = .62f)   // 야간 .npanel 바깥 테 #FFFFFF0D
        Box(Modifier.align(Alignment.TopEnd).offset(x = -caretRight + 12.gpx, y = (-9).gpx).size(24.gpx).graphicsLayer { rotationZ = 45f }
            .background(rim, RoundedCornerShape(5.gpx)))
        Column(
            Modifier.fillMaxWidth()
                .shadow(28.gpx, outer, ambientColor = if (c.isDark) Color(0xA6000000) else c.textPrimary.copy(alpha = 0x4D / 255f), spotColor = if (c.isDark) Color(0x66000000) else c.textPrimary.copy(alpha = 0x24 / 255f))
                .background(rim, outer)
                .border(1.gpx, if (c.isDark) Color(0x17FFFFFF) else c.textPrimary.copy(alpha = 0x0F / 255f), outer)   // 주간 textPrimary(v4 #242135)
                .padding(if (compact) 6.gpx else 8.gpx),
        ) {
            Column(
                Modifier.fillMaxWidth().background(c.surfaceRaised, inner)
                    .padding(start = if (compact) 22.gpx else 30.gpx, end = if (compact) 22.gpx else 30.gpx, top = if (compact) 22.gpx else 28.gpx, bottom = if (compact) 18.gpx else 22.gpx),
            ) {
                fun Modifier.rise(i: Int) = graphicsLayer {
                    val start = (80 + i * 50) / 800f
                    val q = ((rowT.value - start) / (500f / 800f)).coerceIn(0f, 1f)
                    val e = CubicBezierEasing(.32f, .72f, 0f, 1f).transform(q)
                    alpha = e; translationY = (1f - e) * 12f * density
                }
                Row(Modifier.fillMaxWidth().padding(start = 4.gpx, end = 4.gpx, bottom = 14.gpx).rise(0), verticalAlignment = Alignment.CenterVertically) {
                    Text(head, Modifier.weight(1f), style = androidx.compose.ui.text.TextStyle(fontSize = (if (compact) 22 else 26).gsp, lineHeight = (if (compact) 28 else 32).gsp, fontWeight = FontWeight.Bold, letterSpacing = (if (compact) 22 else 26).gsp * .02f), color = c.textTertiary)
                    newLabel?.let {
                        Text(it, Modifier.height(40.gpx).background(c.selectionBackground, CircleShape).padding(horizontal = 16.gpx).wrapContentHeight(Alignment.CenterVertically),
                            style = androidx.compose.ui.text.TextStyle(fontSize = 22.gsp, lineHeight = 28.gsp, fontWeight = FontWeight.Bold), color = c.selectionForeground)
                    }
                }
                items.forEachIndexed { i, n ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.gpx).background(c.dividerDefault))
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = if (compact) 14.gpx else 18.gpx, horizontal = if (compact) 2.gpx else 4.gpx).rise(i + 1),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 16.gpx else 20.gpx),
                    ) {
                        Box(Modifier.size(if (compact) 52.gpx else 60.gpx).background(if (n.fresh) c.selectionBackground else c.bgSubtle, CircleShape), contentAlignment = Alignment.Center) {
                            CgIconMark(CgIcon.CarProfile, if (compact) 26.gpx else 30.gpx, if (n.fresh) c.selectionForeground else c.iconSecondary)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.gpx)) {
                            CgPlaceLine(n.title, n.place, androidx.compose.ui.text.TextStyle(fontSize = (if (compact) 24 else 28).gsp, lineHeight = (if (compact) 32 else 36).gsp, fontWeight = if (n.fresh) FontWeight.ExtraBold else FontWeight.SemiBold),
                                c.textPrimary)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.gpx)) {
                                if (n.fresh) Box(Modifier.size(12.gpx).background(c.notificationDot, CircleShape))
                                Text(n.time, style = androidx.compose.ui.text.TextStyle(fontSize = (if (compact) 20 else 22).gsp, lineHeight = (if (compact) 26 else 28).gsp, fontWeight = if (n.fresh) FontWeight.Bold else FontWeight.Normal),
                                    color = if (n.fresh) c.textPrimary else c.textTertiary)
                            }
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().padding(top = 10.gpx).height(1.gpx).background(c.dividerDefault))
                Text(foot, Modifier.padding(start = 4.gpx, end = 4.gpx, top = 16.gpx).rise(4),
                    style = androidx.compose.ui.text.TextStyle(fontSize = (if (compact) 20 else 22).gsp, lineHeight = (if (compact) 26 else 28).gsp), color = c.textTertiary)
            }
        }
    }
}
