package com.mist.pleos_app.effort

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.math.floor

/*
 * 출발 미리보기 코드 배경(F20, DECISIONS 11/15 명준 · #116). 사진 대신 앱이 그린다(서버 호출 · 이미지 · 저작자 표기 없음).
 * 기준 = `design/components/preview-bg.js`(#182, HANDOFF_v4 7-4). 이 파일은 고르는 규칙 · 씨앗 · 색만 둔 순수 함수이고,
 * 그리기는 ui/components/CgPreviewBg.kt가 같은 순서 · 같은 숫자로 한다.
 */

/** 배경 종류. [id]는 JS `st.type` 문자열 그대로(씨앗 `hash(name + "|" + type)`에 들어간다). */
enum class BgKind(val id: String) { NEUTRAL("neutral"), CITY("city"), HOME("home"), SEA("sea"), NATURE("nature") }

enum class BgTod { DAY, DUSK, NIGHT }

/** 출발 날씨. 앱이 받는 값은 none · rain · snow · unknown뿐이라 CLOUD는 지금 고르지 않는다(시안 시트용). */
enum class BgWx { CLEAR, CLOUD, RAIN, SNOW }

/** 한 번 그릴 배경. [name] = 목적지 이름(씨앗). */
data class PreviewBgSpec(val name: String, val kind: BgKind, val tod: BgTod, val wx: BgWx) {
    val animated: Boolean get() = wx == BgWx.RAIN || wx == BgWx.SNOW
}

/**
 * 카카오 분류 → 종류 표(`constants.json` `preview_bg`, DECISIONS 11/15 '출발 미리보기 배경: 카카오 분류 표', #187).
 * [leaf] = 마지막 칸, [upper] = 윗칸. 표를 늘리면 fixtures만 바꾼다(앱 코드에 값을 두지 않는다).
 */
data class PreviewBgTable(val leaf: Map<String, BgKind>, val upper: Map<String, BgKind>)

/** constants.json 전체 글에서 `preview_bg`를 읽는다. 칸이 없거나 모양이 다르면 null. 모르는 종류 값은 건너뛴다. */
fun parsePreviewBgTable(constantsText: String): PreviewBgTable? = runCatching {
    val o = com.mist.pleos_app.engine.parseObject(constantsText)["preview_bg"]?.jsonObject ?: return null
    fun kinds(k: String) = o[k]?.jsonObject.orEmpty().mapNotNull { (cat, v) -> bgKindOfId((v as? JsonPrimitive)?.contentOrNull)?.let { cat to it } }.toMap()
    PreviewBgTable(kinds("leaf"), kinds("upper"))
}.getOrNull()

/**
 * 카카오 분류(윗칸부터) → 종류(서버 참조 구현 `places.preview_bg_kind`와 같은 순서).
 * ① 마지막 칸이 leaf에 정확히 있으면 그 종류 ② 아니면 마지막 칸에 가까운 윗칸부터 upper에 정확히 있는 칸 ③ 둘 다 아니면 중립.
 * 이름 속 낱말로는 고르지 않는다(강남역 · 부산역이 '강' · '산'으로 걸린다).
 */
fun bgKindOfPath(path: List<String>, table: PreviewBgTable?): BgKind {
    if (path.isEmpty() || table == null) return BgKind.NEUTRAL
    table.leaf[path.last()]?.let { return it }
    for (seg in path.dropLast(1).asReversed()) table.upper[seg]?.let { return it }
    return BgKind.NEUTRAL
}

/** 시연 데이터의 종류 값(JS 종류 문자열) → 종류. 모르는 값은 null(= 중립). */
fun bgKindOfId(id: String?): BgKind? = BgKind.entries.firstOrNull { it.id == id?.trim() }

/**
 * 종류 고르기(HANDOFF_v4 7-4, DECISIONS 11/15).
 * - 즐겨찾기 HOME → 집, OFFICE → 도시(내비 구분값. 시연에서도 같음)
 * - 시연 모드([demoKinds] != null) → 시연 데이터 `scene.preview_bg.kinds`, 없으면 중립
 * - 검색 결과 → 카카오 분류 칸을 [table]에 대어 고름([bgKindOfPath]). 서버가 category_path를 안 주면 category(마지막 칸)만으로
 * - 그 밖(최근 · 그 밖 즐겨찾기 · 분류 없음) → 중립
 */
fun bgKindOf(o: DestOption, table: PreviewBgTable?, demoKinds: Map<String, BgKind>? = null): BgKind = when {
    o.kind == DestOption.Kind.HOME -> BgKind.HOME
    o.kind == DestOption.Kind.OFFICE -> BgKind.CITY
    demoKinds != null -> demoKinds[o.dest.name] ?: BgKind.NEUTRAL
    o.kind == DestOption.Kind.SEARCH -> bgKindOfPath(o.categoryPath.ifEmpty { listOfNotNull(o.category) }, table)
    else -> BgKind.NEUTRAL
}

/** 시간대: 출발 시각 6~16시 낮 · 17~19시 해 질 녘 · 그 밖 밤(JS `bgTod`). */
fun bgTod(hour: Int): BgTod = when (hour) {
    in 6..16 -> BgTod.DAY
    in 17..19 -> BgTod.DUSK
    else -> BgTod.NIGHT
}

/** "2026-11-10T16:00:00+09:00" → 16(현지 시각 그대로). 못 읽으면 null. */
fun hourOf(iso: String?): Int? = iso?.takeIf { it.length >= 13 && it[10] == 'T' }?.substring(11, 13)?.toIntOrNull()?.takeIf { it in 0..23 }

/** 출발 날씨(서버 precip) → 배경 날씨. 비 · 눈만 그리고 그 밖(none · unknown · 아직 못 받음)은 맑음. */
fun bgWx(weather: String?): BgWx = when (weather) {
    "rain" -> BgWx.RAIN
    "snow" -> BgWx.SNOW
    else -> BgWx.CLEAR
}

fun previewBgOf(o: DestOption, hour: Int, weather: String?, table: PreviewBgTable?, demoKinds: Map<String, BgKind>? = null) =
    PreviewBgSpec(o.dest.name, bgKindOf(o, table, demoKinds), bgTod(hour), bgWx(weather))

// ---------------------------------------------------------------- 씨앗(JS와 같은 값)

/** FNV-1a 32비트(JS `hash`: 코드 포인트마다 xor · Math.imul). 부호 없는 값을 Long으로. */
fun bgHash(s: String): Long {
    var h = 2166136261L.toInt()
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        h = (h xor cp) * 16777619
        i += Character.charCount(cp)
    }
    return h.toLong() and 0xFFFFFFFFL
}

/** mulberry32(JS `rng`). seed는 JS Number를 `|0` 한 값과 같게 하위 32비트만 쓴다. 0이면 1. */
class BgRng(seed: Long) {
    private var a: Int = if (seed == 0L) 1 else seed.toInt()

    fun next(): Double {
        a += 0x6D2B79F5
        var t = (a xor (a ushr 15)) * (1 or a)
        t = (t + (t xor (t ushr 7)) * (61 or t)) xor t
        return ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0
    }

    operator fun invoke() = next()
}

// ---------------------------------------------------------------- 색(JS `PAL` · `palette` · `mix`)

/** JS `Math.round`(x ≥ 0). kotlin.math.round는 0.5를 짝수로 보내 1단위로 어긋난다. */
private fun jsRound(v: Double): Int = floor(v + 0.5).toInt()

private fun rgb(hex: String): Int = hex.removePrefix("#").toInt(16)

/** 0xRRGGBB 두 색을 t만큼 섞고 채널마다 반올림(JS `mix` → `toHex`). */
fun bgMix(a: Int, b: Int, t: Double): Int {
    var out = 0
    for (sh in intArrayOf(16, 8, 0)) {
        val x = (a shr sh) and 0xFF
        val y = (b shr sh) and 0xFF
        out = out or (jsRound(x + (y - x) * t).coerceIn(0, 255) shl sh)
    }
    return out
}

/** 한 장면의 색(0xRRGGBB). lit = 창 불빛 비율, star = 별 밝기. */
data class BgPalette(
    val sky: List<Int>, val sun: Int, val far: Int, val mid: Int, val near: Int, val trim: Int, val win: Int, val lit: Double,
    val road: Int, val water: List<Int>, val sand: Int, val ridge: List<Int>, val tree: Int, val star: Double,
) {
    fun map(f: (Int) -> Int) = copy(
        sky = sky.map(f), sun = f(sun), far = f(far), mid = f(mid), near = f(near), trim = f(trim), win = f(win),
        road = f(road), water = water.map(f), sand = f(sand), ridge = ridge.map(f), tree = f(tree),
    )
}

private fun pal(
    sky: List<String>, sun: String, far: String, mid: String, near: String, trim: String, win: String, lit: Double,
    road: String, water: List<String>, sand: String, ridge: List<String>, tree: String, star: Double,
) = BgPalette(sky.map(::rgb), rgb(sun), rgb(far), rgb(mid), rgb(near), rgb(trim), rgb(win), lit, rgb(road), water.map(::rgb), rgb(sand), ridge.map(::rgb), rgb(tree), star)

private val PAL = mapOf(
    BgTod.DAY to pal(listOf("#5E9BD6", "#9CC6EA", "#E2EEF1"), "#FFF7DD", "#A6B9CF", "#8299B4", "#617993", "#C9D7E6", "#E6F0F8", 0.0, "#5E6775", listOf("#79AAD3", "#4D7EA6"), "#E6D6B2", listOf("#B3C9B7", "#88AB90", "#5C8366", "#3A5C44"), "#2E4D37", 0.0),
    BgTod.DUSK to pal(listOf("#2A2450", "#7C4B7E", "#F2A46F"), "#FFD28E", "#4D3F69", "#372D55", "#262040", "#5A4A78", "#FFD49A", .38, "#2A2340", listOf("#6C4C79", "#2B2447"), "#6E4F62", listOf("#735B7D", "#564268", "#3B2E50", "#261E38"), "#1E1830", .2),
    BgTod.NIGHT to pal(listOf("#0D0C1C", "#1F1C3E", "#3B2D5A"), "#F1EDDC", "#2B2749", "#201D3B", "#16142B", "#2F2B52", "#FFD8A0", .46, "#141229", listOf("#262350", "#100F27"), "#272140", listOf("#2E2C51", "#232141", "#1A1833", "#101022"), "#0D0C1C", 1.0),
)

/** JS `palette(tod, wx)`: 날씨만큼 회색 쪽으로 섞고, 눈이면 길 · 모래 · 능선을 희게, 흐림 · 비 · 눈이면 별 없음. */
fun bgPalette(tod: BgTod, wx: BgWx): BgPalette {
    val day = tod == BgTod.DAY
    val k = when (wx) {
        BgWx.CLEAR -> 0.0
        BgWx.CLOUD -> if (day) .28 else .18
        BgWx.RAIN -> if (day) .42 else .26
        BgWx.SNOW -> if (day) .22 else .16
    }
    val to = rgb(if (wx == BgWx.SNOW) (if (day) "#D7DCE6" else "#4A4E66") else (if (day) "#7D8496" else "#3A3B52"))
    var p = PAL.getValue(tod).map { bgMix(it, to, k) }
    if (wx == BgWx.SNOW) p = p.copy(
        road = bgMix(p.road, rgb("#E8ECF3"), if (day) .55 else .25),
        sand = bgMix(p.sand, rgb("#EEF1F6"), .5),
        ridge = p.ridge.mapIndexed { i, c -> bgMix(c, rgb("#E9EDF3"), if (day) .35 - i * .06 else .12) },
    )
    if (wx != BgWx.CLEAR) p = p.copy(star = 0.0)
    return p
}

/** 이름 속 물 · 바닷가 낱말(JS `WATER` · beach 정규식). 그림 모양만 바꾸고 종류는 고르지 않는다. */
fun bgHasWater(name: String) = listOf("강", "호수", "두물머리", "계곡", "저수지", "댐").any { it in name }
fun bgHasBeach(name: String) = listOf("해수욕장", "해변", "비치", "꽃지").any { it in name }
