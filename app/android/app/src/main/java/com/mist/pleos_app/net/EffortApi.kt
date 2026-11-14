package com.mist.pleos_app.net

import com.mist.pleos_app.effort.WEATHER_UNKNOWN
import com.mist.pleos_app.util.ChagokLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import com.mist.pleos_app.engine.WeatherTrip
import com.mist.pleos_app.engine.parseWeatherTrip
import kotlin.math.floor

/** 목적지 검색 결과 한 곳(계약 `Place`, #57). 앱은 고른 곳의 이름만 출발 기록에 남기고 좌표는 메모리에만 둔다(AC-N-4). */
data class PlaceResult(
    val name: String,
    val address: String,
    val lat: Double,
    val lng: Double,
    val placeId: String,
    val category: String?,
    val distanceM: Int?,
    /** 카카오 분류의 모든 칸, 윗칸부터(#184 category_path). 출발 미리보기 배경 종류를 고르는 데만 쓴다. 없으면 []. */
    val categoryPath: List<String> = emptyList(),
)

enum class PlacesStatus { OK, EMPTY, UNAVAILABLE }

data class PlacesResult(val status: PlacesStatus, val places: List<PlaceResult>)

/**
 * 노고 흐름의 서버 호출(계약 `contract/openapi.yaml`). 서버가 하는 일은 이 둘뿐이다(명세 v0.5 2절).
 * - `GET /v1/weather?lat=&lon=` → 강수(none·rain·snow·unknown). 실패하면 unknown(AC-C-6)
 * - `GET /v1/places?query=&lat=&lng=` → 목적지 검색. 실패·3초 초과·200 아님은 unavailable(#57 June)
 * 좌표는 소수 둘째 자리(약 1km)로 반올림해 보낸다(#57). 좌표·검색어는 로그에 남기지 않는다(AC-C-10).
 * 호출 쪽에서 전체 마감 3초를 `withTimeoutOrNull`로 건다(HttpURLConnection에는 전체 마감이 없음).
 */
class EffortApi(private val baseUrl: String, private val timeoutMs: Int = ChagokApi.TIMEOUT_MS) {

    /** 강수 형태. 어떤 실패든 unknown. */
    fun weather(lat: Double, lng: Double): String =
        runCatching { parseWeather(get("/v1/weather?lat=${coord(lat)}&lon=${coord(lng)}")) }
            .onFailure { failed("weather", it) }.getOrDefault(WEATHER_UNKNOWN)

    /** 목적지 검색. 현재 위치를 모르면 lat·lng를 빼고 보낸다(정확도순). 어떤 실패든 unavailable. */
    fun places(query: String, here: Pair<Double, Double>?): PlacesResult = runCatching {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val loc = here?.let { "&lat=${coord(it.first)}&lng=${coord(it.second)}" }.orEmpty()
        parsePlaces(get("/v1/places?query=$q$loc"))
    }.onFailure { failed("places", it) }.getOrDefault(PlacesResult(PlacesStatus.UNAVAILABLE, emptyList()))

    /**
     * 시간대별 강수(#63, `GET /v1/weather/trip`). 200이면 status와 상관없이 dep·arr를 그대로 돌려준다
     * (unavailable·partial은 엔진이 처리, #72 June 답). 200 아님·연결 실패·형식 오류는 null → 1회 관측 규칙.
     */
    fun weatherTrip(dep: Pair<Double, Double>, arr: Pair<Double, Double>, from: String, to: String): WeatherTrip? =
        runCatching { parseWeatherTrip(get(weatherTripPath(dep, arr, from, to))) }
            .onFailure { failed("weather_trip", it) }.getOrNull()

    /** 실패 종류만 남긴다(URL·검색어·좌표 없음, AC-C-10). */
    private fun failed(api: String, e: Throwable) =
        ChagokLog.event("api_failed", "api" to api, "err" to e.javaClass.simpleName, "status" to (e as? ApiException)?.status)

    private fun get(path: String): JsonObject {
        val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.useCaches = false                              // 검색어·좌표가 URL에 있어 HTTP 캐시에 남기지 않는다(#57)
            conn.setRequestProperty("Accept", "application/json")
            val status = conn.responseCode
            if (status !in 200..299) throw ApiException(status, null, null)
            return Json.parseToJsonElement(conn.inputStream.use { it.readBytes().decodeToString() }).jsonObject
        } finally {
            conn.disconnect()
        }
    }
}

/** `/v1/weather/trip` 경로. 좌표는 반올림, 시각의 `+`는 `%2B`로(쿼리에서 공백이 되면 422, #66). */
fun weatherTripPath(dep: Pair<Double, Double>, arr: Pair<Double, Double>, from: String, to: String): String =
    "/v1/weather/trip?dep_lat=${coord(dep.first)}&dep_lon=${coord(dep.second)}&arr_lat=${coord(arr.first)}&arr_lon=${coord(arr.second)}" +
        "&from=${URLEncoder.encode(from, "UTF-8")}&to=${URLEncoder.encode(to, "UTF-8")}"

/** 소수 둘째 자리(약 1km) 반올림. 위치를 덜 정확하게 보내려는 것이라 화면·계산에는 쓰지 않는다. */
fun coord(x: Double): String = "%.2f".format(java.util.Locale.ROOT, floor(x * 100 + 0.5) / 100)

private val KNOWN_PRECIP = setOf("none", "rain", "snow")

fun parseWeather(o: JsonObject): String =
    (o["precip"] as? JsonPrimitive)?.contentOrNull?.takeIf { it in KNOWN_PRECIP } ?: WEATHER_UNKNOWN

fun parsePlaces(o: JsonObject): PlacesResult {
    val status = when ((o["status"] as? JsonPrimitive)?.contentOrNull) {
        "ok" -> PlacesStatus.OK
        "empty" -> PlacesStatus.EMPTY
        else -> PlacesStatus.UNAVAILABLE
    }
    val list = (o["places"] as? JsonArray).orEmpty().mapNotNull { e ->
        val p = e as? JsonObject ?: return@mapNotNull null
        fun s(k: String) = (p[k] as? JsonPrimitive)?.contentOrNull
        val lat = (p["lat"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
        val lng = (p["lng"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
        PlaceResult(
            name = s("name") ?: return@mapNotNull null,
            address = s("address").orEmpty(),
            lat = lat, lng = lng,
            placeId = s("place_id").orEmpty(),
            category = s("category"),
            distanceM = (p["distance_m"] as? JsonPrimitive)?.intOrNull,
            categoryPath = (p["category_path"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotEmpty) },
        )
    }
    // ok인데 목록이 비었으면 empty로 본다
    return if (status == PlacesStatus.OK && list.isEmpty()) PlacesResult(PlacesStatus.EMPTY, list) else PlacesResult(status, list)
}
