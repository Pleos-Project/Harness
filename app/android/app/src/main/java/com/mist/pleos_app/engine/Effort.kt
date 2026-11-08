package com.mist.pleos_app.engine

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/*
 * 운전 노고 엔진 — server/chagok/effort.py 를 그대로 옮긴 Kotlin판(앱 로컬 계산). 기능명세서 v0.5 F21·F23~F25, #47.
 *
 * 규칙
 * - Python 쪽을 바꾸면 여기도 같은 PR에서 바꾼다. 둘 다 fixtures/effort/ 를 통과해야 한다
 *   (서버 pytest, 앱 ./gradlew test 의 FixturesTest). 상수는 constants.json 의 effort 블록(assets/data 로 복사됨).
 * - 반올림은 roundHalfUp / roundTo(approx) 만 쓴다. kotlin.math.round()·Math.rint 는 0.5를 짝수 쪽으로 보낸다.
 * - 덧셈·곱셈·나눗셈 순서를 Python과 같게 적는다.
 * - 시각은 epoch 초, 시간 양은 초, 가산율은 정수 %. 가산은 '%·초'(= 초 × 가산율 %)로 더한다. 정수 초 × 정수 %에는
 *   부동소수 오차가 없어서 명세 12절 경계값(R4·R5 배수 x.xx5, R7 지연 = ETA의 10%)이 Decimal 손 계산과 같다.
 *   · 분 = %·초 ÷ 6000, 배수 × 100 = 노고(%·초) ÷ T(초), 줄별 가산(%) = 그 항(%·초) ÷ T(초)
 *   · 금액 = 노고(%·초) × 시급 ÷ 360000  (= 노고(분) × 시급 ÷ 60)
 */

private const val DAY_S = 86_400L
val EFFORT_LINE_ORDER: List<String> = listOf("rain", "snow", "congestion", "delay", "night", "fatigue")
/** GET /v1/weather 의 precip. 그 밖(unknown·실패)은 '정보 없음', 가산 0. */
val KNOWN_PRECIP: Set<String> = setOf("none", "rain", "snow")
/** 일출·일몰 천정각: 대기 굴절 34′ + 해 반지름 16′(해 윗부분이 지평선에 닿는 순간). */
const val SUN_ZENITH_DEG = 90.833

// ------------------------------------------------------------ 상수
data class EffortItem(
    val key: String,
    val label: String,                      // 선택지 버튼 "공동 경비"
    val priceKrw: Double,
    val phraseText: String = "",            // B 문장에 들어갈 말 "커피값"·"밥값"·"공동 경비"
    val examples: List<String> = emptyList(),   // 그 자리 글자가 차례로 바뀌는 예시(공동 경비만, 첫 값 = phraseText)
    val source: String = "",                // 가격 출처(설정 › 단가 출처, S20)
)

data class EffortConstants(
    val ratesPct: Map<String, Double>,      // rain·snow·congestion·delay·night (정수 %)
    val delayThresholdPct: Double,          // 예상보다 더 문턱(ETA의 %)
    val fatigueLimitMin: Double,            // 장시간: 이 분을 넘은 만큼
    val fatigueRatePct: Double,             // 넘은 분 × 이 %
    val fatigueResetRestMin: Double,        // 이 분 이상 쉬면 연속 운전 구간을 나눈다
    val arrivalEtaTimes: Double,            // 도착 상한(#68): 경과 > max(ETA × 이 값, ETA + 아래 분) 이면 정리하지 못함
    val arrivalEtaPlusMin: Double,
    val arrivalMaxMin: Double,              // 경과 > 이 분(12시간)이면 정리하지 못함
    val wagePerHour: Double,                // 1분 단가 = 시급 ÷ 60
    val wageLabel: String,                  // 단가 출처 표기
    val items: List<EffortItem>,            // 방식 B 항목, 1인분 가격 오름차순
    val copy: EffortCopy,                   // 화면 문구(effort.copy) — 임시, 디자인 확정 대기
)

/** 화면 문구는 모두 constants.json effort.copy에 둔다(명준 #60: 디자인이 확정해 넘겨줌). 엔진은 {…}만 채운다. */
data class EffortCopy(
    val text: Map<String, String>,          // *_text 템플릿(headline_text·a_main_text·b_text 등)
    val lineLabels: Map<String, String>,    // 줄 key → 줄 이름
    val lineDetail: Map<String, String>,    // 줄 값 템플릿(weather_all_text 등)
    val precipWords: Map<String, String>,   // rain → 비, snow → 눈
    val basis: Map<String, String>,         // 줄 key → 근거 한 줄(AC-F23-4)
    val restAnswers: List<Pair<Int, String>>,   // 도착 때 질문의 선택지(분, 문구)
)

private fun JsonObject.strMap(k: String): Map<String, String> =
    getValue(k).jsonObject.mapValues { (_, v) -> v.jsonPrimitive.content }

fun parseEffortCopy(cp: JsonObject): EffortCopy = EffortCopy(
    text = cp.filter { (k, v) -> v is JsonPrimitive && v.isString && k.endsWith("_text") }
        .mapValues { (_, v) -> v.jsonPrimitive.content },
    lineLabels = cp.strMap("line_labels"),
    lineDetail = cp.strMap("line_detail"),
    precipWords = cp.strMap("precip_words"),
    basis = cp.strMap("basis"),
    restAnswers = cp.getValue("rest_answers").jsonArray.map {
        val a = it.jsonObject
        a.integer("min") to a.str("label_text")
    },
)

/** 템플릿의 {이름} 자리를 채운다(Python fill과 같음). */
fun fill(template: String, vararg values: Pair<String, String>): String {
    var t = template
    for ((k, v) in values) t = t.replace("{$k}", v)
    return t
}

/** constants.json 전체 텍스트에서 effort 블록을 읽는다(assets/data/constants.json). */
fun parseEffortConstants(text: String): EffortConstants {
    val e = parseObject(text).getValue("effort").jsonObject
    val f = e.getValue("fatigue").jsonObject
    val r = e.getValue("rates_pct").jsonObject
    return EffortConstants(
        ratesPct = listOf("rain", "snow", "congestion", "delay", "night").associateWith { r.dbl(it) },
        delayThresholdPct = e.dbl("delay_threshold_pct"),
        fatigueLimitMin = f.dbl("limit_min"),
        fatigueRatePct = f.dbl("rate_pct"),
        fatigueResetRestMin = f.dbl("reset_rest_min"),
        arrivalEtaTimes = e.getValue("arrival_cap").jsonObject.dbl("eta_times"),
        arrivalEtaPlusMin = e.getValue("arrival_cap").jsonObject.dbl("eta_plus_min"),
        arrivalMaxMin = e.getValue("arrival_cap").jsonObject.dbl("max_min"),
        wagePerHour = e.dbl("wage_per_hour"),
        wageLabel = e.str("wage_label"),
        items = e.getValue("items").jsonArray.map {
            val i = it.jsonObject
            EffortItem(i.str("key"), i.str("label"), i.dbl("price_krw"), i.str("phrase_text"), i.strList("examples"), i.str("source"))
        }.sortedBy { it.priceKrw },
        copy = parseEffortCopy(e.getValue("copy").jsonObject),
    )
}

// ------------------------------------------------------------ 입력·출력
data class EffortInput(
    val departedAt: String,                 // ISO 8601 + 오프셋
    val arrivedAt: String,
    val etaS: Double,                       // onRouteStarted duration
    val weatherDep: String,                 // none | rain | snow | unknown
    val weatherArr: String,
    val sunriseMin: Int,                    // 출발 지점·날짜의 일출·일몰(그 날 0시부터 분, sunTimes 결과)
    val sunsetMin: Int,
    val n: Int,                             // 나눠 낼 인원(운전자 포함)
    // 정차는 도착 때 답 하나로만 센다(#62, 명준·June 합의). 자동으로 잰 정차 목록은 받지 않는다.
    val restAnswerMin: Int = 0,             // 도착 때 답한 쉰 시간 Q(0·15·30·60분)
    // 정체(열린 결정 28, 효성). 구간 목록이 있으면 통과 시간, 없으면 비율 × T. 둘 다 없으면 정체 정보 없음
    val congestionRatio: Double? = null,    // 기본: 출발 전 requestTrip의 정체 비율(0~1)
    val congestionM: Double? = null,        // 정체 km(비율 × 경로 거리, 표시만)
    val congestionS: Double? = null,        // 보조: 구간 목록 Σ distance ÷ avrSpeed(나란히 배치)
    val distanceKm: Double? = null,         // 표시만
)

data class EffortLine(
    val key: String,
    val label: String,
    val addMin: Double,
    val pct: Int,
    val pctText: String,
    val detailText: String?,            // 줄 값(PRD 5-1): "비, 주행 내내"·"38km (경로의 36%)"·"+25분"·"50분"·"2시간 넘긴 10분"
    val basisText: String?,             // 줄을 탭하면 보이는 근거 한 줄(AC-F23-4, constants effort.basis)
)

/** 방식 A 화면(명세 v0.5 F24, 노고 PRD v1.3 5-2). 방식 B로 넘어가는 문구·버튼은 두지 않는다. */
data class EffortScreenA(
    val titleText: String,              // "오늘 운전을 챙긴다면"
    val mainText: String,               // 큰 숫자 "한 사람 약 9천 원"(동승자 1인 몫)
    val basisText: String,              // "운전 노고 약 3만 6천 원을 운전자 포함 4명이 나눈 값"
    val wageText: String,               // "최저임금 1분 178원 기준"
)

/** 방식 B 화면(명세 v0.5 F25, 노고 PRD v1.3 5-3). 금액은 보여 주지 않는다. 선택지는 effort.items 전부. */
data class EffortScreenB(
    val item: String,                   // 기본값으로 올릴 항목 key
    val text: String,                   // "오늘은 밥값에서 운전자 빼고 나누는 게 어때요" / "오늘은 공동 경비에서 …"
    val itemExamples: List<String>,     // 공동 경비면 그 자리에 차례로 보일 예시(첫 값이 text 안의 말), 커피·밥은 빈 목록
)

data class EffortProposal(
    val amountKrw: Double,              // 노고 금액(어림 전)
    val amountApprox: Int,
    val amountText: String,             // "약 3만 6천 원"
    val perPersonKrw: Double,           // 금액 ÷ n
    val perPersonApprox: Int,
    val perPersonText: String,          // "약 9천 원"
    val driverShareKrw: Double,         // 운전자 몫 G = 금액 × (n − 1) ÷ n
    val a: EffortScreenA,
    val b: EffortScreenB?,              // null = 방식 B 제안 없음(영수증만)
)

data class EffortReceipt(
    val status: String,                 // ok = 영수증 | unsettled = 정리하지 못함(#68, 도착이 너무 늦게 잡힘)
    val statusText: String?,            // unsettled일 때 안내 문구(effort.copy.unsettled_text), ok면 null
    val elapsedLimitMin: Double,        // 적용한 경과 상한(분) = min(max(ETA × 2, ETA + 60), 720)
    val elapsedMin: Double,
    val driveMin: Double,
    val restMin: Double,
    val fatigueSegmentsMin: List<Double>,
    val congestionMin: Double,
    val delayMin: Double,
    val nightMin: Double,
    val rainMin: Double,
    val snowMin: Double,
    val fatigueExcessMin: Double,
    val effortMin: Double,
    val multiplier: Double,
    val lines: List<EffortLine>,        // 가산이 0인 줄은 생략
    val notes: List<String>,
    val footnoteText: String?,          // 정체 줄이 있으면 "정체 구간은 내비 안내 경로 기준"
    val driveText: String,
    val multiplierText: String,
    val headlineText: String,
    val distanceText: String?,
    val proposal: EffortProposal?,      // n = 1이면 null(영수증만)
)

// ------------------------------------------------------------ 시각
fun epochS(iso: String): Long = OffsetDateTime.parse(iso).toEpochSecond()

/** 그 시각의 오프셋 기준 그 날 0시(epoch 초). */
fun localMidnightS(iso: String): Long = OffsetDateTime.parse(iso).truncatedTo(ChronoUnit.DAYS).toEpochSecond()

fun hhmm(minuteOfDay: Int): String =
    "${(minuteOfDay / 60).toString().padStart(2, '0')}:${(minuteOfDay % 60).toString().padStart(2, '0')}"

fun parseHhmm(s: String): Int {
    val (h, m) = s.split(":")
    return h.toInt() * 60 + m.toInt()
}

private fun overlap(a0: Double, a1: Double, b0: Double, b1: Double): Double = max(0.0, min(a1, b1) - max(a0, b0))

/** [일몰, 다음 일출] 구간들. 출발 전날 밤부터 도착하는 날 밤까지(출발 지점·날짜의 시각을 매일 같게 씀). */
fun nightIntervals(departedAt: String, arr: Double, sunriseMin: Int, sunsetMin: Int): List<Pair<Double, Double>> {
    val mid = localMidnightS(departedAt)
    val last = Math.floorDiv(arr.toLong() - mid, DAY_S)
    return (-1L..last).map { k ->
        (mid + k * DAY_S + sunsetMin * 60L).toDouble() to (mid + (k + 1) * DAY_S + sunriseMin * 60L).toDouble()
    }
}

// ------------------------------------------------------------ 표기
fun driveText(driveS: Double): String {
    val mins = roundHalfUp(driveS / 60)
    if (mins <= 0) return "1분 미만"
    val h = mins / 60
    val m = mins % 60
    return when {
        h == 0 -> "${m}분"
        m == 0 -> "${h}시간"
        else -> "${h}시간 ${m}분"
    }
}

/** 배수 x.xx(명세 2절). 1.00이면 '평소와 같은 길' 문구. */
fun multiplierText(m100: Int, c: EffortConstants): String =
    if (m100 <= 100) c.copy.text.getValue("multiplier_same_text")
    else fill(c.copy.text.getValue("multiplier_more_text"), "x" to "${m100 / 100}.${(m100 % 100).toString().padStart(2, '0')}")

fun pctText(pct: Int): String = if (pct > 0) "+$pct%" else "+1% 미만"

/** 거리 m → "104km"(명세 2절 km 정수). 0으로 어림되면 "1km 미만". */
fun kmText(m: Double): String {
    val km = roundHalfUp(m / 1000)
    return if (km > 0) "${km}km" else "1km 미만"
}

/** 정체 비율 → "36%". 0으로 어림되면 "1% 미만". */
fun ratioText(ratio: Double): String {
    val p = roundHalfUp(ratio * 100)
    return if (p > 0) "$p%" else "1% 미만"
}

/** 정체 구간 줄 값 "38km (경로의 36%)"(노고 PRD v1.3 5-1, 열린 결정 28). km·비율을 모르면 null. */
fun congestionText(congestionM: Double?, ratio: Double?, c: EffortConstants): String? {
    if (congestionM == null || ratio == null) return null
    return fill(c.copy.lineDetail.getValue("congestion_text"), "km" to kmText(congestionM), "pct" to ratioText(ratio))
}

/** 출발 화면 미리보기(명세 v0.5 F20·12절 시연 순서 2). requestTrip 값으로 만든다. */
data class DeparturePreview(
    val etaText: String,                // "1시간 45분"
    val routeText: String,              // "104km"
    val congestionText: String?,        // "38km (경로의 36%)". 비율을 모르거나 0이면 null(정체 표시 없음)
)

fun departurePreview(
    etaS: Double, routeM: Double, congestionRatio: Double?, congestionM: Double?, c: EffortConstants,
): DeparturePreview {
    val has = congestionRatio != null && congestionRatio > 0
    return DeparturePreview(
        etaText = driveText(etaS),
        routeText = kmText(routeM),
        congestionText = if (has) congestionText(congestionM, congestionRatio, c) else null,
    )
}

fun DeparturePreview.toJson(): JsonObject =
    obj("eta_text" to etaText, "route_text" to routeText, "congestion_text" to congestionText)

/** 날씨 줄 값: 출발·도착이 같으면 "비, 주행 내내", 한쪽만이면 "비"(임시 문구, 디자인 확정 대기). */
fun weatherDetail(key: String, weatherDep: String, weatherArr: String, c: EffortConstants): String? {
    val dep = weatherDep == key
    val arr = weatherArr == key
    if (!dep && !arr) return null
    val t = c.copy.lineDetail.getValue(if (dep && arr) "weather_all_text" else "weather_part_text")
    return fill(t, "precip" to c.copy.precipWords.getValue(key))
}

/** "+25분"·"50분"·"2시간 넘긴 10분". {time}은 운전 시간과 같은 "○시간 ○분" 표기(명세 2절). */
fun timeDetail(templateKey: String, minutesS: Double, c: EffortConstants): String =
    fill(
        c.copy.lineDetail.getValue(templateKey),
        "limit" to driveText(c.fatigueLimitMin * 60), "time" to driveText(minutesS),
    )

/** 줄 근거 한 줄(명세 v0.5 AC-F23-4). {pct}는 그 줄의 가산율(ratesPct)로 채워 숫자가 상수와 어긋나지 않게 한다. */
fun basisText(key: String, c: EffortConstants): String? {
    val t = c.copy.basis[key] ?: return null
    val rate = c.ratesPct[key] ?: return t
    return fill(t, "pct" to roundHalfUp(rate).toString())
}

/** 천 원 어림. 0이면 '1천 원 미만'(명세 v0.5 2절). */
fun approxText(krw: Double): Pair<Int, String> {
    val a = approx(krw, 1000)
    return a.value to (if (a.under) a.text else "약 " + a.text)
}

// ------------------------------------------------------------ F22 도착 상한 (#68)
/** 경과 상한(초). 기본 배치에서는 도착 시각이 '앱을 다시 연 시각'이라 늦게 잡힐 수 있다(#68).
 *  경과가 max(ETA × 2, ETA + 60분)를 넘거나 12시간을 넘으면 정리하지 못함. ETA를 모르면(0 이하) 12시간만. */
fun elapsedLimitS(etaS: Double, c: EffortConstants): Double {
    var cap = c.arrivalMaxMin * 60
    if (etaS > 0) cap = min(max(etaS * c.arrivalEtaTimes, etaS + c.arrivalEtaPlusMin * 60), cap)
    return cap
}

/** 정리하지 못함: 금액·줄은 계산하지 않는다. headlineText에도 안내 문구를 넣어,
 *  status를 보기 전의 화면이 이 값을 그대로 보여도 잘못된 영수증이 뜨지 않게 한다. */
fun unsettled(elapsed: Double, limit: Double, c: EffortConstants): EffortReceipt {
    val t = c.copy.text.getValue("unsettled_text")
    return EffortReceipt(
        status = "unsettled", statusText = t, elapsedLimitMin = limit / 60,
        elapsedMin = elapsed / 60, driveMin = 0.0, restMin = 0.0, fatigueSegmentsMin = emptyList(),
        congestionMin = 0.0, delayMin = 0.0, nightMin = 0.0, rainMin = 0.0, snowMin = 0.0, fatigueExcessMin = 0.0,
        effortMin = 0.0, multiplier = 0.0, lines = emptyList(), notes = emptyList(), footnoteText = null,
        driveText = "", multiplierText = "", headlineText = t, distanceText = null, proposal = null,
    )
}

// ------------------------------------------------------------ F23 노고 · F24 금액 · F25 방식 B
fun effort(inp: EffortInput, c: EffortConstants): EffortReceipt {
    val dep = epochS(inp.departedAt).toDouble()
    val arr = max(dep, epochS(inp.arrivedAt).toDouble())
    val elapsed = arr - dep
    val limit = elapsedLimitS(inp.etaS, c)
    if (elapsed > limit) return unsettled(elapsed, limit, c)
    val nights = nightIntervals(inp.departedAt, arr, inp.sunriseMin, inp.sunsetMin)

    // 정차는 도착 때 답한 쉰 시간 Q만 안다(#62). 언제 쉬었는지 모르니 Q ≥ 15분이면 "중간쯤 한 번 쉬었다"고 보고
    // 운전 시간을 반으로 나눈다(#47). 밤길은 [출발, 도착]과 밤이 겹친 분 × (T ÷ E).
    val q = inp.restAnswerMin.toDouble()
    val drive = max(0.0, elapsed - q * 60)
    val groups = if (q >= c.fatigueResetRestMin) listOf(drive / 2, drive / 2) else listOf(drive)
    var nightAll = 0.0
    for ((a, b) in nights) nightAll += overlap(dep, arr, a, b)
    val night = if (elapsed > 0) nightAll * drive / elapsed else 0.0

    val congestion = when {
        inp.congestionS != null -> max(0.0, min(inp.congestionS, drive))            // 구간 목록이 있으면 통과 시간 합
        inp.congestionRatio != null -> max(0.0, min(inp.congestionRatio * drive, drive))   // 기본: 정체 비율 × T
        else -> 0.0
    }
    val eta = inp.etaS
    var delay = if (eta > 0) max(0.0, drive - eta) else 0.0
    if (delay * 100 < c.delayThresholdPct * eta) delay = 0.0
    val half = drive / 2
    val rain = (if (inp.weatherDep == "rain") half else 0.0) + (if (inp.weatherArr == "rain") half else 0.0)
    val snow = (if (inp.weatherDep == "snow") half else 0.0) + (if (inp.weatherArr == "snow") half else 0.0)
    val limitS = c.fatigueLimitMin * 60
    var excess = 0.0
    for (g in groups) excess += max(0.0, g - limitS)

    val r = c.ratesPct
    val adds = mapOf(   // %·초
        "rain" to r.getValue("rain") * rain,
        "snow" to r.getValue("snow") * snow,
        "congestion" to r.getValue("congestion") * congestion,
        "delay" to r.getValue("delay") * delay,
        "night" to r.getValue("night") * night,
        "fatigue" to c.fatigueRatePct * excess,
    )
    var total = 100 * drive
    val lines = mutableListOf<EffortLine>()
    for (k in EFFORT_LINE_ORDER) {
        val a = adds.getValue(k)
        total += a
        if (a > 0) {
            val pct = roundHalfUp(a / drive)
            val detail = when (k) {
                "rain", "snow" -> weatherDetail(k, inp.weatherDep, inp.weatherArr, c)
                "congestion" -> congestionText(inp.congestionM, inp.congestionRatio, c)
                "delay" -> timeDetail("delay_text", delay, c)
                "night" -> timeDetail("night_text", night, c)
                else -> timeDetail("fatigue_text", excess, c)   // 구간이 여러 개면 넘긴 시간의 합
            }
            lines.add(EffortLine(k, c.copy.lineLabels.getValue(k), a / 6000, pct, pctText(pct), detail, basisText(k, c)))
        }
    }
    val m100 = if (drive > 0) roundHalfUp(total / drive) else 100

    val notes = mutableListOf<String>()
    if (inp.weatherDep !in KNOWN_PRECIP || inp.weatherArr !in KNOWN_PRECIP) notes.add(c.copy.text.getValue("weather_unknown_text"))
    if (inp.congestionS == null && inp.congestionRatio == null) notes.add(c.copy.text.getValue("congestion_unknown_text"))

    val dt = driveText(drive)
    val mt = multiplierText(m100, c)
    return EffortReceipt(
        status = "ok", statusText = null, elapsedLimitMin = limit / 60,
        elapsedMin = elapsed / 60, driveMin = drive / 60, restMin = (elapsed - drive) / 60,
        fatigueSegmentsMin = groups.map { it / 60 },
        congestionMin = congestion / 60, delayMin = delay / 60, nightMin = night / 60,
        rainMin = rain / 60, snowMin = snow / 60, fatigueExcessMin = excess / 60,
        effortMin = total / 6000, multiplier = m100 / 100.0, lines = lines, notes = notes,
        footnoteText = if (lines.any { it.key == "congestion" }) c.copy.text.getValue("footnote_congestion_text") else null,
        driveText = dt, multiplierText = mt,
        headlineText = fill(c.copy.text.getValue("headline_text"), "drive" to dt, "multiplier" to mt),
        distanceText = inp.distanceKm?.let { kmText(it * 1000) },   // 0km 대신 "1km 미만"
        proposal = if (inp.n >= 2) effortProposal(total, inp.n, c) else null,
    )
}

/** 1분 단가(원, 표기용) = 시급 ÷ 60을 원 단위로. 금액 계산은 반올림 전 시급을 그대로 쓴다. */
fun wagePerMin(c: EffortConstants): Int = roundHalfUp(c.wagePerHour / 60)

/** F24 금액(방식 A)과 F25 한 번 빼 주기(방식 B). 같은 노고 금액을 보여 주는 방식만 다르고, 두 화면은 따로다. */
fun effortProposal(effortPctS: Double, n: Int, c: EffortConstants): EffortProposal {
    val amount = effortPctS * c.wagePerHour / 360_000
    val per = amount / n
    val share = amount * (n - 1) / n
    val (aV, aT) = approxText(amount)
    val (pV, pT) = approxText(per)
    val item = bItem(share, c.items)
    val perMin = wagePerMin(c)
    val t = c.copy.text
    return EffortProposal(
        amountKrw = amount, amountApprox = aV, amountText = aT,
        perPersonKrw = per, perPersonApprox = pV, perPersonText = pT,
        driverShareKrw = share,
        a = EffortScreenA(
            titleText = t.getValue("a_title_text"),
            mainText = fill(t.getValue("a_main_text"), "per_person" to pT),
            basisText = fill(t.getValue("a_basis_text"), "amount" to aT, "n" to n.toString()),
            wageText = fill(t.getValue("a_wage_text"), "wage_label" to c.wageLabel, "wage_per_min" to perMin.toString()),
        ),
        b = item?.let { EffortScreenB(it.key, fill(t.getValue("b_text"), "phrase" to it.phraseText), it.examples) },
    )
}

/** 1인분 가격이 운전자 몫 G에 가장 가까운 항목. 같은 거리면 비싼 쪽(명세 9절 경계 '이상').
 *  G가 가장 싼 항목 1인분의 절반보다 작으면 제안 없음. */
fun bItem(share: Double, items: List<EffortItem>): EffortItem? {
    if (items.isEmpty() || share * 2 < items[0].priceKrw) return null
    var best: EffortItem? = null
    var bestD = 0.0
    for (it in items) {
        val d = abs(it.priceKrw - share)
        if (best == null || d <= bestD) {
            best = it
            bestD = d
        }
    }
    return best
}

// ------------------------------------------------------------ 일출·일몰 (F22·F23)
private fun rad(x: Double): Double = x * PI / 180.0

private fun deg(x: Double): Double = x * 180.0 / PI

/** 균시차(분)·적위(도). NOAA Solar Calculator(Meeus 근사). */
private fun sunEqtimeDecl(jd: Double): Pair<Double, Double> {
    val t = (jd - 2451545.0) / 36525.0
    var l0 = 280.46646 + t * (36000.76983 + t * 0.0003032)
    l0 = l0 - 360.0 * floor(l0 / 360.0)
    val m = 357.52911 + t * (35999.05029 - 0.0001537 * t)
    val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
    val mr = rad(m)
    val c = (sin(mr) * (1.914602 - t * (0.004817 + 0.000014 * t)) + sin(2 * mr) * (0.019993 - 0.000101 * t)
        + sin(3 * mr) * 0.000289)
    val omega = 125.04 - 1934.136 * t
    val appLong = l0 + c - 0.00569 - 0.00478 * sin(rad(omega))
    val eps0 = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
    val eps = eps0 + 0.00256 * cos(rad(omega))
    val decl = deg(asin(sin(rad(eps)) * sin(rad(appLong))))
    var y = tan(rad(eps / 2))
    y = y * y
    val l0r = rad(l0)
    val eqt = 4 * deg(y * sin(2 * l0r) - 2 * e * sin(mr) + 4 * e * y * sin(mr) * cos(2 * l0r)
        - 0.5 * y * y * sin(4 * l0r) - 1.25 * e * e * sin(2 * mr))
    return eqt to decl
}

/** 그 날 0시(UTC)부터 분. 남중에서 시작해 그 시각의 태양 위치로 3번 고쳐 계산한다. */
private fun sunEventUtcMin(jd0: Double, lat: Double, lng: Double, rising: Boolean): Double {
    var t = 720.0 - 4.0 * lng
    repeat(3) {
        val (eqt, decl) = sunEqtimeDecl(jd0 + t / 1440.0)
        val cosHa = (cos(rad(SUN_ZENITH_DEG)) / (cos(rad(lat)) * cos(rad(decl)))
            - tan(rad(lat)) * tan(rad(decl)))
        val ha = deg(acos(min(1.0, max(-1.0, cosHa))))
        t = 720.0 - 4.0 * (if (rising) lng + ha else lng - ha) - eqt
    }
    return t
}

/** 그 지점·날짜(현지)의 일출·일몰, 그 날 0시부터 분(분 단위 반올림). 엔진 두 벌이 같은 값을 쓴다(명세 7절). */
fun sunTimes(lat: Double, lng: Double, day: String, tzOffsetMin: Int = 540): Pair<Int, Int> {
    val jd0 = LocalDate.parse(day).toEpochDay() + 2440587.5
    val rise = sunEventUtcMin(jd0, lat, lng, true) + tzOffsetMin
    val set = sunEventUtcMin(jd0, lat, lng, false) + tzOffsetMin
    return roundHalfUp(rise) to roundHalfUp(set)
}

// ------------------------------------------------------------ JSON (fixtures/effort 와 같은 snake_case)
fun parseEffortInput(o: JsonObject): EffortInput = EffortInput(
    departedAt = o.str("departed_at"), arrivedAt = o.str("arrived_at"), etaS = o.dbl("eta_s"),
    weatherDep = o.str("weather_dep"), weatherArr = o.str("weather_arr"),
    sunriseMin = parseHhmm(o.str("sunrise")), sunsetMin = parseHhmm(o.str("sunset")), n = o.integer("n"),
    restAnswerMin = o.integerOrNull("rest_answer_min") ?: 0,
    congestionRatio = o.dblOrNull("congestion_ratio"),
    congestionM = o.dblOrNull("congestion_m"),
    congestionS = o.dblOrNull("congestion_s"),
    distanceKm = o.dblOrNull("distance_km"),
)

fun EffortLine.toJson(): JsonObject = obj(
    "key" to key, "label" to label, "add_min" to addMin, "pct" to pct, "pct_text" to pctText,
    "detail_text" to detailText, "basis_text" to basisText,
)

fun EffortScreenA.toJson(): JsonObject = obj(
    "title_text" to titleText, "main_text" to mainText, "basis_text" to basisText, "wage_text" to wageText,
)

fun EffortScreenB.toJson(): JsonObject = obj("item" to item, "text" to text, "item_examples" to itemExamples)

fun EffortProposal.toJson(): JsonObject = obj(
    "amount_krw" to amountKrw, "amount_approx" to amountApprox, "amount_text" to amountText,
    "per_person_krw" to perPersonKrw, "per_person_approx" to perPersonApprox, "per_person_text" to perPersonText,
    "driver_share_krw" to driverShareKrw, "a" to a.toJson(), "b" to b?.toJson(),
)

fun EffortReceipt.toJson(): JsonObject = obj(
    "status" to status, "status_text" to statusText, "elapsed_limit_min" to elapsedLimitMin,
    "elapsed_min" to elapsedMin, "drive_min" to driveMin, "rest_min" to restMin,
    "fatigue_segments_min" to fatigueSegmentsMin,
    "congestion_min" to congestionMin, "delay_min" to delayMin, "night_min" to nightMin,
    "rain_min" to rainMin, "snow_min" to snowMin, "fatigue_excess_min" to fatigueExcessMin,
    "effort_min" to effortMin, "multiplier" to multiplier,
    "lines" to lines.map { it.toJson() }, "notes" to notes, "footnote_text" to footnoteText,
    "drive_text" to driveText, "multiplier_text" to multiplierText, "headline_text" to headlineText,
    "distance_text" to distanceText, "proposal" to proposal?.toJson(),
)
