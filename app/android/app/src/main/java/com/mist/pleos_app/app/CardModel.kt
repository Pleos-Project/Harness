package com.mist.pleos_app.app

import com.mist.pleos_app.engine.Card
import com.mist.pleos_app.engine.CardsResponse
import com.mist.pleos_app.engine.Constants
import com.mist.pleos_app.engine.DemoServer
import com.mist.pleos_app.engine.MAX_SWAPS
import com.mist.pleos_app.engine.RING_KRW
import com.mist.pleos_app.engine.SwapResult
import com.mist.pleos_app.engine.Vehicle
import com.mist.pleos_app.engine.cardsRequest
import com.mist.pleos_app.engine.computeRange
import com.mist.pleos_app.engine.roundHalfUp

/*
 * F2 범위 · F5 한 장의 화면 상태. 순위·배지·교체 규칙은 engine(DemoServer = 서버 cards.py와 같은 규칙)이 하고,
 * 여기는 "어떤 조건으로 부르고, 결과를 화면에 어떻게 넘기나"만 정한다. 안드로이드 의존 없음 → CardModelTest.
 * 지금은 오프라인 데모 고정 모드(AC-C-3)만. 서버 호출(/v1/cards)은 P4b에서 같은 자리에 붙인다.
 */

/** 범위 조건(F2·F3). 오늘의 한 곳 기본값 = 추가 0원 · 당일 · 테마 없음 · 인원 자동(PRD v1.3 6-1). */
data class Conditions(
    val extraKrw: Int = 0,
    val days: Int = 1,
    val themes: List<String> = emptyList(),
)

/** 범위 화면 프리셋 칩 하나: 추가 금액, 반경(km, 반올림), 고를 수 있나. */
data class PresetUi(val extraKrw: Int, val radiusKm: Int, val enabled: Boolean)

/**
 * 프리셋 3개의 반경(엔진 computeRange). 잔고 km < 안전 여유면 추가 0원 비활성(AC-F2-2).
 * 1박은 왕복에서 30km 더 뺀다(엔진, AC-F2-3).
 */
fun presets(v: Vehicle, days: Int): List<PresetUi> = RING_KRW.map { extra ->
    val r = computeRange(v, extra, days)
    PresetUi(extra, roundHalfUp(r.radiusKm), enabled = extra > 0 || r.zeroAvailable)
}

/** 추가 0원이 안 되면 +1만부터(AC-F2-2). */
fun defaultExtra(v: Vehicle, days: Int): Int = if (computeRange(v, 0, days).zeroAvailable) 0 else RING_KRW[1]

/** 테마 칩: 0~2개, 3개째를 누르면 가장 먼저 고른 것이 빠진다(AC-F3-1). */
fun toggleTheme(themes: List<String>, theme: String): List<String> = when {
    theme in themes -> themes - theme
    themes.size >= 2 -> themes.drop(1) + theme
    else -> themes + theme
}

/** 편도 시간 "42분" / "1시간 10분"(design/components/card-front의 minutes_text). */
fun minutesText(minutes: Int): String = when {
    minutes < 60 -> "${minutes}분"
    minutes % 60 == 0 -> "${minutes / 60}시간"
    else -> "${minutes / 60}시간 ${minutes % 60}분"
}

/** 한 장 화면 상태. */
sealed class CardUi {
    /**
     * 앞면 카드. [raisedTo]가 있으면 오늘의 한 곳이 추가 0원에서 그 금액 기준으로 자동 전환된 것
     * (DECISIONS '잔고 부족 시 오늘의 한 곳') → 카드 위 한 줄.
     */
    data class Shown(
        val sessionId: String,
        val card: Card,
        val stackLeft: Int,
        val swapsLeft: Int,
        val raisedTo: Int?,
        val conditions: Conditions,
        /** 범위 안 후보 < 3곳(서버 hint few_candidates) → "범위 안 후보가 적어요" + 조건 바꾸기(X2) */
        val fewCandidates: Boolean = false,
    ) : CardUi()

    /** 교체 3회째·후보 소진 → "테마를 바꿔 볼까요?" 카드 → 범위 화면(AC-F5-3). */
    data class AskTheme(val conditions: Conditions) : CardUi()

    /** 범위 안 후보가 없음. 오늘의 한 곳에서 +3만까지 없으면 "가까운 곳만 볼 수 있어요". */
    data class Empty(val fromToday: Boolean, val conditions: Conditions) : CardUi()
}

/**
 * 카드 뽑기·교체. [server]는 오프라인 DemoServer(서버 실패 fallback ②와 같은 것).
 * [seats]는 착석 자동(F11), 출발점은 지금은 constants.origin(에뮬 위치와 같음) — 내비 현재 위치는 F8 작업 때.
 */
class CardFlow(
    private val constants: Constants,
    private val server: DemoServer,
) {
    private fun request(v: Vehicle, c: Conditions, seats: Int, sessionId: String? = null) = cardsRequest(
        constants.originLat, constants.originLng, v, computeRange(v, c.extraKrw, c.days),
        extraKrw = c.extraKrw, days = c.days, seats = seats, themes = c.themes,
        demoSeed = constants.demoSeed, sessionId = sessionId,
    )

    private fun shown(resp: CardsResponse, raisedTo: Int?, c: Conditions): CardUi? {
        val card = resp.picked ?: return null
        val sid = resp.sessionId ?: return null
        return CardUi.Shown(sid, card, resp.stack.size, MAX_SWAPS, raisedTo, c, fewCandidates = resp.hint?.code == "few_candidates")
    }

    /**
     * 오늘의 한 곳(홈 탭 → 범위 화면 없이 한 장). 추가 0원 범위에 후보가 없으면 +1만, 그래도 없으면 +3만으로 자동 전환.
     * 셋 다 없으면 Empty(fromToday=true).
     */
    fun today(v: Vehicle, seats: Int): CardUi {
        for (extra in RING_KRW) {
            val c = Conditions(extraKrw = extra)
            shown(server.cards(request(v, c, seats)), raisedTo = extra.takeIf { it > 0 }, c)?.let { return it }
        }
        return CardUi.Empty(fromToday = true, conditions = Conditions())
    }

    /** 범위 화면 "한 장 뽑기". 자동 전환 없음(사용자가 고른 조건 그대로), 없으면 Empty → "범위 안 후보가 적어요". */
    fun pick(v: Vehicle, seats: Int, c: Conditions): CardUi =
        shown(server.cards(request(v, c, seats)), raisedTo = null, c) ?: CardUi.Empty(fromToday = false, conditions = c)

    /** 교체(스와이프·아이콘). 2회까지, 3회째·후보 소진은 AskTheme. */
    fun swap(cur: CardUi.Shown): CardUi = when (val r = server.swap(cur.sessionId)) {
        is SwapResult.Ok -> cur.copy(card = r.card, stackLeft = r.stackLeft, swapsLeft = r.swapsLeft)
        is SwapResult.Error -> CardUi.AskTheme(cur.conditions)
    }
}
