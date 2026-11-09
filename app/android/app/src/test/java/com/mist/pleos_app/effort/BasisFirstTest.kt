package com.mist.pleos_app.effort

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 근거 펼침은 첫 문장만, 출처는 숨김(#118 HANDOFF_v3 5-5). */
class BasisFirstTest {
    @Test
    fun firstSentenceOnly() {
        assertEquals("비 오는 날엔 맑은 날보다 운전 부담이 40% 커요", basisFirst("비 오는 날엔 맑은 날보다 운전 부담이 40% 커요 · 시뮬레이터 30명 연구(Frontiers in Neuroscience 2026)"))
        assertEquals("출처 없는 글", basisFirst("출처 없는 글"))
        assertNull(basisFirst(null))
        assertNull(basisFirst(" · 출처만"))
    }
}
