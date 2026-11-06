package com.mist.pleos_app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/*
 * 차곡 Compose 테마 — design/tokens.css 값을 옮긴 임시본(Pleos Connect UI Design Guide 기준).
 * 토큰을 어디서 관리할지(tokens.json → 생성 스크립트)는 design/RULES.md 개정(열린 결정 12)에서 정한다.
 * 그 전까지 색·글자를 바꾸면 tokens.css와 같이 바꾼다.
 */

/** tokens.css의 --cg-* 중 Material colorScheme에 없는 것. 화면 코드는 색을 직접 적지 않고 여기서 꺼낸다. */
@Immutable
data class ChagokColors(
    val bgPrimary: Color,
    val bgSecondary: Color,
    val surfaceHigh: Color,
    val surfaceAccent: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val btnBasic: Color,
    val btnBasicPressed: Color,
    val btnDisabled: Color,
    val btnFilled: Color,
    val btnFilledPressed: Color,
    val btnFilledText: Color,
    val line: Color,
    val active: Color,
    val positive: Color,
    val negative: Color,
    // 배지 = 색 바탕 + 대비(4.5:1 이상)를 계산한 글자색 (design/RULES.md 1절)
    val badgeZero: Color,
    val badgeZeroText: Color,
    val badgeExtra: Color,
    val badgeExtraText: Color,
    val badgeTight: Color,
    val badgeTightText: Color,
)

private val DarkColors = ChagokColors(
    bgPrimary = Color(0xFF1D1E21), bgSecondary = Color(0xFF131417),
    surfaceHigh = Color(0xFF313236), surfaceAccent = Color(0xFF44464E),
    textPrimary = Color(0xFFFFFFFF), textSecondary = Color(0xD6FFFFFF), textTertiary = Color(0xA3FFFFFF),
    btnBasic = Color(0x1AFFFFFF), btnBasicPressed = Color(0x33FFFFFF), btnDisabled = Color(0x0DFFFFFF),
    btnFilled = Color(0xFFE0E1E6), btnFilledPressed = Color(0xFFC7C9CE), btnFilledText = Color(0xFF131417),
    line = Color(0x1AFFFFFF),
    active = Color(0xFF00E074), positive = Color(0xFF0082FF), negative = Color(0xFFFF4C28),
    badgeZero = Color(0xFF00E074), badgeZeroText = Color(0xFF131417),
    badgeExtra = Color(0xFF0082FF), badgeExtraText = Color(0xFF131417),
    badgeTight = Color(0xFFFF4C28), badgeTightText = Color(0xFF131417),
)

private val LightColors = ChagokColors(
    bgPrimary = Color(0xFFF7F8FA), bgSecondary = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFEDEEF2), surfaceAccent = Color(0xFFE0E1E6),
    textPrimary = Color(0xFF131417), textSecondary = Color(0xD6131417), textTertiary = Color(0xA3131417),
    btnBasic = Color(0x0D131417), btnBasicPressed = Color(0x1A131417), btnDisabled = Color(0x0D131417),
    btnFilled = Color(0xFF44464E), btnFilledPressed = Color(0xFF313236), btnFilledText = Color(0xFFFFFFFF),
    line = Color(0x1A131417),
    active = Color(0xFF02C265), positive = Color(0xFF0064FF), negative = Color(0xFFFE3D16),
    badgeZero = Color(0xFF02C265), badgeZeroText = Color(0xFF131417),
    badgeExtra = Color(0xFF0064FF), badgeExtraText = Color(0xFFFFFFFF),
    badgeTight = Color(0xFFFE3D16), badgeTightText = Color(0xFF131417),
)

private val Dark = darkColorScheme(
    background = DarkColors.bgPrimary,
    surface = DarkColors.bgPrimary,
    surfaceVariant = DarkColors.surfaceHigh,
    onBackground = DarkColors.textPrimary,
    onSurface = DarkColors.textPrimary,
    primary = DarkColors.btnFilled,
    onPrimary = DarkColors.btnFilledText,
    error = DarkColors.negative,
)

private val Light = lightColorScheme(
    background = LightColors.bgPrimary,
    surface = LightColors.bgSecondary,
    surfaceVariant = LightColors.surfaceHigh,
    onBackground = LightColors.textPrimary,
    onSurface = LightColors.textPrimary,
    primary = LightColors.btnFilled,
    onPrimary = LightColors.btnFilledText,
    error = LightColors.negative,
)

/**
 * Pleos 가이드 글자 위계(가이드 px, tokens.css --cg-*). 크기는 가이드 px라 [Int.gsp]로 바꿔 쓴다.
 * 가장 작은 Label S(20px)도 화면에서 12sp 이상(Pleos 디자인 가이드 Typography, AC-C-9).
 */
@Immutable
data class ChagokType(
    val headlineS: TextStyle,
    val titleM: TextStyle,
    val titleS: TextStyle,
    val bodyL: TextStyle,
    val bodyM: TextStyle,
    val bodyS: TextStyle,
    val labelM: TextStyle,
    val labelS: TextStyle,
)

@Composable
@ReadOnlyComposable
private fun chagokType(): ChagokType {
    val density = LocalDensity.current
    fun style(size: Int, line: Int, weight: FontWeight) =
        with(density) { TextStyle(fontSize = size.toSp(), lineHeight = line.toSp(), fontWeight = weight) }
    return ChagokType(
        headlineS = style(48, 64, FontWeight.ExtraBold),
        titleM = style(36, 44, FontWeight.ExtraBold),
        titleS = style(32, 40, FontWeight.Bold),
        bodyL = style(30, 38, FontWeight.Normal),
        bodyM = style(28, 36, FontWeight.Normal),
        bodyS = style(26, 34, FontWeight.Normal),
        labelM = style(24, 28, FontWeight.Normal),
        labelS = style(20, 24, FontWeight.Normal),
    )
}

private val LocalChagokColors = staticCompositionLocalOf { DarkColors }
private val LocalChagokType = staticCompositionLocalOf<ChagokType?> { null }

/** 화면 코드에서 색·글자를 꺼내는 곳: `Chagok.colors.textPrimary`, `Chagok.type.bodyS`. */
object Chagok {
    val colors: ChagokColors
        @Composable @ReadOnlyComposable get() = LocalChagokColors.current
    val type: ChagokType
        @Composable @ReadOnlyComposable get() = LocalChagokType.current ?: chagokType()
}

/** 주간/야간: 기본 야간, 시스템이 주간이면 주간(tokens.css의 prefers-color-scheme와 같은 규칙). */
@Composable
fun ChagokTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalChagokColors provides if (dark) DarkColors else LightColors,
        LocalChagokType provides chagokType(),
    ) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
    }
}

/*
 * 크기 단위: Pleos 디자인 가이드 크기는 "디스플레이 실제 픽셀"이다(tokens.css의 --u).
 * Compose에서는 가이드 값을 px로 보고 dp·sp로 바꾼다. 터치 영역 48처럼 dp 기준 값은 그냥 48.dp.
 */
val Int.gpx: Dp
    @Composable @ReadOnlyComposable get() = with(LocalDensity.current) { this@gpx.toDp() }

val Int.gsp: TextUnit
    @Composable @ReadOnlyComposable get() = with(LocalDensity.current) { this@gsp.toSp() }

/** 터치 영역 최소값(Pleos·Android 접근성 48dp). */
val MinTouch: Dp = 48.dp

/** Window Policy: 이 폭(px) 이하면 한 단으로 쌓는다(1:1 분할·세로 창, design/RULES.md 4절). */
const val NARROW_MAX_PX = 900
