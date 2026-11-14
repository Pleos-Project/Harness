package com.mist.pleos_app.effort

import com.mist.pleos_app.navi.Destination
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** 출발 미리보기 코드 배경 규칙 · 씨앗 · 색(#182 preview-bg.js, HANDOFF_v4 7-4). 기대값은 preview-bg.js를 node로 돌린 값. */
class PreviewBgTest {
    private fun opt(name: String, kind: DestOption.Kind, category: String? = null) = DestOption(Destination("id", name, 0.0, 0.0, ""), kind, category)

    @Test fun `hash는 JS FNV-1a와 같다`() {
        assertEquals(2166136261L, bgHash(""))
        assertEquals(2437743447L, bgHash("집|home"))
        assertEquals(2676892225L, bgHash("태안 꽃지해수욕장|sea"))
        assertEquals(2288435621L, bgHash("양평 두물머리"))
        assertEquals(950528197L, bgHash("회사"))
        assertEquals(1861725018L, bgHash("😀a"))   // 코드 포인트 단위(서로게이트 쌍을 한 글자로)
    }

    @Test fun `rng는 JS mulberry32와 같은 값을 낸다`() {
        fun first3(seed: Long) = BgRng(seed).let { r -> listOf(r(), r(), r()) }
        assertEquals(listOf(0.6112444521859288, 0.4935242917854339, 0.7740248835179955), first3(2166136261L))
        assertEquals(listOf(0.9472165936604142, 0.4403662527911365, 0.5714229273144156), first3(bgHash("태안 꽃지해수욕장|sea")))
        assertEquals(listOf(0.19721310283057392, 0.6917957768309861, 0.8339054945390671), first3(bgHash("회사")))
        // 비 · 눈 씨앗 = hash(name) + 7, 2^32를 넘어도 JS `|0`처럼 하위 32비트
        BgRng(bgHash("태안 꽃지해수욕장") + 7).let { r -> assertEquals(0.387900112895295, r(), 0.0); assertEquals(0.7866672694217414, r(), 0.0) }
        BgRng(4294967295L + 7).let { r -> assertEquals(0.5257466111797839, r(), 0.0); assertEquals(0.002343546599149704, r(), 0.0) }
    }

    @Test fun `palette는 JS와 같은 색이다`() {
        fun hex(c: Int) = "#%06x".format(c)
        bgPalette(BgTod.DAY, BgWx.RAIN).let { p ->
            assertEquals(listOf("#6b91bb", "#8faac7", "#b8c1cb"), p.sky.map(::hex))
            assertEquals("#6b7383", hex(p.road)); assertEquals("#bab4a6", hex(p.sand))
            assertEquals(listOf("#9caca9", "#839b93", "#6a837a", "#566d66"), p.ridge.map(::hex))
        }
        bgPalette(BgTod.NIGHT, BgWx.SNOW).let { p ->
            assertEquals(listOf("#171728", "#262444", "#3d325c"), p.sky.map(::hex))
            assertEquals("#505063", hex(p.road)); assertEquals("#8e8d9e", hex(p.sand))
            assertEquals(listOf("#484867", "#40405c", "#3a3951", "#323345"), p.ridge.map(::hex))
            assertEquals(0.0, p.star, 0.0); assertEquals(.46, p.lit, 0.0)
        }
        bgPalette(BgTod.DAY, BgWx.SNOW).let { p ->
            assertEquals("#b6bcc6", hex(p.road)); assertEquals("#e9e4da", hex(p.sand))
            assertEquals(listOf("#cbd8d3", "#b0c6ba", "#91ab9c", "#758c80"), p.ridge.map(::hex))
        }
        bgPalette(BgTod.DUSK, BgWx.CLOUD).let { p ->
            assertEquals(listOf("#2d2850", "#704876", "#d1916a"), p.sky.map(::hex)); assertEquals("#2d2743", hex(p.road))
        }
        assertEquals(1.0, bgPalette(BgTod.NIGHT, BgWx.CLEAR).star, 0.0)
    }

    private val constantsText by lazy { java.io.File(locate("fixtures"), "constants.json").readText() }
    private val table by lazy { requireNotNull(parsePreviewBgTable(constantsText)) }
    private fun search(name: String, vararg path: String) = DestOption(Destination("id", name, 0.0, 0.0, ""), DestOption.Kind.SEARCH, path.lastOrNull(), categoryPath = path.toList())

    @Test fun `종류 - 즐겨찾기 집 회사는 내비 구분값`() {
        assertEquals(BgKind.HOME, bgKindOf(opt("우리 집", DestOption.Kind.HOME), table))
        assertEquals(BgKind.CITY, bgKindOf(opt("회사", DestOption.Kind.OFFICE), table))
        assertEquals(BgKind.HOME, bgKindOf(opt("집", DestOption.Kind.HOME), table, demoKinds = emptyMap()))   // 시연에서도
        assertEquals(BgKind.CITY, bgKindOf(opt("회사", DestOption.Kind.OFFICE), null))                      // 표를 못 읽어도
    }

    @Test fun `종류 - constants json preview_bg 표의 examples를 모두 맞춘다`() {
        val ex = com.mist.pleos_app.engine.parseObject(constantsText).getValue("preview_bg").jsonObject.getValue("examples").jsonArray
        assert(ex.size >= 10)
        ex.forEach { e ->
            val o = e.jsonObject
            val path = o.getValue("category_path").jsonArray.map { it.jsonPrimitive.content }
            val name = o.getValue("name").jsonPrimitive.content
            assertEquals(name, o.getValue("kind").jsonPrimitive.content, bgKindOf(search(name, *path.toTypedArray()), table).id)
        }
    }

    @Test fun `종류 - 검색 결과는 분류 칸이 표와 정확히 같을 때만`() {
        assertEquals(BgKind.SEA, bgKindOf(search("태안 꽃지해수욕장", "여행", "관광,명소", "해수욕장,해변"), table))
        assertEquals(BgKind.NATURE, bgKindOf(search("청평호", "여행", "관광,명소", "호수"), table))
        assertEquals(BgKind.CITY, bgKindOf(search("강남역 2호선", "교통,수송", "지하철,전철", "수도권2호선"), table))   // 윗칸(upper)
        // 서버가 category_path를 안 줬으면 마지막 칸(category)만으로
        assertEquals(BgKind.NATURE, bgKindOf(opt("북한산", DestOption.Kind.SEARCH, "산"), table))
        // 표에 없음 · 분류 없음 · 이름 속 낱말 · 표를 못 읽음 → 중립
        assertEquals(BgKind.NEUTRAL, bgKindOf(search("꽃지 해안공원", "여행", "공원", "도시근린공원"), table))
        assertEquals(BgKind.NEUTRAL, bgKindOf(search("양평 두물머리", "여행", "관광,명소"), table))
        assertEquals(BgKind.NEUTRAL, bgKindOf(search("해변"), table))
        assertEquals(BgKind.NEUTRAL, bgKindOf(search("태안 꽃지해수욕장", "여행", "관광,명소", "해수욕장,해변"), null))
        assertEquals(BgKind.NEUTRAL, bgKindOfPath(listOf("해수욕장"), table))
    }

    @Test fun `분류 표 - 모양이 다르거나 없으면 null, 모르는 종류 값은 건너뛴다`() {
        assertEquals(null, parsePreviewBgTable("""{"wage":1}"""))
        assertEquals(null, parsePreviewBgTable("not json"))
        assertEquals(PreviewBgTable(mapOf("산" to BgKind.NATURE), emptyMap()), parsePreviewBgTable("""{"preview_bg":{"leaf":{"산":"nature","바위":"rock"}}}"""))
    }

    @Test fun `검색 응답 - category_path를 읽는다, 없으면 빈 목록`() {
        val r = com.mist.pleos_app.net.parsePlaces(com.mist.pleos_app.engine.parseObject(
            """{"status":"ok","places":[{"name":"a","address":"","lat":1,"lng":2,"place_id":"1","category":"호수","distance_m":null,"category_path":["여행","관광,명소","호수"]},
               {"name":"b","address":"","lat":1,"lng":2,"place_id":"2","category":null,"distance_m":null}]}"""))
        assertEquals(listOf("여행", "관광,명소", "호수"), r.places[0].categoryPath)
        assertEquals(emptyList<String>(), r.places[1].categoryPath)
        assertEquals(listOf("여행", "관광,명소", "호수"), r.places[0].toOption().categoryPath)
    }

    @Test fun `종류 - 최근 그 밖 즐겨찾기는 이름과 상관없이 중립`() {
        assertEquals(BgKind.NEUTRAL, bgKindOf(opt("태안 꽃지해수욕장", DestOption.Kind.RECENT), table))
        assertEquals(BgKind.NEUTRAL, bgKindOf(opt("양평 두물머리", DestOption.Kind.BOOKMARK), table))
    }

    @Test fun `종류 - 시연은 시연 데이터의 종류, 칸이 없으면 중립`() {
        val demo = mapOf("태안 꽃지해수욕장" to BgKind.SEA, "양평 두물머리" to BgKind.NATURE)
        assertEquals(BgKind.SEA, bgKindOf(opt("태안 꽃지해수욕장", DestOption.Kind.RECENT), table, demo))
        assertEquals(BgKind.NATURE, bgKindOf(opt("양평 두물머리", DestOption.Kind.SEARCH, "시연 장소"), table, demo))
        assertEquals(BgKind.NEUTRAL, bgKindOf(opt("코엑스", DestOption.Kind.SEARCH, "시연 장소"), table, demo))
        assertEquals(BgKind.NEUTRAL, bgKindOf(opt("태안 꽃지해수욕장", DestOption.Kind.RECENT), table, emptyMap()))
        // 시연에서는 카카오 표를 보지 않는다(시연 분류는 "시연 장소")
        assertEquals(BgKind.NEUTRAL, bgKindOf(search("북한산", "여행", "관광,명소", "산"), table, emptyMap()))
        assertEquals(BgKind.SEA, bgKindOfId("sea")); assertEquals(null, bgKindOfId("바다")); assertEquals(null, bgKindOfId(null))
    }

    @Test fun `시연 데이터 배경 종류 칸 - 없으면 빈 표, 있으면 읽는다`() {
        assertEquals(emptyMap<String, BgKind>(), parseDemoBgKinds(com.mist.pleos_app.engine.parseObject("""{"dest_name":"태안 꽃지해수욕장"}""")))
        val obj = com.mist.pleos_app.engine.parseObject("""{"preview_bg":{"note":"…","kinds":{"태안 꽃지해수욕장":"sea","양평 두물머리":"nature","코엑스":"neutral","이상한 곳":"바다"}}}""")
        assertEquals(mapOf("태안 꽃지해수욕장" to BgKind.SEA, "양평 두물머리" to BgKind.NATURE, "코엑스" to BgKind.NEUTRAL), parseDemoBgKinds(obj))
    }

    @Test fun `시연 demo json - 시연 목록 목적지마다 배경 종류가 있다`() {
        val scene = parseDemoScene(java.io.File(locate("fixtures"), "effort/demo.json").readText())
        assertEquals(BgKind.SEA, scene.bgKinds["태안 꽃지해수욕장"])
        assertEquals(BgKind.NATURE, scene.bgKinds["양평 두물머리"])
        // 카드 · 검색 목록에서 고른 곳의 종류(집 · 회사는 내비 구분값)
        val all = demoRecents(scene) + demoSearch("강릉", scene).second + demoSearch("코엑스", scene).second + demoSearch("가평", scene).second
        val kinds = all.associate { it.dest.name to bgKindOf(it, table, scene.bgKinds) }
        assertEquals(BgKind.HOME, kinds["집"]); assertEquals(BgKind.CITY, kinds["회사"])
        assertEquals(BgKind.SEA, kinds["강릉 안목해변"]); assertEquals(BgKind.NATURE, kinds["가평 아침고요수목원"]); assertEquals(BgKind.NEUTRAL, kinds["코엑스"])
        assertEquals(16, hourOf(scene.departedAt))   // 시연 출발 16:00 = 낮
    }

    @Test fun `시간대 - 6~16 낮 17~19 해 질 녘 그 밖 밤`() {
        assertEquals(BgTod.NIGHT, bgTod(5)); assertEquals(BgTod.DAY, bgTod(6)); assertEquals(BgTod.DAY, bgTod(16))
        assertEquals(BgTod.DUSK, bgTod(17)); assertEquals(BgTod.DUSK, bgTod(19)); assertEquals(BgTod.NIGHT, bgTod(20)); assertEquals(BgTod.NIGHT, bgTod(0))
        assertEquals(16, hourOf("2026-11-10T16:00:00+09:00")); assertEquals(null, hourOf("2026-11-10")); assertEquals(null, hourOf(null))
    }

    @Test fun `날씨 - 비 눈만 그리고 그 밖은 맑음`() {
        assertEquals(BgWx.RAIN, bgWx("rain")); assertEquals(BgWx.SNOW, bgWx("snow"))
        assertEquals(BgWx.CLEAR, bgWx("none")); assertEquals(BgWx.CLEAR, bgWx("unknown")); assertEquals(BgWx.CLEAR, bgWx(null))
        previewBgOf(search("태안 꽃지해수욕장", "여행", "관광,명소", "해수욕장,해변"), 16, "rain", table).let {
            assertEquals(PreviewBgSpec("태안 꽃지해수욕장", BgKind.SEA, BgTod.DAY, BgWx.RAIN), it); assertEquals(true, it.animated)
        }
    }

    private fun locate(rel: String): java.io.File {
        var dir: java.io.File? = java.io.File("").absoluteFile
        while (dir != null) {
            val f = java.io.File(dir, rel)
            if (f.isDirectory && java.io.File(f, "constants.json").exists()) return f
            dir = dir.parentFile
        }
        error("$rel 를 찾지 못함")
    }
}
