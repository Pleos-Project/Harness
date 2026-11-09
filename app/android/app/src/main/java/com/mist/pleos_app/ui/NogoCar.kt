package com.mist.pleos_app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser

/*
 * 노고 카 v3.1(#118 Design v3 HANDOFF_v3 5-4, `design/components/nogo-car.html`): 현대 코나 일렉트릭 옆모습, 170 × 70 틀, 오른쪽을 봄.
 * 주간 = 딥 잉크 차체, 야간 = 라임 차체, 정체 장면의 다른 차 = other. 색은 tokens.css `--color-illust-*`(주간 · 야간 두 벌).
 * 전기차라 연기(배기 · 타이어)는 없다. 경로는 시안 SVG에서 그대로 옮겼다(손으로 고치지 말고 시안을 바꾼 뒤 다시 옮긴다).
 */

/** illust 토큰(tokens.css `--color-illust-*`, 주간 · 야간). */
internal data class IllustColors(
    val car: Color, val carShade: Color, val carHi: Color, val carLine: Color,
    val inkCar: Color, val inkCarShade: Color, val inkCarHi: Color, val inkCarLine: Color,
    val inkGlass: Color, val inkGlassTop: Color, val inkTrim: Color, val inkHead: Color,
    val glass: Color, val glassTop: Color, val glassHi: Color, val trim: Color, val head: Color, val well: Color,
    val tire: Color, val tireEdge: Color, val tireHi: Color,
    val rim: Color, val rimHi: Color, val rimShade: Color, val rimDark: Color, val cap: Color,
    val tail: Color, val tailHi: Color, val ground: Color,
    val other: Color, val otherShade: Color, val otherLine: Color, val otherGlass: Color,
)

internal val IllustDay = IllustColors(
    car = Color(0xFFD9F77A), carShade = Color(0xFFC2DF5C), carHi = Color(0xFFF3FEC9), carLine = Color(0xFFA6C246),
    inkCar = Color(0xFF242033), inkCarShade = Color(0xFF1B1828), inkCarHi = Color(0xFF4A445C), inkCarLine = Color(0xFF3A354A),
    inkGlass = Color(0xFF4A4560), inkGlassTop = Color(0xFF6B637B), inkTrim = Color(0xFF121019), inkHead = Color(0xFFD9F77A),
    glass = Color(0xFF2E2A3D), glassTop = Color(0xFF4A4560), glassHi = Color(0x59FFFFFF), trim = Color(0xFF242033), head = Color(0xFFFFFFFF), well = Color(0xFF16131F),
    tire = Color(0xFF242033), tireEdge = Color(0xFF242033), tireHi = Color(0xFF51495F),
    rim = Color(0xFFCBD2DB), rimHi = Color(0xFFEDF1F6), rimShade = Color(0xFF8994A3), rimDark = Color(0xFF3E4754), cap = Color(0xFFD9F77A),
    tail = Color(0xFFF78F92), tailHi = Color(0xFFFFADAD), ground = Color(0x29242033),
    other = Color(0xFFFFFFFF), otherShade = Color(0xFFE6E3EF), otherLine = Color(0xFFC9C3D6), otherGlass = Color(0xFFAFA8BD),
)

internal val IllustNight = IllustColors(
    car = Color(0xFFD9F77A), carShade = Color(0xFFBFD95F), carHi = Color(0xFFF3FEC9), carLine = Color(0xFF9CB743),
    inkCar = Color(0xFF242033), inkCarShade = Color(0xFF1B1828), inkCarHi = Color(0xFF4A445C), inkCarLine = Color(0xFF3A354A),
    inkGlass = Color(0xFF4A4560), inkGlassTop = Color(0xFF6B637B), inkTrim = Color(0xFF121019), inkHead = Color(0xFFD9F77A),
    glass = Color(0xFF2E2A3D), glassTop = Color(0xFF4A4560), glassHi = Color(0x59FFFFFF), trim = Color(0xFF242033), head = Color(0xFFFFFFFF), well = Color(0xFF0E0C14),
    tire = Color(0xFF16131F), tireEdge = Color(0xFF5C546B), tireHi = Color(0xFF5C546B),
    rim = Color(0xFFA5AFBC), rimHi = Color(0xFFEDF1F6), rimShade = Color(0xFF626D7D), rimDark = Color(0xFF3E4754), cap = Color(0xFFD9F77A),
    tail = Color(0xFFF78F92), tailHi = Color(0xFFFFADAD), ground = Color(0x66000000),
    other = Color(0xFF51495F), otherShade = Color(0xFF464055), otherLine = Color(0xFF3A354A), otherGlass = Color(0xFF312C40),
)

internal fun illustOf(dark: Boolean) = if (dark) IllustNight else IllustDay

/** 차 한 대의 색(시안 `--k-*`). [other]면 광택 · 크롬 · 주름 선을 그리지 않는다. */
internal class CarTone(
    val body: Color, val shade: Color, val hi: Color, val line: Color, val glass: Color, val glassTop: Color,
    val trim: Color, val head: Color, val cap: Color, val other: Boolean, val il: IllustColors,
)

/** 내 차: 주간 딥 잉크 · 야간 라임. */
internal fun myCarTone(il: IllustColors, dark: Boolean) = if (dark)
    CarTone(il.car, il.carShade, il.carHi, il.carLine, il.glass, il.glassTop, il.trim, il.head, il.cap, false, il)
else CarTone(il.inkCar, il.inkCarShade, il.inkCarHi, il.inkCarLine, il.inkGlass, il.inkGlassTop, il.inkTrim, il.inkHead, il.cap, false, il)

/** 정체 장면의 다른 차. */
internal fun otherCarTone(il: IllustColors) =
    CarTone(il.other, il.otherShade, il.other, il.otherLine, il.otherGlass, il.otherGlass, il.otherLine, il.other, il.rim, true, il)

internal class NogoCarPaths {
    private fun p(d: String) = PathParser().parsePathString(d).toPath()
    val body = p(NK_BODY); val wells = p(NK_WELLS); val shade = p(NK_SHADE); val shoulder = p(NK_SHOULDER); val roof = p(NK_ROOF)
    val glass = p(NK_GLASS); val glare = p(NK_GLARE); val chrome = p(NK_CHROME); val crease = p(NK_CREASE); val creaseShade = p(NK_CREASE_SHADE)
    val door = p(NK_DOOR); val trim = p(NK_TRIM); val tail = p(NK_TAIL); val tailBar = p(NK_TAIL_BAR); val head = p(NK_HEAD)
    val lightBar = p(NK_LIGHT_BAR); val mirror = p(NK_MIRROR); val tireHi = p(NK_TIRE_HI); val spokes = p(NK_SPOKES)
}

/** 뒤 · 앞 바퀴: 타이어 중심 x와 휠 중심 x(시안은 휠을 0.6 앞으로). 앞바퀴 경로 = 뒷바퀴 + 108.3. */
private const val WHEEL_Y = 52.5f
private val TIRE_X = floatArrayOf(30.6f, 138.9f)
private const val FRONT_DX = 108.3f

/** 노고 카 그리기: topLeft에서 폭 w(높이 = w × 70/170), 오른쪽을 봄. wheel = 휠 회전(도), alpha = 전체 투명도. */
internal fun DrawScope.drawNogoCar(p: NogoCarPaths, topLeft: Offset, w: Float, t: CarTone, wheel: Float, alpha: Float = 1f) {
    val il = t.il
    fun Color.a(k: Float = 1f) = copy(alpha = this.alpha * alpha * k)
    val k = w / 170f
    translate(topLeft.x, topLeft.y) {
        scale(k, k, Offset.Zero) {
            // 바닥 그림자 · 바퀴 집
            drawOval(Brush.radialGradient(listOf(il.ground.a(), il.ground.a(0f)), Offset(85f, 68.2f), 84f), Offset(1f, 65.6f), Size(168f, 5.2f))
            drawPath(p.wells, il.well.a())
            // 차체: 위 → 아래 하이라이트 · 기본 · 그늘
            drawPath(p.body, Brush.verticalGradient(0f to t.hi.a(), .26f to t.body.a(), .55f to t.body.a(), .8f to t.shade.a(), 1f to t.shade.a(), startY = 4.2f, endY = 55f))
            clipPath(p.body) {
                drawPath(p.shade, t.shade.a(.55f))
                if (!t.other) drawPath(p.shoulder, t.hi.a(.9f), style = Stroke(1f, cap = StrokeCap.Round))
                drawPath(p.roof, t.trim.a())
                drawPath(p.glass, Brush.linearGradient(listOf(t.glassTop.a(), t.glass.a()), Offset(36f, 8.3f), Offset(36f + .35f * 75.8f, 22.9f)))
                if (!t.other) {
                    drawPath(p.glare, il.glassHi.a())
                    drawPath(p.chrome, il.rim.a(), style = Stroke(1.1f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawPath(p.crease, t.hi.a(.85f), style = Stroke(.9f, cap = StrokeCap.Round))
                    drawPath(p.creaseShade, t.shade.a(.9f), style = Stroke(.9f, cap = StrokeCap.Round))
                }
                drawPath(p.door, t.line.a(), style = Stroke(.7f, cap = StrokeCap.Round))
                drawRoundRect(t.line.a(), Offset(41.2f, 25.1f), Size(8f, 1.4f), androidx.compose.ui.geometry.CornerRadius(.7f))
                drawRoundRect(t.line.a(), Offset(84.2f, 25.7f), Size(8f, 1.4f), androidx.compose.ui.geometry.CornerRadius(.7f))
                drawPath(p.trim, t.trim.a())
                drawPath(p.tail, Brush.horizontalGradient(listOf(il.tail.a(), il.tailHi.a()), startX = 1.6f, endX = 12.8f))
                drawPath(p.tailBar, il.tail.a(), style = Stroke(1.1f, cap = StrokeCap.Round))
                drawPath(p.head, t.trim.a())
                drawPath(p.lightBar, t.head.a(), style = Stroke(1.2f, cap = StrokeCap.Round))
                drawPath(p.mirror, t.trim.a())
            }
            // 바퀴: 타이어 · 휠(Satin Silver 투톤, 돌아감)
            TIRE_X.forEachIndexed { i, tx ->
                val dx = i * FRONT_DX
                val c = Offset(tx, WHEEL_Y)
                val hub = Offset(tx + .6f, WHEEL_Y)
                drawCircle(il.tire.a(), 15.6f, c)
                drawCircle(il.tireEdge.a(), 15.6f, c, style = Stroke(.8f))
                drawCircle(il.tireHi.a(.7f), 13.4f, c, style = Stroke(.6f))
                translate(dx, 0f) { drawPath(p.tireHi, il.tireHi.a(), style = Stroke(1.3f, cap = StrokeCap.Round)) }
                rotate(wheel, hub) {
                    val rim = Brush.linearGradient(
                        0f to il.rimHi.a(), .55f to il.rim.a(), 1f to il.rimShade.a(),
                        start = Offset(hub.x - 11.2f, hub.y - 11.2f), end = Offset(hub.x - 11.2f + .6f * 22.4f, hub.y + 11.2f),
                    )
                    drawCircle(il.rimDark.a(), 11.2f, hub)
                    drawCircle(rim, 11.2f, hub)
                    drawCircle(il.rimDark.a(), 9.6f, hub)
                    translate(dx, 0f) { drawPath(p.spokes, Brush.linearGradient(
                        0f to il.rimHi.a(), .55f to il.rim.a(), 1f to il.rimShade.a(),
                        start = Offset(hub.x - dx - 11.2f, hub.y - 11.2f), end = Offset(hub.x - dx - 11.2f + .6f * 22.4f, hub.y + 11.2f),
                    )) }
                    drawCircle(il.rimShade.a(), 3.4f, hub)
                    drawCircle(t.cap.a(), 1.9f, hub)
                }
            }
        }
    }
}

// 시안 SVG 경로(design/components/nogo-car.html, viewBox 0 0 170 70)
private const val NK_BODY = "M3.2 55 L12.3 55 A18.5 18.5 0 1 1 48.9 55 L120.6 55 A18.5 18.5 0 1 1 157.2 55 L164.8 55 C167.4 55 168.6 53.6 168.7 51.2 L169.2 44 C169.5 39.5 169.6 35.5 169.2 33.2 C168.8 30.9 167.4 29.8 165.2 29.1 C160 27.4 152 25.6 143 24.4 C135 23.4 127 22.9 121.4 22.5 C112 17.2 101 10.9 95.2 7.9 C92.6 6.5 90.3 5.3 87.2 5.1 C79 4.6 70 4.2 62 4.2 C53 4.2 43.5 4.7 36.4 5.3 C32.6 5.7 30.4 6.6 28.6 7.7 C24 8.4 19.8 9.1 17 9.7 C11.4 11 8 14.6 5.8 20.2 C4.2 24.8 3 29.6 2.2 33.6 C1.3 38.4 0.8 43 0.9 47 C1 50.8 1.3 53 2 54.2 C2.3 54.8 2.7 55 3.2 55 Z"
private const val NK_WELLS = "M12.2 55 A18.6 18.6 0 1 1 49 55 Z M120.5 55 A18.6 18.6 0 1 1 157.3 55 Z"
private const val NK_SHADE = "M0 44.5 C40 47.6 120 47.4 170 43.2 L170 70 L0 70 Z"
private const val NK_SHOULDER = "M4 29.6 C40 27.4 110 27.6 166 31.6"
private const val NK_ROOF = "M5.6 21 C9.6 16 13.6 12.2 17.4 10.4 L37.9 21.3 Z M37.9 21.2 L29.6 8.6 C31.4 7.2 33.6 6.6 36.2 6.5 C52 5.8 72 5.8 87.2 6.3 C90 6.4 92 7.2 93.8 8.4 L117.6 23.9 Z M28 7.9 C30.4 6.4 32.8 5.6 36.4 5.3 C46 4.5 70 3.9 87.2 5.1 C90.6 5.3 92.8 6.4 95.4 8 L93.8 8.4 C92 7.2 90 6.4 87.2 6.3 C72 5.8 52 5.8 36.2 6.5 C33.6 6.6 31.4 7.2 29.6 8.6 Z"
private const val NK_GLASS = "M41.8 20.9 L36 9.6 C36.4 9 37.2 8.7 38.4 8.6 L71.6 8.3 L71.6 21.6 Z M77.4 8.3 L87.4 8.3 C89.4 8.4 90.8 8.9 92.2 9.9 L111.8 22.9 L77.4 21.8 Z"
private const val NK_GLARE = "M47.6 20.4 L54.4 9.2 L58.4 9.2 L51.6 20.6 Z M84.6 21.2 L90.4 10.4 L92.2 11.2 L87.2 21.3 Z"
private const val NK_CHROME = "M17.4 10.3 L37.9 21.4 L117.6 24.1"
private const val NK_CREASE = "M37 30.4 C64 35.6 92 41.4 118.4 47.6 M96.4 28.6 C104 31.4 112 34.4 119.6 37.6"
private const val NK_CREASE_SHADE = "M37.4 31.6 C64 36.8 92 42.6 118.2 48.8"
private const val NK_DOOR = "M37.6 22.2 C36.6 27.6 37.4 32 40.6 35.6 M74.6 22.6 L74.6 49.6 M118.6 24.8 C119.6 31.6 120.4 38.6 120.6 45.6"
private const val NK_TRIM = "M0 49.4 L13.2 49.6 C14 51.4 14.4 53.2 14.4 55 L0 55 Z M156 55 C156 53.2 156.4 51.4 157.2 49.6 L170 49.2 L170 55 Z M48.4 55 C48.4 53.2 48.6 51.6 49.2 50.2 L120.2 50.2 C120.8 51.6 121 53.2 121 55 Z"
private const val NK_TAIL = "M1.6 31.8 L12.8 31.9 C11.6 35 10.4 38 9.2 41 L3.6 40.6 C2.6 37.6 2 34.6 1.6 31.8 Z"
private const val NK_TAIL_BAR = "M3.4 25.9 C6.8 26 10.4 26.3 13.6 26.7"
private const val NK_HEAD = "M160.2 36.6 C163.4 36.8 166.6 37.1 170 37.5 L170 44.8 C167.4 44.4 165 43.8 162.8 43 C161.8 41 161 38.8 160.2 36.6 Z"
private const val NK_LIGHT_BAR = "M150.6 29.2 C157 29.8 163.4 30.6 170 31.6 M162.8 39.3 L168.4 39.9"
private const val NK_MIRROR = "M104.6 19.8 C104.8 18.6 106 17.9 107.8 17.9 L112.6 18.2 C114.6 18.5 115.4 19.6 114.8 21.4 L113.8 24 L106.4 23.5 C105.2 22.6 104.5 21.3 104.6 19.8 Z"
private const val NK_TIRE_HI = "M19.400 45.3 A13.4 13.4 0 0 1 29.200 39.2"
private const val NK_SPOKES = "M32.20 49.20 L33.80 42.20 L28.60 42.20 L30.20 49.20 Z M34.65 52.43 L41.80 51.79 L40.19 46.84 L34.03 50.53 Z M32.33 55.76 L35.15 62.36 L39.36 59.30 L33.95 54.58 Z M28.45 54.58 L23.04 59.30 L27.25 62.36 L30.07 55.76 Z M28.37 50.53 L22.21 46.84 L20.60 51.79 L27.75 52.43 Z"
