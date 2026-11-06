package com.mist.pleos_app.app

import com.mist.pleos_app.engine.LedgerEvent
import com.mist.pleos_app.engine.LedgerState
import com.mist.pleos_app.engine.MonthSummary
import com.mist.pleos_app.engine.Place
import com.mist.pleos_app.engine.Row
import com.mist.pleos_app.engine.Vehicle
import com.mist.pleos_app.engine.ledgerStep
import com.mist.pleos_app.engine.monthSummary
import com.mist.pleos_app.ledger.LedgerStore
import com.mist.pleos_app.util.ChagokLog
import java.time.OffsetDateTime

/*
 * F8·F10·F17 장부를 화면에 잇는 곳. 정산·도착·핀·월 합계 규칙은 engine/Ledger.kt(서버 ledger_step과 같은 규칙,
 * fixtures/ledger L1~L5)가 하고, 여기는 "언제 어떤 이벤트를 넣나"와 저장만 한다. 안드로이드 의존 없음 → LedgerFlowTest.
 *
 * 이벤트를 넣는 때
 *   Open   — 화면이 보이고(onStart) 정차이며 누적 주행거리·잔량을 읽은 뒤, 화면이 보일 때마다 1번(F10 "앱 실행·복귀(기어 P)")
 *   Depart — 출발 1탭(F8)
 *   Arrive — trip이 있을 때 기어 P에서 운전석 문이 닫힘 → 열림으로 바뀜(F17, 에뮬 대안)
 */
class LedgerFlow(
    private val store: LedgerStore,
    private val now: () -> OffsetDateTime = { OffsetDateTime.now() },
) {
    var state: LedgerState = store.load()
        private set

    /** 홈 맨 위 정산 카드 문구(닫으면 null). DECISIONS '정산 카드 위치': 전면 화면이 아니라 홈 맨 위 카드. */
    var settleCard: String? = null
        private set

    private var openedThisVisit = false
    private var doorWasOpen: Boolean? = null

    private fun step(ev: LedgerEvent) = ledgerStep(state, ev).also { (next, _) -> state = next; store.save(next) }.second

    /** 이번 방문에 아직 정산(Open)을 안 넣었나. */
    val needsOpen: Boolean get() = !openedThisVisit

    /**
     * 화면이 가려지면(onStop) 다음에 보일 때 다시 Open을 넣는다. 문 기준값은 지우지 않는다:
     * 돌아왔을 때 차량값이 그대로면 새 값이 오지 않아(같은 값은 흐름이 다시 내보내지 않음) 기준을 못 잡고 첫 열림을 놓친다(11/6 콘솔 실측).
     */
    fun onHidden() { openedThisVisit = false }

    /**
     * 화면이 보이는 동안 정차 상태에서 차량값이 들어올 때마다 부른다. 이번 방문에 아직 Open을 안 넣었으면 1번 넣는다.
     * [recentDestination]은 내비 최근 목적지(없으면 null → 핀 없이 금액만, T4).
     */
    fun open(v: Vehicle, odometerKm: Double, seats: Int, recentDestination: Place?) {
        if (openedThisVisit) return
        openedThisVisit = true
        val out = step(LedgerEvent.Open(now().toString(), odometerKm, seats, v.pricePerUnit, v.efficiency, v.priceSource, recentDestination))
        if (out.card != null) settleCard = out.card
        ChagokLog.event("ledger_open", "card" to (out.card != null), "skipped" to out.skipped, "pinned" to out.row?.pinned)
    }

    fun dismissSettle() { settleCard = null }

    /** 출발(F8): trip을 연다(장소 ID·이름·좌표·편도 추정·출발 때 누적 주행거리, AC-F8-3). */
    fun depart(place: Place, routeKm: Double, odometerKm: Double) {
        step(LedgerEvent.Depart(now().toString(), odometerKm, place, routeKm))
        ChagokLog.event("ledger_depart", "trip" to true)
    }

    /**
     * 운전석 문 값이 바뀔 때마다 부른다. trip이 있고 정차 중에 닫힘 → 열림이면 도착(F17). 처음 받은 값은 기준만 잡는다.
     * 반환: 도착으로 처리했으면 true.
     */
    fun onDoor(open: Boolean?, parked: Boolean, v: Vehicle?, odometerKm: Double?, seats: Int): Boolean {
        val was = doorWasOpen
        doorWasOpen = open
        if (open != true || was != false || !parked || state.trip == null || v == null || odometerKm == null) return false
        val out = step(LedgerEvent.Arrive(now().toString(), odometerKm, seats, v.pricePerUnit, v.efficiency, v.priceSource))
        ChagokLog.event("ledger_arrive", "pinned" to out.row?.pinned, "demo" to out.row?.demo)
        return out.row != null
    }

    fun summary(month: String = now().toString().take(7)): MonthSummary = monthSummary(state.rows, month)

    /** 이번 달 기록(최신이 위). 핀 = 다녀온 곳. */
    fun rows(month: String = now().toString().take(7)): List<Row> = state.rows.filter { it.toTs.take(7) == month }.reversed()

    /** 기록 초기화(S1, PT 리허설 전). 다음 Open은 스냅샷만 저장(AC-S1-2). */
    fun reset() {
        store.clear(); state = LedgerState(); settleCard = null; openedThisVisit = false
    }
}
