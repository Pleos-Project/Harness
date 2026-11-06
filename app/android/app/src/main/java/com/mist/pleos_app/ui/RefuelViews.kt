package com.mist.pleos_app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.window.Dialog
import com.mist.pleos_app.R
import com.mist.pleos_app.app.RefuelFlow
import com.mist.pleos_app.app.RefuelUi
import com.mist.pleos_app.engine.approx
import com.mist.pleos_app.ui.components.CgBody
import com.mist.pleos_app.ui.components.CgButton
import com.mist.pleos_app.ui.components.CgButtonKind
import com.mist.pleos_app.ui.components.CgCard
import com.mist.pleos_app.ui.components.CgChip
import com.mist.pleos_app.ui.components.CgIconButton
import com.mist.pleos_app.ui.components.CgTitle
import com.mist.pleos_app.ui.theme.Chagok
import com.mist.pleos_app.ui.theme.ChagokTheme
import com.mist.pleos_app.ui.theme.gpx
import kotlinx.coroutines.delay

/** 충전 카드가 뜬 뒤 한 장으로 넘어가기까지(명세 F9 "탭 또는 3초 후"). 화면이 보이는 동안만 센다. */
const val CHARGE_CARD_AUTO_MS = 3_000L

/**
 * F9 홈 맨 위 카드: "방금 약 1만 4천 원어치 충전 → 갈 곳 한 장 볼까요?"(design/RULES.md charge-card).
 * 탭하거나 3초 뒤 범위 화면 없이 한 장(AC-F9-2). 닫기 가능.
 */
@Composable
fun ChargeCard(ui: RefuelUi.Charged, onOpen: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val open by rememberUpdatedState(onOpen)
    LaunchedEffect(ui) { delay(CHARGE_CARD_AUTO_MS); open() }
    val head = ui.text ?: stringResource(R.string.charge_done_only)
    CgCard(modifier, onClick = onOpen) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            CgBody(stringResource(R.string.charge_card, head), Modifier.weight(1f))
            CgButton(stringResource(R.string.close), onClose, kind = CgButtonKind.Basic)
        }
    }
}

/**
 * F9 내연 금액 시트: 3만 / 5만 / 가득 + 1만 원 단위 ±(키보드 없음, DECISIONS 11/5). 확인하면 한 장.
 * '가득'은 엔진 fullTankAmount(주유 시작 때 연료 %부터 100%까지 × 탱크 × 단가, 천 원 어림).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RefuelSheet(ui: RefuelUi.Sheet, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    var krw by rememberSaveable(ui) { mutableIntStateOf(RefuelFlow.PRESETS.last()) }
    val gap = Arrangement.spacedBy(16.gpx)
    Dialog(onDismissRequest = onDismiss) {
        CgCard(Modifier.width(880.gpx)) {
            CgTitle(stringResource(R.string.refuel_title))
            FlowRow(horizontalArrangement = gap, verticalArrangement = gap) {
                RefuelFlow.PRESETS.forEach { p -> CgChip(won(p), selected = krw == p, onClick = { krw = p }) }
                CgChip(stringResource(R.string.refuel_full, won(ui.fullKrw)), selected = krw == ui.fullKrw && ui.fullKrw !in RefuelFlow.PRESETS,
                    onClick = { krw = ui.fullKrw }, enabled = ui.fullKrw > 0)
            }
            Row(horizontalArrangement = gap, verticalAlignment = Alignment.CenterVertically) {
                CgIconButton("−", stringResource(R.string.refuel_minus), { krw -= RefuelFlow.STEP }, enabled = krw > RefuelFlow.STEP)
                Text(won(krw), style = Chagok.type.titleM, color = Chagok.colors.textPrimary)
                CgIconButton("+", stringResource(R.string.refuel_plus), { krw += RefuelFlow.STEP })
                Spacer(Modifier.weight(1f))
            }
            Row(horizontalArrangement = gap, modifier = Modifier.fillMaxWidth()) {
                CgButton(stringResource(R.string.refuel_skip), onDismiss, Modifier.weight(1f), kind = CgButtonKind.Basic)
                CgButton(stringResource(R.string.refuel_confirm), { onConfirm(krw) }, Modifier.weight(1f))
            }
        }
    }
}

/** 금액 표기는 엔진 approx(천 원 단위) 문구 그대로: 50,000 → "5만 원". */
private fun won(krw: Int): String = approx(krw.toDouble(), 1_000).text

@Preview(name = "충전 카드", widthDp = 1280, heightDp = 200)
@Composable
private fun ChargeCardPreview() {
    ChagokTheme(dark = true) { ChargeCard(RefuelUi.Charged("방금 약 1만 4천 원어치 충전"), {}, {}) }
}
