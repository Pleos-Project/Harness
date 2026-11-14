package com.mist.pleos_app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.mist.pleos_app.effort.BgKind
import com.mist.pleos_app.effort.BgPalette
import com.mist.pleos_app.effort.BgRng
import com.mist.pleos_app.effort.BgTod
import com.mist.pleos_app.effort.BgWx
import com.mist.pleos_app.effort.PreviewBgSpec
import com.mist.pleos_app.effort.bgHasBeach
import com.mist.pleos_app.effort.bgHasWater
import com.mist.pleos_app.effort.bgHash
import com.mist.pleos_app.effort.bgMix
import com.mist.pleos_app.effort.bgPalette
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/*
 * 출발 미리보기 코드 배경 그리기(F20, #182 `design/components/preview-bg.js`의 scene · precip, HANDOFF_v4 7-4).
 * JS와 같은 순서 · 같은 숫자 · 같은 씨앗으로 그려 시안과 같은 그림이 나온다. 난수를 부르는 순서가 그림을 정하므로
 * JS의 `r()` 호출 순서(인자 왼쪽 → 오른쪽, 조건이 짧게 끊기는 곳 포함)를 그대로 지킨다.
 * 크기 단위 u = 높이 ÷ 478. 가이드 px = 기기 px(`gpx`)라 JS의 CSS px 값(별 · 빗줄기 개수, `+2` 등)을 그대로 px로 쓴다.
 * 중립은 '길' 하나만 옮긴다(빛 번짐 · 경로선은 시안에서 빠짐).
 */

/**
 * 배경 칸. 목적지(spec) · 크기 · 배치가 바뀔 때 한 번 비트맵으로 그려 두고, 비 · 눈만 보이는 동안 매 프레임 그 위에 다시 그린다.
 * 앱이 가려지면(STARTED 아래) · 움직임 줄이기면 멈춘다(AC-C-2). 멈춰도 t = 0 장면의 빗줄기 · 눈송이는 남는다(시안 f(0)).
 */
@Composable
fun CgPreviewBg(spec: PreviewBgSpec, land: Boolean, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    val t = remember(spec) { mutableFloatStateOf(0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by lifecycle.currentStateAsState()
    val run = spec.animated && !reduceMotion && state.isAtLeast(Lifecycle.State.STARTED)
    if (run) LaunchedEffect(spec) {
        val t0 = withFrameNanos { it } - (t.floatValue * 1e9).toLong()   // 다시 보이면 멈춘 자리부터
        while (true) withFrameNanos { t.floatValue = ((it - t0) / 1e9).toFloat() }
    }
    Box(
        modifier.drawWithCache {
            val w = size.width.toInt().coerceAtLeast(1)
            val h = size.height.toInt().coerceAtLeast(1)
            val base = ImageBitmap(w, h)
            CanvasDrawScope().draw(this, layoutDirection, Canvas(base), Size(w.toFloat(), h.toFloat())) {
                PreviewBgPainter(this, w.toDouble(), h.toDouble()).scene(spec, land)
            }
            val seed = bgHash(spec.name) + 7
            onDrawBehind {
                drawImage(base)
                if (spec.animated) PreviewBgPainter(this, w.toDouble(), h.toDouble()).precip(spec.wx, t.floatValue.toDouble(), seed)
            }
        },
    )
}

/** JS 2D 캔버스 호출을 DrawScope로 옮긴 것. 계산은 Double(JS Number), 그릴 때만 Float. */
private class PreviewBgPainter(val ds: DrawScope, val W: Double, val H: Double) {
    private val Double.f get() = toFloat()
    private fun col(rgb: Int, a: Double = 1.0) = Color((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, (a.coerceIn(0.0, 1.0) * 255 + .5).toInt())
    private fun hex(s: String) = s.removePrefix("#").toInt(16)
    private fun jsRound(v: Double) = floor(v + .5).toInt()

    private fun fillRect(x: Double, y: Double, w: Double, h: Double, c: Color) {
        if (w == 0.0 || h == 0.0) return
        val l = minOf(x, x + w); val tp = minOf(y, y + h)
        ds.drawRect(c, Offset(l.f, tp.f), Size(kotlin.math.abs(w).f, kotlin.math.abs(h).f))
    }
    private fun circle(x: Double, y: Double, r: Double, c: Color) = ds.drawCircle(c, r.f, Offset(x.f, y.f))
    private fun ellipse(x: Double, y: Double, rx: Double, ry: Double, c: Color) = ds.drawOval(c, Offset((x - rx).f, (y - ry).f), Size((2 * rx).f, (2 * ry).f))
    private fun line(x0: Double, y0: Double, x1: Double, y1: Double, c: Color, w: Double) = ds.drawLine(c, Offset(x0.f, y0.f), Offset(x1.f, y1.f), w.f)
    private fun path(b: Path.() -> Unit) = Path().apply(b)
    private fun Path.m(x: Double, y: Double) = moveTo(x.f, y.f)
    private fun Path.l(x: Double, y: Double) = lineTo(x.f, y.f)
    private fun Path.q(cx: Double, cy: Double, x: Double, y: Double) = quadraticTo(cx.f, cy.f, x.f, y.f)
    private fun Path.c(c1x: Double, c1y: Double, c2x: Double, c2y: Double, x: Double, y: Double) = cubicTo(c1x.f, c1y.f, c2x.f, c2y.f, x.f, y.f)
    private fun fill(p: Path, c: Color) = ds.drawPath(p, c)
    private fun stroke(p: Path, c: Color, w: Double) = ds.drawPath(p, c, style = Stroke(w.f))
    /** 세로 그라데이션으로 칸 채우기(JS createLinearGradient(0, y0, 0, y1) + fillRect). */
    private fun vgrad(x: Double, y: Double, w: Double, h: Double, y0: Double, y1: Double, vararg stops: Pair<Float, Color>) =
        ds.drawRect(Brush.verticalGradient(*stops, startY = y0.f, endY = y1.f), Offset(x.f, y.f), Size(w.f, h.f))

    private fun glow(x: Double, y: Double, r: Double, c: Int, a: Double) {
        if (r <= 0) return
        ds.drawCircle(Brush.radialGradient(listOf(col(c, a), col(c, 0.0)), Offset(x.f, y.f), r.f), r.f, Offset(x.f, y.f))
    }

    private class O(
        val P: BgPalette, val r: BgRng, val u: Double, val g: Double, val hor: Double, val x0: Double, val tod: BgTod, val wx: BgWx,
        val sunX: Double, val name: String, val water: Boolean, val beach: Boolean, val land: Boolean,
    )

    fun scene(st: PreviewBgSpec, land: Boolean) {
        val P = bgPalette(st.tod, st.wx)
        val r = BgRng(bgHash(st.name + "|" + st.kind.id))
        val u = H / 478
        val x0 = if (land) W * .26 else 0.0
        val sea = st.kind == BgKind.SEA
        val hor = if (sea) (if (land) H * .64 else H * .44) else (if (land) H * .5 else H * .3)
        val g = if (land) H * .88 else H * .58
        val sunX = if (land) W * (.5 + r() * .16) else W * (.6 + r() * .25)   // 가로: 글자와 [안내 시작] 사이
        val sunY = when (st.tod) { BgTod.DAY -> hor * (.32 + r() * .18); BgTod.DUSK -> hor - 18 * u; BgTod.NIGHT -> hor * (.25 + r() * .2) }
        val neu = st.kind == BgKind.NEUTRAL
        val nhor = if (land) H * .56 else H * .42   // 중립 = 길
        sky(P, if (neu) nhor else if (sea) hor else g)
        stars(if (neu) nhor else hor, P, r, u)
        sun(if (neu) (if (land) W * .6 else W * .5) else sunX, if (neu) nhor - (when (st.tod) { BgTod.DAY -> 90; BgTod.DUSK -> 14; BgTod.NIGHT -> 110 }) * u else sunY, u, P, st.tod, st.wx)
        clouds(if (neu) nhor else hor, P, r, u, st.wx, st.tod)
        val o = O(P, r, u, g, if (neu) nhor else hor, x0, st.tod, st.wx, sunX, st.name, bgHasWater(st.name), bgHasBeach(st.name), land)
        when (st.kind) {
            BgKind.NEUTRAL -> neutralRoad(o)
            BgKind.CITY -> city(o)
            BgKind.HOME -> apartments(o)
            BgKind.SEA -> sea(o)
            BgKind.NATURE -> nature(o)
        }
        if (st.wx == BgWx.RAIN || st.wx == BgWx.CLOUD) fillRect(0.0, 0.0, W, H, col(hex("#20203A"), if (st.wx == BgWx.RAIN) .14 else .06))
    }

    private fun sky(P: BgPalette, hor: Double) = vgrad(0.0, 0.0, W, H, 0.0, hor, 0f to col(P.sky[0]), .58f to col(P.sky[1]), 1f to col(P.sky[2]))

    private fun stars(hor: Double, P: BgPalette, r: BgRng, u: Double) {
        if (P.star == 0.0) return
        val n = jsRound(W * hor / 2600)
        repeat(n) {
            val x = r() * W; val y = r() * hor * .8
            val c = Color.White.copy(alpha = ((.25 + r() * .6) * P.star).coerceIn(0.0, 1.0).f)
            val s = (if (r() < .08) 2.2 else 1.1) * u
            fillRect(x, y, s, s, c)
        }
    }

    private fun clouds(hor: Double, P: BgPalette, r: BgRng, u: Double, wx: BgWx, tod: BgTod) {
        val n = when (wx) { BgWx.CLEAR -> if (tod == BgTod.DAY) 3 else 0; BgWx.CLOUD -> 9; else -> 12 }
        if (n == 0) return
        val c = hex(if (tod == BgTod.DAY) (if (wx == BgWx.CLEAR) "#FFFFFF" else "#E9ECF2") else if (tod == BgTod.DUSK) "#C9A3B6" else "#4A4766")
        repeat(n) {
            val cx = r() * W; val cy = hor * (.12 + r() * .55); val w = (90 + r() * 170) * u
            for (j in 0 until 5) {
                val y = cy + (r() - .5) * 18 * u
                val rad = w * (.32 + r() * .18)
                glow(cx + (j - 2) * w * .28, y, rad, c, if (wx == BgWx.CLEAR) .5 else .62)
            }
        }
    }

    private fun sun(x: Double, y: Double, u: Double, P: BgPalette, tod: BgTod, wx: BgWx) {
        if (wx == BgWx.RAIN || wx == BgWx.CLOUD) { glow(x, y, 120 * u, P.sun, if (tod == BgTod.NIGHT) .08 else .12); return }
        if (wx == BgWx.SNOW && tod != BgTod.NIGHT) { glow(x, y, 110 * u, P.sun, .18); return }
        glow(x, y, (if (tod == BgTod.NIGHT) 110 else 150) * u, P.sun, if (tod == BgTod.DAY) .45 else .35)
        circle(x, y, (if (tod == BgTod.NIGHT) 20 else 30) * u, col(P.sun))
    }

    private fun windows(x: Double, y: Double, w: Double, h: Double, u: Double, P: BgPalette, r: BgRng, lit: Double, dayRef: Boolean, apt: Boolean) {
        val cw = if (apt) 13 * u else 10 * u
        val rh = if (apt) 15 * u else 12 * u
        val ww = if (apt) 6 * u else 4 * u
        val wh = if (apt) 6 * u else 5 * u
        var wy = y + 9 * u
        while (wy < y + h - 8 * u) {
            var wx = x + 6 * u
            while (wx < x + w - 6 * u) {
                if (P.lit > 0) {
                    if (r() < lit) {
                        val c = if (r() < .86) P.win else hex("#C8F04A")
                        fillRect(wx, wy, ww, wh, col(c, .45 + r() * .5))
                    }
                } else if (dayRef && r() < .55) fillRect(wx, wy, ww, wh, col(P.trim, .55))
                wx += cw
            }
            wy += rh
        }
    }

    private fun road(o: O) {
        val P = o.P; val r = o.r; val u = o.u; val g = o.g
        fillRect(0.0, g, W, H - g, col(P.road))
        if (o.tod == BgTod.DAY) {
            var x = o.x0
            while (x < W) { fillRect(x, g + (H - g) * .55, 26 * u, 3 * u, col(0xFFFFFF, .35)); x += 60 * u }
            return
        }
        repeat(70) {
            val x = o.x0 + r() * (W - o.x0); val y = g + 3 * u + r() * (H - g - 6 * u)
            val rad = (4 + r() * 9) * u
            glow(x, y, rad, hex(if (r() < .5) "#FF5E45" else "#FFE3AA"), if (o.tod == BgTod.NIGHT) .85 else .6)
        }
        stroke(path { m(o.x0, H); q((o.x0 + W) / 2, g + (H - g) * .2, W, g + (H - g) * .35) }, col(hex("#FF8A5C"), .55), 2.5 * u)
    }

    private fun city(o: O) {
        val P = o.P; val r = o.r; val u = o.u; val g = o.g; val x0 = o.x0
        val layers = listOf(Triple(P.far, .34 to .66, .12), Triple(P.mid, .22 to .62, .26), Triple(P.near, .14 to .44, .36))
        val towerX = x0 + (W - x0) * (.35 + r() * .5)
        val hasTower = r() < .6
        layers.forEachIndexed { li, (cRgb, ab, lit) ->
            val (a, b) = ab
            val c = col(cRgb)
            var x = x0 - 40 * u + li * 14 * u
            while (x < W + 20 * u) {
                val w = (36 + r() * 84) * u
                val h = (a + r() * (b - a)) * g
                fillRect(x, g - h, w, h + 2, c)
                if (r() < .25) fillRect(x + w * .3, g - h - 14 * u, w * .4, 14 * u, c)
                if (r() < .18) fillRect(x + w * .5 - 1.5 * u, g - h - 34 * u, 3 * u, 34 * u, c)
                if (li > 0) windows(x, g - h, w, h, u, P, r, lit * (if (P.lit > 0) 1 else 0), li == 2, apt = false)
                x += w + (2 + r() * 8) * u
            }
            if (li == 1 && hasTower) {
                val th = g * .86
                fillRect(towerX - 4 * u, g - th, 8 * u, th, col(P.mid))
                ellipse(towerX, g - th * .78, 20 * u, 9 * u, col(P.mid))
                if (P.lit > 0) glow(towerX, g - th, 14 * u, hex("#FF6A55"), .9)
            }
        }
        if (P.lit > 0) glow((x0 + W) / 2, g, (W - x0) * .6, hex(if (o.tod == BgTod.NIGHT) "#FFB872" else "#FF9F6E"), if (o.tod == BgTod.NIGHT) .16 else .1)
        road(o)
    }

    private fun apartments(o: O) {
        val P = o.P; val r = o.r; val u = o.u; val g = o.g; val x0 = o.x0
        listOf(Triple(P.far, .46 to .62, .0), Triple(P.mid, .38 to .56, .5)).forEachIndexed { li, (cRgb, ab, lit) ->
            val (a, b) = ab
            val c = col(cRgb)
            var x = x0 - 20 * u + li * 36 * u
            while (x < W) {
                val w = (84 + r() * 40) * u
                val h = (a + r() * (b - a)) * g
                fillRect(x, g - h, w, h + 2, c)
                fillRect(x + w * .38, g - h - 12 * u, w * .24, 12 * u, c)
                if (li == 1) windows(x, g - h, w, h, u, P, r, lit * (if (P.lit > 0) 1.2 else 0.0), true, apt = true)
                x += w + (26 + r() * 30) * u
            }
        }
        fillRect(0.0, g - 10 * u, W, H - g + 10 * u, col(P.near))
        var x = x0 - 30 * u
        while (x < W + 30 * u) {
            val rr = (16 + r() * 16) * u
            circle(x, g - rr * .6, rr, col(P.tree))
            x += (26 + r() * 20) * u
        }
        if (P.lit > 0) {
            var lx = x0 + 40 * u
            while (lx < W) { glow(lx, g - 4 * u, 26 * u, hex("#FFD49A"), .55); fillRect(lx - 2 * u, g - 6 * u, 4 * u, 4 * u, col(hex("#FFE7BF"))); lx += 140 * u }
        }
        fillRect(0.0, g + 10 * u, W, H - g, col(P.road))
    }

    private fun sea(o: O) {
        val P = o.P; val r = o.r; val u = o.u; val hor = o.hor; val sunX = o.sunX
        vgrad(0.0, hor, W, H - hor, hor, H, 0f to col(P.water[0]), 1f to col(P.water[1]))
        if (r() < .75) {
            val ix = sunX - (80 + r() * 260) * u; val iw = (120 + r() * 160) * u; val ih = (22 + r() * 36) * u
            fill(path { m(ix - iw / 2, hor); q(ix - iw * .15, hor - ih * 1.3, ix + iw * .1, hor - ih * .6); q(ix + iw * .3, hor - ih * 1.1, ix + iw / 2, hor); close() }, col(bgMix(P.water[1], P.sky[0], .4)))
        }
        if (o.wx == BgWx.CLEAR || o.tod == BgTod.NIGHT && o.wx != BgWx.RAIN) {
            for (i in 0 until 30) {
                val y = hor + 4 * u + i * 3.6 * u
                val w = (50 + r() * 150 - i * 2.5) * u
                if (w < 4) continue
                val a = max(0.0, .6 - i * .019) * (if (o.wx == BgWx.CLEAR) 1.0 else .4)
                fillRect(sunX - w / 2 + (r() - .5) * 24 * u, y, w, 2 * u, col(P.sun, a))
            }
        }
        val wave = col(0xFFFFFF, if (o.tod == BgTod.DAY) .35 else .12)
        repeat(26) {
            val y = hor + (H - hor) * (.15 + r() * .8); val x = r() * W; val w = (20 + r() * 60) * u
            stroke(path { m(x, y); q(x + w / 2, y - 3 * u, x + w, y) }, wave, 1.5 * u)
        }
        if (o.beach) {
            val sy = hor + (H - hor) * .62
            fill(path { m(0.0, H); l(0.0, sy + 12 * u); c(W * .3, sy - 10 * u, W * .6, sy + 18 * u, W, sy - 4 * u); l(W, H); close() }, col(P.sand))
            stroke(path { m(0.0, sy + 12 * u); c(W * .3, sy - 10 * u, W * .6, sy + 18 * u, W, sy - 4 * u) }, col(0xFFFFFF, if (o.tod == BgTod.DAY) .6 else .2), 2 * u)
        }
    }

    private fun ridge(base: Double, amp: Double, c: Int, r: BgRng, u: Double) {
        val p1 = r() * 6; val p2 = r() * 6; val f1 = (90 + r() * 80) * u; val f2 = (34 + r() * 22) * u
        fill(path {
            m(0.0, H)
            var x = 0.0
            while (x <= W + 10) { l(x, base + sin(x / f1 + p1) * amp + sin(x / f2 + p2) * amp * .32); x += 8 * u }
            l(W, H); close()
        }, col(c))
    }

    private fun nature(o: O) {
        val P = o.P; val r = o.r; val u = o.u; val g = o.g; val hor = o.hor
        val span = g - hor
        ridge(hor + span * .05, 26 * u, P.ridge[0], r, u)
        ridge(hor + span * .28, 22 * u, P.ridge[1], r, u)
        if (o.water) {
            val wy = hor + span * .62
            vgrad(0.0, wy, W, H - wy, wy, H, 0f to col(bgMix(P.sky[2], P.water[0], .4)), 1f to col(P.water[1]))
            val wl = col(0xFFFFFF, if (o.tod == BgTod.DAY) .3 else .12)
            repeat(18) { val y = wy + (H - wy) * r(); val x = r() * W; val w = (30 + r() * 80) * u; line(x, y, x + w, y, wl, 1.5 * u) }
            ridge(hor + span * .5, 14 * u, P.ridge[2], r, u)
            val lx = W * (.66 + r() * .12); val ly = hor + span * .6
            if ("두물머리" in o.name || "느티" in o.name || r() < .5) {
                fillRect(lx - 4 * u, ly - 46 * u, 8 * u, 46 * u, col(P.tree))
                ellipse(lx, ly - 62 * u, 58 * u, 34 * u, col(P.tree))
            }
            fill(path { m(0.0, H); l(0.0, g + 8 * u); q(W * .25, g - 6 * u, W * .45, g + 14 * u); l(W * .45, H); close() }, col(P.ridge[3]))
        } else {
            ridge(hor + span * .55, 18 * u, P.ridge[2], r, u)
            ridge(g - span * .02, 12 * u, P.ridge[3], r, u)
            val tc = col(P.tree)
            repeat(70) {
                val x = r() * W; val y = g - span * .02 + (r() * .6) * span * .5; val s = (10 + r() * 14) * u
                fill(path { m(x, y - s * 1.8); l(x + s * .55, y); l(x - s * .55, y); close() }, tc)
            }
        }
    }

    /** 중립 = 길(JS neutral variant "road"). */
    private fun neutralRoad(o: O) {
        val P = o.P; val r = o.r; val u = o.u; val tod = o.tod; val land = o.land
        val accent = hex(when (tod) { BgTod.NIGHT -> "#C8F04A"; BgTod.DUSK -> "#FFB37A"; BgTod.DAY -> "#FFFFFF" })
        val vx = if (land) W * .6 else W * .5
        val vy = o.hor
        vgrad(0.0, vy, W, H - vy, vy, H, 0f to col(bgMix(P.road, P.sky[2], .45)), 1f to col(P.road))
        glow(vx, vy, (if (land) 420 else 300) * u, accent, if (tod == BgTod.DAY) .35 else .22)
        val bw = if (land) W * 1.05 else W * 1.6
        fill(path { m(vx - 4 * u, vy); l(vx + 4 * u, vy); l(vx + bw / 2, H); l(vx - bw / 2, H); close() }, col(bgMix(P.road, hex("#0B0A16"), .18)))
        val edge = col(0xFFFFFF, if (tod == BgTod.DAY) .6 else .32)
        for (sx in intArrayOf(-1, 1)) line(vx + sx * 4 * u, vy, vx + sx * bw / 2 * .92, H, edge, 3 * u)
        for (i in 0 until 9) {
            val t0 = (i / 9.0).pow(1.9); val t1 = ((i + .45) / 9).pow(1.9)
            val y0 = vy + (H - vy) * t0; val y1 = vy + (H - vy) * t1
            line(vx, y0, vx, y1, col(hex(if (tod == BgTod.NIGHT) "#C8F04A" else "#FFFFFF"), if (tod == BgTod.DAY) .75 else .5), (1 + t1 * 5) * u)
        }
        if (tod != BgTod.DAY) repeat(5) {
            val t = .08 + r() * .5; val y = vy + (H - vy) * t; val dx = (H - vy) * t * (.18 + r() * .1); val sz = (2 + t * 9) * u
            glow(vx - dx, y, sz * 2.4, hex("#FF5E45"), .8); glow(vx - dx + sz * 2.2, y, sz * 2.4, hex("#FF5E45"), .8)
        }
        fill(path {
            m(0.0, vy)
            var x = 0.0
            while (x <= W) { l(x, vy - (6 + sin(x / (120 * u)) * 5 + sin(x / (47 * u)) * 3) * u); x += 24 * u }
            l(W, vy); close()
        }, col(bgMix(P.far, P.sky[2], .3), .9))
    }

    /** 비 · 눈(JS precip). [t] = 초, 같은 씨앗이라 프레임마다 같은 방울이 자리만 옮긴다. */
    fun precip(wx: BgWx, t: Double, seed: Long) {
        if (wx != BgWx.RAIN && wx != BgWx.SNOW) return
        val r = BgRng(seed); val u = H / 478
        val n = jsRound(W * H / (if (wx == BgWx.RAIN) 5200 else 7800))
        for (i in 0 until n) {
            val bx = r() * W; val by = r() * H
            val sp = if (wx == BgWx.RAIN) (900 + r() * 500) * u else (40 + r() * 50) * u
            if (wx == BgWx.RAIN) {
                val y = (by + t * sp) % (H + 60 * u) - 30 * u
                val x = (bx - t * sp * .22) % W
                val len = (18 + r() * 20) * u
                val xx = if (x < 0) x + W else x
                line(xx, y, xx - len * .22, y + len, Color(220, 230, 255, (((.18 + r() * .28)) * 255 + .5).toInt()), 1.4 * u)
            } else {
                val y = (by + t * sp) % (H + 10 * u); val x = bx + sin(t * 1.3 + i) * 8 * u; val s = (1.5 + r() * 2.6) * u
                circle(x, y, s, Color.White.copy(alpha = (.55 + r() * .4).f))
            }
        }
    }
}
