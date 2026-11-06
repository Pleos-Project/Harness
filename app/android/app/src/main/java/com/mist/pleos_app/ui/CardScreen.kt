package com.mist.pleos_app.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.mist.pleos_app.R
import com.mist.pleos_app.app.CardUi
import com.mist.pleos_app.app.Conditions
import com.mist.pleos_app.app.minutesText
import com.mist.pleos_app.engine.Card
import com.mist.pleos_app.engine.CardCost
import com.mist.pleos_app.engine.CardDisplay
import com.mist.pleos_app.engine.THEME_KO
import com.mist.pleos_app.engine.approx
import com.mist.pleos_app.navi.DepartState
import com.mist.pleos_app.ui.components.BadgeKind
import com.mist.pleos_app.ui.components.CgBadge
import com.mist.pleos_app.ui.components.CgBody
import com.mist.pleos_app.ui.components.CgButton
import com.mist.pleos_app.ui.components.CgButtonKind
import com.mist.pleos_app.ui.components.CgCaption
import com.mist.pleos_app.ui.components.CgCard
import com.mist.pleos_app.ui.components.CgIconButton
import com.mist.pleos_app.ui.components.CgNotice
import com.mist.pleos_app.ui.components.CgTitle
import com.mist.pleos_app.ui.theme.ChagokTheme
import com.mist.pleos_app.ui.theme.gpx
import kotlin.math.abs

/**
 * F5 한 장. 정보 순서: 테마·시간 → 장소명 → 금액 배지 → 이유 한 줄 → (탭) 비용 분해(PRD v1.3 6-4).
 * 교체는 앞면을 옆으로 밀거나 아이콘 버튼(남은 횟수 표시). 2회 쓰고 한 번 더 넘기면 "어떤 쪽으로 다시 볼까요?"(PRD v1.6, 이유 칩은 다음 PR).
 * 배지·이유·비용 문구는 엔진(display·reason)이 만든 것을 그대로 쓴다.
 * 일러스트는 SVG를 앱에 넣는 방식이 정해지면(열린 결정 12·13) 앞면 위에 붙인다. 출발(F8)은 P5.
 */
@Composable
fun CardScreen(ui: CardUi?, depart: DepartState, onSwap: () -> Unit, onRange: () -> Unit, onDepart: (Card) -> Unit, ev: Boolean = true) {
    when (ui) {
        null -> Column(verticalArrangement = Arrangement.spacedBy(24.gpx)) {
            CgTitle(stringResource(R.string.screen_card))
            CgNotice(stringResource(R.string.reading_vehicle))
        }
        is CardUi.Shown -> Shown(ui, depart, ev, onSwap) { onDepart(ui.card) }
        is CardUi.AskTheme -> Column(verticalArrangement = Arrangement.spacedBy(24.gpx)) {
            CgCard(Modifier.fillMaxWidth(), onClick = onRange) {
                CgTitle(stringResource(R.string.ask_theme))
                CgBody(stringResource(R.string.ask_theme_sub), secondary = true)
            }
            CgButton(stringResource(R.string.change_conditions), onRange, Modifier.fillMaxWidth())
        }
        is CardUi.Empty -> Column(verticalArrangement = Arrangement.spacedBy(24.gpx)) {
            CgNotice(stringResource(if (ui.fromToday) R.string.only_nearby else R.string.few_candidates))
            CgButton(stringResource(R.string.change_conditions), onRange, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Shown(ui: CardUi.Shown, depart: DepartState, ev: Boolean, onSwap: () -> Unit, onDepart: () -> Unit) {
    var showCost by remember(ui.card.id) { mutableStateOf(false) }
    val narrow = LocalNarrow.current
    val front = @Composable { m: Modifier -> FrontCard(ui.card, showCost, onTap = { showCost = !showCost }, onSwipe = onSwap, modifier = m) }
    val side = @Composable { m: Modifier ->
        Column(m, verticalArrangement = Arrangement.spacedBy(20.gpx)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.gpx)) {
                CgIconButton("›", stringResource(R.string.swap_label), onSwap)
                Column {
                    CgBody(stringResource(R.string.stack_left, ui.stackLeft))
                    CgCaption(stringResource(R.string.swaps_left, ui.swapsLeft))
                }
            }
            // 출발 1탭 → 순정 내비(F8). 실패하면 안내 + 같은 버튼으로 재시도(X2)
            if (depart == DepartState.FAILED) CgNotice(stringResource(R.string.navi_failed))
            CgButton(
                stringResource(if (depart == DepartState.REQUESTING) R.string.depart_requesting else R.string.depart),
                onDepart, Modifier.fillMaxWidth(), enabled = depart != DepartState.REQUESTING,
            )
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.gpx)) {
        // 자동 전환 안내(PRD v1.6 5-3): "추가 주유 없이 갈 곳이 없어 +1만 원 기준이에요"(EV "추가 충전 없이")
        ui.raisedTo?.let { CgNotice(stringResource(if (it == 10_000) R.string.raised_10k else R.string.raised_30k, zeroText(ev))) }
        if (ui.fewCandidates) CgNotice(stringResource(R.string.few_candidates))
        if (narrow) {
            front(Modifier.fillMaxWidth())
            side(Modifier.fillMaxWidth())
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(32.gpx)) {
                front(Modifier.weight(1.6f))
                side(Modifier.weight(1f))
            }
        }
    }
}

/** 앞면 카드. 옆으로 밀면 교체, 탭하면 비용 분해를 펼친다. */
@Composable
private fun FrontCard(card: Card, showCost: Boolean, onTap: () -> Unit, onSwipe: () -> Unit, modifier: Modifier) {
    var dx by remember(card.id) { mutableFloatStateOf(0f) }
    val swipe = Modifier
        .graphicsLayer { translationX = dx }
        .pointerInput(card.id) {
            detectHorizontalDragGestures(
                onDragEnd = { if (abs(dx) > size.width / 4f) onSwipe(); dx = 0f },
                onDragCancel = { dx = 0f },
            ) { change, amount -> change.consume(); dx += amount }
        }
    CgCard(modifier.then(swipe), onClick = onTap) {
        CgCaption("${THEME_KO[card.theme] ?: card.theme} · ${minutesText(card.minutes)}")
        CgTitle(card.name)
        CgBadge(card.display.badgeText, badgeKind(card.display))
        // 1인 몫(F11): 착석 2 이상일 때만 엔진이 문구를 준다("1인 7천 원 안팎"), 착석 1이면 없음(AC-F11-2)
        card.display.perPersonText?.let { CgBadge(it, BadgeKind.Neutral) }
        CgBody(card.reason, secondary = true)
        // 통행료·주차는 범위(주유·충전 기준)와 따로 작은 줄로(PRD v1.6 5-3, "0원" 오해 방지)
        CgCaption(stringResource(R.string.card_extra_costs, tollText(card.cost.toll), parkingText(card.cost.parking)))
        if (showCost) CostBreakdown(card)
        else CgCaption(stringResource(R.string.cost_tap_hint))
    }
}

private fun badgeKind(d: CardDisplay): BadgeKind = when {
    d.tight -> BadgeKind.Tight
    d.zeroExtra -> BadgeKind.Zero
    else -> BadgeKind.Extra
}

/** 비용 분해(어림): 연료 · 통행료(왕복) · 주차(없으면 "정보 없음") · 합계(5천 원 어림) · 1인 몫. */
@Composable
private fun CostBreakdown(card: Card) {
    val c = card.cost
    Column(verticalArrangement = Arrangement.spacedBy(8.gpx)) {
        CostRow(stringResource(R.string.cost_fuel), around(c.fuel))
        CostRow(stringResource(R.string.cost_toll), tollText(c.toll))
        CostRow(stringResource(R.string.cost_parking), parkingText(c.parking))
        CostRow(stringResource(R.string.cost_total), card.display.totalText)
        card.display.perPersonText?.let { CostRow(stringResource(R.string.cost_per_person), it) }
    }
}

@Composable
private fun tollText(toll: Int): String = if (toll > 0) around(toll) else stringResource(R.string.cost_none)

@Composable
private fun parkingText(parking: Int?): String =
    parking?.let { if (it > 0) around(it) else stringResource(R.string.cost_none) } ?: stringResource(R.string.cost_unknown)

/** 분해 항목은 천 원 어림(엔진 approx). 어림이 0이면 "1천 원 미만". */
private fun around(krw: Int): String = approx(krw.toDouble(), 1_000).let { if (it.under) it.text else "약 ${it.text}" }

@Composable
private fun CostRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        CgBody(label, secondary = true)
        CgBody(value)
    }
}

// ------------------------------------------------------------ 미리보기
private val previewCard = Card(
    "p_dumulmeori", "양평 두물머리", "river", 37.5363, 127.314, 37.2, 42, "75분 안",
    CardCost(fuel = 4_200, toll = 3_600, parking = 2_000, total = 9_800, perPerson = null),
    CardDisplay("1만 원 안팎", null, zeroExtra = true, tight = false, ringKrw = 0, badgeText = "추가 충전 없이 · 50km 남기고", marginKm = 50),
    "추가 충전 없이 · 75분 안 · 지난번과 다른 테마",
)

@Preview(name = "한 장·가로", widthDp = 1280, heightDp = 720)
@Preview(name = "한 장·세로", widthDp = 640, heightDp = 720)
@Composable
private fun CardPreview() {
    ChagokTheme(dark = true) { CardScreen(CardUi.Shown("s", previewCard, 3, 2, null, Conditions()), DepartState.IDLE, {}, {}, {}) }
}
