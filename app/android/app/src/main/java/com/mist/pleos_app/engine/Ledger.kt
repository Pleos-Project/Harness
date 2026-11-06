package com.mist.pleos_app.engine

import java.time.Duration
import java.time.OffsetDateTime
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * F8·F10·F17 로컬 장부 — server/chagok/engine.py ledger_step·month_summary 를 옮긴 것.
 * state는 JSON으로 그대로 저장할 수 있게 ChagokJson.kt 에 직렬화가 있다(명세 9절 snapshot·settlement·trip).
 *
 * 이벤트
 *   Open   — 앱 실행·복귀(기어 P)
 *   Depart — F8 출발 1탭
 *   Arrive — F17 도착(기어 P + 운전석 문 / onDestinationArrived, 앱이 보일 때)
 * 규칙
 *   - Open: 직전 스냅샷 이후 주행거리로 정산. 진행 중 trip이 있으면 이 정산이 trip을 닫고 목적지는 trip 장소
 *     (실차는 도착 때 앱이 가려져 Arrive를 못 받는 경우가 많다).
 *   - Arrive: trip 출발 대비 Δodo. 0이면(에뮬) 편도 추정 + demo. 스냅샷 갱신 → 다음 Open과 중복 없음.
 *   - 핀: 직전 핀(정산·도착 모두)과 같은 장소(이름 같음 또는 300m 안)면 금액만 쌓는다(AC-F10-6).
 */

/** id는 출발 때의 장소 ID. 기록(row)·핀에는 이름·좌표만 남긴다. */
data class Place(val name: String, val lat: Double?, val lng: Double?, val id: String? = null)

data class Trip(val place: Place, val routeKm: Double, val startOdometerKm: Double, val departedTs: String)

data class Row(
    val type: String,              // "settle" | "arrive"
    val fromTs: String,
    val toTs: String,
    val km: Double,
    val krw: Int,
    val perPerson: Int?,
    val place: Place?,
    val pinned: Boolean,
    val demo: Boolean,             // true → 화면에 "시연용 값"
)

data class LedgerState(
    val snapshot: Snapshot? = null,
    val trip: Trip? = null,
    val rows: List<Row> = emptyList(),
    val lastPin: Place? = null,
)

sealed class LedgerEvent {
    abstract val ts: String

    data class Open(
        override val ts: String,
        val odometerKm: Double,
        val seats: Int,
        val price: Double,
        val efficiency: Double,
        val priceSource: String? = "user",
        val recentDestination: Place? = null,
    ) : LedgerEvent()

    data class Depart(override val ts: String, val odometerKm: Double, val place: Place, val routeKm: Double) : LedgerEvent()

    /** [priceSource]는 Open과 같다: 앱 내장 기본값 단가면 "default" → 도착이 남긴 스냅샷으로 다음 정산할 때도 "(평균 단가)". */
    data class Arrive(
        override val ts: String,
        val odometerKm: Double,
        val seats: Int,
        val price: Double,
        val efficiency: Double,
        val priceSource: String? = "user",
    ) : LedgerEvent()
}

/** card = 정산 카드 문구(없으면 null), row = 새 기록, skipped = 정산을 건너뛴 이유. */
data class LedgerOutput(val card: String?, val row: Row?, val skipped: String?)

data class MonthSummary(val krw: Int, val places: Int, val demo: Boolean, val text: String)

private const val DEG = PI / 180

private fun distKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val la1 = lat1 * DEG
    val la2 = lat2 * DEG
    val dla = la2 - la1
    val dlo = (lng2 - lng1) * DEG
    val h = sin(dla / 2).pow(2) + cos(la1) * cos(la2) * sin(dlo / 2).pow(2)
    return 2 * 6371.0 * asin(sqrt(h))
}

fun samePlace(a: Place?, b: Place?): Boolean {
    if (a == null || b == null) return false
    if (a.name.isNotEmpty() && a.name == b.name) return true
    val aLat = a.lat ?: return false
    val aLng = a.lng ?: return false
    val bLat = b.lat ?: return false
    val bLng = b.lng ?: return false
    return distKm(aLat, aLng, bLat, bLng) < PIN_SAME_KM
}

private fun daysBetween(from: String, to: String): Int {
    val sec = Duration.between(OffsetDateTime.parse(from), OffsetDateTime.parse(to)).seconds
    return max(0L, Math.floorDiv(sec, 86_400L)).toInt()
}

private fun placeOf(p: Place): Place = Place(p.name, p.lat, p.lng)

private fun snapshotOf(ev: LedgerEvent.Open) =
    Snapshot(ev.odometerKm, ev.seats, ev.price, ev.efficiency, ev.ts, ev.priceSource ?: "user")

private fun snapshotOf(ev: LedgerEvent.Arrive) =
    Snapshot(ev.odometerKm, ev.seats, ev.price, ev.efficiency, ev.ts, ev.priceSource ?: "user")

fun ledgerStep(state: LedgerState, event: LedgerEvent): Pair<LedgerState, LedgerOutput> = when (event) {
    is LedgerEvent.Depart -> Pair(
        state.copy(trip = Trip(event.place, event.routeKm, event.odometerKm, event.ts)),
        LedgerOutput(null, null, null),
    )
    is LedgerEvent.Open -> onOpen(state, event)
    is LedgerEvent.Arrive -> onArrive(state, event)
}

private fun onOpen(st: LedgerState, ev: LedgerEvent.Open): Pair<LedgerState, LedgerOutput> {
    val prev = st.snapshot ?: return Pair(st.copy(snapshot = snapshotOf(ev)), LedgerOutput(null, null, "no_snapshot"))
    val trip = st.trip
    val dest = if (trip != null) placeOf(trip.place) else ev.recentDestination?.let { placeOf(it) }
    val s = settle(prev, ev.odometerKm, dest?.name, daysBetween(prev.ts, ev.ts))
    if (s.skipped) {
        // 콘솔 오입력·초기화로 주행거리가 줄면 기준을 지금으로 다시 잡는다
        val next = if (s.reason == "odometer_decreased") st.copy(snapshot = snapshotOf(ev)) else st
        return Pair(next, LedgerOutput(null, null, s.reason))
    }
    val pinned = dest != null && !samePlace(dest, st.lastPin)
    val row = Row("settle", prev.ts, ev.ts, s.km, s.krw, s.perPerson, dest, pinned, false)
    return Pair(
        LedgerState(snapshotOf(ev), null, st.rows + row, if (pinned) dest else st.lastPin),
        LedgerOutput(s.text, row, null),
    )
}

private fun onArrive(st: LedgerState, ev: LedgerEvent.Arrive): Pair<LedgerState, LedgerOutput> {
    val trip = st.trip ?: return Pair(st, LedgerOutput(null, null, "no_trip"))
    val delta = ev.odometerKm - trip.startOdometerKm
    val demo = delta <= 0
    val km = if (demo) trip.routeKm else delta
    val krw = approx(km / ev.efficiency * ev.price, 1000).value
    val dest = placeOf(trip.place)
    val pinned = !samePlace(dest, st.lastPin)
    val row = Row("arrive", trip.departedTs, ev.ts, km, krw, null, dest, pinned, demo)
    return Pair(
        LedgerState(snapshotOf(ev), null, st.rows + row, if (pinned) dest else st.lastPin),
        LedgerOutput(null, row, null),
    )
}

/** F17 "이번 달 이동비 약 7만 원 · 다녀온 곳 3곳". month = "YYYY-MM", 기록의 toTs 기준. */
fun monthSummary(rows: List<Row>, month: String): MonthSummary {
    val mine = rows.filter { it.toTs.take(7) == month }
    if (mine.isEmpty()) return MonthSummary(0, 0, false, "첫 드라이브를 기다리는 중")
    val a = approx(mine.sumOf { it.krw }.toDouble(), 1000)
    val places = mine.count { it.pinned }
    return MonthSummary(a.value, places, mine.any { it.demo },
        "이번 달 이동비 ${if (a.under) a.text else "약 ${a.text}"} · 다녀온 곳 ${places}곳")
}
