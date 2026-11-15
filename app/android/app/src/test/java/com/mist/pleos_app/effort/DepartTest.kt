package com.mist.pleos_app.effort

import com.mist.pleos_app.engine.hhmm
import com.mist.pleos_app.engine.parseObject
import com.mist.pleos_app.engine.sunTimes
import com.mist.pleos_app.navi.Destination
import com.mist.pleos_app.navi.NaviPlace
import com.mist.pleos_app.navi.TripPreview
import com.mist.pleos_app.navi.mergeNaviPlaces
import com.mist.pleos_app.net.PlacesStatus
import com.mist.pleos_app.net.coord
import com.mist.pleos_app.net.parsePlaces
import com.mist.pleos_app.net.parseWeather
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 출발 화면(명세 v0.5 F20): 계약 샘플 파싱(#54·#57), 출발 기록 만들기, 내비 후보 합치기. */
class DepartTest {

    private val samples: File = locate("contract/samples")

    private fun sample(name: String) = parseObject(File(samples, name).readText())

    @Test
    fun weatherSamples() {
        assertEquals("rain", parseWeather(sample("weather.rain.response.json")))
        assertEquals("unknown", parseWeather(sample("weather.unknown.response.json")))
        assertEquals("unknown", parseWeather(parseObject("""{"precip":"hail"}""")))   // 모르는 값은 정보 없음
    }

    @Test
    fun placesSamples() {
        val ok = parsePlaces(sample("places.ok.response.json"))
        assertEquals(PlacesStatus.OK, ok.status)
        assertTrue(ok.places.isNotEmpty())
        val first = ok.places[0]
        assertEquals("두물머리", first.name)
        assertEquals("관광,명소", first.category)
        // #57 매핑: place_id → poiId, address 그대로
        val o = first.toOption()
        assertEquals(first.placeId, o.dest.id)
        assertEquals(first.address, o.dest.address)
        assertEquals(DestOption.Kind.SEARCH, o.kind)
        assertEquals(PlacesStatus.EMPTY, parsePlaces(sample("places.empty.response.json")).status)
        assertEquals(PlacesStatus.UNAVAILABLE, parsePlaces(sample("places.unavailable.response.json")).status)
        // ok인데 비었으면 empty, 모르는 status는 unavailable
        assertEquals(PlacesStatus.EMPTY, parsePlaces(parseObject("""{"status":"ok","places":[]}""")).status)
        assertEquals(PlacesStatus.UNAVAILABLE, parsePlaces(parseObject("""{"status":"x","places":[]}""")).status)
    }

    @Test
    fun coordinatesAreRoundedToAboutOneKm() {
        // #57: 앱은 소수 둘째 자리로 반올림해 보낸다
        assertEquals("37.41", coord(37.4127))
        assertEquals("127.10", coord(127.0950))
        assertEquals("37.42", coord(37.415))
    }

    @Test
    fun departTripFromPreview() {
        val d = Destination("7845373", "두물머리", 37.53, 127.31, "경기 양평군 양서면 양수리")
        val p = TripPreview(etaS = 3516, distanceM = 104300, congestionRatio = 0.36)
        val t = departTrip("t1", "2026-11-17T16:00:00+09:00", d, n = 4, preview = p, weatherDep = "rain")
        assertEquals("두물머리", t.destName)
        assertEquals(3516.0, t.etaS, 0.0)
        assertEquals(104300.0, t.routeM!!, 0.0)
        assertEquals(0.36, t.congestionRatio!!, 0.0)
        assertEquals(37548.0, t.congestionM!!, 1e-6)
        assertEquals("rain", t.weatherDep)
        // AC-F20-4: 출발 기록에 좌표가 없다
        val json = t.toJson().toString()
        assertFalse(json.contains("37.53"))
        assertFalse(json.contains("127.31"))
    }

    @Test
    fun departWithoutPreviewHasNoEtaOrCongestion() {
        // F20 예외: 미리보기가 안 와도 출발은 막지 않는다 → 정체 정보 없음
        val t = departTrip("t1", "2026-11-17T16:00:00+09:00", Destination("1", "집", 0.0, 0.0), n = 0, preview = null, weatherDep = "unknown")
        assertEquals(0.0, t.etaS, 0.0)
        assertNull(t.congestionRatio)
        assertNull(t.routeM)
        assertEquals(1, t.n)
    }

    @Test
    fun sunTimesFromDeparturePointOnly() {
        // 출발 지점·날짜로 출발 때 계산해 "HH:MM"만 남긴다(좌표는 남기지 않음)
        val d = Destination("1", "두물머리", 37.53, 127.31)
        val seoul = 37.5665 to 126.978
        val t = departTrip("t1", "2026-11-07T16:00:00+09:00", d, 2, null, "none", here = seoul)
        val (rise, set) = sunTimes(seoul.first, seoul.second, "2026-11-07")
        assertEquals(hhmm(rise), t.sunrise)
        assertEquals(hhmm(set), t.sunset)
        assertTrue(t.sunset!! > "17:00" && t.sunset!! < "17:40")   // 서울 11월 초 일몰 17시 20분 안팎
        assertFalse(t.toJson().toString().contains("126.97"))
        // 현재 위치를 못 읽었으면 null → 도착 때 도착 좌표로(명세 F23)
        assertNull(departTrip("t2", "2026-11-07T16:00:00+09:00", d, 2, null, "none").sunrise)
    }

    @Test
    fun demoJsonFromFixtures() {
        // fixtures/effort/demo.json(#55) → 시연 출발 기록
        val scene = parseDemoScene(File(locate("fixtures/effort"), "demo.json").readText())
        val t = DemoEffortSource(scene).demoTrip("d1")
        assertEquals("태안 꽃지해수욕장", t.destName)
        assertEquals(4, t.n)
        assertEquals("rain", t.weatherDep)
        assertTrue(t.demo)
    }

    @Test
    fun canDepartWaitsForPreview() {
        val o = DestOption(Destination("1", "집", 0.0, 0.0), DestOption.Kind.HOME)
        assertFalse(DepartUi().canDepart)
        assertFalse(DepartUi(selected = o, previewing = true).canDepart)
        assertTrue(DepartUi(selected = o, previewing = false).canDepart)
    }

    @Test
    fun naviPlacesBookmarksFirstAndUniqueByName() {
        fun p(name: String, kind: NaviPlace.Kind) = NaviPlace(Destination(name, name, 0.0, 0.0), kind)
        val merged = mergeNaviPlaces(
            listOf(p("집", NaviPlace.Kind.HOME), p("회사", NaviPlace.Kind.OFFICE)),
            listOf(p("두물머리", NaviPlace.Kind.RECENT), p("집", NaviPlace.Kind.RECENT), p("", NaviPlace.Kind.RECENT)),
        )
        assertEquals(listOf("집", "회사", "두물머리"), merged.map { it.dest.name })
        assertEquals(NaviPlace.Kind.HOME, merged[0].kind)
    }

    private fun locate(rel: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, rel)
            if (f.isDirectory) return f
            dir = dir.parentFile
        }
        throw IllegalStateException("$rel 을 찾지 못함")
    }
}
