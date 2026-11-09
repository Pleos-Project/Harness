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
    // Design v2(11/8, #75) 흑백 와이어가 쓰는 면·선·자리
    val surfaceCard: Color,
    val lineWeak: Color,
    val lineStrong: Color,
    val placeholder: Color,
    val placeholderStrong: Color,
    // Nogo 컬러 v3.1 역할 토큰(#118 design/tokens.css 맨 아래, HANDOFF_v3 1-2). 이름 = --color-* 를 camelCase로
    val textDisabled: Color,
    val btnBasicText: Color,
    val btnDisabledText: Color,
    val btnDestructive: Color,
    val btnDestructivePressed: Color,
    val btnDestructiveText: Color,
    val selectionBackground: Color,
    val selectionForeground: Color,
    val selectionBorder: Color,
    val selectionPressed: Color,
    val switchTrackOn: Color,
    val switchThumbOn: Color,
    val switchTrackOff: Color,
    val switchThumbOff: Color,
    val progressIndicator: Color,
    val progressIndicatorBorder: Color,
    val progressLine: Color,
    val progressTrack: Color,
    val fieldBackground: Color,
    val fieldBorder: Color,
    val fieldCaret: Color,
    val focusRing: Color,
    val receiptPaper: Color,
    val receiptRule: Color,
    val receiptMarker: Color,
    val notificationDot: Color,           // v93 알림 종 빨간 점
    val surfaceRaised: Color,             // 알림 창 판
    val iconSecondary: Color,
    val dividerDefault: Color,
    val bgSubtle: Color,
    val receiptPrinterHighlight: Color,   // Satin Silver(출력구 · 시동 버튼 테)
    val receiptPrinterMidtone: Color,
    val receiptPrinterShade: Color,
    val statusWarningBackground: Color,
    val statusWarningForeground: Color,
    val statusErrorBackground: Color,
    val statusErrorForeground: Color,
    val statusInfoBackground: Color,
    val statusInfoForeground: Color,
    val dimScrim: Color,
    val brandWordmark: Color,
    val drivingLockBackground: Color,
    val drivingLockForeground: Color,
    /** 야간 테마인지(로고 · 앱 아이콘처럼 주간 · 야간 그림이 따로 있는 곳에서 고름). */
    val isDark: Boolean = false,
)

private val DarkColors = ChagokColors(
    bgPrimary = Color(0xFF242033), bgSecondary = Color(0xFF131417),
    surfaceHigh = Color(0xFF313236), surfaceAccent = Color(0xFF44464E),
    textPrimary = Color(0xFFF7F5FC), textSecondary = Color(0xFFDEDAE8), textTertiary = Color(0xFFAFA8BD),
    btnBasic = Color(0xFF464055), btnBasicPressed = Color(0xFF5C546B), btnDisabled = Color(0xFF3A354A),
    btnFilled = Color(0xFFCDEA6D), btnFilledPressed = Color(0xFFBFD95F), btnFilledText = Color(0xFF242033),
    line = Color(0xFF464055),
    active = Color(0xFF00E074), positive = Color(0xFF0082FF), negative = Color(0xFFFF4C28),
    badgeZero = Color(0xFF00E074), badgeZeroText = Color(0xFF131417),
    badgeExtra = Color(0xFF0082FF), badgeExtraText = Color(0xFF131417),
    badgeTight = Color(0xFFFF4C28), badgeTightText = Color(0xFF131417),
    surfaceCard = Color(0xFF312C40), lineWeak = Color(0xFF464055), lineStrong = Color(0xFF464055),
    placeholder = Color(0xFF292538), placeholderStrong = Color(0xFF51495F),
    // v3.1 역할 토큰
    textDisabled = Color(0xFF797187),
    btnBasicText = Color(0xFFF7F5FC),
    btnDisabledText = Color(0xFF797187),
    btnDestructive = Color(0xFFFFADAD),
    btnDestructivePressed = Color(0xFFF78F92),
    btnDestructiveText = Color(0xFF242033),
    selectionBackground = Color(0xFFD9F77A),
    selectionForeground = Color(0xFF242033),
    selectionBorder = Color(0xFFD9F77A),
    selectionPressed = Color(0xFFCDEA6D),
    switchTrackOn = Color(0xFFCDEA6D),
    switchThumbOn = Color(0xFF242033),
    switchTrackOff = Color(0xFF8B849C),
    switchThumbOff = Color(0xFF242033),
    progressIndicator = Color(0xFFD9F77A),
    progressIndicatorBorder = Color(0xFFD9F77A),
    progressLine = Color(0xFFD9F77A),
    progressTrack = Color(0xFF6B637B),
    fieldBackground = Color(0xFF292538),
    fieldBorder = Color(0xFF8B849C),
    fieldCaret = Color(0xFFD9F77A),
    focusRing = Color(0xFFD9F77A),
    receiptPaper = Color(0xFF312C40),
    receiptRule = Color(0xFF464055),
    receiptMarker = Color(0xFF445616),
    notificationDot = Color(0xFFFF6B6B), surfaceRaised = Color(0xFF3A354A), iconSecondary = Color(0xFFAFA8BD),
    dividerDefault = Color(0xFF464055), bgSubtle = Color(0xFF292538),
    receiptPrinterHighlight = Color(0xFFA5AFBC),
    receiptPrinterMidtone = Color(0xFF626D7D),
    receiptPrinterShade = Color(0xFF3E4754),
    statusWarningBackground = Color(0xFF493518),
    statusWarningForeground = Color(0xFFF9D171),
    statusErrorBackground = Color(0xFF48272E),
    statusErrorForeground = Color(0xFFFFADAD),
    statusInfoBackground = Color(0xFF203750),
    statusInfoForeground = Color(0xFFA9CFFF),
    dimScrim = Color(0xB8000000),
    brandWordmark = Color(0xFFD9F77A),
    drivingLockBackground = Color(0xFF242033),
    drivingLockForeground = Color(0xFFF7F5FC),
    isDark = true,
)

private val LightColors = ChagokColors(
    bgPrimary = Color(0xFFF7F5FC), bgSecondary = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFEDEEF2), surfaceAccent = Color(0xFFE0E1E6),
    textPrimary = Color(0xFF242033), textSecondary = Color(0xFF51495F), textTertiary = Color(0xFF6B637B),
    btnBasic = Color(0xFFE6E3EF), btnBasicPressed = Color(0xFFD1CCDD), btnDisabled = Color(0xFFEFEDF5),
    btnFilled = Color(0xFFD9F77A), btnFilledPressed = Color(0xFFBFD95F), btnFilledText = Color(0xFF242033),
    line = Color(0xFFDEDAE8),
    active = Color(0xFF02C265), positive = Color(0xFF0064FF), negative = Color(0xFFFE3D16),
    badgeZero = Color(0xFF02C265), badgeZeroText = Color(0xFF131417),
    badgeExtra = Color(0xFF0064FF), badgeExtraText = Color(0xFFFFFFFF),
    badgeTight = Color(0xFFFE3D16), badgeTightText = Color(0xFF131417),
    surfaceCard = Color(0xFFFFFFFF), lineWeak = Color(0xFFDEDAE8), lineStrong = Color(0xFFDEDAE8),
    placeholder = Color(0xFFEFEDF5), placeholderStrong = Color(0xFFDEDAE8),
    // v3.1 역할 토큰
    textDisabled = Color(0xFF8B849C),
    btnBasicText = Color(0xFF242033),
    btnDisabledText = Color(0xFF8B849C),
    btnDestructive = Color(0xFF9D303D),
    btnDestructivePressed = Color(0xFF792532),
    btnDestructiveText = Color(0xFFFFFFFF),
    selectionBackground = Color(0xFFD9F77A),
    selectionForeground = Color(0xFF242033),
    selectionBorder = Color(0xFF242033),
    selectionPressed = Color(0xFFCDEA6D),
    switchTrackOn = Color(0xFFD9F77A),
    switchThumbOn = Color(0xFF242033),
    switchTrackOff = Color(0xFF797187),
    switchThumbOff = Color(0xFFFFFFFF),
    progressIndicator = Color(0xFFD9F77A),
    progressIndicatorBorder = Color(0xFF242033),
    progressLine = Color(0xFF242033),
    progressTrack = Color(0xFF9A93AA),
    fieldBackground = Color(0xFFFFFFFF),
    fieldBorder = Color(0xFF797187),
    fieldCaret = Color(0xFF242033),
    focusRing = Color(0xFF242033),
    receiptPaper = Color(0xFFFFFFFF),
    receiptRule = Color(0xFFDEDAE8),
    receiptMarker = Color(0xFFD9F77A),
    notificationDot = Color(0xFFE5484D), surfaceRaised = Color(0xFFFFFFFF), iconSecondary = Color(0xFF6B637B),
    dividerDefault = Color(0xFFDEDAE8), bgSubtle = Color(0xFFEFEDF5),
    receiptPrinterHighlight = Color(0xFFEDF1F6),
    receiptPrinterMidtone = Color(0xFFCBD2DB),
    receiptPrinterShade = Color(0xFF8994A3),
    statusWarningBackground = Color(0xFFFFF1CC),
    statusWarningForeground = Color(0xFF6F4908),
    statusErrorBackground = Color(0xFFFDE9E9),
    statusErrorForeground = Color(0xFF9D303D),
    statusInfoBackground = Color(0xFFE7F1FD),
    statusInfoForeground = Color(0xFF244D7F),
    dimScrim = Color(0x8F242033),
    brandWordmark = Color(0xFF242033),
    drivingLockBackground = Color(0xFFEFEDF5),
    drivingLockForeground = Color(0xFF242033),
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
    val headlineL: TextStyle,
    val headlineM: TextStyle,
    val headlineS: TextStyle,
    val titleL: TextStyle,
    val titleM: TextStyle,
    val titleS: TextStyle,
    val bodyL: TextStyle,
    val bodyM: TextStyle,
    val bodyMB: TextStyle,
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
    // 굵기는 Pleos design assets 2.1.5(Design v2, #75 명준 11/8): Title M 700, Body L·S 700
    return ChagokType(
        headlineL = style(60, 80, FontWeight.ExtraBold),
        headlineM = style(56, 72, FontWeight.ExtraBold),
        headlineS = style(48, 64, FontWeight.ExtraBold),
        titleL = style(40, 52, FontWeight.Bold),
        titleM = style(36, 44, FontWeight.Bold),
        titleS = style(32, 40, FontWeight.Bold),
        bodyL = style(30, 38, FontWeight.Bold),
        bodyM = style(28, 36, FontWeight.Normal),
        bodyMB = style(28, 36, FontWeight.Bold),
        bodyS = style(26, 34, FontWeight.Bold),
        labelM = style(24, 28, FontWeight.Normal),
        labelS = style(20, 24, FontWeight.Normal),
    )
}

private val LocalChagokColors = staticCompositionLocalOf { DarkColors }
private val LocalChagokType = staticCompositionLocalOf<ChagokType?> { null }

/** 화면 코드에서 색·글자를 꺼내는 곳: `Chagok.colors.textPrimary`, `Chagok.type.bodyS`. */
/**
 * 사진(또는 딥 잉크) 위 칸의 색(#118 HANDOFF_v3 4-2, 주간·야간 같음): 글자 흰색 · [출발] 라임 + 잉크 글자 ·
 * 꺼짐 #FFFFFF2E 면 + #FFFFFFA6 글자 · 불러오는 막대 #FFFFFF33 · 오류 #FFADAD · 안내 #A9CFFF.
 */
@Composable
fun ChagokOnPhoto(content: @Composable () -> Unit) {
    val c = LocalChagokColors.current
    val white = Color(0xFFFFFFFF)
    CompositionLocalProvider(
        LocalChagokColors provides c.copy(
            textPrimary = white, textSecondary = Color(0xE0FFFFFF), textTertiary = Color(0xB8FFFFFF), textDisabled = Color(0xA6FFFFFF),
            btnFilled = Color(0xFFD9F77A), btnFilledPressed = Color(0xFFBFD95F), btnFilledText = Color(0xFF242033),
            btnDisabled = Color(0x2EFFFFFF), btnDisabledText = Color(0xA6FFFFFF), line = Color(0x00FFFFFF),
            btnBasic = Color(0x2EFFFFFF), btnBasicPressed = Color(0x47FFFFFF), btnBasicText = white,
            placeholderStrong = Color(0x33FFFFFF),
            statusErrorForeground = Color(0xFFFFADAD), statusInfoForeground = Color(0xFFA9CFFF),
        ),
        content = content,
    )
}

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
