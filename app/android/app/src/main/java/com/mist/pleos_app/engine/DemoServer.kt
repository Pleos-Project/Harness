package com.mist.pleos_app.engine

import kotlin.math.abs

/*
 * F5 한 장 + 스택 + 교체 — server/chagok/cards.py 를 옮긴 것.
 * 오프라인 데모 모드(AC-C-3·AC-F5-8)와 서버 실패 fallback ②(AC-F5-7)에서 /v1/cards · /v1/cards/swap 과 같은 응답을 낸다.
 * 같은 규칙인지는 FixturesTest가 fixtures/cards/D*.json 으로 대조한다.
 */

const val SESSION_TTL_MS = 30L * 60 * 1000
const val STACK_SIZE = 3

/** 앱 → 서버 요청(contract/openapi.yaml CardsRequest). 원시 차량값 없이 budget 파생값만. */
data class CardsRequest(
    val originLat: Double,
    val originLng: Double,
    val budget: Budget,
    val seatsOccupied: Int = 1,
    val extraKrw: Int = 0,
    val days: Int = 1,
    val themes: List<String> = emptyList(),
    val recentVisits: List<String> = emptyList(),
    val demoSeed: String? = null,
    val sessionId: String? = null,
)

/** 로컬 계산 결과로 요청을 만든다. origin은 내비 현재 위치(없으면 직전 위치 → 설정 집 위치). */
fun cardsRequest(
    originLat: Double, originLng: Double, vehicle: Vehicle, range: RangeResult,
    extraKrw: Int = 0, days: Int = 1, seats: Int = 1, themes: List<String> = emptyList(),
    recentVisits: List<String> = emptyList(), demoSeed: String? = null, sessionId: String? = null,
): CardsRequest = CardsRequest(originLat, originLng, budgetOf(range, vehicle), seats, extraKrw, days, themes, recentVisits, demoSeed, sessionId)

data class Hint(val code: String, val text: String, val actions: List<String>)

data class CardsResponse(
    val sessionId: String?,
    val radiusKm: Double,
    val candidatesInRange: Int,
    val hint: Hint?,
    val picked: Card?,
    val stack: List<Card>,
)

sealed class SwapResult {
    data class Ok(val sessionId: String, val card: Card, val swapsUsed: Int, val swapsLeft: Int, val stackLeft: Int) : SwapResult()
    data class Error(val error: String, val hint: String? = null) : SwapResult()
}

// ------------------------------------------------------------ 순위
internal data class Ranked(val card: Card, val score: Double, val routeKm: Double)

/** 후보를 카드로. 최근 방문·90분 초과·+3만 밖은 뺀다. */
internal fun evaluate(candidates: List<Candidate>, b: Budget, seats: Int, themes: List<String>, recentIds: List<String>): List<Ranked> {
    val recentThemes = candidates.filter { it.id in recentIds }.map { it.theme }
    val out = mutableListOf<Ranked>()
    for (c in candidates) {
        if (c.id in recentIds || timeLabel(c.minutes) == null) continue
        val badge = computeBadge(c, b)
        if (badge.ringKrw == null) continue
        val s = score(c, badge, themes, recentThemes)
        out.add(Ranked(cardOf(c, computeCost(c, b, seats), badge, reasonLine(c, s, badge)), weighted(s), c.routeKm))
    }
    return out
}

/** 동점이면 "반경의 절반쯤 쓰는 곳" 우선 [제안, DECISIONS.md]. 같은 값이면 id 순(결정적). */
internal fun sortRanked(cards: List<Ranked>, radius: Double): List<Ranked> {
    val target = radius * 0.5
    return cards.sortedWith(
        compareByDescending<Ranked> { it.score }.thenBy { abs(it.routeKm - target) }.thenBy { it.card.id },
    )
}

/** 새로 뽑을 때의 경계: 고른 프리셋 안 + 고른 테마 안(PRD F2 범위, F3 '테마 경계'). */
internal fun eligible(card: Card, extraKrw: Int, themes: List<String>): Boolean =
    (card.display.ringKrw ?: Int.MAX_VALUE) <= extraKrw && (themes.isEmpty() || card.theme in themes)

/** 스택: 상위 9장 안에서 테마가 서로 다르게, 그중 1장은 75~90분(조금 먼 곳). 화면·교체 순서 = 이 순서. */
internal fun buildStack(picked: Ranked, pool: List<Ranked>, size: Int = STACK_SIZE): List<Ranked> {
    val p = pool.filter { it.card.id != picked.card.id }.take(9)
    val stack = mutableListOf<Ranked>()
    val used = mutableSetOf(picked.card.theme)
    val far = p.firstOrNull { it.card.timeLabel == "조금 먼 곳" }
    if (far != null) {
        stack.add(far)
        used.add(far.card.theme)
    }
    for (c in p) {
        if (stack.size >= size) break
        if (c === far || c.card.theme in used) continue
        stack.add(c)
        used.add(c.card.theme)
    }
    for (c in p) {
        if (stack.size >= size) break
        if (stack.none { it === c }) stack.add(c)
    }
    return stack
}

/** X2 '범위 안 후보가 적어요' + 테마 해제 / 다음 프리셋. */
internal fun fewHint(n: Int, extraKrw: Int, themes: List<String>): Hint? {
    if (n >= 3) return null
    val actions = mutableListOf<String>()
    if (themes.isNotEmpty()) actions.add("clear_themes")
    val next = RING_KRW.firstOrNull { it > extraKrw }
    if (next != null) actions.add("extra_$next")
    return Hint("few_candidates", "범위 안 후보가 적어요", actions)
}

internal fun ranked(req: CardsRequest, candidates: List<Candidate>): Triple<List<Ranked>, List<Ranked>, Double> {
    val radius = radiusKm(req.budget, req.extraKrw)
    val all = sortRanked(evaluate(candidates, req.budget, req.seatsOccupied, req.themes, req.recentVisits), radius)
    return Triple(all, all.filter { eligible(it.card, req.extraKrw, req.themes) }, radius)
}

// ------------------------------------------------------------ 세션
/** 메모리 세션(30분). 서버 cards.py 의 build_cards·swap 과 같은 규칙. */
class DemoServer(
    private val candidates: List<Candidate>,
    private val now: () -> Long = { System.currentTimeMillis() },
    ids: (() -> String)? = null,
) {
    private class Session(
        var pickedId: String?,
        var stackIds: List<String>,
        var excluded: List<String>,
        var swaps: Int,
        var request: CardsRequest,
        var t: Long,
    )

    private var counter = 0
    private val nextId: () -> String = ids ?: { "demo-${++counter}" }
    private val sessions = LinkedHashMap<String, Session>()

    private fun prune() {
        val t = now()
        sessions.entries.removeAll { t - it.value.t > SESSION_TTL_MS }
    }

    private fun get(sid: String): Session? {
        prune()
        val s = sessions[sid] ?: return null
        s.t = now()
        return s
    }

    /** POST /v1/cards 와 같은 응답. sessionId가 있으면 재호출(같은 picked에 새 배지·1인 몫·이유). */
    fun cards(req: CardsRequest): CardsResponse {
        val (all, pool, radius) = ranked(req, candidates)
        var sid = req.sessionId
        val sess = sid?.let { get(it) }
        val picked: Ranked?
        val stack: List<Ranked>
        if (sess != null) {
            sess.request = req.copy(sessionId = null)
            val live = pool.filter { it.card.id !in sess.excluded }
            var p = all.firstOrNull { it.card.id == sess.pickedId }
            if (p == null) {                                  // +3만 범위를 벗어남 → 다시 뽑기
                p = live.firstOrNull()
                sess.pickedId = p?.card?.id
            }
            picked = p
            stack = if (p != null) buildStack(p, live) else emptyList()
            sess.stackIds = stack.map { it.card.id }
        } else {
            picked = pool.firstOrNull()
            stack = if (picked != null) buildStack(picked, pool) else emptyList()
            sid = null
            if (picked != null) {
                prune()
                val newId = nextId()
                sessions[newId] = Session(picked.card.id, stack.map { it.card.id }, emptyList(), 0, req.copy(sessionId = null), now())
                sid = newId
            }
        }
        return CardsResponse(sid, round1(radius), pool.size, fewHint(pool.size, req.extraKrw, req.themes), picked?.card, stack.map { it.card })
    }

    /** POST /v1/cards/swap 과 같은 규칙: 화면 스택 순서대로 다음 카드, 세션의 최신 값으로 배지. */
    fun swap(sessionId: String, excludeIds: List<String> = emptyList()): SwapResult {
        val sess = get(sessionId) ?: return SwapResult.Error("session_not_found")
        if (sess.swaps >= MAX_SWAPS) return SwapResult.Error("swap_limit", "테마를 바꿔 볼까요?")
        val (all, pool, _) = ranked(sess.request, candidates)
        val byId = all.associateBy { it.card.id }
        val excluded = (sess.excluded + excludeIds + listOfNotNull(sess.pickedId)).distinct()
        val nxt = sess.stackIds.firstOrNull { it in byId && it !in excluded }?.let { byId.getValue(it) }
            ?: pool.firstOrNull { it.card.id !in excluded }
            ?: return SwapResult.Error("no_more_candidates", "테마를 바꿔 볼까요?")
        sess.excluded = excluded
        sess.swaps += 1
        sess.pickedId = nxt.card.id
        sess.stackIds = sess.stackIds.filter { it != nxt.card.id }
        return SwapResult.Ok(sessionId, nxt.card, sess.swaps, MAX_SWAPS - sess.swaps, sess.stackIds.size)
    }
}
