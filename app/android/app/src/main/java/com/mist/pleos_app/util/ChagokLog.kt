package com.mist.pleos_app.util

import android.util.Log
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * 기능별 1줄 JSON 로그(명세 2절 "로그", AC-C-10). 좌표·VIN·장소·세션 id 같은 개인정보는 넣지 않는다.
 * logcat 태그 "chagok". 값은 숫자·불리언·짧은 코드 문자열만.
 */
object ChagokLog {
    private const val TAG = "chagok"

    fun event(ev: String, vararg fields: Pair<String, Any?>) {
        val line = buildJsonObject {
            put("ev", JsonPrimitive(ev))
            for ((k, v) in fields) {
                put(k, when (v) {
                    null -> JsonPrimitive(null as String?)
                    is Number -> JsonPrimitive(v)
                    is Boolean -> JsonPrimitive(v)
                    else -> JsonPrimitive(v.toString())
                })
            }
        }.toString()
        runCatching { Log.i(TAG, line) }   // JVM 단위 테스트에서는 android.util.Log가 없어 조용히 넘긴다
    }
}
