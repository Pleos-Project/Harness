package com.mist.pleos_app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
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
    /** 이름은 그대로 두지만(사용처가 많음) 값 = --color-border-subtle(아티팩트 옛 별칭 --div3). 강한 테두리는 [borderStrong]. */
    val lineStrong: Color,
    val placeholder: Color,
    val placeholderStrong: Color,
    // Nogo 컬러 역할 토큰(값 = v4, #162 HANDOFF_v4 1절 · design/tokens.css 맨 아래). 이름 = --color-* 를 camelCase로
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
    val selectionHalo: Color,          // 고른 목적지 카드 바깥 자리(아티팩트 --color-selection-halo)
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
    // 아티팩트 대조(11/11)로 더한 v3.1 토큰
    val borderStrong: Color,              // 점선 빈 자리 · 강한 테두리(--color-border-strong). lineStrong은 이름과 달리 border-subtle 값
    val btnTertiaryText: Color,           // 글자 버튼(--color-button-tertiary-foreground)
    val btnTertiaryPressed: Color,        // 글자 · 아이콘 버튼 눌림(--color-button-tertiary-pressed)
    val surfaceModal: Color,              // 하단 시트(--color-surface-modal)
    val surfaceNotification: Color,       // 인원 알림 바(--color-surface-notification)
    val sheetBorder: Color,               // 시트 · 알림 바 테두리: 주간 border-subtle, 야간 없음
    val receiptDetailBackground: Color,   // 영수증 근거 펼침 칸
    val skeletonBase: Color,
    val skeletonHighlight: Color,
    // Design v2 디자인시스템(11/14) — tokens.css v3.1 --color-* 중 앱에 없던 것. 값은 v4(#162 HANDOFF_v4 1절).
    // hover 3종(--color-button-primary-hover · secondary-hover · --color-overlay-hover)은 터치 화면이라 넣지 않는다.
    val textLink: Color,                  // 글자 링크(--color-text-link)
    val iconPrimary: Color,               // 기본 아이콘(--color-icon-primary)
    val fieldForeground: Color,           // 입력칸 글자(--color-field-foreground)
    val fieldPlaceholder: Color,          // 입력칸 안내 글자(--color-field-placeholder)
    val receiptPrinterSlot: Color,        // 영수증 출력구 홈(--color-receipt-printer-slot, 주간·야간 같음)
    val receiptResultText: Color,         // 영수증 결과 글자(--color-receipt-result-text)
    val statusNeutralBackground: Color,   // 중립 상태 칩 면(--color-status-neutral-background)
    val statusNeutralForeground: Color,   // 중립 상태 칩 글자(--color-status-neutral-foreground)
    val overlayPressed: Color,            // 눌림 겹침(--color-overlay-pressed, 값 = btnTertiaryPressed)
    val reviewRain: Color,                // 돌아보기 비 줄기(아티팩트 --rc, tokens.css에는 없음 · v4와 무관)
    /** 야간 테마인지(로고 · 앱 아이콘처럼 주간 · 야간 그림이 따로 있는 곳에서 고름). */
    val isDark: Boolean = false,
)

private val DarkColors = ChagokColors(
    bgPrimary = Color(0xFF242135), bgSecondary = Color(0xFF131417),
    surfaceHigh = Color(0xFF313236), surfaceAccent = Color(0xFF44464E),
    textPrimary = Color(0xFFF7F5FC), textSecondary = Color(0xFFDDDAE9), textTertiary = Color(0xFFAEA8BE),
    btnBasic = Color(0xFF454056), btnBasicPressed = Color(0xFF5B546C), btnDisabled = Color(0xFF39354B),
    btnFilled = Color(0xFFBDE342), btnFilledPressed = Color(0xFFB0D33A), btnFilledText = Color(0xFF242135),
    line = Color(0xFF454056),
    active = Color(0xFF00E074), positive = Color(0xFF0082FF), negative = Color(0xFFFF4C28),
    badgeZero = Color(0xFF00E074), badgeZeroText = Color(0xFF131417),
    badgeExtra = Color(0xFF0082FF), badgeExtraText = Color(0xFF131417),
    badgeTight = Color(0xFFFF4C28), badgeTightText = Color(0xFF131417),
    surfaceCard = Color(0xFF302C41), lineWeak = Color(0xFF454056), lineStrong = Color(0xFF454056),
    placeholder = Color(0xFF282539), placeholderStrong = Color(0xFF504960),
    // v3.1 역할 토큰
    textDisabled = Color(0xFF787188),
    btnBasicText = Color(0xFFF7F5FC),
    btnDisabledText = Color(0xFF787188),
    btnDestructive = Color(0xFFFFADAD),
    btnDestructivePressed = Color(0xFFF78F92),
    btnDestructiveText = Color(0xFF242135),
    selectionBackground = Color(0xFFC8F04A),
    selectionForeground = Color(0xFF242135),
    selectionBorder = Color(0xFFC8F04A),
    selectionPressed = Color(0xFFBDE342),
    selectionHalo = Color(0x40C8F04A),
    switchTrackOn = Color(0xFFBDE342),
    switchThumbOn = Color(0xFF242135),
    switchTrackOff = Color(0xFF8A849D),
    switchThumbOff = Color(0xFF242135),
    progressIndicator = Color(0xFFC8F04A),
    progressIndicatorBorder = Color(0xFFC8F04A),
    progressLine = Color(0xFFC8F04A),
    progressTrack = Color(0xFF6A637C),
    fieldBackground = Color(0xFF282539),
    fieldBorder = Color(0xFF8A849D),
    fieldCaret = Color(0xFFC8F04A),
    focusRing = Color(0xFFC8F04A),
    receiptPaper = Color(0xFF302C41),
    receiptRule = Color(0xFF454056),
    receiptMarker = Color(0xFF41570B),
    notificationDot = Color(0xFFFF6B6B), surfaceRaised = Color(0xFF39354B), iconSecondary = Color(0xFFAEA8BE),
    dividerDefault = Color(0xFF454056), bgSubtle = Color(0xFF282539),
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
    brandWordmark = Color(0xFFC8F04A),
    drivingLockBackground = Color(0xFF242135),
    drivingLockForeground = Color(0xFFF7F5FC),
    borderStrong = Color(0xFF8A849D), btnTertiaryText = Color(0xFFDDDAE9), btnTertiaryPressed = Color(0x1FFFFFFF),
    surfaceModal = Color(0xFF39354B), surfaceNotification = Color(0xFF39354B), sheetBorder = Color(0x00000000),
    receiptDetailBackground = Color(0xFF282539), skeletonBase = Color(0xFF39354B), skeletonHighlight = Color(0xFF504960),
    textLink = Color(0xFFC8F04A), iconPrimary = Color(0xFFF7F5FC),
    fieldForeground = Color(0xFFF7F5FC), fieldPlaceholder = Color(0xFFAEA8BE),
    receiptPrinterSlot = Color(0xFF171B22), receiptResultText = Color(0xFFF7F5FC),
    statusNeutralBackground = Color(0xFF39354B), statusNeutralForeground = Color(0xFFDDDAE9),
    overlayPressed = Color(0x1FFFFFFF), reviewRain = Color(0x4DFFFFFF),
    isDark = true,
)

private val LightColors = ChagokColors(
    bgPrimary = Color(0xFFF7F5FC), bgSecondary = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFEDEEF2), surfaceAccent = Color(0xFFE0E1E6),
    textPrimary = Color(0xFF242135), textSecondary = Color(0xFF504960), textTertiary = Color(0xFF6A637C),
    btnBasic = Color(0xFFE5E3F0), btnBasicPressed = Color(0xFFD0CCDE), btnDisabled = Color(0xFFEFEDF5),
    btnFilled = Color(0xFFC8F04A), btnFilledPressed = Color(0xFFB0D33A), btnFilledText = Color(0xFF242135),
    line = Color(0xFFDDDAE9),
    active = Color(0xFF02C265), positive = Color(0xFF0064FF), negative = Color(0xFFFE3D16),
    badgeZero = Color(0xFF02C265), badgeZeroText = Color(0xFF131417),
    badgeExtra = Color(0xFF0064FF), badgeExtraText = Color(0xFFFFFFFF),
    badgeTight = Color(0xFFFE3D16), badgeTightText = Color(0xFF131417),
    surfaceCard = Color(0xFFFFFFFF), lineWeak = Color(0xFFDDDAE9), lineStrong = Color(0xFFDDDAE9),
    placeholder = Color(0xFFEFEDF5), placeholderStrong = Color(0xFFDDDAE9),
    // v3.1 역할 토큰
    textDisabled = Color(0xFF8A849D),
    btnBasicText = Color(0xFF242135),
    btnDisabledText = Color(0xFF8A849D),
    btnDestructive = Color(0xFF9D303D),
    btnDestructivePressed = Color(0xFF792532),
    btnDestructiveText = Color(0xFFFFFFFF),
    selectionBackground = Color(0xFFC8F04A),
    selectionForeground = Color(0xFF242135),
    selectionBorder = Color(0xFFC8F04A),   // 11/9 명준: 주간 선택 테두리도 야간처럼 라임
    selectionPressed = Color(0xFFBDE342),
    selectionHalo = Color(0x80C8F04A),
    switchTrackOn = Color(0xFFC8F04A),
    switchThumbOn = Color(0xFF242135),
    switchTrackOff = Color(0xFF787188),
    switchThumbOff = Color(0xFFFFFFFF),
    progressIndicator = Color(0xFFC8F04A),
    progressIndicatorBorder = Color(0xFF242135),
    progressLine = Color(0xFF242135),
    progressTrack = Color(0xFF9993AB),
    fieldBackground = Color(0xFFFFFFFF),
    fieldBorder = Color(0xFF787188),
    fieldCaret = Color(0xFF242135),
    focusRing = Color(0xFF242135),
    receiptPaper = Color(0xFFFFFFFF),
    receiptRule = Color(0xFFDDDAE9),
    receiptMarker = Color(0xFFC8F04A),
    notificationDot = Color(0xFFE5484D), surfaceRaised = Color(0xFFFFFFFF), iconSecondary = Color(0xFF6A637C),
    dividerDefault = Color(0xFFDDDAE9), bgSubtle = Color(0xFFEFEDF5),
    receiptPrinterHighlight = Color(0xFFEDF1F6),
    receiptPrinterMidtone = Color(0xFFCBD2DB),
    receiptPrinterShade = Color(0xFF8994A3),
    statusWarningBackground = Color(0xFFFFF1CC),
    statusWarningForeground = Color(0xFF6F4908),
    statusErrorBackground = Color(0xFFFDE9E9),
    statusErrorForeground = Color(0xFF9D303D),
    statusInfoBackground = Color(0xFFE7F1FD),
    statusInfoForeground = Color(0xFF244D7F),
    dimScrim = Color(0x8F242135),
    brandWordmark = Color(0xFF242135),
    drivingLockBackground = Color(0xFFEFEDF5),
    drivingLockForeground = Color(0xFF242135),
    borderStrong = Color(0xFF787188), btnTertiaryText = Color(0xFF504960), btnTertiaryPressed = Color(0x1F242135),
    surfaceModal = Color(0xFFFFFFFF), surfaceNotification = Color(0xFFFFFFFF), sheetBorder = Color(0xFFDDDAE9),
    receiptDetailBackground = Color(0xFFF7F5FC), skeletonBase = Color(0xFFEFEDF5), skeletonHighlight = Color(0xFFDDDAE9),
    textLink = Color(0xFF41570B), iconPrimary = Color(0xFF242135),
    fieldForeground = Color(0xFF242135), fieldPlaceholder = Color(0xFF6A637C),
    receiptPrinterSlot = Color(0xFF171B22), receiptResultText = Color(0xFF242135),
    statusNeutralBackground = Color(0xFFEFEDF5), statusNeutralForeground = Color(0xFF504960),
    overlayPressed = Color(0x1F242135), reviewRain = Color(0x59504960),
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
            btnFilled = Color(0xFFC8F04A), btnFilledPressed = Color(0xFFB0D33A), btnFilledText = Color(0xFF242135),
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

/**
 * 간격 단계(Design v2 디자인시스템 11/14, 4의 배수만). 가이드 px라 쓰는 쪽에서 `ChagokSpace.S24.gpx`.
 * 화면의 숫자를 이 이름으로 바꾸는 일은 화면 PR이 한다(이 상수만으로는 화면이 바뀌지 않음).
 */
object ChagokSpace {
    /** 글자 줄 사이. */
    const val S4 = 4
    /** 작은 묶음 안. */
    const val S8 = 8
    /** 칩 사이. */
    const val S12 = 12
    /** 버튼 사이 · 분할 창 덩어리 사이. */
    const val S16 = 16
    /** 덩어리 사이(가로 창 기본). */
    const val S24 = 24
    /** 카드 안쪽. */
    const val S28 = 28
    /** 큰 카드 안쪽. */
    const val S32 = 32
    /** 앱 안 여백 · 시트 가장자리. */
    const val S40 = 40
    /** 홈 인사 ↔ 내 차. */
    const val S64 = 64
}

/**
 * 모서리 단계(Design v2 디자인시스템 11/14). 가이드 px라 `RoundedCornerShape(ChagokRadius.R32.gpx)`.
 * 알약은 [PILL](50%) — 높이와 상관없이 양 끝이 반원.
 */
object ChagokRadius {
    /** 점선 아이콘 자리. */
    const val R12 = 12
    /** S 버튼 · 작은 아이콘 버튼. */
    const val R16 = 16
    /** 아이콘 버튼 80. */
    const val R20 = 20
    /** M 버튼 · 칩 · 검색칸 · 스테퍼. */
    const val R24 = 24
    /** L 버튼 · 카드 · 제안 카드 · 시트. */
    const val R32 = 32
    /** 앱 창 · 목적지 카드 바깥 · 미리보기. */
    const val R40 = 40
    /** 시스템 알림. */
    const val R48 = 48
    /** 알약(아티팩트 999px). */
    val PILL: Shape = RoundedCornerShape(percent = 50)
}

/**
 * 모션 곡선 · 누름 값(Design v2 디자인시스템 11/14, 아티팩트 CSS cubic-bezier 그대로).
 * transform·opacity만 움직이고, 움직임 줄이기면 끝 모습으로 바로 간다(각 화면의 reduce 분기).
 * 기존 private 곡선(CgParts `EaseRise` 등)은 PR-2·PR-9가 이 이름으로 옮긴다.
 */
object ChagokMotion {
    /** 돌아보기 나타남(아티팩트 --rv-out). */
    val Out = CubicBezierEasing(.23f, 1f, .32f, 1f)
    /** 돌아보기 오가기(아티팩트 --rv-io). */
    val InOut = CubicBezierEasing(.45f, 0f, .55f, 1f)
    /** 홈 카드 나타남 0.7초 · 알림 바 내려옴 0.42초 · 스위치. */
    val Rise = CubicBezierEasing(.32f, .72f, 0f, 1f)
    /** 시트 나타남 0.38초. */
    val Sheet = CubicBezierEasing(.2f, .7f, .2f, 1f)
    /** 팝(살짝 넘쳤다 돌아옴). */
    val Pop = CubicBezierEasing(.175f, .885f, .32f, 1.275f)
    /** 카드 · 버튼 눌림 크기. */
    const val PRESS_SCALE = .985f
    /** 눌림 면 색 전환 시간(ms). */
    const val PRESS_COLOR_MS = 120
}

/** 터치 영역 최소값(Pleos·Android 접근성 48dp). */
val MinTouch: Dp = 48.dp

/** Window Policy: 이 폭(px) 이하면 한 단으로 쌓는다(1:1 분할·세로 창, design/RULES.md 4절). */
const val NARROW_MAX_PX = 900
