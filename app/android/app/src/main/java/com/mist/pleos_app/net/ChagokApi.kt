package com.mist.pleos_app.net

import com.mist.pleos_app.engine.CardsRequest
import com.mist.pleos_app.engine.CardsResponse
import com.mist.pleos_app.engine.SwapResult
import com.mist.pleos_app.engine.parseCard
import com.mist.pleos_app.engine.parseCardsResponse
import com.mist.pleos_app.engine.toJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 서버가 2xx가 아님(503 candidates_not_ready 등). 앱은 데모 fallback으로 간다. */
class ApiException(val status: Int, val error: String?, val hint: String?) : IOException("HTTP $status ${error.orEmpty()}")

/** GET /v1/prices(DECISIONS '계약: 단가'). 원/L 정수. */
data class Prices(val gasoline: Double, val diesel: Double?, val tradeDt: String?)

/**
 * 차곡 서버(contract/openapi.yaml). 앱이 부르는 건 /v1/cards · /v1/cards/swap · /v1/prices 셋뿐(app/README 3절).
 * - 요청은 엔진 `CardsRequest.toJson()` = budget 파생값만(원시 차량값 없음, 서버 extra="forbid"가 422로 막음).
 * - 타임아웃 3초. 블로킹 호출이라 반드시 IO 스레드에서 부른다. 화면을 연 동안만 부른다(폴링·백그라운드 없음).
 * - HTTP 클라이언트는 플랫폼 HttpURLConnection(새 의존성 없음).
 */
class ChagokApi(private val baseUrl: String, private val timeoutMs: Int = TIMEOUT_MS) {

    fun cards(req: CardsRequest): CardsResponse = parseCardsResponse(call("POST", "/v1/cards", req.toJson().toString()))

    /** 404 session_not_found·409 swap_limit/no_more_candidates는 SwapResult.Error, 그 밖의 실패는 예외. */
    fun swap(sessionId: String, excludeIds: List<String> = emptyList()): SwapResult {
        val body = JsonObject(mapOf("session_id" to JsonPrimitive(sessionId), "exclude_ids" to JsonArray(excludeIds.map { JsonPrimitive(it) })))
        return try {
            val o = call("POST", "/v1/cards/swap", body.toString())
            SwapResult.Ok(
                sessionId = o.str("session_id") ?: sessionId,
                card = parseCard(o.getValue("card").jsonObject),
                swapsUsed = o.int("swaps_used") ?: 0,
                swapsLeft = o.int("swaps_left") ?: 0,
                stackLeft = o.int("stack_left") ?: 0,
            )
        } catch (e: ApiException) {
            if (e.status == 404 || e.status == 409) SwapResult.Error(e.error ?: "status_${e.status}", e.hint) else throw e
        }
    }

    fun prices(): Prices {
        val o = call("GET", "/v1/prices", null)
        val gas = (o["gasoline"] as? JsonPrimitive)?.doubleOrNull ?: throw ApiException(200, "no_gasoline", null)
        return Prices(gas, (o["diesel"] as? JsonPrimitive)?.doubleOrNull, o.str("trade_dt"))
    }

    private fun call(method: String, path: String, body: String?): JsonObject {
        val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.encodeToByteArray()) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes().decodeToString() }.orEmpty()
            if (status !in 200..299) {
                // FastAPI HTTPException → {"detail": {"error": ..., "hint": ...}}
                val detail = runCatching { (Json.parseToJsonElement(text).jsonObject["detail"] as? JsonObject) }.getOrNull()
                throw ApiException(status, detail?.str("error"), detail?.str("hint"))
            }
            return Json.parseToJsonElement(text).jsonObject
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val TIMEOUT_MS = 3_000
    }
}

private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
