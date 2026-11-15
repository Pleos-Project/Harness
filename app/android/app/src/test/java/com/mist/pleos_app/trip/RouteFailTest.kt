package com.mist.pleos_app.trip

import com.mist.pleos_app.effort.GUIDE_ARRIVED
import com.mist.pleos_app.effort.GUIDE_CANCELLED
import com.mist.pleos_app.effort.ROUTE_FAIL_WINDOW_S
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 주행 중 서비스의 경로 요청 실패 판정(F20 예외 #132, F22 · AC-F22-1·7, 설계 검수 R-1 · R-3). 시각은 넣어 준다(타이머 없음). */
class RouteFailTest {

    private val dep = 1_000_000L
    private val window = ROUTE_FAIL_WINDOW_S * 1000L

    private fun judge(kind: String = GUIDE_CANCELLED, started: Boolean = false, departed: Long? = dep, now: Long, old: Boolean = false) =
        judgeGuideEnd(kind, started, departed, now, old)

    @Test fun windowIsSameAsViewModel() = assertEquals(120, ROUTE_FAIL_WINDOW_S)

    @Test fun cancelInsideWindowBeforeStartIsRouteFailure() {
        // 11/13 에뮬: 오프라인 [출발] → 약 3.4초에 취소
        assertEquals(GuideEndVerdict.ROUTE_FAILED, judge(now = dep + 3_400))
    }

    @Test fun cancelAfterWindowIsGuideEndNotFailure() {
        // onRouteStarted를 놓친 채 한참 뒤 [안내 종료] → 앱을 띄우지 않고 "도착했나요?" 알림
        assertEquals(GuideEndVerdict.GUIDE_END, judge(now = dep + 30 * 60_000))
    }

    @Test fun cancelAfterRouteStartedIsGuideEnd() {
        assertEquals(GuideEndVerdict.GUIDE_END, judge(started = true, now = dep + 3_400))
        assertEquals(GuideEndVerdict.GUIDE_END, judge(started = true, now = dep + 30 * 60_000))
    }

    @Test fun boundaryExactWindowIsInsideOneMsLaterIsOutside() {
        assertEquals(GuideEndVerdict.ROUTE_FAILED, judge(now = dep + window))
        assertEquals(GuideEndVerdict.GUIDE_END, judge(now = dep + window + 1))
        assertTrue(inRouteFailWindow(dep, dep + window))
        assertFalse(inRouteFailWindow(dep, dep + window + 1))
    }

    @Test fun oldGuideCancelInsideWindowIsSkipped() {
        // 이미 안내 중이던 내비에 새 길: 0.2초에 이전 안내 취소
        assertEquals(GuideEndVerdict.OLD_CANCEL, judge(now = dep + 200, old = true))
    }

    @Test fun staleOldCancelFlagDoesNotSwallowLateGuideEnd() {
        // R-3: 이전 안내 취소를 못 받아 표시가 남아 있어도 창이 지나면 보통의 안내 끝
        assertEquals(GuideEndVerdict.GUIDE_END, judge(now = dep + window + 1, old = true))
    }

    @Test fun arrivalBeforeStartInsideWindowIsIgnoredButAfterWindowCounts() {
        assertEquals(GuideEndVerdict.IGNORE, judge(kind = GUIDE_ARRIVED, now = dep + 3_000))
        assertEquals(GuideEndVerdict.GUIDE_END, judge(kind = GUIDE_ARRIVED, now = dep + window + 1))
        assertEquals(GuideEndVerdict.GUIDE_END, judge(kind = GUIDE_ARRIVED, started = true, now = dep + 3_000))
    }

    @Test fun unknownDepartureIsOutsideWindow() {
        // 이어 가는 주행(fresh 아님)은 출발 시각이 없다 → 경로 요청 실패로 보지 않는다
        assertEquals(GuideEndVerdict.GUIDE_END, judge(departed = null, now = dep))
        assertFalse(inRouteFailWindow(null, dep))
    }

    @Test fun clockGoingBackCountsAsJustDeparted() {
        assertEquals(GuideEndVerdict.ROUTE_FAILED, judge(now = dep - 500))
    }
}
