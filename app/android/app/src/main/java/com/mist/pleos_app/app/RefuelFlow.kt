package com.mist.pleos_app.app

import com.mist.pleos_app.engine.Vehicle
import com.mist.pleos_app.engine.chargeAmount
import com.mist.pleos_app.engine.fullTankAmount

/**
 * F9 충전·주유 직후(명세 F9, PRD v1.6 F9). 앱 화면이 보이는 동안만 판단한다(백그라운드 수집 없음).
 * - EV: 충전 스위치 OFF → ON 때 SOC를 기억하고 ON → OFF 때 (증가 % × 용량) × 단가 → "방금 약 1만 4천 원어치 충전"(엔진 chargeAmount, D6).
 *   앱을 연 때 이미 충전 중이었으면(시작 SOC 모름) "충전 끝났어요"만.
 * - 내연: 정차 중 연료 %가 기준보다 3%p 이상 오르면 금액 시트(3만 / 5만 / 가득 + 1만 원 ±, 키보드 없음). 2%p는 무시(AC-F9-3).
 *   기준은 마지막으로 본 값. 내려가면(주행) 따라 내려가고, 작은 상승은 쌓여서 3%p가 되면 시트.
 * EV는 금액 시트를 띄우지 않는다(AC-F9-4).
 */
sealed interface RefuelUi {
    /** 홈 맨 위 카드. [text] null이면 "충전 끝났어요"(시작 SOC 모름). */
    data class Charged(val text: String?) : RefuelUi

    /** 내연 금액 시트. [fullKrw] = '가득' 금액(엔진 fullTankAmount, 천 원 어림). */
    data class Sheet(val startPct: Int, val fullKrw: Int) : RefuelUi
}

class RefuelFlow {
    private var lastSwitch: Boolean? = null
    private var startSoc: Int? = null
    private var baseFuel: Int? = null

    /** 충전 스위치가 바뀔 때. 끝났으면 카드를 돌려준다. */
    fun onCharge(on: Boolean?, soc: Int?, v: Vehicle?): RefuelUi.Charged? {
        val prev = lastSwitch
        lastSwitch = on
        if (on == true && prev != true) {
            startSoc = if (prev == false) soc else null      // 처음 본 값이 이미 ON이면 시작을 모름
            return null
        }
        if (on == false && prev == true) {
            val st = startSoc
            startSoc = null
            if (v != null && v.fuelType != "ev") return null                     // 내연 차종이면 충전 카드 없음
            if (st == null || soc == null || v == null) return RefuelUi.Charged(null)
            return RefuelUi.Charged(chargeAmount(st.toDouble(), soc.toDouble(), v.capacity, v.pricePerUnit).text)
        }
        return null
    }

    /** 연료 %(SDK, 없으면 설정 모의 값)가 바뀔 때. 3%p 이상 오르면 시트. */
    fun onFuel(levelPct: Int?, parked: Boolean, v: Vehicle?): RefuelUi.Sheet? {
        if (levelPct == null || v == null || v.fuelType != "ice") { baseFuel = levelPct; return null }
        val base = baseFuel
        if (base == null || !parked || levelPct < base) { baseFuel = levelPct; return null }
        if (levelPct - base < RISE_PCT) return null
        baseFuel = levelPct
        return RefuelUi.Sheet(base, fullTankAmount(base.toDouble(), v.capacity, v.pricePerUnit).krw)
    }

    companion object {
        const val RISE_PCT = 3
        val PRESETS = listOf(30_000, 50_000)
        const val STEP = 10_000
    }
}
