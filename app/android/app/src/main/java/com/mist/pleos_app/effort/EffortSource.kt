package com.mist.pleos_app.effort

import com.mist.pleos_app.engine.dbl
import com.mist.pleos_app.engine.dblOrNull
import com.mist.pleos_app.engine.integer
import com.mist.pleos_app.engine.integerOrNull
import com.mist.pleos_app.engine.parseObject
import com.mist.pleos_app.engine.str
import com.mist.pleos_app.engine.strOrNull
import kotlinx.serialization.json.jsonObject
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 출발·도착에 쓰는 바깥 값의 출처. 화면·ViewModel은 이것만 보고 실제/시연을 구분하지 않는다.
 * - [LiveEffortSource]: 지금 시각. 내비(requestTrip)·날씨 값은 출발 화면(F20, 2단계)에서 채운다
 * - [DemoEffortSource]: 시연 데이터(`assets/data/effort/demo.json`, 명세 v0.5 12절). 2시간을 기다릴 수 없어 시각·경로·날씨를 고정한다
 */
interface EffortSource {
    /** 시연용 값이면 true → 화면 구석 "시연용 값"(AC-C-7). */
    val demo: Boolean

    /** 출발 시각(ISO +09:00). */
    fun departedAt(): String

    /** 도착 시각(ISO +09:00). */
    fun arrivedAt(): String

    /** 시연이면 출발 기록을 통째로 준다(목적지·인원·ETA·정체·날씨·일출일몰). 실제면 null → 출발 화면 값으로 만든다. */
    fun demoTrip(tripId: String = newTripId()): OpenTrip?

    /** 시연이면 도착 날씨, 실제면 null → GET /v1/weather(F22). */
    fun demoWeatherArr(): String?
}

fun newTripId(): String = UUID.randomUUID().toString()

/** 지금 시각을 엔진 입력 형식(초 단위, +09:00)으로. */
fun isoNow(zone: ZoneId = SEOUL): String =
    OffsetDateTime.now(zone).truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

class LiveEffortSource(private val now: () -> String = { isoNow() }) : EffortSource {
    override val demo = false
    override fun departedAt() = now()
    override fun arrivedAt() = now()
    override fun demoTrip(tripId: String): OpenTrip? = null
    override fun demoWeatherArr(): String? = null
}

/** demo.json의 scene(화면 값) + input(엔진 입력). 좌표는 들어 있지 않다. */
data class DemoScene(
    val destName: String,
    val routeM: Double,
    val n: Int,
    val departedAt: String,
    val arrivedAt: String,
    val etaS: Double,
    val congestionRatio: Double?,
    val congestionM: Double?,
    val weatherDep: String,
    val weatherArr: String,
    val sunrise: String?,
    val sunset: String?,
    val restAnswerMin: Int?,
)

fun parseDemoScene(text: String): DemoScene {
    val root = parseObject(text)
    val scene = root.getValue("scene").jsonObject
    val input = root.getValue("input").jsonObject
    return DemoScene(
        destName = scene.str("dest_name"),
        routeM = scene.dbl("route_m"),
        n = scene.integerOrNull("n") ?: input.integer("n"),
        departedAt = input.str("departed_at"),
        arrivedAt = input.str("arrived_at"),
        etaS = input.dbl("eta_s"),
        congestionRatio = input.dblOrNull("congestion_ratio"),
        congestionM = input.dblOrNull("congestion_m"),
        weatherDep = input.strOrNull("weather_dep") ?: WEATHER_UNKNOWN,
        weatherArr = input.strOrNull("weather_arr") ?: WEATHER_UNKNOWN,
        sunrise = input.strOrNull("sunrise"),
        sunset = input.strOrNull("sunset"),
        restAnswerMin = input.integerOrNull("rest_answer_min"),
    )
}

class DemoEffortSource(val scene: DemoScene) : EffortSource {
    override val demo = true
    override fun departedAt() = scene.departedAt
    override fun arrivedAt() = scene.arrivedAt
    override fun demoWeatherArr() = scene.weatherArr

    override fun demoTrip(tripId: String) = OpenTrip(
        tripId = tripId,
        departedAt = scene.departedAt,
        destName = scene.destName,
        n = scene.n,
        etaS = scene.etaS,
        routeM = scene.routeM,
        congestionRatio = scene.congestionRatio,
        congestionM = scene.congestionM,
        weatherDep = scene.weatherDep,
        sunrise = scene.sunrise,
        sunset = scene.sunset,
        demo = true,
    )
}
