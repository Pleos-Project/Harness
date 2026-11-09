package com.mist.pleos_app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.mist.pleos_app.R
import com.mist.pleos_app.effort.CHOICE_ACCEPT
import com.mist.pleos_app.effort.EffortUi
import com.mist.pleos_app.effort.MidwayUi
import com.mist.pleos_app.effort.OpenTrip
import com.mist.pleos_app.effort.ProposalView
import com.mist.pleos_app.effort.clockText
import com.mist.pleos_app.effort.ieyo
import com.mist.pleos_app.effort.isUnsettled
import com.mist.pleos_app.effort.midwayBText
import com.mist.pleos_app.engine.EffortItem
import com.mist.pleos_app.ui.components.CgButton
import com.mist.pleos_app.ui.components.CgButtonKind
import com.mist.pleos_app.ui.components.CgIcon
import com.mist.pleos_app.ui.components.CgIconButton
import com.mist.pleos_app.ui.components.CgOfferCard
import com.mist.pleos_app.ui.components.CgOptionButton
import com.mist.pleos_app.ui.components.CgSize
import com.mist.pleos_app.ui.components.CgTitleBar
import com.mist.pleos_app.ui.theme.Chagok
import com.mist.pleos_app.ui.theme.gpx

/*
 * 지금까지의 노고(중간 정산, F27) — Design v2 v41 `screens/midway.html` mid-a · mid-b · *-done.
 * 왼쪽 "오늘 주행 영수증"(v3)(도착과 같은 줄, 쉰 시간 줄 없음) / 오른쪽 제안 카드: A [좋아요] · B 항목 → 여기까지 기록 · [계속 가기].
 * 숫자는 엔진(midwayReceiptOf)에서만 온다.
 */
@Composable
internal fun EffortMidwayScreen(ui: EffortUi, onPick: (String) -> Unit, onClose: () -> Unit) {
    val m = ui.midway
    val trip = ui.openTrip
    if (m == null || trip == null) { LaunchedEffect(Unit) { onClose() }; return }   // 프로세스가 다시 만들어지면 메모리 상태가 없다 → 홈
    val narrow = LocalNarrow.current
    val r = m.receipt
    val showPaper = r != null && !isUnsettled(r)
    var open by rememberSaveable(m.at) { mutableStateOf<String?>(null) }
    val since = stringResource(R.string.effort_mid_receipt_sub, clockText(trip.departedAt), trip.n)   // "14:20 출발 · 아직 가는 중 · 4명"
    val cap = stringResource(R.string.effort_mid_cap, clockText(trip.departedAt))                 // 카드 캡션 "14:20 출발 · 지금까지"
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (narrow) 16.gpx else 24.gpx)) {
        CgTitleBar(
            stringResource(R.string.effort_mid_title), compact = narrow,
            leading = { CgIconButton(stringResource(R.string.back), CgIcon.Back, onClose, plain = true) },
            trailing = if (narrow) null else ({ Text(stringResource(R.string.effort_mid_people, trip.n), style = Chagok.type.labelM, color = Chagok.colors.textTertiary) }),
        )
        val receipt: @Composable (Modifier) -> Unit = { mod ->
            if (r != null && showPaper) ReceiptColumn(
                trip, r, ui, narrow, reduce = true, paperShift = 0f, open = open,
                onToggle = { k -> open = if (open == k) null else k }, onChangeRest = {}, modifier = mod, editable = false,
                midwaySub = since,
            )
        }
        val card: @Composable (Modifier) -> Unit = { mod -> MidwayCard(m, cap, ui.items, narrow, onPick, onClose, mod) }
        if (narrow) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.gpx)) {
                receipt(Modifier.fillMaxWidth())
                card(Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.gpx)) {
                if (showPaper) receipt(Modifier.weight(1f).fillMaxHeight()) else Spacer(Modifier.weight(1f))
                card(Modifier.width(640.gpx).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun MidwayCard(m: MidwayUi, since: String, items: List<EffortItem>, narrow: Boolean, onPick: (String) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val c = Chagok.colors
    val t = Chagok.type
    val head = if (narrow) t.titleL else t.headlineS
    val big = if (narrow) t.headlineS else t.headlineL
    val why = if (narrow) t.labelM else t.bodyM
    val btn = if (narrow) CgSize.M else CgSize.L
    val keep: @Composable (Boolean) -> Unit = { filled ->
        CgButton(stringResource(R.string.effort_mid_keep), onClose, Modifier.fillMaxWidth(), kind = if (filled) CgButtonKind.Filled else CgButtonKind.Basic, size = btn)
    }
    CgOfferCard(modifier, compact = narrow) {
        val view = m.proposal
        val r = m.receipt
        when {
            m.pending -> {
                Text(since, style = why, color = c.textTertiary)
                Text(stringResource(R.string.effort_mid_pending), style = head, color = c.textPrimary)
                Filler(narrow)
                keep(false)
            }
            m.failed || r == null -> {
                Text(stringResource(R.string.effort_mid_failed), style = head, color = c.textPrimary)
                Text(stringResource(R.string.effort_mid_failed_why), style = why, color = c.textSecondary)
                Filler(narrow)
                keep(true)
            }
            isUnsettled(r) -> {
                Text(r.statusText.orEmpty(), style = head, color = c.textPrimary)
                Text(stringResource(R.string.effort_mid_none_why), style = why, color = c.textSecondary)
                Filler(narrow)
                keep(true)
            }
            m.choice != null -> { Text(since, style = why, color = c.textTertiary); DoneMid(m, view, items, narrow) { keep(true) } }
            view is ProposalView.A -> {
                Text(since, style = why, color = c.textTertiary)   // v104 mid-a: 캡션
                Text(stringResource(R.string.effort_mid_a_title), style = head, color = c.textPrimary)
                Text(view.screen.mainText, style = big, color = c.textPrimary, maxLines = 1)
                Column(verticalArrangement = Arrangement.spacedBy(4.gpx)) {
                    Text(view.screen.basisText, style = why, color = c.textSecondary)
                    Text(view.screen.wageText, style = why, color = c.textTertiary)
                }
                Filler(narrow)
                Text(stringResource(R.string.effort_mid_note), style = why, color = c.textTertiary)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.gpx)) {
                    CgButton(stringResource(R.string.effort_offer_like), { onPick(CHOICE_ACCEPT) }, Modifier.weight(1f), size = btn)
                    CgButton(stringResource(R.string.effort_mid_keep), onClose, Modifier.weight(1f), kind = CgButtonKind.Basic, size = btn)
                }
            }
            view is ProposalView.B -> {
                Text(midwayBText(view.screen.text), style = head, color = c.textPrimary)
                view.items.firstOrNull { it.key == view.screen.item }?.let { Text(stringResource(R.string.effort_mid_b_sub, ieyo(it.label)), style = why, color = c.textSecondary) }
                Filler(narrow)
                val opt: @Composable (Modifier, EffortItem) -> Unit = { mod, item ->
                    CgOptionButton(item.examples.ifEmpty { listOf(item.label) }, isDefault = item.key == view.screen.item, onClick = { onPick(item.key) }, modifier = mod, compact = narrow)
                }
                if (narrow) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.gpx)) { view.items.forEach { opt(Modifier.weight(1f), it) } }
                else Column(verticalArrangement = Arrangement.spacedBy(12.gpx)) { view.items.forEach { opt(Modifier.fillMaxWidth(), it) } }
                keep(false)
            }
            else -> {
                // 제안 없음: 혼자 · 남은 몫이 커피 1인분 절반보다 작음(Q-07-04) · 앞서 다 정산함
                Text(since, style = why, color = c.textTertiary)
                Text(stringResource(R.string.effort_mid_none), style = head, color = c.textPrimary)
                Text(stringResource(R.string.effort_mid_none_why), style = why, color = c.textSecondary)
                Filler(narrow)
                keep(true)
            }
        }
    }
}

/** 고른 뒤(mid-a-done · mid-b-done): 여기까지 기록해 뒀다 + [계속 가기]. */
@Composable
private fun ColumnScope.DoneMid(m: MidwayUi, view: ProposalView?, items: List<EffortItem>, narrow: Boolean, keep: @Composable () -> Unit) {
    val c = Chagok.colors
    val t = Chagok.type
    val head = if (narrow) t.titleL else t.headlineS
    val why = if (narrow) t.labelM else t.bodyM
    if (view is ProposalView.A) {
        Text(stringResource(R.string.effort_mid_a_done), style = head, color = c.textPrimary)
        Text(view.screen.mainText, style = if (narrow) t.titleM else t.titleL, color = c.textSecondary)
    } else {
        val phrase = items.firstOrNull { it.key == m.choice }?.phraseText ?: m.choice.orEmpty()
        Text(stringResource(R.string.effort_mid_b_done, phrase), style = head, color = c.textPrimary)
    }
    Text(stringResource(R.string.effort_mid_done_note), style = why, color = c.textSecondary)
    Filler(narrow)
    keep()
}
