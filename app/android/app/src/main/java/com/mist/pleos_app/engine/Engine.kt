package com.mist.pleos_app.engine

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

/** zeroText = "추가 주유 없이"(EV "추가 충전 없이") — 배지·이유 한 줄 공통(PRD v1.6 5-3). */
data class Badge(
    val zeroExtra: Boolean, val tight: Boolean, val ringKrw: Int?, val text: String, val marginKm: Int,
    val zeroText: String,
)

fun computeBadge(c: Candidate, b: Budget): Badge {
    val rt = 2 * c.routeKm
    val ring = RING_KRW.firstOrNull { rt <= b.usableKm + extraKm(b, it) }
    val zero = ring == 0
    val tight = zero && rt >= b.usableKm * TIGHT_LOW
    val margin = roundHalfUp(b.marginKm)
    val zeroText = "추가 ${if (b.fuelType == "ev") "충전" else "주유"} 없이"   // '추가 0원'은 내부 조건 이름(PRD v1.6 5-3)
    val text = when {
        tight -> "빠듯"                                  // 경계에서는 0원을 약속하지 않는다(PRD 5-3)
        zero -> "$zeroText · ${margin}km 남기고"          // '남겨 둘 거리'(PRD v1.6 5-3·5-4)
        ring != null -> "+${krwText(ring)} 안팎"
        else -> "범위 밖"
    }
    return Badge(zero, tight, ring, text, margin, zeroText)
}

fun timeLabel(minutes: Int): String? = when {
    minutes <= NEAR_MINUTES -> "75분 안"
    minutes <= FAR_MINUTES -> "조금 먼 곳"
    else -> null                                        // 90분 초과는 카드로 내지 않는다
}

// ------------------------------------------------------------ E2 점수·이유
data class Score(val zero: Double, val time: Double, val theme: Double, val diversity: Double, val parking: Double)

private const val W_ZERO = 0.30
private const val W_TIME = 0.25
private const val W_THEME = 0.20
private const val W_DIVERSITY = 0.15
private const val W_PARKING = 0.10

fun score(c: Candidate, b: Badge, themes: List<String>, recentThemes: List<String>): Score {
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
    return Score(zero, time, theme, diversity, parking)
}

fun weighted(s: Score): Double {
    var total = 0.0
    total += W_ZERO * s.zero
    total += W_TIME * s.time
    total += W_THEME * s.theme
    total += W_DIVERSITY * s.diversity
    total += W_PARKING * s.parking
    return floor(total * 10_000 + 0.5) / 10_000
}

/** 점수 상위 근거 최대 3개. 사실이 아닌 문구는 점수가 있어도 쓰지 않는다(빠듯·+1만에 "추가 0원 안", 75분 넘는 카드에 "75분 안", 주차). */
fun reasonLine(c: Candidate, s: Score, b: Badge): String {
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
            "zero" -> b.zeroText
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

/** 계약(contract/openapi.yaml)의 Card. 온라인에서는 서버 display 문구를 그대로 쓴다. */
data class Card(
    val id: String, val name: String, val theme: String, val lat: Double, val lng: Double,
    val routeKm: Double, val minutes: Int, val timeLabel: String?,
    val cost: CardCost, val display: CardDisplay, val reason: String,
)

fun cardOf(c: Candidate, cost: Cost, badge: Badge, reason: String): Card = Card(
    c.id, c.name, c.theme, c.lat, c.lng, c.routeKm, c.minutes, timeLabel(c.minutes),
    CardCost(cost.fuel, cost.toll, cost.parking, cost.total, cost.perPerson),
    CardDisplay(cost.totalText, cost.perPersonText, badge.zeroExtra, badge.tight, badge.ringKrw, badge.text, badge.marginKm),
    reason,
)

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
