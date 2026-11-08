package com.mist.pleos_app.effort

import com.mist.pleos_app.engine.HourPrecip
import com.mist.pleos_app.engine.WeatherTrip
import com.mist.pleos_app.engine.parseEffortConstants
import com.mist.pleos_app.engine.parseObject
import com.mist.pleos_app.engine.parseWeatherTrip
import com.mist.pleos_app.navi.Destination
import com.mist.pleos_app.net.weatherTripPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 시간대별 강수(#63): 출발 좌표는 반올림해 도착 조회 전까지만, 조각별 강수는 엔진 입력으로, 쿼리의 +는 %2B로. */
class WeatherTripTest {

    private val fixtures = locate("fixtures")
    private val c = parseEffortConstants(File(fixtures, "constants.json").readText())

    @Test
    fun departRoundsAndKeepsDepartureCoordUntilArrival() {
        val t = departTrip("t1", "2026-11-17T14:20:00+09:00", Destination("p", "양평", 37.5, 127.3, null), 2, null, "rain", here = 37.40123 to 127.10789)
        assertEquals(37.40, t.depLat!!, 0.0)
        assertEquals(127.11, t.depLng!!, 0.0)
        val back = parseOpenTrip(t.toJson())
        assertEquals(37.40, back.depLat!!, 0.0)
        assertNull(departTrip("t2", t.departedAt, Destination("p", "양평", 0.0, 0.0, null), 2, null, "none", here = null).depLat)
    }

    @Test
    fun arrivalStoresHoursAndForgetsCoord() {
        val f = EffortFlow(MemoryEffortStore())
        f.depart(OpenTrip("t1", "2026-11-17T14:20:00+09:00", "양평", 2, 8700.0, depLat = 37.40, depLng = 127.11, weatherDep = "rain"))
        f.arrive("2026-11-17T16:45:00+09:00", weatherArr = null)
        assertEquals(37.40, f.state.openTrip!!.depLat!!, 0.0)          // 조회 전에는 남아 있음
        val wt = WeatherTrip(
            listOf(HourPrecip("2026-11-17T14:00:00+09:00", "rain"), HourPrecip("2026-11-17T15:00:00+09:00", "none"), HourPrecip("2026-11-17T16:00:00+09:00", "none")),
            listOf(HourPrecip("2026-11-17T14:00:00+09:00", "none"), HourPrecip("2026-11-17T15:00:00+09:00", "none"), HourPrecip("2026-11-17T16:00:00+09:00", "none")),
        )
        f.completeArrival("none", "07:13", "17:20", weatherTrip = wt)
        val t = f.state.openTrip!!
        assertNull(t.depLat); assertNull(t.depLng)                        // 성공해도 지움
        assertEquals(wt, parseOpenTrip(t.toJson()).weatherTrip)           // 저장·복원
        assertEquals(wt, effortInputOf(t)!!.weatherTrip)                  // 엔진 입력으로
        // #63 "출발 비 → 중간에 그침": 1회 관측(출발 비·도착 맑음)이면 앞 절반이 비지만, 시간대별이면 14시 조각만 비
        val hourly = receiptOf(t, c)!!
        val once = receiptOf(t.copy(weatherTrip = null), c)!!
        assertTrue(hourly.rainMin < once.rainMin)
    }

    @Test
    fun failedLookupStillForgetsCoord() {
        val f = EffortFlow(MemoryEffortStore())
        f.depart(OpenTrip("t1", "2026-11-17T14:20:00+09:00", "양평", 2, 8700.0, depLat = 37.40, depLng = 127.11))
        f.arrive("2026-11-17T16:45:00+09:00", weatherArr = null)
        f.completeArrival(WEATHER_UNKNOWN, weatherTrip = null)
        assertNull(f.state.openTrip!!.depLat)
        assertNull(f.state.openTrip!!.weatherTrip)
        assertFalse(f.state.openTrip!!.toJson().toString().contains("37.4"))
    }

    @Test
    fun pathEncodesPlusAndRoundsCoords() {
        val p = weatherTripPath(37.40123 to 127.10789, 37.55 to 127.49, "2026-11-17T14:20:00+09:00", "2026-11-17T16:45:00+09:00")
        assertEquals(
            "/v1/weather/trip?dep_lat=37.40&dep_lon=127.11&arr_lat=37.55&arr_lon=127.49&from=2026-11-17T14%3A20%3A00%2B09%3A00&to=2026-11-17T16%3A45%3A00%2B09%3A00",
            p,
        )
    }

    @Test
    fun contractSampleParses() {
        // 계약 샘플 그대로 엔진 입력이 된다(status는 안 씀)
        val o = parseObject(File(fixtures.parentFile, "contract/samples/weather.trip.ok.response.json").readText())
        val wt = parseWeatherTrip(o)
        assertEquals(wt.dep.size, wt.arr.size)
        assertTrue(wt.dep.isNotEmpty())
    }

    private fun locate(rel: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, rel)
            if (f.isDirectory && File(f, "constants.json").exists()) return f
            dir = dir.parentFile
        }
        throw IllegalStateException("$rel 을 찾지 못함")
    }
}
