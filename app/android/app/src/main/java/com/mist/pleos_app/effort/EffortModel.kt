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
) {
    val arrived: Boolean get() = arrivedAt != null
}

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
)

fun EffortRecord.toJson(): JsonObject = obj(
    "trip_id" to tripId, "date" to date, "dest_name" to destName,
    "T" to driveMin, "multiplier" to multiplier, "effort_min" to effortMin,
    "method" to method, "suggestion" to suggestion, "choice" to choice, "headline_text" to headlineText, "n" to n,
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
)

fun EffortState.toJson(): JsonObject = obj(
    "open_trip" to openTrip?.toJson(),
    "records" to JsonArray(records.map { it.toJson() }),
)

fun parseEffortState(o: JsonObject): EffortState = EffortState(
    openTrip = o.objOrNull("open_trip")?.let(::parseOpenTrip),
    records = o["records"]?.takeIf { it !is JsonNull }?.jsonArray?.map { parseEffortRecord(it.jsonObject) }.orEmpty(),
)
