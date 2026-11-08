package com.mist.pleos_app.effort

import com.mist.pleos_app.engine.EffortConstants
import com.mist.pleos_app.engine.fill
import com.mist.pleos_app.engine.roundHalfUp
import com.mist.pleos_app.engine.wagePerMin
import java.util.Locale

/*
 * 설정 › 단가 출처(S20, 노고 PRD 5-2 "단가 출처를 화면에 표시한다"). 숫자는 상수(constants.json effort)에서만 꺼낸다.
 */

/** 단가 출처 칸. [wageText]는 A 화면 근거 줄과 같은 문구("최저임금 1분 178원 기준"). */
data class WageSource(
    val wageText: String,
    val wageLabel: String,          // "최저임금"
    val hourlyText: String,         // "10,700원"
    val perMinText: String,         // "178원"
    val items: List<ItemSource>,    // 한 번 빼 주기(B) 항목, 가격 오름차순
)

data class ItemSource(val label: String, val priceText: String, val source: String)

/** 10700.0 → "10,700원"(원 단위 반올림, 천 단위 쉼표). */
fun wonText(krw: Double): String = String.format(Locale.ROOT, "%,d원", roundHalfUp(krw))

fun wageSourceOf(c: EffortConstants): WageSource {
    val perMin = wagePerMin(c)
    val tmpl = c.copy.text["a_wage_text"] ?: "{wage_label} 1분 {wage_per_min}원 기준"
    return WageSource(
        wageText = fill(tmpl, "wage_label" to c.wageLabel, "wage_per_min" to perMin.toString()),
        wageLabel = c.wageLabel,
        hourlyText = wonText(c.wagePerHour),
        perMinText = "${perMin}원",
        items = c.items.map { ItemSource(it.label, wonText(it.priceKrw), it.source) },
    )
}
