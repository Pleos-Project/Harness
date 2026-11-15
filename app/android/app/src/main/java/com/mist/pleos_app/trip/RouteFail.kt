package com.mist.pleos_app.trip

import com.mist.pleos_app.effort.GUIDE_CANCELLED
import com.mist.pleos_app.effort.ROUTE_FAIL_WINDOW_S

/** 주행 중 서비스가 받은 안내 끝(도착 신호 · 안내 종료)을 어떻게 다룰지(F20 예외 #132, F22). */
enum class GuideEndVerdict {
    /** 보통의 안내 끝: 기기에 남기고 앱이 안 보이면 도착 확인 알림 1번(F22 '알림과 열기', AC-F22-1·7). 앱을 띄우지 않는다. */
    GUIDE_END,
    /** 길을 보내기 전에 내비가 이미 안내 중이었고, 이번이 그 이전 안내의 취소다. 넘긴다. */
    OLD_CANCEL,
    /** 경로 요청 실패(#132): 출발 화면으로 되돌리고 노고를 앞으로(F20 예외, DECISIONS 11/13 · 11/14 유일한 예외). */
    ROUTE_FAILED,
    /** 이번 출발의 안내가 시작되기 전의 도착 신호: 이전 안내의 것이라 버린다. */
    IGNORE,
}

/**
 * [departedAtMs](이번 [출발]에서 서비스를 fresh로 띄운 시각)부터 [nowMs]까지가 경로 요청 실패 창 안인지.
 * 뷰모델 `onRouteFailed`와 같은 [ROUTE_FAIL_WINDOW_S]를 쓰고, 경계도 같다(정확히 창 길이면 안, 넘으면 밖).
 * 출발 시각을 모르면(이어 가는 주행) 창 밖이다. 시계가 뒤로 가 음수면 방금 출발한 것으로 본다.
 */
fun inRouteFailWindow(departedAtMs: Long?, nowMs: Long): Boolean =
    departedAtMs != null && nowMs - departedAtMs <= ROUTE_FAIL_WINDOW_S * 1000L

/**
 * 안내 끝 이벤트를 **받는 순간** 판정한다(타이머 · 폴링 없음, 명세 v0.6 F22 '주행 중 서비스').
 * - 이번 출발의 안내가 시작됐으면([routeStarted]) 보통의 안내 끝이다.
 * - 시작 전이어도 출발 뒤 창이 지났으면 보통의 안내 끝이다. `onRouteStarted`를 놓친 채(서비스 리스너가 늦게 붙음 등)
 *   한참 뒤에 온 [안내 종료]를 경로 요청 실패로 읽어 주행 중에 앱을 띄우거나 출발 기록을 지우지 않게(R-1).
 *   이때 도착 신호도 버리지 않고 받는다.
 * - 창 안 · 시작 전: 취소면 이전 안내의 것([expectOldCancel])이거나 경로 요청 실패, 도착 신호는 버린다.
 */
fun judgeGuideEnd(kind: String, routeStarted: Boolean, departedAtMs: Long?, nowMs: Long, expectOldCancel: Boolean): GuideEndVerdict = when {
    routeStarted -> GuideEndVerdict.GUIDE_END
    !inRouteFailWindow(departedAtMs, nowMs) -> GuideEndVerdict.GUIDE_END
    kind != GUIDE_CANCELLED -> GuideEndVerdict.IGNORE
    expectOldCancel -> GuideEndVerdict.OLD_CANCEL
    else -> GuideEndVerdict.ROUTE_FAILED
}
