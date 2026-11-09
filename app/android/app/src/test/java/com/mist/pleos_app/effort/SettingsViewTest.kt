package com.mist.pleos_app.effort

import com.mist.pleos_app.engine.effortProposal
import com.mist.pleos_app.engine.parseEffortConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 설정 › 단가 출처(S20): A 화면 근거 줄과 같은 문구, 시급·1분 단가, B 항목 가격과 출처. */
class SettingsViewTest {

    private val c = parseEffortConstants(File(locate("fixtures"), "constants.json").readText())

    @Test
    fun wageSameAsScreenA() {
        val w = wageSourceOf(c)
        assertEquals("최저임금 1분 178원 기준", w.wageText)
        assertEquals(effortProposal(3600.0 * 100, 2, c).a.wageText, w.wageText)   // A 화면과 한 글자도 다르지 않게
        assertEquals("최저임금", w.wageLabel)
        assertEquals("10,700원", w.hourlyText)
        assertEquals("178원", w.perMinText)                                      // 10700 ÷ 60 = 178.33 → 178
    }

    @Test
    fun wageConstantMovesAmountAndSourceTogether() {
        // AC-F24-2: 단가 상수를 바꾸면 화면 금액과 출처 표기가 같이 바뀐다(설정 › 단가 출처도 같이)
        val c2 = c.copy(wagePerHour = 12000.0, wageLabel = "다른 기준")
        val p1 = effortProposal(3600.0 * 100, 2, c)
        val p2 = effortProposal(3600.0 * 100, 2, c2)
        assertEquals(10700.0, p1.amountKrw, 1e-9)                                    // 60분 × 100% × 10,700원/시
        assertEquals(12000.0, p2.amountKrw, 1e-9)
        assertEquals("다른 기준 1분 200원 기준", p2.a.wageText)
        assertEquals(p2.a.wageText, wageSourceOf(c2).wageText)
    }

    @Test
    fun wageYearOnlyInOpenLine() {
        // #96·명준 #86: 연도가 있으면 펼침 줄만 "2027년 최저임금 …", 접힌 줄(A 화면 근거와 같음)은 그대로
        val w = wageSourceOf(c)
        assertEquals(2027, c.wageYear)
        assertEquals("2027년 최저임금", w.wageYearLabel)
        assertEquals("최저임금 1분 178원 기준", w.wageText)
        assertEquals("최저임금", wageSourceOf(c.copy(wageYear = null)).wageYearLabel)   // 없으면 연도 없이
    }

    @Test
    fun manWonAndExactPerMin() {
        // #75 v41 설정 › 단가 출처 펼침: "최저임금 시급 1만 700원을 60분으로 나눈 값이에요(1분 178.33원)"
        assertEquals("1만 700원", manWonText(10700.0))
        assertEquals("2만 원", manWonText(20000.0))
        assertEquals("4,700원", manWonText(4700.0))
        assertEquals("12만 3,456원", manWonText(123456.0))
        assertEquals("178.33원", perMinExactText(c))
        val w = wageSourceOf(c)
        assertEquals("1만 700원", w.hourlyManText)
        assertEquals("178.33원", w.perMinExactText)
    }

    @Test
    fun demoSummaryIsDestAndMultiplier() {
        val scene = parseDemoScene(File(locate("fixtures"), "effort/demo.json").readText())
        assertEquals("태안 꽃지해수욕장" to "1.57배", demoSummaryOf(scene, c))
    }

    @Test
    fun itemsWithPriceAndSource() {
        val items = wageSourceOf(c).items
        assertEquals(listOf("커피", "밥", "공동 경비"), items.map { it.label })   // 가격 오름차순
        assertEquals(listOf("4,700원", "10,711원", "69,000원"), items.map { it.priceText })
        assertTrue(items.all { it.source.isNotBlank() })
    }

    @Test
    fun wonTextRoundsHalfUp() {
        assertEquals("10,731원", wonText(10730.5))
        assertEquals("0원", wonText(0.0))
    }

    private fun locate(rel: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, rel)
            if (f.isDirectory && File(f, "constants.json").exists()) return f
            dir = dir.parentFile
        }
        throw IllegalStateException("$rel 을 찾지 못함")
    }
}
