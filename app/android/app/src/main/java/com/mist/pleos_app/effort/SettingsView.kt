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
    val hourlyManText: String = "", // "1만 700원"(설정 단가 출처 펼침, #75 v41)
    val perMinExactText: String = "",   // "178.33원"(표시만, 금액 계산은 시급 그대로)
    val items: List<ItemSource>,    // 한 번 빼 주기(B) 항목, 가격 오름차순
)

data class ItemSource(val label: String, val priceText: String, val source: String)

/** 10700.0 → "10,700원"(원 단위 반올림, 천 단위 쉼표). */
fun wonText(krw: Double): String = String.format(Locale.ROOT, "%,d원", roundHalfUp(krw))

/** 10700.0 → "1만 700원", 20000 → "2만 원", 4700 → "4,700원"(원 단위 반올림). */
fun manWonText(krw: Double): String {
    val n = roundHalfUp(krw)
    if (n < 10_000) return String.format(Locale.ROOT, "%,d원", n)
    val man = n / 10_000
    val rest = n % 10_000
    return if (rest == 0) "${man}만 원" else String.format(Locale.ROOT, "%d만 %,d원", man, rest)
}

/** 1분 단가를 소수 둘째 자리까지: 10,700 ÷ 60 = 178.333… → "178.33원". 표시만(금액은 반올림 전 시급으로 계산). */
fun perMinExactText(c: EffortConstants): String {
    val v = roundHalfUp(c.wagePerHour * 100 / 60)
    return String.format(Locale.ROOT, "%d.%02d원", v / 100, v % 100)
}

/** 설정 '시연 고정 모드' 설명 줄 "태안 꽃지해수욕장 · 1.57배"(#75 v41): 시연 데이터로 엔진을 한 번 돌린 배수. */
fun demoSummaryOf(scene: DemoScene, c: EffortConstants): Pair<String, String>? {
    val trip = DemoEffortSource(scene).demoTrip("demo")
        .copy(arrivedAt = scene.arrivedAt, weatherArr = scene.weatherArr, restAnswerMin = scene.restAnswerMin ?: 0)
    val r = receiptOf(trip, c) ?: return null
    // 홈 기록 카드와 같은 짧은 표기("1.57배", 1.00은 엔진 문구 "평소와 같은 길")
    val m100 = roundHalfUp(r.multiplier * 100)
    val mult = if (m100 == 100) c.copy.text["multiplier_same_text"] ?: "1.00배" else String.format(Locale.ROOT, "%d.%02d배", m100 / 100, m100 % 100)
    return scene.destName to mult
}

fun wageSourceOf(c: EffortConstants): WageSource {
    val perMin = wagePerMin(c)
    val tmpl = c.copy.text["a_wage_text"] ?: "{wage_label} 1분 {wage_per_min}원 기준"
    return WageSource(
        wageText = fill(tmpl, "wage_label" to c.wageLabel, "wage_per_min" to perMin.toString()),
        wageLabel = c.wageLabel,
        hourlyText = wonText(c.wagePerHour),
        perMinText = "${perMin}원",
        hourlyManText = manWonText(c.wagePerHour),
        perMinExactText = perMinExactText(c),
        items = c.items.map { ItemSource(it.label, wonText(it.priceKrw), it.source) },
    )
}
