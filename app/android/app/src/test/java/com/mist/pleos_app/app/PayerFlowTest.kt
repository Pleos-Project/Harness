package com.mist.pleos_app.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F11 인원 확인 토스트(PRD v1.5, DECISIONS 11/6). */
class PayerFlowTest {

    @Test
    fun soloNeverAsks() {
        val f = PayerFlow(MemoryPayerStore())
        assertFalse(f.shouldAsk(1))
        assertEquals(1, f.payersFor(1))
    }

    @Test
    fun askThenConfirmFewerPayers() {
        // PT 4단계: 착석 1 → 4, 확인 전에는 감지 인원으로 1인 몫
        val store = MemoryPayerStore()
        val f = PayerFlow(store)
        assertTrue(f.shouldAsk(4))
        assertEquals(4, f.payersFor(4))
        // 가족: 운전자 1명만 냄 → 1인 몫 숨김(엔진은 2 미만이면 안 붙임)
        f.confirm(4, 1)
        assertFalse(f.shouldAsk(4))
        assertEquals(1, f.payersFor(4))
        // 재시작 뒤에도 같은 인원이면 다시 묻지 않음
        assertFalse(PayerFlow(store).shouldAsk(4))
        assertEquals(1, PayerFlow(store).payersFor(4))
    }

    @Test
    fun dismissKeepsDetectedAndOnlyAsksAgainOnChange() {
        val f = PayerFlow(MemoryPayerStore())
        f.dismiss(3)
        assertFalse(f.shouldAsk(3))
        assertEquals(3, f.payersFor(3))
        assertEquals(1, f.payersFor(1))       // 혼자가 되면 1
        assertFalse(f.shouldAsk(1))
        assertFalse(f.shouldAsk(3))           // 다시 3명: 지난번 확인값과 같음
        assertTrue(f.shouldAsk(2))            // 다른 인원이면 다시 묻는다
        assertEquals(2, f.payersFor(2))
    }

    @Test
    fun confirmClampsToContractRange() {
        val f = PayerFlow(MemoryPayerStore())
        f.confirm(2, 0); assertEquals(1, f.payersFor(2))
        f.confirm(3, 12); assertEquals(9, f.payersFor(3))
    }
}
