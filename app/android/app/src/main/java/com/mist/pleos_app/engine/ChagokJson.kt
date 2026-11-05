package com.mist.pleos_app.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * 엔진 타입 ↔ JSON. 필드 이름은 계약(contract/openapi.yaml)·fixtures 와 같은 snake_case.
 * 앱에서 쓰는 곳: assets/data/constants.json·candidates_demo.json 읽기, /v1/cards 요청·응답, 장부 state 저장.
 */

// ------------------------------------------------------------ 읽기 helpers
internal fun parseObject(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

internal fun JsonObject.str(k: String): String = getValue(k).jsonPrimitive.content
internal fun JsonObject.strOrNull(k: String): String? = this[k]?.jsonPrimitive?.contentOrNull
internal fun JsonObject.dbl(k: String): Double = getValue(k).jsonPrimitive.double
internal fun JsonObject.dblOrNull(k: String): Double? = this[k]?.jsonPrimitive?.doubleOrNull
internal fun JsonObject.integer(k: String): Int = getValue(k).jsonPrimitive.int
internal fun JsonObject.integerOrNull(k: String): Int? = this[k]?.jsonPrimitive?.intOrNull
internal fun JsonObject.bool(k: String): Boolean = getValue(k).jsonPrimitive.boolean
internal fun JsonObject.objOrNull(k: String): JsonObject? = this[k]?.takeIf { it !is JsonNull }?.jsonObject
internal fun JsonObject.strList(k: String): List<String> =
    this[k]?.takeIf { it !is JsonNull }?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

// ------------------------------------------------------------ 쓰기 helpers
internal fun j(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is List<*> -> JsonArray(v.map { j(it) })
    else -> throw IllegalArgumentException("json: ${v::class}")
}

internal fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(linkedMapOf(*pairs.map { it.first to j(it.second) }.toTypedArray()))

// ------------------------------------------------------------ 상수·후보 (assets/data)
fun parseConstants(text: String): Constants {
    val o = parseObject(text)
    val origin = o.getValue("origin").jsonObject
    val price = o.getValue("price").jsonObject
    val vehicles = o.getValue("vehicles").jsonObject.mapValues { (_, v) ->
        val s = v.jsonObject
        VehicleSpec(s.str("fuel_type"), s.dbl("capacity"), s.dbl("official_efficiency"))
    }
    return Constants(
        demoSeed = o.str("demo_seed"),
        originLat = origin.dbl("lat"),
        originLng = origin.dbl("lng"),
        gasolineKrwPerL = price.dbl("gasoline_krw_per_l"),
        homeChargeKrwPerKwh = price.dbl("home_charge_krw_per_kwh"),
        fastChargeKrwPerKwh = price.dbl("fast_charge_krw_per_kwh"),
        vehicles = vehicles,
    )
}

fun parseCandidates(text: String): List<Candidate> =
    parseObject(text).getValue("candidates").jsonArray.map { e ->
        val c = e.jsonObject
        Candidate(
            id = c.str("id"), name = c.str("name"), theme = c.str("theme"),
            lat = c.dbl("lat"), lng = c.dbl("lng"), routeKm = c.dbl("route_km"), minutes = c.integer("minutes"),
            tollKrw = c.integerOrNull("toll_krw") ?: 0, parkingKrw = c.integerOrNull("parking_krw"),
            hasParkingInfo = c["has_parking_info"]?.jsonPrimitive?.let { it.contentOrNull == "true" } ?: false,
        )
    }

// ------------------------------------------------------------ /v1/cards
fun Budget.toJson(): JsonObject = obj(
    "fuel_type" to fuelType, "usable_km" to usableKm, "margin_km" to marginKm,
    "efficiency" to efficiency, "price_per_unit" to pricePerUnit, "price_source" to priceSource,
)

fun parseBudget(o: JsonObject): Budget = Budget(
    o.str("fuel_type"), o.dbl("usable_km"), o.dbl("margin_km"), o.dbl("efficiency"), o.dbl("price_per_unit"),
    o.strOrNull("price_source") ?: "user",
)

/** 서버로 보낼 본문. session_id는 있을 때만 넣는다. */
fun CardsRequest.toJson(): JsonObject {
    val pairs = mutableListOf<Pair<String, Any?>>(
        "origin" to obj("lat" to originLat, "lng" to originLng),
        "budget" to budget.toJson(),
        "seats_occupied" to seatsOccupied, "extra_krw" to extraKrw, "days" to days,
        "themes" to themes, "recent_visits" to recentVisits, "demo_seed" to demoSeed,
    )
    if (sessionId != null) pairs.add("session_id" to sessionId)
    return obj(*pairs.toTypedArray())
}

fun parseCardsRequest(o: JsonObject): CardsRequest {
    val origin = o.getValue("origin").jsonObject
    return CardsRequest(
        originLat = origin.dbl("lat"), originLng = origin.dbl("lng"),
        budget = parseBudget(o.getValue("budget").jsonObject),
        seatsOccupied = o.integerOrNull("seats_occupied") ?: 1,
        extraKrw = o.integerOrNull("extra_krw") ?: 0,
        days = o.integerOrNull("days") ?: 1,
        themes = o.strList("themes"), recentVisits = o.strList("recent_visits"),
        demoSeed = o.strOrNull("demo_seed"), sessionId = o.strOrNull("session_id"),
    )
}

fun Card.toJson(): JsonObject = obj(
    "id" to id, "name" to name, "theme" to theme, "lat" to lat, "lng" to lng,
    "route_km" to routeKm, "minutes" to minutes, "time_label" to timeLabel,
    "cost" to obj(
        "fuel" to cost.fuel, "toll" to cost.toll, "parking" to cost.parking,
        "total" to cost.total, "per_person" to cost.perPerson,
    ),
    "display" to obj(
        "total_text" to display.totalText, "per_person_text" to display.perPersonText,
        "zero_extra" to display.zeroExtra, "tight" to display.tight, "ring_krw" to display.ringKrw,
        "badge_text" to display.badgeText, "margin_km" to display.marginKm,
    ),
    "reason" to reason,
)

fun parseCard(o: JsonObject): Card {
    val cost = o.getValue("cost").jsonObject
    val d = o.getValue("display").jsonObject
    return Card(
        o.str("id"), o.str("name"), o.str("theme"), o.dbl("lat"), o.dbl("lng"),
        o.dbl("route_km"), o.integer("minutes"), o.strOrNull("time_label"),
        CardCost(cost.integer("fuel"), cost.integer("toll"), cost.integerOrNull("parking"), cost.integer("total"), cost.integerOrNull("per_person")),
        CardDisplay(
            d.str("total_text"), d.strOrNull("per_person_text"), d.bool("zero_extra"), d.bool("tight"),
            d.integerOrNull("ring_krw"), d.str("badge_text"), d.integer("margin_km"),
        ),
        o.str("reason"),
    )
}

fun Hint.toJson(): JsonObject = obj("code" to code, "text" to text, "actions" to actions)

/** maskSession이 있으면 session_id를 그 값으로 바꿔 쓴다(fixture 대조용 "<session>"). */
fun CardsResponse.toJson(maskSession: String? = null): JsonObject = obj(
    "session_id" to (if (sessionId != null && maskSession != null) maskSession else sessionId),
    "radius_km" to radiusKm,
    "candidates_in_range" to candidatesInRange,
    "hint" to hint?.toJson(),
    "picked" to picked?.toJson(),
    "stack" to stack.map { it.toJson() },
)

/** 서버 응답 읽기. 온라인에서는 display 문구를 그대로 쓴다. */
fun parseCardsResponse(o: JsonObject): CardsResponse {
    val hint = o.objOrNull("hint")?.let { Hint(it.str("code"), it.str("text"), it.strList("actions")) }
    return CardsResponse(
        sessionId = o.strOrNull("session_id"),
        radiusKm = o.dbl("radius_km"),
        candidatesInRange = o.integer("candidates_in_range"),
        hint = hint,
        picked = o.objOrNull("picked")?.let { parseCard(it) },
        stack = o.getValue("stack").jsonArray.map { parseCard(it.jsonObject) },
    )
}

// ------------------------------------------------------------ 정산·장부
fun Settlement.toJson(): JsonObject = obj(
    "km" to km, "krw" to krw, "per_person" to perPerson, "text" to text, "skipped" to skipped, "reason" to reason,
)

fun Snapshot.toJson(): JsonObject {
    val pairs = mutableListOf<Pair<String, Any?>>(
        "odometer_km" to odometerKm, "seats" to seats, "price_per_unit" to pricePerUnit,
        "efficiency" to efficiency, "ts" to ts,
    )
    if (priceSource != null) pairs.add("price_source" to priceSource)
    return obj(*pairs.toTypedArray())
}

fun parseSnapshot(o: JsonObject?): Snapshot? = o?.let {
    Snapshot(it.dbl("odometer_km"), it.integer("seats"), it.dbl("price_per_unit"), it.dbl("efficiency"), it.str("ts"), it.strOrNull("price_source"))
}

fun Place.toJson(): JsonObject {
    val pairs = mutableListOf<Pair<String, Any?>>()
    if (id != null) pairs.add("id" to id)
    pairs.add("name" to name)
    pairs.add("lat" to lat)
    pairs.add("lng" to lng)
    return obj(*pairs.toTypedArray())
}

fun parsePlace(o: JsonObject?): Place? = o?.let { Place(it.str("name"), it.dblOrNull("lat"), it.dblOrNull("lng"), it.strOrNull("id")) }

fun Row.toJson(): JsonObject = obj(
    "type" to type, "from_ts" to fromTs, "to_ts" to toTs, "km" to km, "krw" to krw,
    "per_person" to perPerson, "place" to place?.toJson(), "pinned" to pinned, "demo" to demo,
)

fun parseRow(o: JsonObject): Row = Row(
    o.str("type"), o.str("from_ts"), o.str("to_ts"), o.dbl("km"), o.integer("krw"), o.integerOrNull("per_person"),
    parsePlace(o.objOrNull("place")), o.bool("pinned"), o.bool("demo"),
)

fun Trip.toJson(): JsonObject = obj(
    "place" to place.toJson(), "route_km" to routeKm, "start_odometer_km" to startOdometerKm, "departed_ts" to departedTs,
)

fun LedgerState.toJson(): JsonObject = obj(
    "snapshot" to snapshot?.toJson(), "trip" to trip?.toJson(),
    "rows" to rows.map { it.toJson() }, "last_pin" to lastPin?.toJson(),
)

/** 저장해 둔 장부 state 읽기(localStorage/DataStore 등에 toJson() 문자열로 저장). */
fun parseLedgerState(o: JsonObject): LedgerState {
    val trip = o.objOrNull("trip")?.let {
        Trip(parsePlace(it.getValue("place").jsonObject)!!, it.dbl("route_km"), it.dbl("start_odometer_km"), it.str("departed_ts"))
    }
    return LedgerState(
        snapshot = parseSnapshot(o.objOrNull("snapshot")),
        trip = trip,
        rows = o["rows"]?.jsonArray?.map { parseRow(it.jsonObject) } ?: emptyList(),
        lastPin = parsePlace(o.objOrNull("last_pin")),
    )
}

fun parseLedgerEvent(o: JsonObject): LedgerEvent = when (val t = o.str("type")) {
    "open" -> LedgerEvent.Open(
        o.str("ts"), o.dbl("odometer_km"), o.integer("seats"), o.dbl("price"), o.dbl("efficiency"),
        o.strOrNull("price_source"), parsePlace(o.objOrNull("recent_destination")),
    )
    "depart" -> LedgerEvent.Depart(o.str("ts"), o.dbl("odometer_km"), parsePlace(o.getValue("place").jsonObject)!!, o.dbl("route_km"))
    "arrive" -> LedgerEvent.Arrive(o.str("ts"), o.dbl("odometer_km"), o.integer("seats"), o.dbl("price"), o.dbl("efficiency"))
    else -> throw IllegalArgumentException("unknown event type: $t")
}

fun LedgerOutput.toJson(): JsonObject = obj("card" to card, "row" to row?.toJson(), "skipped" to skipped)

fun MonthSummary.toJson(): JsonObject = obj("krw" to krw, "places" to places, "demo" to demo, "text" to text)
