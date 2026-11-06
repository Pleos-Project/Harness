package com.mist.pleos_app.app

import com.mist.pleos_app.engine.CardsRequest
import com.mist.pleos_app.engine.CardsResponse
import com.mist.pleos_app.engine.DemoServer
import com.mist.pleos_app.engine.SwapResult
import com.mist.pleos_app.net.ChagokApi
import com.mist.pleos_app.util.ChagokLog
import java.io.IOException

/** [offline] = 서버 대신 내장 데모 후보로 뽑았다(X2 "잠시 후 다시 볼까요?" + 재시도). */
data class Fetched<T>(val value: T, val offline: Boolean)

/**
 * /v1/cards · /v1/cards/swap 을 부르는 곳. 블로킹이라 IO 스레드(한 줄로 직렬화)에서 부른다.
 * - [DemoSource]: 데모 고정 모드(설정 S1, 기본 켜짐). 네트워크 없이 DemoServer — PT·리허설(AC-C-3·AC-F5-8).
 * - [LiveSource]: 서버 실서비스(demo_seed 없음). 실패(타임아웃 3초·5xx·503 candidates_not_ready)면 DemoServer로 fallback(AC-F5-7).
 */
interface CardSource {
    /** [offlineOnly]면 서버를 건너뛴다(같은 화면 안에서 이미 실패한 뒤 타임아웃을 반복하지 않게). */
    fun cards(req: CardsRequest, offlineOnly: Boolean = false): Fetched<CardsResponse>
    fun swap(sessionId: String): SwapResult
}

class DemoSource(private val demo: DemoServer) : CardSource {
    override fun cards(req: CardsRequest, offlineOnly: Boolean) = Fetched(demo.cards(req), offline = false)
    override fun swap(sessionId: String) = demo.swap(sessionId)
}

/**
 * 서버 우선, 실패하면 내장 후보(명세 F5 fallback ②, X2 '서버 타임아웃·오류').
 * fallback으로 만든 세션은 이후 재호출·교체도 DemoServer로 보낸다(세션 id가 서버에 없으므로).
 * fallback ①(직전 응답 캐시)·`requestTrip` 거리 보정은 아직 없음 — 내장 후보의 거리·시간을 그대로 쓴다.
 */
class LiveSource(private val api: ChagokApi, private val demo: DemoServer) : CardSource {
    private val fallbackSessions = mutableSetOf<String>()

    override fun cards(req: CardsRequest, offlineOnly: Boolean): Fetched<CardsResponse> {
        val sid = req.sessionId
        if (sid != null && sid in fallbackSessions) return Fetched(demo.cards(req), offline = true)
        if (!offlineOnly) {
            try {
                return Fetched(api.cards(req.copy(demoSeed = null)), offline = false)
            } catch (e: IOException) {
                ChagokLog.event("server_fail", "call" to "cards", "err" to e.javaClass.simpleName, "status" to (e as? com.mist.pleos_app.net.ApiException)?.status)
                if (sid != null) throw e                      // 서버 세션 재호출 실패 → 화면은 지금 카드 유지(CardFlow.recall)
            }
        }
        val resp = demo.cards(req.copy(sessionId = null))
        resp.sessionId?.let { fallbackSessions += it }
        return Fetched(resp, offline = true)
    }

    override fun swap(sessionId: String): SwapResult {
        if (sessionId in fallbackSessions) return demo.swap(sessionId)
        return try {
            api.swap(sessionId)
        } catch (e: IOException) {
            ChagokLog.event("server_fail", "call" to "swap", "err" to e.javaClass.simpleName)
            SwapResult.Error(NETWORK)
        }
    }

    companion object {
        const val NETWORK = "network"
    }
}
