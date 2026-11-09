package com.mist.pleos_app.app

import android.content.Context

/**
 * F11 나눠 낼 인원(PRD v1.5, DECISIONS 11/6 '인원 확인 토스트'·'1인 몫·정산 인원').
 * - 착석 수(센서)가 2명 이상이고 지난번에 확인한 착석 수와 다르면 묻는다([shouldAsk]). 혼자면 묻지 않는다.
 * - [확인] → 고른 인원, 닫기 → 감지 인원 그대로. 둘 다 그 착석 수는 다시 묻지 않는다.
 * - 1인 몫·정산은 [payersFor]로 나눈다. 확인 전에는 감지 인원, 1이면 1인 몫 숨김(엔진이 2 미만이면 안 붙임).
 * 확인값은 기기 안에만 둔다(서버에 보내는 건 카드 요청의 인원 하나뿐, 원래도 보내던 값).
 */
data class PayerState(val askedSeats: Int? = null, val payers: Int? = null)

interface PayerStore {
    fun load(): PayerState
    fun save(state: PayerState)
}

class MemoryPayerStore(private var state: PayerState = PayerState()) : PayerStore {
    override fun load() = state
    override fun save(state: PayerState) { this.state = state }
}

class PrefsPayerStore(context: Context) : PayerStore {
    private val prefs = context.getSharedPreferences("chagok_payers", Context.MODE_PRIVATE)
    override fun load() = PayerState(
        prefs.getInt(KEY_SEATS, 0).takeIf { it > 0 },
        prefs.getInt(KEY_PAYERS, 0).takeIf { it > 0 },
    )
    override fun save(state: PayerState) {
        prefs.edit().putInt(KEY_SEATS, state.askedSeats ?: 0).putInt(KEY_PAYERS, state.payers ?: 0).apply()
    }
    private companion object {
        const val KEY_SEATS = "asked_seats"
        const val KEY_PAYERS = "payers"
    }
}

class PayerFlow(private val store: PayerStore) {
    var state: PayerState = store.load()
        private set

    fun shouldAsk(seats: Int): Boolean = seats >= 2 && state.askedSeats != seats

    /** 지금 1인 몫·정산에 쓰는 인원. */
    fun payersFor(seats: Int): Int = when {
        seats < 2 -> if (state.askedSeats == seats) state.payers ?: 1 else 1   // 좌석 센서를 못 읽어(0) 직접 고른 인원은 그대로
        state.askedSeats == seats -> state.payers ?: seats
        else -> seats                                   // 아직 확인 전: 감지 인원(PRD 5-3)
    }

    fun confirm(seats: Int, payers: Int) {
        state = PayerState(seats, payers.coerceIn(MIN, MAX))
        store.save(state)
    }

    /** 닫기(×): 감지 인원 그대로, 같은 인원이면 다시 묻지 않음. */
    fun dismiss(seats: Int) = confirm(seats, seats)

    companion object {
        const val MIN = 1
        const val MAX = 9          // contract seats_occupied 최대
    }
}
