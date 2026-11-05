package com.mist.pleos_app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit

/*
 * 차곡 Compose 테마 — design/tokens.css 값을 옮긴 임시본.
 * 토큰을 어디서 관리할지(tokens.css ↔ Compose 테마)는 design/RULES.md 개정(11/7, 열린 결정 12)에서 정한다.
 * 그 전까지 색을 바꾸면 tokens.css와 같이 바꾼다.
 */

private val Dark = darkColorScheme(
    background = Color(0xFF1D1E21),      // --cg-bg-primary
    surface = Color(0xFF1D1E21),         // --cg-surface-basic
    surfaceVariant = Color(0xFF313236),  // --cg-surface-high
    onBackground = Color(0xFFFFFFFF),    // --cg-text-primary
    onSurface = Color(0xFFFFFFFF),
    primary = Color(0xFFE0E1E6),         // --cg-btn-filled
    onPrimary = Color(0xFF131417),       // --cg-btn-filled-text
    error = Color(0xFFFF4C28),           // --cg-negative
)

private val Light = lightColorScheme(
    background = Color(0xFFF7F8FA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEDEEF2),
    onBackground = Color(0xFF131417),
    onSurface = Color(0xFF131417),
    primary = Color(0xFF44464E),
    onPrimary = Color(0xFFFFFFFF),
    error = Color(0xFFFE3D16),
)

/** 주간/야간: 기본 야간, 시스템이 주간이면 주간(tokens.css의 prefers-color-scheme와 같은 규칙). */
@Composable
fun ChagokTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}

/*
 * 크기 단위: Pleos 디자인 가이드 크기는 "디스플레이 실제 픽셀"이다(tokens.css의 --u).
 * Compose에서는 가이드 값을 px로 보고 dp·sp로 바꾼다. 터치 영역 48처럼 dp 기준 값은 그냥 48.dp.
 */
val Int.gpx: Dp
    @Composable @ReadOnlyComposable get() = with(LocalDensity.current) { this@gpx.toDp() }

val Int.gsp: TextUnit
    @Composable @ReadOnlyComposable get() = with(LocalDensity.current) { this@gsp.toSp() }
