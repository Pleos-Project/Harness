package com.mist.pleos_app.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb

/*
 * 그림자 Level 1~3(Design v2 디자인시스템 11/14, tokens.css --cg-shadow-1~3 · 아티팩트 --sh1~3).
 *
 * Compose `Modifier.shadow`는 높이(elevation) 하나라 CSS의 y · blur · 투명도를 그대로 옮기지 못한다.
 * 그래서 `drawWithCache`의 onDrawBehind 안에서 `android.graphics.Paint.setShadowLayer`로 CSS 값을 그대로 그린다
 * (minSdk 28 — 하드웨어 가속 캔버스가 모든 도형의 shadow layer를 지원하는 API 28 이상이라 근사 없이 된다).
 *
 * - 크기 단위: CSS 값은 가이드 px = 디스플레이 실제 픽셀이고, DrawScope도 px라 바꾸지 않고 쓴다.
 * - blur: CSS blur 반지름 b는 가우시안 σ = b/2. Skia는 shadow 반지름 r을 σ = 0.57735·r + 0.5로 바꾸므로
 *   r = (b/2 − 0.5) / 0.57735 로 넘긴다.
 * - 도형 면은 투명으로 그리고 그림자만 남긴다. CSS box-shadow와 달리 그림자가 도형 아래까지 깔리므로
 *   반투명 면 위에는 쓰지 않는다(카드 · 바 · 시트처럼 불투명한 면 전용).
 * - 주간 · 야간 값은 [Chagok.colors] `isDark`로 고른다.
 *
 * 어디에 붙일지(카드 · 검색 한 줄 · 스테퍼 · 프린터 슬롯 = 1, 인원 알림 바 = 2, 하단 시트 = 3)는 공용 부품 PR(PR-2)이 정한다.
 * 이 파일만으로는 화면이 바뀌지 않는다.
 */

/** CSS box-shadow 한 겹: y 오프셋 · blur(가이드 px) · 색. x 오프셋과 spread는 Level 1~3 모두 0이라 두지 않는다. */
private data class CgShadowLayer(val y: Float, val blur: Float, val color: Color)

// Light / Dark 순서. 값 = design/tokens.css --cg-shadow-1~3(야간 = :root, 주간 = prefers-color-scheme: light)
private val LevelLight = listOf(
    listOf(CgShadowLayer(6f, 24f, Color(0x14000000))),
    listOf(CgShadowLayer(2f, 2f, Color(0x14000000)), CgShadowLayer(8f, 32f, Color(0x29000000))),
    listOf(CgShadowLayer(2f, 4f, Color(0x33000000)), CgShadowLayer(20f, 40f, Color(0x3D000000))),
)
private val LevelDark = listOf(
    listOf(CgShadowLayer(6f, 24f, Color(0x52000000))),
    listOf(CgShadowLayer(2f, 2f, Color(0x52000000)), CgShadowLayer(8f, 32f, Color(0x66000000))),
    listOf(CgShadowLayer(2f, 4f, Color(0x66000000)), CgShadowLayer(20f, 40f, Color(0x7A000000))),
)

/**
 * 디자인 그림자 Level [level](1~3)을 [shape] 모양으로 뒤에 그린다. 면 · 모서리 자르기는 그대로 따로 둔다:
 * `Modifier.cgElevation(1, shape).clip(shape).background(...)`.
 */
fun Modifier.cgElevation(level: Int, shape: Shape): Modifier {
    require(level in 1..3) { "그림자 Level은 1~3: $level" }
    return composed {
        val layers = (if (Chagok.colors.isDark) LevelDark else LevelLight)[level - 1]
        drawWithCache {
            // 크기 · 모양 · 주야가 바뀔 때만 Paint · Path를 다시 만든다(매 프레임 할당 없음)
            val outline = shape.createOutline(size, layoutDirection, this)
            val path = when (outline) {
                is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
                is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
                is Outline.Generic -> outline.path
            }.asAndroidPath()
            val paints = layers.map { it.toPaint() }
            onDrawBehind { drawIntoCanvas { canvas -> paints.forEach { canvas.nativeCanvas.drawPath(path, it) } } }
        }
    }
}

private fun CgShadowLayer.toPaint(): android.graphics.Paint {
    val sigma = blur / 2f
    val radius = ((sigma - 0.5f) / 0.57735f).coerceAtLeast(0.01f)   // 0이면 shadow layer가 지워진다
    return android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.TRANSPARENT
        setShadowLayer(radius, 0f, this@toPaint.y, this@toPaint.color.toArgb())
    }
}
