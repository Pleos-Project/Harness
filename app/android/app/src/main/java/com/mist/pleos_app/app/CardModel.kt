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

/**
 * 범위 화면 테마 칩(F3). 엔진 THEME_KO는 카드 라벨용이라 서버 테마가 늘어도(#39 실내·축제) 칩은 이 8개로 묶어 둔다.
 * 명준 확정 전이고 데모 후보에 실내·축제가 없어 오프라인 데모에서 고르면 0개가 된다. 확정되면 여기에 더한다.
 */
val RANGE_THEMES: List<String> = listOf("sea", "lake", "river", "mountain", "park", "city", "island", "drive")

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

private fun fixedSource(s: CardSource): () -> CardSource = { s }

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
        /** 서버 대신 내장 후보로 뽑음 → 스택 자리에 "잠시 후 다시 볼까요?" + 다시 보기(X2, AC-F5-7) */
        val offline: Boolean = false,
    ) : CardUi()

    /** 서버 응답을 기다리는 중(최대 3초, 실패하면 내장 후보). */
    data object Loading : CardUi()

    /** 교체 3회째·후보 소진 → "어떤 쪽으로 다시 볼까요?" 카드 → 범위 화면(PRD v1.6, 이유 칩 3개는 다음 PR). */
    data class AskTheme(val conditions: Conditions) : CardUi()

    /** 범위 안 후보가 없음. 오늘의 한 곳에서 +3만까지 없으면 "가까운 곳만 볼 수 있어요". */
    data class Empty(val fromToday: Boolean, val conditions: Conditions) : CardUi()
}

/**
 * 카드 뽑기·교체. [source]는 지금 모드의 카드 출처(데모 고정 → DemoSource, 아니면 서버 + 내장 후보 fallback).
 * 모든 함수가 블로킹(서버 호출)이라 IO 스레드에서 부른다.
 * [seats]는 나눠 낼 인원(F11), 출발점은 지금은 constants.origin(에뮬 위치와 같음) — 내비 현재 위치는 다음 PR.
 */
class CardFlow(
    private val constants: Constants,
    private val source: () -> CardSource,
) {
    /** 테스트·데모 고정용: DemoServer 하나로. */
    constructor(constants: Constants, server: DemoServer) : this(constants, fixedSource(DemoSource(server)))

    /** 출발한 곳(최근 10곳). 다음 뽑기에서 빼고 "지난번과 다른 테마"를 판단한다(명세 F5 저장). */
    var recentVisits: List<String> = emptyList()

    private fun request(v: Vehicle, c: Conditions, seats: Int, sessionId: String? = null) = cardsRequest(
        constants.originLat, constants.originLng, v, computeRange(v, c.extraKrw, c.days),
        extraKrw = c.extraKrw, days = c.days, seats = seats, themes = c.themes,
        recentVisits = recentVisits, demoSeed = constants.demoSeed, sessionId = sessionId,
    )

    private fun shown(f: Fetched<CardsResponse>, raisedTo: Int?, c: Conditions): CardUi? {
        val resp = f.value
        val card = resp.picked ?: return null
        val sid = resp.sessionId ?: return null
        return CardUi.Shown(sid, card, resp.stack.size, MAX_SWAPS, raisedTo, c, fewCandidates = resp.hint?.code == "few_candidates", offline = f.offline)
    }

    /**
     * 오늘의 한 곳(홈 탭 → 범위 화면 없이 한 장). 추가 0원 범위에 후보가 없으면 +1만, 그래도 없으면 +3만으로 자동 전환.
     * 셋 다 없으면 Empty(fromToday=true).
     */
    fun today(v: Vehicle, seats: Int): CardUi {
        val src = source()
        var offline = false                                   // 한 번 실패하면 이번 뽑기 안에서는 서버를 다시 기다리지 않는다
        for (extra in RING_KRW) {
            val c = Conditions(extraKrw = extra)
            val f = src.cards(request(v, c, seats), offlineOnly = offline)
            offline = offline || f.offline
            shown(f, raisedTo = extra.takeIf { it > 0 }, c)?.let { return it }
        }
        return CardUi.Empty(fromToday = true, conditions = Conditions())
    }

    /** 범위 화면 "한 장 뽑기". 자동 전환 없음(사용자가 고른 조건 그대로), 없으면 Empty → "범위 안 후보가 적어요". */
    fun pick(v: Vehicle, seats: Int, c: Conditions): CardUi =
        shown(source().cards(request(v, c, seats)), raisedTo = null, c) ?: CardUi.Empty(fromToday = false, conditions = c)

    /**
     * 차량값·착석이 바뀌었을 때 재호출(명세 F5 '유지·재계산', AC-F5-2·AC-F11-1): 같은 세션으로 다시 불러
     * 앞면은 그대로 두고 배지·1인 몫·이유만 새로. 앞면이 +3만 범위를 벗어나면 엔진이 다시 뽑는다.
     * 자동 전환(raisedTo) 중이면 그 금액 기준을 유지한다.
     */
    fun recall(cur: CardUi.Shown, v: Vehicle, seats: Int): CardUi {
        val c = cur.conditions
        val resp = try {
            source().cards(request(v, c, seats, sessionId = cur.sessionId)).value
        } catch (e: java.io.IOException) {
            return cur.copy(offline = true)                   // 서버 세션 재호출 실패: 앞면 유지 + "잠시 후 다시"
        }
        val card = resp.picked ?: return CardUi.Empty(fromToday = cur.raisedTo != null, conditions = c)
        return cur.copy(card = card, sessionId = resp.sessionId ?: cur.sessionId, fewCandidates = resp.hint?.code == "few_candidates")
    }

    /** 교체(스와이프·아이콘). 2회까지, 3회째·후보 소진은 AskTheme. */
    fun swap(cur: CardUi.Shown): CardUi = when (val r = source().swap(cur.sessionId)) {
        is SwapResult.Ok -> cur.copy(card = r.card, stackLeft = r.stackLeft, swapsLeft = r.swapsLeft)
        is SwapResult.Error -> if (r.error == LiveSource.NETWORK) cur.copy(offline = true) else CardUi.AskTheme(cur.conditions)
    }
}

/**
 * 재호출을 부를 만큼 바뀌었나(명세 F5: SOC 1%p·주행가능거리 1km 미만 변화는 무시). 착석 수는 바뀌면 바로.
 * 키 = (잔량 %, 주행가능거리 km 반올림, 착석 수).
 */
data class RecallKey(val levelPct: Int?, val rangeKm: Int?, val seats: Int)

fun recallKey(levelPct: Int?, rangeM: Double?, seats: Int) = RecallKey(levelPct, rangeM?.let { roundHalfUp(it / 1000.0) }, seats)

/** 1초 디바운스 뒤 재호출할 값(명세 F5 '마지막 변화 후 1초 디바운스'). */
const val RECALL_DEBOUNCE_MS = 1_000L
