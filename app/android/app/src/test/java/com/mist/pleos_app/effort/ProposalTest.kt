package com.mist.pleos_app.effort

import com.mist.pleos_app.app.Settings
import com.mist.pleos_app.engine.EffortScreenB
import com.mist.pleos_app.engine.parseEffortConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 제안 A·B(명세 v0.5 F24·F25) → 선택·기록(F26). 글자·금액은 엔진 출력 그대로인지, 기록이 남는지. */
class ProposalTest {

    private val fixtures: File = locate("fixtures")
    private val c = parseEffortConstants(File(fixtures, "constants.json").readText())

    /** R1(PRD 대표 장면)과 같은 출발 기록: 4명, 2시간 10분, 비, 정체 36%. */
    private fun r1Trip(n: Int = 4) = OpenTrip(
        "t1", "2026-11-17T16:00:00+09:00", "태안 꽃지해수욕장", n, 6300.0, routeM = 104300.0,
        congestionRatio = 0.36, congestionM = 37548.0, weatherDep = "rain", sunrise = "07:13", sunset = "17:20",
        arrivedAt = "2026-11-17T18:10:00+09:00", weatherArr = "rain", restAnswerMin = 0,
    )

    /** R6과 같은 짧은 주행: 2명, 10분, 맑음 → 운전자 몫 891.7원 → B 제안 없음. */
    private val r6Trip = OpenTrip(
        "t6", "2026-11-17T10:00:00+09:00", "동네 마트", 2, 600.0, routeM = 400.0,
        congestionRatio = 0.0, congestionM = 0.0, weatherDep = "none", sunrise = "07:13", sunset = "17:20",
        arrivedAt = "2026-11-17T10:10:00+09:00", weatherArr = "none", restAnswerMin = 0,
    )

    @Test
    fun methodAShowsPerPersonFirst() {
        // AC-F24-1: R1 → 한 사람 약 9천 원, 근거 약 3만 6천 원 · 4명. AC-F24-4: A 화면은 A뿐
        val v = proposalViewOf(receiptOf(r1Trip(), c), Settings.METHOD_A, c.items) as ProposalView.A
        assertEquals("오늘 운전을 챙긴다면", v.screen.titleText)
        assertEquals("한 사람 약 9천 원", v.screen.mainText)
        assertEquals("운전 노고 약 3만 6천 원을 운전자 포함 4명이 나눈 값", v.screen.basisText)
        assertEquals("최저임금 1분 178원 기준", v.screen.wageText)
    }

    @Test
    fun methodBMealWithAllItemsAndNoAmount() {
        // AC-F25-1: R1 → 밥. AC-F25-2: 세 항목이 항상 선택지. AC-F25-3: 금액 없음
        val v = proposalViewOf(receiptOf(r1Trip(), c), Settings.METHOD_B, c.items) as ProposalView.B
        assertEquals("meal", v.screen.item)
        assertEquals("오늘은 밥값에서 운전자 빼는 게 어때요", v.screen.text)
        assertEquals(listOf("coffee", "meal", "shared"), v.items.map { it.key })
        assertFalse(v.screen.text.contains("원"))
    }

    @Test
    fun noProposalWhenShareTooSmallOrAlone() {
        // AC-F25-1: R6(G 891.7원) → B 제안 없음(영수증만). Q-07-04(명준 11/8): A도 같은 기준이라 제안 없음
        assertNull(proposalViewOf(receiptOf(r6Trip, c), Settings.METHOD_B, c.items))
        assertNull(proposalViewOf(receiptOf(r6Trip, c), Settings.METHOD_A, c.items))
        // 기준을 넘으면(R1) A는 금액을 보여 준다
        assertTrue(proposalViewOf(receiptOf(r1Trip(), c), Settings.METHOD_A, c.items) is ProposalView.A)
        // 혼자 탄 주행(n = 1)은 A·B 모두 제안 없음
        assertNull(proposalViewOf(receiptOf(r1Trip(n = 1), c), Settings.METHOD_A, c.items))
        assertNull(proposalViewOf(null, Settings.METHOD_A, c.items))
    }

    @Test
    fun sharedExpenseExamplesRotateInPlace() {
        // 공동 경비 회전(디자인 기본값): 문장 속 말만 예시로 바뀌고 처음으로 돌아온다
        val ex = c.items.first { it.key == "shared" }.examples
        val b = EffortScreenB("shared", "오늘은 공동 경비에서 운전자 빼는 게 어때요", ex)
        assertEquals(b.text, bTextAt(b, 0))
        assertEquals("오늘은 ${ex[1]}에서 운전자 빼는 게 어때요", bTextAt(b, 1))
        assertEquals(b.text, bTextAt(b, ex.size))
        val meal = EffortScreenB("meal", "오늘은 밥값에서 운전자 빼는 게 어때요", emptyList())
        assertEquals(meal.text, bTextAt(meal, 3))
    }

    @Test
    fun anyChoiceLeavesOneRecordThatSurvivesRestart() {
        // AC-F26-1: 어떤 선택이든 기록 1건 → 홈. AC-F26-2: 재시작 뒤에도 남음. 좌표 없음
        val store = MemoryEffortStore()
        val f = EffortFlow(store)
        val trip = r1Trip()
        f.depart(trip.copy(arrivedAt = null, weatherArr = null))
        f.arrive(trip.arrivedAt!!, "rain")
        val r = receiptOf(f.state.openTrip, c)!!
        val v = proposalViewOf(r, Settings.METHOD_B, c.items)
        assertTrue(f.finish(recordOf(f.state.openTrip!!, r, v, Settings.METHOD_B, "coffee")))
        assertEquals(EffortPhase.IDLE, f.phase)
        val rec = EffortFlow(store).recent().single()
        assertEquals("2026-11-17", rec.date)
        assertEquals("B", rec.method)
        assertEquals("meal", rec.suggestion)          // 기본으로 올린 항목
        assertEquals("coffee", rec.choice)            // 사용자가 고른 항목
        assertEquals(130.0, rec.driveMin, 1e-9)
        assertEquals(r.headlineText, rec.headlineText)
        assertFalse(rec.toJson().toString().contains("lat"))
    }

    @Test
    fun laterLeavesOneRecordWithZeroRestAndNoAmount() {
        // AC-F29-2: [다음에] = 쉰 시간 0(묻지 않음) + choice = later 기록 1건, 상세에서도 금액을 보이지 않음.
        // 뷰모델(AndroidViewModel)은 JVM 단위 테스트로 띄울 수 없어(Robolectric 없음) later()가 부르는 저장 경로를
        // 같은 순서로 따라 단언한다: answerRest(0) → receiptOf → recordOf(CHOICE_LATER) → finish.
        val store = MemoryEffortStore()
        val f = EffortFlow(store)
        val trip = r1Trip()
        f.depart(trip.copy(arrivedAt = null, weatherArr = null, restAnswerMin = null))
        f.arrive(trip.arrivedAt!!, "rain")
        assertNull(f.state.openTrip?.restAnswerMin)           // 쉰 시간을 묻지 않은 상태
        f.answerRest(0)                                       // later()가 비어 있으면 '없음'(0)으로 채움
        val cur = f.state.openTrip!!
        assertEquals(0, cur.restAnswerMin)
        val r = receiptOf(cur, c)!!
        val v = proposalViewOf(r, Settings.METHOD_A, c.items)
        assertTrue(f.finish(recordOf(cur, r, v, Settings.METHOD_A, CHOICE_LATER)))
        assertEquals(EffortPhase.IDLE, f.phase)
        val rec = EffortFlow(store).recent().single()         // 재시작 뒤에도 1건
        assertEquals(CHOICE_LATER, rec.choice)
        assertEquals("later", rec.choice)
        assertEquals(0, rec.trip?.restAnswerMin)              // 쉰 시간 0
        assertTrue(isClosedChoice(rec.choice))
        assertFalse(recordShowsAmount(rec))                   // 기록 상세 합계는 배수까지만
        assertFalse(rec.toJson().toString().contains("lat\""))
    }

    @Test
    fun methodARecordKeepsPerPersonText() {
        val r = receiptOf(r1Trip(), c)!!
        val v = proposalViewOf(r, Settings.METHOD_A, c.items)
        val rec = recordOf(r1Trip(), r, v, Settings.METHOD_A, CHOICE_CLOSE)
        assertEquals("한 사람 약 9천 원", rec.suggestion)
        assertEquals(CHOICE_CLOSE, rec.choice)
        assertNull(recordOf(r6Trip, receiptOf(r6Trip, c)!!, null, Settings.METHOD_B, CHOICE_CLOSE).suggestion)
    }

    @Test
    fun bigNumberStepsDownForLongAmounts() {
        // #118 D4 · 10-3 ②: 10만 원 이상 또는 15자 이상이면 한 단계 작게
        assertFalse(bigNumberSmall("한 사람 약 9천 원"))
        assertFalse(bigNumberSmall("한 사람 약 9만 8천 원"))
        assertTrue(bigNumberSmall("한 사람 약 12만 3천 원"))
        assertTrue(bigNumberSmall("가".repeat(15)))
        assertFalse(bigNumberSmall("가".repeat(14)))
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
