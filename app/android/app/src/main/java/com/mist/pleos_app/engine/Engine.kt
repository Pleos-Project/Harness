package com.mist.pleos_app.engine

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.floor
import kotlin.math.max

/*
 * 차곡 엔진 — server/chagok/engine.py 를 그대로 옮긴 Kotlin판(앱 로컬 계산).
 *
 * 규칙
 * - Python 쪽을 바꾸면 여기도 같은 PR에서 바꾼다. 둘 다 fixtures/ 를 통과해야 한다
 *   (서버 pytest, 앱 ./gradlew test 의 FixturesTest).
 * - 반올림은 roundHalfUp / roundTo / round1 만 쓴다. kotlin.math.round()·Math.rint 는 0.5를 짝수 쪽으로 보내서
 *   1원 단위로 서버와 어긋난다(파이썬 round()와 같은 함정).
 * - 곱셈·나눗셈 순서를 Python과 같게 적는다(부동소수 결과가 순서에 따라 달라진다).
 *
 * 단위: 거리 km, 금액 원(정수), 전비 km/kWh, 연비 km/L(내연 SDK값 L/100km는 lPer100kmToKmPerL로 환산).
 */

const val ROAD_FACTOR = 1.3
const val MARGIN_MIN_KM = 50.0
const val MARGIN_RATIO = 0.15
const val OVERNIGHT_KM = 30.0
const val TIGHT_LOW = 0.90
const val OFFICIAL_TO_REAL = 0.85
val RING_KRW: List<Int> = listOf(0, 10_000, 30_000)
const val MAX_SWAPS = 2
const val NEAR_MINUTES = 75
const val FAR_MINUTES = 90
const val PIN_SAME_KM = 0.3
val AVG_PRICE_SOURCES: Set<String> = setOf("default")

val THEME_KO: Map<String, String> = mapOf(
    "sea" to "바다", "lake" to "호수", "river" to "강", "mountain" to "산",
    "park" to "공원", "city" to "도시", "island" to "섬", "drive" to "드라이브길",
    "indoor" to "실내", "festival" to "축제",
)

// ------------------------------------------------------------ 숫자·문구
fun roundHalfUp(x: Double): Int = floor(x + 0.5).toInt()

fun roundTo(n: Double, unit: Int): Int = (floor((n + unit / 2.0) / unit) * unit).toInt()

fun round1(x: Double): Double = floor(x * 10 + 0.5) / 10

fun lPer100kmToKmPerL(v: Double): Double = if (v > 0) 100.0 / v else 0.0

fun krwText(n: Int): String {
    val man = n / 10_000
    val cheon = (n % 10_000) / 1_000
    val parts = mutableListOf<String>()
    if (man != 0) parts.add("${man}만")
    if (cheon != 0) parts.add("${cheon}천")
    return parts.joinToString(" ").ifEmpty { "0" } + " 원"
}

/** unit 단위 어림. 0으로 떨어지면 '{unit} 미만'(화면에 '0 원'을 내지 않는다). */
data class Approx(val value: Int, val text: String, val under: Boolean)

fun approx(n: Double, unit: Int): Approx {
    val r = roundTo(n, unit)
    return if (r > 0) Approx(r, krwText(r), false) else Approx(0, "${krwText(unit)} 미만", true)
}

// ------------------------------------------------------------ 입력
data class VehicleSpec(val fuelType: String, val capacity: Double, val officialEfficiency: Double)

/** fixtures/constants.json — 빌드 때 assets/data/constants.json 으로 복사된다(syncChagokData). */
data class Constants(
    val demoSeed: String,
    val originLat: Double,
    val originLng: Double,
    val gasolineKrwPerL: Double,
    val homeChargeKrwPerKwh: Double,
    val fastChargeKrwPerKwh: Double,
    val vehicles: Map<String, VehicleSpec>,
    val ranking: RankingConfig,     // constants.json "ranking" — A1·B 순위 값(서버 engine.py RankingConfig와 같은 출처)
)

data class Vehicle(
    val fuelType: String,          // "ev" | "ice"
    val levelPct: Double,          // SOC % 또는 연료 %
    val capacity: Double,          // kWh 또는 L
    val efficiency: Double,        // km/kWh 또는 km/L (이미 실연비)
    val pricePerUnit: Double,      // 원/kWh 또는 원/L
    val remainingRangeM: Double = 0.0,
    val priceSource: String = "user", // user | default(앱 내장 기본값 → "평균 단가")
)

/** F1 입력 우선순위: 용량 SDK > 차종 표, 연비 SDK(실연비) > 차종 표 × 0.85, 단가 설정 > 앱 기본값. */
fun vehicleInputs(
    constants: Constants,
    model: String,
    levelPct: Double,
    remainingRangeM: Double = 0.0,
    capacitySdk: Double? = null,
    efficiencySdk: Double? = null,
    price: Double? = null,
): Vehicle {
    val spec = constants.vehicles.getValue(model)
    val ev = spec.fuelType == "ev"
    val p = price ?: if (ev) constants.homeChargeKrwPerKwh else constants.gasolineKrwPerL
    val source = if (price == null) "default" else "user"
    return Vehicle(
        fuelType = spec.fuelType,
        levelPct = levelPct,
        capacity = if (capacitySdk != null && capacitySdk != 0.0) capacitySdk else spec.capacity,
        efficiency = if (efficiencySdk != null && efficiencySdk != 0.0) efficiencySdk else spec.officialEfficiency * OFFICIAL_TO_REAL,
        pricePerUnit = p,
        remainingRangeM = remainingRangeM,
        priceSource = source,
    )
}

// ------------------------------------------------------------ E1 잔고·범위 (앱 로컬)
/** /v1/cards 로 보내는 값. 원시 차량값이 아니라 로컬 계산 결과(파생값)만(PRD 5-2, 7-1). */
data class Budget(
    val fuelType: String,
    val usableKm: Double,          // 잔고 km − 안전 여유 − (1박 30)
    val marginKm: Double,
    val efficiency: Double,
    val pricePerUnit: Double,
    val priceSource: String = "user",
)

data class RangeResult(
    val balanceKm: Double,
    val balanceKrw: Int,
    val marginKm: Double,
    val usableKm: Double,
    val radiusKm: Double,
    val rangeSource: String,       // "range_remaining" | "assembled"
    val zeroAvailable: Boolean,    // false → 추가 0원 비활성, 기본 +1만 (AC-F2-2)
    val balanceText: String,       // "배터리에 1만 9천 원어치 · 약 326km" (단가 출처는 화면에 따로 작게)
)

fun balanceKm(v: Vehicle): Pair<Double, String> =
    if (v.remainingRangeM > 0) Pair(v.remainingRangeM / 1000.0, "range_remaining")
    else Pair(v.levelPct / 100.0 * v.capacity * v.efficiency, "assembled")

fun extraKm(b: Budget, extraKrw: Int): Double =
    if (extraKrw != 0) extraKrw / b.pricePerUnit * b.efficiency else 0.0

fun radiusKm(b: Budget, extraKrw: Int): Double =
    max(0.0, (b.usableKm + extraKm(b, extraKrw)) / 2.0 / ROAD_FACTOR)

fun computeRange(v: Vehicle, extraKrw: Int = 0, days: Int = 1): RangeResult {
    val (km, src) = balanceKm(v)
    val a = approx(v.levelPct / 100.0 * v.capacity * v.pricePerUnit, 1000)
    val margin = max(MARGIN_MIN_KM, km * MARGIN_RATIO)
    val usable = km - margin - (if (days == 2) OVERNIGHT_KM else 0.0)
    val b = Budget(v.fuelType, usable, margin, v.efficiency, v.pricePerUnit, v.priceSource)
    val where = if (v.fuelType == "ev") "배터리에" else "탱크에"
    val amount = if (a.under) a.text else "${a.text}어치"
    return RangeResult(km, a.value, margin, usable, radiusKm(b, extraKrw), src, usable > 0,
        "$where $amount · 약 ${roundHalfUp(km)}km")
}

fun budgetOf(r: RangeResult, v: Vehicle): Budget =
    Budget(v.fuelType, r.usableKm, r.marginKm, v.efficiency, v.pricePerUnit, v.priceSource)

// ------------------------------------------------------------ E3 비용·배지
data class Candidate(
    val id: String,
    val name: String,
    val theme: String,
    val lat: Double,
    val lng: Double,
    val routeKm: Double,           // 편도 도로 거리
    val minutes: Int,              // 편도
    val tollKrw: Int = 0,          // 편도
    val parkingKrw: Int? = null,   // null → 정보 없음
    val hasParkingInfo: Boolean = false,
    // 실서비스 후보만 갖는 값(데모 후보는 비어 있음 → 점수·이유·카드가 지금 그대로)
    val lcls3: String? = null,      // TourAPI 신분류 소분류 코드 → 목적지성·실내 여부
    val eventStart: String? = null, // 행사 시작일 YYYY-MM-DD(축제만)
    val eventEnd: String? = null,
    val reputation: Double? = null, // A2 큐레이션 평판 1~5(근거 없음 = null). B 순위만 쓴다
    val intro: String? = null,      // A2 한 줄 소개(근거 있는 것만). B 이유 한 줄
    val months: List<Int>? = null,  // A2 '좋은 시기'(월). 없으면 연중
)

data class Cost(
    val fuel: Int, val toll: Int, val parking: Int?, val total: Int,
    val totalText: String, val perPerson: Int?, val perPersonText: String?,
)

fun computeCost(c: Candidate, b: Budget, seats: Int): Cost {
    val fuel = roundHalfUp(2 * c.routeKm / b.efficiency * b.pricePerUnit)
    val toll = 2 * c.tollKrw
    val parking = if (c.hasParkingInfo) c.parkingKrw else null
    val total = fuel + toll + (parking ?: 0)
    val t = approx(total.toDouble(), 5000)
    var pp: Int? = null
    var ppText: String? = null
    if (seats >= 2) {
        val a = approx(total.toDouble() / seats, 1000)
        pp = a.value
        ppText = if (a.under) "1인 ${a.text}" else "1인 ${a.text} 안팎"
    }
    return Cost(fuel, toll, parking, total, if (t.under) t.text else "${t.text} 안팎", pp, ppText)
}

data class Badge(val zeroExtra: Boolean, val tight: Boolean, val ringKrw: Int?, val text: String, val marginKm: Int)

fun computeBadge(c: Candidate, b: Budget): Badge {
    val rt = 2 * c.routeKm
    val ring = RING_KRW.firstOrNull { rt <= b.usableKm + extraKm(b, it) }
    val zero = ring == 0
    val tight = zero && rt >= b.usableKm * TIGHT_LOW
    val margin = roundHalfUp(b.marginKm)
    val text = when {
        tight -> "빠듯"                                  // 경계에서는 0원을 약속하지 않는다(PRD 5-3)
        zero -> "추가 ${if (b.fuelType == "ev") "충전" else "주유"} 없이 · 여유 ${margin}km"
        ring != null -> "+${krwText(ring)} 안팎"
        else -> "범위 밖"
    }
    return Badge(zero, tight, ring, text, margin)
}

fun timeLabel(minutes: Int): String? = when {
    minutes <= NEAR_MINUTES -> "75분 안"
    minutes <= FAR_MINUTES -> "조금 먼 곳"
    else -> null                                        // 90분 초과는 카드로 내지 않는다
}

// ------------------------------------------------------------ E2 점수·이유
/** 기존 5항목 + A1(quality·weather) + B(reputation). ctx 없이 매기면 새 항목은 null(기존 규칙 그대로). */
data class Score(
    val zero: Double, val time: Double, val theme: Double, val diversity: Double, val parking: Double,
    val quality: Double? = null, val weather: Double? = null, val reputation: Double? = null,
)

private const val W_ZERO = 0.30
private const val W_TIME = 0.25
private const val W_THEME = 0.20
private const val W_DIVERSITY = 0.15
private const val W_PARKING = 0.10

/**
 * A1 점수(목적지성·날씨)와 B(A2 평판)의 값. fixtures/constants.json "ranking" 이 단일 출처이고
 * 서버 engine.py RankingConfig 도 같은 파일을 읽는다. 기존 5항목 가중(W_*)은 코드에 그대로 둔다(데모·PT 결과 고정).
 */
data class RankingConfig(
    val weights: Map<String, Double>,       // quality · weather · reputation
    val qualityDefault: Double,             // 소분류를 모름(데모 후보) → 중립
    val quality: Map<String, Double>,       // 소분류 코드 → 목적지성 0~1
    val subcategoryKo: Map<String, String>, // 소분류 코드 → 이유 한 줄·카드에 쓰는 이름
    val penaltyWords: List<String>,         // 이름에 들어 있으면 목적지성 × penaltyFactor
    val penaltyFactor: Double,
    val excludeNames: List<String>,         // 이름에 들어 있으면 후보에서 뺀다
    val excludeSubcategories: List<String>,
    val excludePrefixes: List<String>,      // 예: EV02(공연 전체)
    val longEventDays: Int,                 // 행사 기간이 이보다 길면 목적지성 × longEventFactor
    val longEventFactor: Double,
    val indoor: Set<String>,                // 실내 소분류
    val indoorClear: Double,                // 맑은 날 실내의 날씨 적합도(비 1이면 1)
    val rainPhrase: String,                 // 비 오는 날 실내 카드 이유 한 줄
    val reputationMissing: Double,          // A2 근거 없음 → 중간보다 낮게
    val offSeasonFactor: Double,            // '좋은 시기'가 아닌 달 → 평판 × 이 값
    val rainHint: Hint,                     // 비 예보인데 야외 테마를 고른 경우 응답 hint
)

/**
 * 요청 하나의 순위 조건. variant: a1 = 규칙(목적지성·날씨), b = a1 + A2 평판·소개.
 * rain: 후보 id → 나들이 날 비 정도(0 · 0.5 · 1, 없으면 0). tripDate: 나들이 날 YYYY-MM-DD(없으면 행사 날짜를 보지 않음).
 */
data class RankContext(
    val cfg: RankingConfig,
    val rain: Map<String, Double> = emptyMap(),
    val tripDate: String? = null,
    val variant: String = "a1",
) {
    fun rainOf(c: Candidate): Double = rain[c.id] ?: 0.0
    val month: Int? get() = tripDate?.substring(5, 7)?.toInt()
}

/** 행사 기간(일). YYYY-MM-DD. */
private fun eventDays(a: String, b: String): Long = ChronoUnit.DAYS.between(LocalDate.parse(a), LocalDate.parse(b))

fun isIndoor(c: Candidate, cfg: RankingConfig): Boolean = c.lcls3 != null && c.lcls3 in cfg.indoor

/** 후보에서 아예 빼는 것: 이름(놀이터 등), 소분류(스포츠경기), 소분류 접두어(공연 전체). */
fun excluded(c: Candidate, cfg: RankingConfig): Boolean {
    if (cfg.excludeNames.any { c.name.contains(it) }) return true
    val l = c.lcls3 ?: return false
    return l in cfg.excludeSubcategories || cfg.excludePrefixes.any { l.startsWith(it) }
}

/** 행사는 나들이 날(1박이면 둘째 날까지) 열리는 것만. 장소·나들이 날 모름 → 통과. */
fun eventActive(c: Candidate, tripDate: String?, days: Int = 1): Boolean {
    val start = c.eventStart
    val end = c.eventEnd
    if (start.isNullOrEmpty() || end.isNullOrEmpty() || tripDate == null) return true
    if (start <= tripDate && tripDate <= end) return true
    if (days == 2) {
        val d2 = LocalDate.parse(tripDate).plusDays(1).toString()
        return start <= d2 && d2 <= end
    }
    return false
}

/** 목적지성 0~1: 소분류 값 × (이름 감점) × (장기 행사 감점). 소분류를 모르면 중립. */
fun quality(c: Candidate, cfg: RankingConfig): Double {
    val l = c.lcls3 ?: return cfg.qualityDefault
    var q = cfg.quality[l] ?: cfg.qualityDefault
    if (cfg.penaltyWords.any { c.name.contains(it) }) q = q * cfg.penaltyFactor
    val start = c.eventStart
    val end = c.eventEnd
    if (!start.isNullOrEmpty() && !end.isNullOrEmpty() && eventDays(start, end) > cfg.longEventDays) q = q * cfg.longEventFactor
    return q
}

/** 야외 = 1 − 비, 실내 = 맑은 날 indoorClear → 비 1이면 1. 맑은 날에는 야외가 앞선다(나들이 앱). */
fun weatherFit(c: Candidate, cfg: RankingConfig, rain: Double): Double =
    if (isIndoor(c, cfg)) cfg.indoorClear + (1.0 - cfg.indoorClear) * rain else 1.0 - rain

/** B만: (평판 − 1) ÷ 4, 근거 없음 = reputationMissing. '좋은 시기'가 아닌 달이면 × offSeasonFactor. */
fun reputationFit(c: Candidate, cfg: RankingConfig, month: Int?): Double {
    var r = c.reputation?.let { (it - 1.0) / 4.0 } ?: cfg.reputationMissing
    val ms = c.months
    if (!ms.isNullOrEmpty() && month != null && month !in ms) r = r * cfg.offSeasonFactor
    return r
}

fun score(c: Candidate, b: Badge, themes: List<String>, recentThemes: List<String>, ctx: RankContext? = null): Score {
    val zero = if (b.zeroExtra && !b.tight) 1.0 else if (b.tight) 0.5 else 0.0
    val time = when {
        c.minutes <= NEAR_MINUTES -> 1.0
        c.minutes <= FAR_MINUTES -> 1.0 - (c.minutes - NEAR_MINUTES) / 15.0
        else -> 0.0
    }
    val theme = if (themes.isEmpty()) 0.5 else if (c.theme in themes) 1.0 else 0.0
    // [명준 확인] 최근 방문이 없어도 1.0 → 이유에 "지난번과 다른 테마"가 붙는다(AC-F5-1 문구 그대로)
    val diversity = if (c.theme in recentThemes) 0.0 else 1.0
    val parking = if (c.hasParkingInfo) 1.0 else 0.0
    if (ctx == null) return Score(zero, time, theme, diversity, parking)
    return Score(
        zero, time, theme, diversity, parking,
        quality = quality(c, ctx.cfg),
        weather = weatherFit(c, ctx.cfg, ctx.rainOf(c)),
        reputation = if (ctx.variant == "b") reputationFit(c, ctx.cfg, ctx.month) else null,
    )
}

fun weighted(s: Score, cfg: RankingConfig? = null): Double {
    var total = 0.0
    total += W_ZERO * s.zero
    total += W_TIME * s.time
    total += W_THEME * s.theme
    total += W_DIVERSITY * s.diversity
    total += W_PARKING * s.parking
    if (cfg != null) {
        s.quality?.let { total += cfg.weights.getValue("quality") * it }
        s.weather?.let { total += cfg.weights.getValue("weather") * it }
        s.reputation?.let { total += cfg.weights.getValue("reputation") * it }
    }
    return floor(total * 10_000 + 0.5) / 10_000
}

/**
 * 점수 상위 근거 최대 3개. 사실이 아닌 문구는 점수가 있어도 쓰지 않는다(빠듯·+1만에 "추가 0원 안", 75분 넘는 카드에 "75분 안", 주차).
 * 실서비스 후보(소분류 있음): 소분류 이름이 맨 앞(테마 이름 대신), 비 오는 날 실내면 rainPhrase, 나머지는 위 규칙대로.
 * B: A2 한 줄 소개가 있으면 그것(근거 URL을 확인한 것만 저장됨).
 */
fun reasonLine(c: Candidate, s: Score, b: Badge, ctx: RankContext? = null): String {
    if (ctx != null && ctx.variant == "b" && !c.intro.isNullOrEmpty()) return c.intro
    if (ctx != null && c.lcls3 != null) {
        val cfg = ctx.cfg
        val phrases = mutableListOf(cfg.subcategoryKo[c.lcls3]?.takeIf { it.isNotEmpty() } ?: (THEME_KO[c.theme] ?: c.theme))
        if (isIndoor(c, cfg) && ctx.rainOf(c) >= 0.5) phrases.add(cfg.rainPhrase)
        val keyed = listOf("zero" to W_ZERO * s.zero, "time" to W_TIME * s.time, "diversity" to W_DIVERSITY * s.diversity)
            .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenByDescending { it.first })
        for ((key, v) in keyed) {
            if (phrases.size == 3) break
            if (v <= 0) continue
            if (key == "zero" && !(b.zeroExtra && !b.tight)) continue
            if (key == "time" && c.minutes > NEAR_MINUTES) continue
            val phrase = when (key) {
                "zero" -> "추가 0원 안"
                "time" -> "75분 안"
                else -> "지난번과 다른 테마"
            }
            if (phrase !in phrases) phrases.add(phrase)
        }
        return phrases.joinToString(" · ")
    }
    val contrib = listOf(
        "zero" to W_ZERO * s.zero, "time" to W_TIME * s.time,
        "theme" to W_THEME * s.theme, "diversity" to W_DIVERSITY * s.diversity,
    ).sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenByDescending { it.first })
    val themeKo = THEME_KO[c.theme] ?: c.theme
    val phrases = mutableListOf<String>()
    for ((key, v) in contrib) {
        if (v <= 0) continue
        if (key == "zero" && !(b.zeroExtra && !b.tight)) continue
        if (key == "time" && c.minutes > NEAR_MINUTES) continue
        val phrase = when (key) {
            "zero" -> "추가 0원 안"
            "time" -> "75분 안"
            "theme" -> themeKo
            else -> "지난번과 다른 테마"
        }
        if (phrase !in phrases) phrases.add(phrase)
        if (phrases.size == 3) break
    }
    if (phrases.size < 3 && themeKo !in phrases) phrases.add(themeKo)
    return phrases.joinToString(" · ")
}

data class CardCost(val fuel: Int, val toll: Int, val parking: Int?, val total: Int, val perPerson: Int?)

data class CardDisplay(
    val totalText: String, val perPersonText: String?, val zeroExtra: Boolean, val tight: Boolean,
    val ringKrw: Int?, val badgeText: String, val marginKm: Int,
)

/**
 * 계약(contract/openapi.yaml)의 Card. 온라인에서는 서버 display 문구를 그대로 쓴다.
 * 실서비스 후보만 갖는 값(subcategory·indoor·eventStart·eventEnd·rainLevel)은 있을 때만 JSON에 넣는다
 * → 데모 응답(fixtures/cards/D*.json)은 키가 그대로다.
 */
data class Card(
    val id: String, val name: String, val theme: String, val lat: Double, val lng: Double,
    val routeKm: Double, val minutes: Int, val timeLabel: String?,
    val cost: CardCost, val display: CardDisplay, val reason: String,
    val subcategory: String? = null,
    val indoor: Boolean? = null,
    val eventStart: String? = null,
    val eventEnd: String? = null,
    val rainLevel: Double? = null,
)

fun cardOf(c: Candidate, cost: Cost, badge: Badge, reason: String, ctx: RankContext? = null): Card {
    val l = c.lcls3
    val cfg = ctx?.cfg
    val hasEvent = !c.eventStart.isNullOrEmpty() && !c.eventEnd.isNullOrEmpty()
    val rain = ctx?.rainOf(c) ?: 0.0
    return Card(
        c.id, c.name, c.theme, c.lat, c.lng, c.routeKm, c.minutes, timeLabel(c.minutes),
        CardCost(cost.fuel, cost.toll, cost.parking, cost.total, cost.perPerson),
        CardDisplay(cost.totalText, cost.perPersonText, badge.zeroExtra, badge.tight, badge.ringKrw, badge.text, badge.marginKm),
        reason,
        subcategory = if (cfg != null && l != null) cfg.subcategoryKo[l] ?: l else null,
        indoor = if (cfg != null && l != null) isIndoor(c, cfg) else null,
        eventStart = if (hasEvent) c.eventStart else null,
        eventEnd = if (hasEvent) c.eventEnd else null,
        rainLevel = if (ctx != null && rain > 0) rain else null,
    )
}

// ------------------------------------------------------------ E4 정산 · F9 충전
data class Snapshot(
    val odometerKm: Double,
    val seats: Int,
    val pricePerUnit: Double,
    val efficiency: Double,
    val ts: String,
    val priceSource: String? = "user",
)

data class Settlement(val km: Double, val krw: Int, val perPerson: Int?, val text: String, val skipped: Boolean, val reason: String?)

/** F10. 직전 스냅샷의 연비·단가·착석으로 계산(직전 정산 시점 기준). */
fun settle(prev: Snapshot?, odometerNow: Double, lastDestination: String?, spanDays: Int = 0): Settlement {
    fun skip(reason: String) = Settlement(0.0, 0, null, "", true, reason)
    if (prev == null) return skip("no_snapshot")
    val delta = odometerNow - prev.odometerKm
    if (delta < 0) return skip("odometer_decreased")
    if (delta == 0.0) return skip("no_distance")
    val a = approx(delta / prev.efficiency * prev.pricePerUnit, 1000)
    val pp = if (prev.seats >= 2) approx(delta / prev.efficiency * prev.pricePerUnit / prev.seats, 1000).value else null
    val head = if (spanDays >= 1) "지난번 이후 ${spanDays}일 · " else "지난번 이후 "
    var text = "$head${roundHalfUp(delta)}km · ${if (a.under) a.text else "약 ${a.text}"}"
    if (AVG_PRICE_SOURCES.contains(prev.priceSource ?: "")) text += " (평균 단가)"
    if (!lastDestination.isNullOrEmpty()) text += " · 마지막 목적지 $lastDestination"
    return Settlement(delta, a.value, pp, text, false, null)
}

data class ChargeResult(val kwh: Double, val krw: Int, val text: String)

fun chargeAmount(startSoc: Double, endSoc: Double, capacityKwh: Double, price: Double): ChargeResult {
    val kwh = max(0.0, endSoc - startSoc) / 100.0 * capacityKwh
    val a = approx(kwh * price, 1000)
    return ChargeResult(kwh, a.value, if (a.under) "방금 ${a.text} 충전" else "방금 약 ${a.text}어치 충전")
}

data class FullTankResult(val liters: Double, val krw: Int, val text: String)

/**
 * F9 내연 금액 시트의 '가득'(PRD v1.3 F9: 3만 / 5만 / 가득 + 1만 원 단위 ±). Python full_tank_amount 와 같은 수식.
 * 주유 시작 때 연료 %에서 100%까지 채우는 양 × 단가. 천 원 어림 "약 7만 원"(그랜저 HEV 50L·25%·1,856원, fixture D11), 어림이 0이면 "1천 원 미만"(PRD 5-3).
 */
fun fullTankAmount(startLevelPct: Double, tankL: Double, price: Double): FullTankResult {
    val liters = max(0.0, 100.0 - startLevelPct) / 100.0 * tankL
    val a = approx(liters * price, 1000)
    return FullTankResult(liters, a.value, if (a.under) a.text else "약 ${a.text}")
}

// ------------------------------------------------------------ 화면 helper (Python 짝 없음)
/**
 * 테마 일러스트 변형 고르기(design/RULES.md 3절): 같은 장소는 늘 같은 그림.
 * 반환은 확장자 없는 이름 "<theme>-<n>"(n = 1..variants). SVG·VectorDrawable 등 리소스 연결은 화면 쪽에서.
 */
fun illustrationName(theme: String, placeId: String, variants: Int = 1): String {
    var h = 0
    for (ch in placeId) h = (h + ch.code) % 9973
    return "$theme-${h % maxOf(1, variants) + 1}"
}
