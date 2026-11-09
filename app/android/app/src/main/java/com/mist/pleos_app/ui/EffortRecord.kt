package com.mist.pleos_app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.mist.pleos_app.R
import com.mist.pleos_app.app.Settings
import com.mist.pleos_app.effort.CHOICE_ACCEPT
import com.mist.pleos_app.effort.CHOICE_CLOSE
import com.mist.pleos_app.effort.EffortRecord
import com.mist.pleos_app.effort.EffortUi
import com.mist.pleos_app.effort.dayText
import com.mist.pleos_app.effort.recordLogLine
import com.mist.pleos_app.effort.recordReceiptOf
import com.mist.pleos_app.ui.components.CgButton
import com.mist.pleos_app.ui.components.CgButtonKind
import com.mist.pleos_app.ui.components.CgEmpty
import com.mist.pleos_app.ui.components.CgIcon
import com.mist.pleos_app.ui.components.CgIconButton
import com.mist.pleos_app.ui.components.CgIconMark
import com.mist.pleos_app.ui.components.CgOfferCard
import com.mist.pleos_app.ui.components.CgSize
import com.mist.pleos_app.ui.components.CgTitleBar
import com.mist.pleos_app.ui.theme.Chagok
import com.mist.pleos_app.ui.theme.gpx

/*
 * 주행 기록 목록 · 기록 상세 — Design v2 v41 `screens/record.html`(log · log-detail · log-detail-b), 명준 Q-08-01(11/8).
 * 홈 위 바 [기록 전체] → 목록, 최근 기록 카드·목록 줄 → 상세. 좌표 없이 수치와 고른 것만 이 차에 남는다.
 */

// ------------------------------------------------------------ 목록(log)
@Composable
internal fun EffortLogScreen(ui: EffortUi, onBack: () -> Unit, onRecord: (String) -> Unit) {
    val narrow = LocalNarrow.current
    val c = Chagok.colors
    val t = Chagok.type
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.gpx)) {
        CgTitleBar(stringResource(R.string.effort_log_title), compact = narrow,
            leading = { CgIconButton(stringResource(R.string.back), CgIcon.Back, onBack, plain = true) })
        if (ui.records.isEmpty()) {
            CgEmpty(stringResource(R.string.effort_recent_empty), stringResource(R.string.effort_recent_empty_hint), Modifier.fillMaxWidth().height(240.gpx))
        } else {
            val shape = RoundedCornerShape(40.gpx)
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).background(c.surfaceCard, shape).border(1.gpx, c.line, shape).verticalScroll(rememberScrollState())) {
                ui.records.forEachIndexed { i, r ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.gpx).background(c.line))
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = if (narrow) 112.gpx else 136.gpx).clickable { onRecord(r.tripId) }
                            .padding(horizontal = if (narrow) 24.gpx else 40.gpx, vertical = 20.gpx),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (narrow) 16.gpx else 32.gpx),
                    ) {
                        if (!narrow) Text(dayText(r.date + "T"), Modifier.width(180.gpx), style = t.bodyM, color = c.textTertiary)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.gpx)) {
                            if (narrow) Text(dayText(r.date + "T"), style = t.labelM, color = c.textTertiary)
                            Text(r.destName, style = t.titleS, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(recordLogLine(r, ui.constants), style = if (narrow) t.labelM else t.bodyM, color = c.textSecondary, maxLines = 1)
                            if (narrow) Text(recordNoteText(r, ui), style = t.labelM, color = c.textTertiary, maxLines = 1)
                        }
                        if (!narrow) Text(recordNoteText(r, ui), style = t.bodyM, color = c.textSecondary, maxLines = 1)
                        CgIconMark(CgIcon.Chevron, 28.gpx, c.textTertiary, Modifier.graphicsLayer { rotationZ = -90f })
                    }
                }
            }
        }
        Text(stringResource(R.string.effort_log_note), Modifier.padding(start = 8.gpx), style = t.labelM, color = c.textTertiary)
    }
}

// ------------------------------------------------------------ 상세(log-detail · log-detail-b)
@Composable
internal fun EffortRecordScreen(ui: EffortUi, onBack: () -> Unit) {
    val r = ui.record ?: return
    val narrow = LocalNarrow.current
    val c = Chagok.colors
    val t = Chagok.type
    val receipt = recordReceiptOf(r, ui.constants)
    var open by rememberSaveable(r.tripId) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (narrow) 16.gpx else 24.gpx)) {
        CgTitleBar(stringResource(R.string.effort_record_title, dayText(r.date + "T"), r.destName), compact = narrow,
            leading = { CgIconButton(stringResource(R.string.back), CgIcon.Back, onBack, plain = true) })
        val paper: @Composable (Modifier) -> Unit = { m ->
            val trip = r.trip
            if (receipt != null && trip != null) ReceiptColumn(
                trip, receipt, ui, narrow, reduce = true, paperShift = 0f, open = open,
                onToggle = { k -> open = if (open == k) null else k }, onChangeRest = {}, modifier = m, editable = false,
            )
        }
        val card: @Composable (Modifier) -> Unit = { m ->
            CgOfferCard(m, compact = narrow) {
                Text(stringResource(R.string.effort_record_day_cap), style = t.labelM, color = c.textTertiary)
                Text(receipt?.multiplierText ?: r.headlineText, style = if (narrow) t.titleL else t.headlineS, color = c.textPrimary)
                Text(dayEffortLine(r, receipt?.proposal?.amountText), style = t.bodyM, color = c.textSecondary)
                Box(Modifier.fillMaxWidth().padding(vertical = 8.gpx).height(1.gpx).background(c.line))
                Text(stringResource(R.string.effort_record_choice_cap), style = t.labelM, color = c.textTertiary)
                Text(choiceLine(r, ui), style = t.bodyM, color = c.textSecondary)
                Text(stringResource(R.string.effort_record_note), style = t.labelM, color = c.textTertiary)
                if (!narrow) Spacer(Modifier.weight(1f))
                CgButton(stringResource(R.string.close), onBack, Modifier.fillMaxWidth(), kind = CgButtonKind.Basic, size = if (narrow) CgSize.M else CgSize.L)
            }
        }
        if (narrow) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.gpx)) {
                paper(Modifier.fillMaxWidth())
                card(Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.gpx)) {
                if (receipt != null) paper(Modifier.weight(1f).fillMaxHeight()) else Spacer(Modifier.weight(1f))
                card(Modifier.width(640.gpx).fillMaxHeight())
            }
        }
    }
}

/** "운전 노고 약 3만 6천 원 · 운전 2시간 10분 · 104km · 함께 4명"(금액·거리를 모르면 빼고). */
@Composable
private fun dayEffortLine(r: EffortRecord, amountText: String?): String = listOfNotNull(
    amountText?.let { stringResource(R.string.effort_record_amount, it) },
    if (r.choice == com.mist.pleos_app.effort.CHOICE_UNSETTLED) null   // 정리하지 못함: 운전 시간을 세지 않았다
    else stringResource(R.string.effort_record_drive, com.mist.pleos_app.engine.driveText(r.driveMin * 60)),
    r.trip?.routeM?.takeIf { it > 0 }?.let { "${com.mist.pleos_app.engine.roundHalfUp(it / 1000)}km" },
    r.n?.let { stringResource(R.string.effort_record_people, it) },
).joinToString(" · ")

/** 그날 고른 것: "방식 A · 한 사람 약 9천 원 · 좋아요를 눌렀어요" / "방식 B · 커피값에서 운전자 빼기로 했어요" / 닫기 / 제안 없음. */
@Composable
private fun choiceLine(r: EffortRecord, ui: EffortUi): String {
    val method = stringResource(if (r.method == Settings.METHOD_B) R.string.effort_record_method_b else R.string.effort_record_method_a)
    return when {
        r.choice == com.mist.pleos_app.effort.CHOICE_UNSETTLED -> stringResource(R.string.effort_record_choice_unsettled)
        r.suggestion == null -> stringResource(R.string.effort_record_choice_none)
        r.choice == CHOICE_ACCEPT -> stringResource(R.string.effort_record_choice_accept, method, r.suggestion)
        r.choice == CHOICE_CLOSE -> stringResource(R.string.effort_record_choice_close, method)
        else -> stringResource(R.string.effort_record_choice_item, method, ui.items.firstOrNull { it.key == r.choice }?.phraseText ?: r.choice)
    }
}
