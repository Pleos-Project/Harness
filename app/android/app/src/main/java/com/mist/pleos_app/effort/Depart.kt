package com.mist.pleos_app.effort

import com.mist.pleos_app.engine.DeparturePreview
import com.mist.pleos_app.engine.hhmm
import com.mist.pleos_app.engine.sunTimes
import com.mist.pleos_app.navi.Destination
import com.mist.pleos_app.navi.NaviPlace
import com.mist.pleos_app.navi.TripPreview
import com.mist.pleos_app.net.PlaceResult
import com.mist.pleos_app.net.PlacesStatus

/** 출발 화면 목적지 후보 한 줄: 즐겨찾기·최근 목적지(내비) 또는 검색 결과(서버). */
data class DestOption(val dest: Destination, val kind: Kind, val category: String? = null, val distanceM: Int? = null) {
    enum class Kind { HOME, OFFICE, BOOKMARK, RECENT, SEARCH }

    /** 검색 결과의 현재 위치부터 거리(서버 distance_m) → "104km". 위치를 못 보냈으면 null(정확도순, 거리 칸 비움). */
    val distanceText: String? get() = distanceM?.let { com.mist.pleos_app.engine.kmText(it.toDouble()) }
}

fun NaviPlace.toOption() = DestOption(
    dest,
    when (kind) {
        NaviPlace.Kind.HOME -> DestOption.Kind.HOME
        NaviPlace.Kind.OFFICE -> DestOption.Kind.OFFICE
        NaviPlace.Kind.BOOKMARK -> DestOption.Kind.BOOKMARK
        NaviPlace.Kind.RECENT -> DestOption.Kind.RECENT
    },
)

/** 검색 결과 → 내비 목적지(#57 매핑: name→poiName, address, lat·lng, place_id→poiId). */
fun PlaceResult.toOption() = DestOption(Destination(placeId, name, lat, lng, address), DestOption.Kind.SEARCH, category, distanceM)

/**
 * 출발 화면 상태(명세 v0.5 F20). 좌표는 [DestOption.dest] 안에만 있고 메모리에만 둔다(AC-N-4).
 * @property preview requestTrip 결과. 목적지를 골랐는데 아직 안 왔으면 [previewing]
 */
data class DepartUi(
    val naviOptions: List<DestOption> = emptyList(),
    val naviLoading: Boolean = false,
    val query: String = "",
    val searching: Boolean = false,
    val searchStatus: PlacesStatus? = null,
    val searchResults: List<DestOption> = emptyList(),
    val selected: DestOption? = null,
    val preview: TripPreview? = null,
    val previewing: Boolean = false,
    /** 미리보기 문구(엔진 departurePreview: "1시간 45분"·"104km"·"경로의 36%"). 화면에서 계산하지 않는다. */
    val previewText: DeparturePreview? = null,
    /** 내비로 길을 보내지 못함(#132 · #118 E1 depart-route-fail): 출발 기록 없이 출발 화면에 남아 [다시 시도]. */
    val routeFailed: Boolean = false,
) {
    /** 목적지를 골랐고 미리보기를 기다리는 중이 아니면 [출발]을 누를 수 있다(미리보기가 실패해도 출발은 막지 않음). */
    val canDepart: Boolean get() = selected != null && !previewing
}

/**
 * [출발] 때 남기는 출발 기록(명세 v0.5 F20 출발 기록). 좌표는 넣지 않는다(AC-F20-4).
 * 미리보기가 없으면 ETA 0·정체 null → 엔진이 "예상보다 더" 없음·"정체 정보 없음"으로 계산한다(F20 예외).
 * 일출·일몰은 출발 지점·날짜로 출발 때 계산해 "HH:MM"만 남긴다(#55 June·효성: 도착 때는 앱이 얼어 있을 수 있음).
 * 현재 위치를 못 읽었으면 null → 도착 때 도착 좌표로 계산한다(명세 F23).
 */
fun departTrip(
    tripId: String,
    departedAt: String,
    dest: Destination,
    n: Int,
    preview: TripPreview?,
    weatherDep: String,
    here: Pair<Double, Double>? = null,
): OpenTrip {
    val sun = here?.let { (lat, lng) -> sunTimes(lat, lng, departedAt.take(10)) }
    return OpenTrip(
    tripId = tripId,
    departedAt = departedAt,
    destName = dest.name,
    n = n.coerceAtLeast(1),
    etaS = preview?.etaS?.toDouble() ?: 0.0,
    routeM = preview?.distanceM?.toDouble(),
    congestionRatio = preview?.congestionRatio,
    congestionM = preview?.congestionM,
    weatherDep = weatherDep,
    sunrise = sun?.first?.let(::hhmm),
    sunset = sun?.second?.let(::hhmm),
    // #63: 도착 때 시간대별 날씨 조회에 쓸 출발 지점. 서버로 보내는 것과 같은 반올림 값만 둔다(도착 조회 뒤 삭제)
    depLat = here?.first?.let { com.mist.pleos_app.net.coord(it).toDouble() },
    depLng = here?.second?.let { com.mist.pleos_app.net.coord(it).toDouble() },
    )
}
