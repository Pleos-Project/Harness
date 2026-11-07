package com.mist.pleos_app.engine

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
val EFFORT_LINE_LABEL: Map<String, String> = mapOf(
    "rain" to "날씨", "snow" to "날씨", "congestion" to "정체 구간", "delay" to "예상보다 더",
    "night" to "밤길", "fatigue" to "장시간",
)
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
)

data class EffortConstants(
    val ratesPct: Map<String, Double>,      // rain·snow·congestion·delay·night (정수 %)
    val delayThresholdPct: Double,          // 예상보다 더 문턱(ETA의 %)
    val fatigueLimitMin: Double,            // 장시간: 이 분을 넘은 만큼
    val fatigueRatePct: Double,             // 넘은 분 × 이 %
    val fatigueResetRestMin: Double,        // 이 분 이상 쉬면 연속 운전 구간을 나눈다
    val wagePerHour: Double,                // 1분 단가 = 시급 ÷ 60
    val wageLabel: String,                  // 단가 출처 표기
    val items: List<EffortItem>,            // 방식 B 항목, 1인분 가격 오름차순
    val basis: Map<String, String>,         // 줄 key → 근거 한 줄(AC-F23-4). {pct}는 ratesPct로 채운다
    val detail: Map<String, String>,        // 줄 값 템플릿(weather_both_text 등). {precip}·{time}·{limit}
    val precipWords: Map<String, String>,   // rain → 비, snow → 눈
    val footnoteCongestion: String,         // 정체 줄이 있을 때 영수증 아래 주석
)

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
        wagePerHour = e.dbl("wage_per_hour"),
        wageLabel = e.str("wage_label"),
        items = e.getValue("items").jsonArray.map {
            val i = it.jsonObject
            EffortItem(i.str("key"), i.str("label"), i.dbl("price_krw"), i.str("phrase_text"), i.strList("examples"))
        }.sortedBy { it.priceKrw },
        basis = e["basis"]?.takeIf { it !is JsonNull }?.jsonObject?.mapValues { (_, v) -> v.jsonPrimitive.content }
            ?: emptyMap(),
        detail = e.getValue("detail").jsonObject.mapValues { (_, v) -> v.jsonPrimitive.content },
        precipWords = e.getValue("precip_words").jsonObject.mapValues { (_, v) -> v.jsonPrimitive.content },
        footnoteCongestion = e.str("footnote_congestion_text"),
    )
}

// ------------------------------------------------------------ 입력·출력
/** 정차 시작 = 기어 P·운전석 문(안내 중, 남은 거리 > 0), 없으면 onStop. 끝 = 재시동 뒤 기어 D, 없으면 onStart. ISO 8601 + 오프셋. */
data class EffortStop(val start: String, val end: String)

data class EffortInput(
    val departedAt: String,                 // ISO 8601 + 오프셋
    val arrivedAt: String,
    val etaS: Double,                       // onRouteStarted duration
    val weatherDep: String,                 // none | rain | snow | unknown
    val weatherArr: String,
    val sunriseMin: Int,                    // 출발 지점·날짜의 일출·일몰(그 날 0시부터 분, sunTimes 결과)
    val sunsetMin: Int,
    val n: Int,                             // 나눠 낼 인원(운전자 포함)
    // 정차(명세 v0.5 F21, #58). 앱은 둘 중 하나를 채운다. 둘 다 오면 쉰 지점을 아는 정차 목록으로 계산한다.
    val restAnswerMin: Int? = null,         // 기본: 도착 때 답한 쉰 시간 Q(0·15·30·60)
    val stops: List<EffortStop>? = null,    // 보조: 나란히 배치에서 자동으로 잰 정차 목록. 빈 목록 = 정차 없음
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
    val method: String,                 // answer(기본: 쉰 시간 답) | stops(보조: 자동으로 잰 정차 목록)
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

/** [출발, 도착] − 정차들. 정차는 [출발, 도착] 안으로 자르고 겹치거나 맞닿으면 합친다. */
fun driveSegments(dep: Double, arr: Double, stops: List<EffortStop>): List<Pair<Double, Double>> {
    val clipped = mutableListOf<Pair<Double, Double>>()
    for (st in stops) {
        val s = max(dep, epochS(st.start).toDouble())
        val e = min(arr, epochS(st.end).toDouble())
        if (e > s) clipped.add(s to e)
    }
    val sorted = clipped.sortedWith(compareBy<Pair<Double, Double>>({ it.first }, { it.second }))
    val merged = mutableListOf<Pair<Double, Double>>()
    for ((s, e) in sorted) {
        if (merged.isNotEmpty() && s <= merged.last().second) {
            merged[merged.size - 1] = merged.last().first to max(merged.last().second, e)
        } else {
            merged.add(s to e)
        }
    }
    val segs = mutableListOf<Pair<Double, Double>>()
    var cur = dep
    for ((s, e) in merged) {
        if (s > cur) segs.add(cur to s)
        cur = e
    }
    if (arr > cur) segs.add(cur to arr)
    return segs
}

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

fun multiplierText(m100: Int): String =
    if (m100 <= 100) "평소와 같은 길"
    else "평소보다 ${m100 / 100}.${(m100 % 100).toString().padStart(2, '0')}배 힘든 길"

fun pctText(pct: Int): String = if (pct > 0) "+$pct%" else "+1% 미만"

/** 거리 m → "104km"(명세 2절 km 정수). 0으로 어림되면 "1km 미만". */
fun kmText(m: Double): String {
    val km = roundHalfUp(m / 1000)
    return if (km > 0) "${km}km" else "1km 미만"
}

/** 정체 구간 줄 "38km (경로의 36%)"(노고 PRD v1.3 5-1, 열린 결정 28). km·비율을 모르면 null. */
fun congestionText(congestionM: Double?, ratio: Double?): String? {
    if (congestionM == null || ratio == null) return null
    val p = roundHalfUp(ratio * 100)
    val pT = if (p > 0) "경로의 $p%" else "경로의 1% 미만"
    return "${kmText(congestionM)} ($pT)"
}

/** 출발 화면 미리보기(명세 v0.5 F20·12절 시연 순서 2). requestTrip 값으로 만든다. */
data class DeparturePreview(
    val etaText: String,                // "1시간 45분"
    val routeText: String,              // "104km"
    val congestionText: String?,        // "38km (경로의 36%)". 비율을 모르거나 0이면 null(정체 표시 없음)
)

fun departurePreview(etaS: Double, routeM: Double, congestionRatio: Double?, congestionM: Double?): DeparturePreview {
    val has = congestionRatio != null && congestionRatio > 0
    return DeparturePreview(
        etaText = driveText(etaS),
        routeText = kmText(routeM),
        congestionText = if (has) congestionText(congestionM, congestionRatio) else null,
    )
}

fun DeparturePreview.toJson(): JsonObject =
    obj("eta_text" to etaText, "route_text" to routeText, "congestion_text" to congestionText)

/** 날씨 줄 값: 출발·도착이 같으면 "비, 주행 내내", 한쪽만이면 "비, 주행 앞 절반"·"비, 주행 뒤 절반"([제안]). */
fun weatherDetail(key: String, weatherDep: String, weatherArr: String, c: EffortConstants): String? {
    val dep = weatherDep == key
    val arr = weatherArr == key
    val t = when {
        dep && arr -> c.detail.getValue("weather_both_text")
        dep -> c.detail.getValue("weather_first_text")
        arr -> c.detail.getValue("weather_second_text")
        else -> return null
    }
    return t.replace("{precip}", c.precipWords.getValue(key))
}

/** "+25분"·"50분"·"2시간 넘긴 10분". {time}은 운전 시간과 같은 "○시간 ○분" 표기(명세 2절). */
fun timeDetail(templateKey: String, minutesS: Double, c: EffortConstants): String =
    c.detail.getValue(templateKey).replace("{limit}", driveText(c.fatigueLimitMin * 60))
        .replace("{time}", driveText(minutesS))

/** 줄 근거 한 줄(명세 v0.5 AC-F23-4). {pct}는 그 줄의 가산율(ratesPct)로 채워 숫자가 상수와 어긋나지 않게 한다. */
fun basisText(key: String, c: EffortConstants): String? {
    val t = c.basis[key] ?: return null
    val rate = c.ratesPct[key] ?: return t
    return t.replace("{pct}", roundHalfUp(rate).toString())
}

/** 천 원 어림. 0이면 '1천 원 미만'(명세 v0.5 2절). */
fun approxText(krw: Double): Pair<Int, String> {
    val a = approx(krw, 1000)
    return a.value to (if (a.under) a.text else "약 " + a.text)
}

// ------------------------------------------------------------ F23 노고 · F24 금액 · F25 방식 B
fun effort(inp: EffortInput, c: EffortConstants): EffortReceipt {
    val dep = epochS(inp.departedAt).toDouble()
    val arr = max(dep, epochS(inp.arrivedAt).toDouble())
    val elapsed = arr - dep
    val nights = nightIntervals(inp.departedAt, arr, inp.sunriseMin, inp.sunsetMin)
    val resetS = c.fatigueResetRestMin * 60

    val method: String
    val drive: Double
    val groups = mutableListOf<Double>()
    val night: Double
    if (inp.stops != null) {
        // 보조(나란히 배치): 주행 구간을 안다. 15분 이상 정차에서 장시간 구간을 나누고, 짧은 정차는 시간만 뺀다.
        method = "stops"
        val segs = driveSegments(dep, arr, inp.stops)
        var d = 0.0
        for ((i, seg) in segs.withIndex()) {
            val (s, e) = seg
            if (i == 0 || s - segs[i - 1].second >= resetS) groups.add(0.0)
            groups[groups.size - 1] = groups[groups.size - 1] + (e - s)
            d += e - s
        }
        drive = d
        var nt = 0.0
        for ((s, e) in segs) for ((a, b) in nights) nt += overlap(s, e, a, b)
        night = nt
    } else {
        // 기본(도착 때 묻기): 쉰 총시간만 안다. 15분 이상이면 "중간쯤 한 번 쉬었다"고 보고 반으로 나눈다(#47).
        method = "answer"
        val q = (inp.restAnswerMin ?: 0).toDouble()
        drive = max(0.0, elapsed - q * 60)
        if (q >= c.fatigueResetRestMin) {
            groups.add(drive / 2)
            groups.add(drive / 2)
        } else {
            groups.add(drive)
        }
        var all = 0.0
        for ((a, b) in nights) all += overlap(dep, arr, a, b)
        night = if (elapsed > 0) all * drive / elapsed else 0.0
    }

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
                "congestion" -> congestionText(inp.congestionM, inp.congestionRatio)
                "delay" -> timeDetail("delay_text", delay, c)
                "night" -> timeDetail("night_text", night, c)
                else -> timeDetail("fatigue_text", excess, c)   // 구간이 여러 개면 넘긴 시간의 합
            }
            lines.add(EffortLine(k, EFFORT_LINE_LABEL.getValue(k), a / 6000, pct, pctText(pct), detail, basisText(k, c)))
        }
    }
    val m100 = if (drive > 0) roundHalfUp(total / drive) else 100

    val notes = mutableListOf<String>()
    if (inp.weatherDep !in KNOWN_PRECIP || inp.weatherArr !in KNOWN_PRECIP) notes.add("날씨 정보 없음")
    if (inp.congestionS == null && inp.congestionRatio == null) notes.add("정체 정보 없음")

    val dt = driveText(drive)
    val mt = multiplierText(m100)
    return EffortReceipt(
        method = method, elapsedMin = elapsed / 60, driveMin = drive / 60, restMin = (elapsed - drive) / 60,
        fatigueSegmentsMin = groups.map { it / 60 },
        congestionMin = congestion / 60, delayMin = delay / 60, nightMin = night / 60,
        rainMin = rain / 60, snowMin = snow / 60, fatigueExcessMin = excess / 60,
        effortMin = total / 6000, multiplier = m100 / 100.0, lines = lines, notes = notes,
        footnoteText = if (lines.any { it.key == "congestion" }) c.footnoteCongestion else null,
        driveText = dt, multiplierText = mt, headlineText = "오늘 운전 $dt · $mt",
        distanceText = inp.distanceKm?.let { "${roundHalfUp(it)}km" },
        proposal = if (inp.n >= 2) effortProposal(total, inp.n, c) else null,
    )
}

/** F24 금액(방식 A)과 F25 한 번 빼 주기(방식 B). 같은 노고 금액을 보여 주는 방식만 다르고, 두 화면은 따로다. */
fun effortProposal(effortPctS: Double, n: Int, c: EffortConstants): EffortProposal {
    val amount = effortPctS * c.wagePerHour / 360_000
    val per = amount / n
    val share = amount * (n - 1) / n
    val (aV, aT) = approxText(amount)
    val (pV, pT) = approxText(per)
    val item = bItem(share, c.items)
    val perMin = roundHalfUp(c.wagePerHour / 60)
    return EffortProposal(
        amountKrw = amount, amountApprox = aV, amountText = aT,
        perPersonKrw = per, perPersonApprox = pV, perPersonText = pT,
        driverShareKrw = share,
        a = EffortScreenA(
            titleText = "오늘 운전을 챙긴다면",
            mainText = "한 사람 $pT",
            basisText = "운전 노고 ${aT}을 운전자 포함 ${n}명이 나눈 값",
            wageText = "${c.wageLabel} 1분 ${perMin}원 기준",
        ),
        b = item?.let { EffortScreenB(it.key, "오늘은 ${it.phraseText}에서 운전자 빼고 나누는 게 어때요", it.examples) },
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
    stops = o["stops"]?.takeIf { it !is JsonNull }?.jsonArray?.map {
        val s = it.jsonObject
        EffortStop(s.str("start"), s.str("end"))
    },
    restAnswerMin = o.integerOrNull("rest_answer_min"),
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
    "method" to method, "elapsed_min" to elapsedMin, "drive_min" to driveMin, "rest_min" to restMin,
    "fatigue_segments_min" to fatigueSegmentsMin,
    "congestion_min" to congestionMin, "delay_min" to delayMin, "night_min" to nightMin,
    "rain_min" to rainMin, "snow_min" to snowMin, "fatigue_excess_min" to fatigueExcessMin,
    "effort_min" to effortMin, "multiplier" to multiplier,
    "lines" to lines.map { it.toJson() }, "notes" to notes, "footnote_text" to footnoteText,
    "drive_text" to driveText, "multiplier_text" to multiplierText, "headline_text" to headlineText,
    "distance_text" to distanceText, "proposal" to proposal?.toJson(),
)
