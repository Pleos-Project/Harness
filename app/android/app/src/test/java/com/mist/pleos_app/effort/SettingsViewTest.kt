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
    fun itemsWithPriceAndSource() {
        val items = wageSourceOf(c).items
        assertEquals(listOf("커피", "밥", "공동 경비"), items.map { it.label })   // 가격 오름차순
        assertEquals(listOf("4,700원", "10,731원", "69,000원"), items.map { it.priceText })
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
