package com.mist.pleos_app.effort

import com.mist.pleos_app.navi.Destination
import com.mist.pleos_app.net.PlacesStatus

/*
 * 시연 모드 출발 화면 데이터(June 11/9): 화면 · 기능은 실제와 같고 데이터만 시연용이다.
 * 최근 목적지 카드 4장과 검색 결과를 앱 안 목록에서 꺼낸다(내비 · 서버를 부르지 않음, 좌표 없음).
 * 어느 곳을 골라도 미리보기 · 주행 숫자는 시연 장면(demo.json) 그대로이고 목적지 이름만 바뀐다(명세 12절 "이름과 경로 거리는 바꿔도 배수 · 금액이 그대로").
 * 주소 · 거리는 화면 칸을 채우는 시연용 값이다(화면 구석 "시연용 값", AC-C-7).
 */

private data class DemoPlace(val id: String, val name: String, val address: String, val kind: DestOption.Kind, val distanceM: Int, val recent: Boolean)

private fun places(scene: DemoScene) = listOf(
    DemoPlace("demo-1", scene.destName, "충남 태안군 안면읍", DestOption.Kind.RECENT, 104_000, true),
    DemoPlace("demo-2", "가평 아침고요수목원", "경기 가평군 상면", DestOption.Kind.RECENT, 62_000, true),
    DemoPlace("demo-3", "양평 두물머리", "경기 양평군 양서면", DestOption.Kind.BOOKMARK, 45_000, true),
    DemoPlace("demo-4", "회사", "서울 강남구 테헤란로", DestOption.Kind.OFFICE, 12_000, true),
    DemoPlace("demo-5", "강릉 안목해변", "강원 강릉시 창해로", DestOption.Kind.SEARCH, 228_000, false),
    DemoPlace("demo-6", "양양 서피비치", "강원 양양군 현남면", DestOption.Kind.SEARCH, 196_000, false),
    DemoPlace("demo-7", "코엑스", "서울 강남구 영동대로", DestOption.Kind.SEARCH, 11_000, false),
)

private fun DemoPlace.option(search: Boolean) = DestOption(
    Destination(id, name, 0.0, 0.0, address),
    if (search) DestOption.Kind.SEARCH else kind,
    category = if (search) "시연 장소" else null,
    distanceM = if (search) distanceM else null,
)

/** 출발 화면 최근 목적지 · 즐겨찾기 카드(첫 장 = 시연 장면 목적지). */
fun demoRecents(scene: DemoScene): List<DestOption> = places(scene).filter { it.recent }.map { it.option(false) }

/** 시연 검색: 이름 · 주소에 검색어(띄어쓰기 무시)가 들어간 곳. 없으면 EMPTY. */
fun demoSearch(query: String, scene: DemoScene): Pair<PlacesStatus, List<DestOption>> {
    val q = query.replace(" ", "")
    if (q.isEmpty()) return PlacesStatus.EMPTY to emptyList()
    val hits = places(scene).filter { (it.name + it.address).replace(" ", "").contains(q) }.map { it.option(true) }
    return (if (hits.isEmpty()) PlacesStatus.EMPTY else PlacesStatus.OK) to hits
}
