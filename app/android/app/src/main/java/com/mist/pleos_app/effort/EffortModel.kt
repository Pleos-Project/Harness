package com.mist.pleos_app.effort

import com.mist.pleos_app.engine.dblOrNull
import com.mist.pleos_app.engine.integer
import com.mist.pleos_app.engine.integerOrNull
import com.mist.pleos_app.engine.obj
import com.mist.pleos_app.engine.objOrNull
import com.mist.pleos_app.engine.str
import com.mist.pleos_app.engine.strOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 출발 기록(명세 v0.5 F20). 도착(F22)·쉰 시간 답(F21)까지 같은 기록에 붙여 두고, 선택(F26)이 끝나면 닫는다.
 * 좌표는 넣지 않는다(AC-F20-4·AC-N-4): 날씨 조회·일몰 계산에 쓴 좌표는 메모리에만 두고, 일출·일몰은 계산한 "HH:MM"만 남긴다.
 * 필드 이름은 엔진 입력(`fixtures/effort/R*.json`의 input)과 같게 두어 영수증 계산 때 그대로 넘긴다.
 */
data class OpenTrip(
    val tripId: String,
    val departedAt: String,                 // ISO +09:00
    val destName: String,
    val n: Int,                             // 나눠 낼 인원(운전자 포함)
    val etaS: Double,                       // 출발 화면 requestTrip ETA(초)
    val routeM: Double? = null,             // 경로 거리(m)
    val congestionRatio: Double? = null,    // 출발 전 정체 비율(0~1, 열린 결정 28)
    val congestionM: Double? = null,        // 정체 거리(m, 표시만)
    val congestionS: Double? = null,        // 나란히 배치에서 받은 구간 목록 통과 시간 합(초)
    val weatherDep: String = WEATHER_UNKNOWN,
    val sunrise: String? = null,            // 출발 지점·날짜로 출발 때 계산한 "HH:MM"
    val sunset: String? = null,
    val arrivedAt: String? = null,          // F22에서 채움
    val weatherArr: String? = null,
    val restAnswerMin: Int? = null,         // F21: 도착 때 답한 쉰 시간(0·15·30·60). 자동 측정은 없음(#62)
    val demo: Boolean = false,              // 데모 고정 모드에서 만든 기록이면 "시연용 값" 표시(AC-C-7)
    // #63 시간대별 날씨(효성 11/8 선택지 2): 출발 지점 좌표를 소수 둘째 자리(약 1km)로 반올림해 도착 조회 전까지만 기기에 둔다.
    // 도착 때 GET /v1/weather/trip 호출이 끝나면 성공·실패와 상관없이 지운다(AC-N-4 개정 예정).
    val depLat: Double? = null,
    val depLng: Double? = null,
    val weatherTrip: com.mist.pleos_app.engine.WeatherTrip? = null,   // 도착 때 받은 조각별 강수(hour·precip만, 좌표 없음)
    // 중간 정산(F27, 명준 Q-08-02 · 효성 #97 · June #97 답): 정산마다 한 줄. 도착 제안은 남은 몫으로(엔진 settledKrw = remainKrw 합)
    val settlements: List<Settlement> = emptyList(),
) {
    val arrived: Boolean get() = arrivedAt != null

    /** 앞서 정산한 원 단위 합(어림 전). 정산이 없으면 null → 엔진은 지금과 같은 출력(R1~R26 그대로). */
    val settledKrw: Double? get() = if (settlements.isEmpty()) null else settlements.fold(0.0) { a, s -> a + s.remainKrw }
}

/**
 * 중간 정산 한 번(F27). [remainKrw]는 그때 영수증의 proposal.remainKrw(어림 전 원 단위) — 어림 값을 남기면
 * "마지막에 한 번 반올림"이 깨진다(#97). [suggestion]은 기록 상세에 보일 A 1인 몫 문구, B면 항목 key와 같다.
 */
data class Settlement(
    val at: String,                         // 정산한 시각(ISO)
    val remainKrw: Double,
    val method: String,                     // A | B
    val choice: String,                     // accept | 항목 key
    val suggestion: String? = null,
)

/** 선택 기록(명세 v0.5 F26). 좌표 없음. 홈 최근 기록에 쓰려고 엔진 머리줄 문구를 같이 남긴다. */
data class EffortRecord(
    val tripId: String,
    val date: String,                       // "2026-11-17"
    val destName: String,
    val driveMin: Double,                   // T
    val multiplier: Double,
    val effortMin: Double,
    val method: String,                     // A | B
    val suggestion: String?,                // A: 1인 몫 문구, B: 기본으로 올린 항목 key. 제안 없음이면 null
    val choice: String,                     // accept | close | coffee·meal·shared …
    val headlineText: String,               // 엔진 headline_text("오늘 운전 2시간 10분 · 평소보다 1.57배 힘든 길")
    val n: Int? = null,                     // 나눠 낼 인원(홈 기록 카드 "· 4명", Design v2 #75). 예전 기록은 없음
    val trip: OpenTrip? = null,             // 그날 엔진 입력(기록 상세 영수증, Q-08-01). 좌표는 지우고 남긴다(AC-N-4). 예전 기록은 없음
)

/** 노고 흐름 저장 상태: 열린 출발 기록 1건 + 선택 기록(최근이 앞). */
data class EffortState(
    val openTrip: OpenTrip? = null,
    val records: List<EffortRecord> = emptyList(),
)

const val WEATHER_UNKNOWN = "unknown"

// ------------------------------------------------------------ JSON(저장소)
fun OpenTrip.toJson(): JsonObject = obj(
    "trip_id" to tripId, "departed_at" to departedAt, "dest_name" to destName, "n" to n,
    "eta_s" to etaS, "route_m" to routeM,
    "congestion_ratio" to congestionRatio, "congestion_m" to congestionM, "congestion_s" to congestionS,
    "weather_dep" to weatherDep, "sunrise" to sunrise, "sunset" to sunset,
    "arrived_at" to arrivedAt, "weather_arr" to weatherArr,
    "rest_answer_min" to restAnswerMin,
    "demo" to demo,
    "dep_lat" to depLat, "dep_lng" to depLng,
    "weather_trip" to weatherTrip?.toJson(),
    "settlements" to JsonArray(settlements.map { obj("at" to it.at, "remain_krw" to it.remainKrw, "method" to it.method, "choice" to it.choice, "suggestion" to it.suggestion) }),
)

/** 조각별 강수 저장 모양 = 계약 응답 WeatherTrip의 dep·arr 그대로(status 없음). */
fun com.mist.pleos_app.engine.WeatherTrip.toJson(): JsonObject = obj(
    "dep" to JsonArray(dep.map { obj("hour" to it.hour, "precip" to it.precip) }),
    "arr" to JsonArray(arr.map { obj("hour" to it.hour, "precip" to it.precip) }),
)

fun parseOpenTrip(o: JsonObject): OpenTrip = OpenTrip(
    tripId = o.str("trip_id"),
    departedAt = o.str("departed_at"),
    destName = o.str("dest_name"),
    n = o.integer("n"),
    etaS = o.dblOrNull("eta_s") ?: 0.0,
    routeM = o.dblOrNull("route_m"),
    congestionRatio = o.dblOrNull("congestion_ratio"),
    congestionM = o.dblOrNull("congestion_m"),
    congestionS = o.dblOrNull("congestion_s"),
    weatherDep = o.strOrNull("weather_dep") ?: WEATHER_UNKNOWN,
    sunrise = o.strOrNull("sunrise"),
    sunset = o.strOrNull("sunset"),
    arrivedAt = o.strOrNull("arrived_at"),
    weatherArr = o.strOrNull("weather_arr"),
    restAnswerMin = o.integerOrNull("rest_answer_min"),
    demo = o["demo"]?.jsonPrimitive?.booleanOrNull == true,
    depLat = o.dblOrNull("dep_lat"),
    depLng = o.dblOrNull("dep_lng"),
    weatherTrip = o["weather_trip"]?.takeIf { it !is JsonNull }?.let { runCatching { com.mist.pleos_app.engine.parseWeatherTrip(it.jsonObject) }.getOrNull() },
    settlements = o["settlements"]?.takeIf { it !is JsonNull }?.jsonArray?.mapNotNull { e ->
        runCatching {
            val s = e.jsonObject
            Settlement(s.str("at"), s.dblOrNull("remain_krw") ?: 0.0, s.str("method"), s.str("choice"), s.strOrNull("suggestion"))
        }.getOrNull()
    }.orEmpty(),
)

fun EffortRecord.toJson(): JsonObject = obj(
    "trip_id" to tripId, "date" to date, "dest_name" to destName,
    "T" to driveMin, "multiplier" to multiplier, "effort_min" to effortMin,
    "method" to method, "suggestion" to suggestion, "choice" to choice, "headline_text" to headlineText, "n" to n,
    // 기록 속 엔진 입력에는 좌표 칸 자체를 두지 않는다(AC-N-4, 출발 좌표는 도착 조회 뒤 이미 지워짐)
    "trip" to trip?.toJson()?.let { JsonObject(it - "dep_lat" - "dep_lng") },
)

fun parseEffortRecord(o: JsonObject): EffortRecord = EffortRecord(
    tripId = o.str("trip_id"),
    date = o.str("date"),
    destName = o.str("dest_name"),
    driveMin = o.dblOrNull("T") ?: 0.0,
    multiplier = o.dblOrNull("multiplier") ?: 1.0,
    effortMin = o.dblOrNull("effort_min") ?: 0.0,
    method = o.str("method"),
    suggestion = o.strOrNull("suggestion"),
    choice = o.str("choice"),
    headlineText = o.strOrNull("headline_text").orEmpty(),
    n = o.integerOrNull("n"),
    trip = o.objOrNull("trip")?.let { runCatching { parseOpenTrip(it) }.getOrNull() },
)

fun EffortState.toJson(): JsonObject = obj(
    "open_trip" to openTrip?.toJson(),
    "records" to JsonArray(records.map { it.toJson() }),
)

fun parseEffortState(o: JsonObject): EffortState = EffortState(
    openTrip = o.objOrNull("open_trip")?.let(::parseOpenTrip),
    records = o["records"]?.takeIf { it !is JsonNull }?.jsonArray?.map { parseEffortRecord(it.jsonObject) }.orEmpty(),
)
