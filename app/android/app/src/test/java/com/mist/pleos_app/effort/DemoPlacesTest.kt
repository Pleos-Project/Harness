package com.mist.pleos_app.effort

import com.mist.pleos_app.net.PlacesStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 시연 모드 출발 화면 데이터(June 11/9): 실제와 같은 화면에 채울 최근 목적지 4장 · 검색 결과. */
class DemoPlacesTest {
    private val scene = parseDemoScene(File(locate(), "effort/demo.json").readText())

    @Test
    fun recentsStartWithDemoDestination() {
        val r = demoRecents(scene)
        assertEquals(4, r.size)
        assertEquals(scene.destName, r.first().dest.name)
        assertTrue(r.all { it.dest.lat == 0.0 && it.dest.lng == 0.0 })          // 좌표 없음
    }

    @Test
    fun searchByNameOrAddress() {
        val (ok, list) = demoSearch("강릉", scene)
        assertEquals(PlacesStatus.OK, ok)
        assertEquals(listOf("강릉 안목해변"), list.map { it.dest.name })
        assertEquals(DestOption.Kind.SEARCH, list.first().kind)
        assertEquals(listOf("코엑스", "회사").sorted(), demoSearch("강남 구", scene).second.map { it.dest.name }.sorted())   // 띄어쓰기 무시
        assertEquals(PlacesStatus.EMPTY, demoSearch("없는곳", scene).first)
    }

    @Test
    fun demoTripKeepsNumbersWithPickedName() {
        // 명세 12절: 이름을 바꿔도 배수 · 금액은 그대로 → 엔진 입력은 이름만 다르다
        val t = DemoEffortSource(scene).demoTrip("d")
        val picked = t.copy(destName = "양평 두물머리")
        assertEquals(t.copy(destName = picked.destName), picked)
        assertEquals(t.routeM, picked.routeM)
    }

    private fun locate(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, "fixtures")
            if (f.isDirectory && File(f, "constants.json").exists()) return f
            dir = dir.parentFile
        }
        throw IllegalStateException("fixtures 를 찾지 못함")
    }
}
