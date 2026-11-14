package com.mist.pleos_app.debug

import com.mist.pleos_app.effort.DemoEffortSource
import com.mist.pleos_app.effort.parseDemoScene
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** 촬영용 도착 신호(debug 전용, 시연 영상 5번 컷): 시연 주행이 가는 중이고 앱이 가려져 있을 때만 시연 도착 시각으로 올린다. */
class ShootArrivalTest {

    private val scene = parseDemoScene(locate("fixtures/effort/demo.json").readText())
    private val demo = DemoEffortSource(scene)
    private val trip = demo.demoTrip("d1")!!.copy(destName = "꽃지해수욕장")

    @Test
    fun postsDemoArrivalWhenHidden() {
        assertEquals(ShootArrival.Post("2026-11-10T18:10:00+09:00", "꽃지해수욕장"), shootArrival(trip, scene.arrivedAt, appVisible = false))
    }

    @Test
    fun skipsOtherwise() {
        assertEquals(ShootArrival.Skip("no_trip"), shootArrival(null, scene.arrivedAt, false))
        assertEquals(ShootArrival.Skip("arrived"), shootArrival(trip.copy(arrivedAt = scene.arrivedAt), scene.arrivedAt, false))
        assertEquals(ShootArrival.Skip("not_demo"), shootArrival(trip.copy(demo = false), scene.arrivedAt, false))
        assertEquals(ShootArrival.Skip("no_demo_data"), shootArrival(trip, null, false))
        assertEquals(ShootArrival.Skip("visible"), shootArrival(trip, scene.arrivedAt, true))
    }

    private fun locate(rel: String): File {
        var d: File? = File("").absoluteFile
        while (d != null) { File(d, rel).takeIf { it.exists() }?.let { return it }; d = d.parentFile }
        error("$rel 없음")
    }
}
