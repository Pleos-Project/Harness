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
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
            // .card.logl: 모서리 32 · 테두리 2 · 위아래 8. 줄 .logr 여백 24 36(분할 18 24) · 날짜 150 · 사이 28 · 누르면 btn-basic-p
            val shape = RoundedCornerShape(32.gpx)
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).background(c.surfaceCard, shape).border(2.gpx, c.line, shape).verticalScroll(rememberScrollState()).padding(vertical = 8.gpx)) {
                ui.records.forEachIndexed { i, r ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.gpx).background(c.dividerDefault))
                    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    val pressed by src.collectIsPressedAsState()
                    Row(
                        Modifier.fillMaxWidth().background(if (pressed) c.btnBasicPressed else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable(src, indication = null) { onRecord(r.tripId) }
                            .padding(horizontal = if (narrow) 24.gpx else 36.gpx, vertical = if (narrow) 18.gpx else 24.gpx),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (narrow) 16.gpx else 28.gpx),
                    ) {
                        if (!narrow) Text(dayText(r.date + "T"), Modifier.width(150.gpx), style = t.bodyM, color = c.textTertiary)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (narrow) 6.gpx else 4.gpx)) {
                            // 분할: 날짜 · 요약 body-m(.pg.p .logr span), 장소 title-s / 넓은 창: 장소 body-l 700
                            if (narrow) Text(dayText(r.date + "T"), style = t.bodyM, color = c.textTertiary)
                            Text(r.destName, style = if (narrow) t.titleS else t.bodyL, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(recordLogLine(r, ui.constants), style = t.bodyM, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        CgIconMark(CgIcon.Chevron, 12.gpx, c.textTertiary, Modifier.graphicsLayer { rotationZ = -90f })   // .logr .chev 12
                    }
                }
            }
        }
        Text(stringResource(R.string.effort_log_note), style = t.labelM, color = c.textTertiary)
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
    var open by remember(r.tripId) { mutableStateOf<String?>(null) }   // v88: 새로 열 때 근거는 닫힌 채로
    val seq = rememberPrintSeq(r.tripId, play = !rememberReduceMotion())   // v88: 도착과 같은 출력 연출을 한 번
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.gpx)) {   // .pg gap 24(분할도)
        CgTitleBar(stringResource(R.string.effort_record_title, dayText(r.date + "T"), r.destName), compact = narrow,
            leading = { CgIconButton(stringResource(R.string.back), CgIcon.Back, onBack, plain = true) })
        val paper: @Composable (Modifier) -> Unit = { m ->
            val trip = r.trip
            if (receipt != null && trip != null) ReceiptColumn(
                trip, receipt, ui, narrow, reduce = true, paperShift = seq.paper.value, open = open, print = seq,
                onToggle = { k -> open = if (open == k) null else k }, onChangeRest = {}, modifier = m, editable = false,
            )
        }
        val card: @Composable (Modifier) -> Unit = { m0 ->
            val m = m0.graphicsLayer { alpha = seq.offer.value; translationY = (1f - seq.offer.value) * 16f * density }
            // 소제목 · 안내(.offer .cap2 · .note) body-m(분할 label-m), 소제목과 큰 제목 사이 10(분할 6)
            val capS = if (narrow) t.labelM else t.bodyM
            CgOfferCard(m, compact = narrow) {
                Column(verticalArrangement = Arrangement.spacedBy(if (narrow) 6.gpx else 10.gpx)) {
                    Text(stringResource(R.string.effort_record_day_cap), style = capS, color = c.textTertiary)
                    Text(receipt?.multiplierText ?: r.headlineText, style = (if (narrow) t.titleL else t.headlineS).copy(lineBreak = androidx.compose.ui.text.style.LineBreak.Heading), color = c.textPrimary)
                }
                // 11/9 명준: 금액 밑줄(도착과 같은 .ul) 뒤 줄바꿈 → 아래 줄 운전 시간 · 거리 · 인원, 밑줄과 아래 줄 사이 8(분할 6)
                val amt = receipt?.proposal?.amountText ?: receipt?.let { com.mist.pleos_app.effort.receiptAmountText(it, ui.constants) }
                Column(verticalArrangement = Arrangement.spacedBy(if (narrow) 6.gpx else 8.gpx)) {
                    amt?.let { DayAmountUnderline(stringResource(R.string.effort_record_amount, it), narrow) }
                    Text(dayEffortLine(r, null), style = if (narrow) t.labelM else t.bodyM, color = c.textSecondary)
                }
                Box(Modifier.fillMaxWidth().padding(vertical = 14.gpx).height(2.gpx).background(c.dividerDefault))   // .odiv 2 · 위아래 14
                Column(verticalArrangement = Arrangement.spacedBy(if (narrow) 6.gpx else 10.gpx)) {
                    Text(stringResource(R.string.effort_record_choice_cap), style = capS, color = c.textTertiary)
                    Text(choiceLine(r, ui), style = if (narrow) t.labelM else t.bodyM, color = c.textSecondary)
                }
                // 중간 정산(F27): "중간 정산 15:25 · 한 사람 약 4천 원" / "… · 밥값에서 운전자 빼기"
                r.trip?.settlements.orEmpty().forEach { s ->
                    val what = if (s.method == Settings.METHOD_B) stringResource(R.string.effort_home_rec_item, ui.items.firstOrNull { it.key == s.choice }?.phraseText ?: s.choice)
                    else s.suggestion.orEmpty()
                    Text(stringResource(R.string.effort_record_midway, com.mist.pleos_app.effort.clockText(s.at), what), style = t.bodyM, color = c.textSecondary)
                }
                Text(stringResource(R.string.effort_record_note), style = capS, color = c.textTertiary)
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
    r.trip?.routeM?.takeIf { it > 0 }?.let { com.mist.pleos_app.engine.kmText(it) },   // 500m 미만은 "1km 미만"(명세 2절)
    r.n?.let { stringResource(R.string.effort_record_people, it) },
).joinToString(" · ")

/** 그날 고른 것: "방식 A · 한 사람 약 9천 원 · 좋아요를 눌렀어요" / "방식 B · 커피값에서 운전자 빼기로 했어요" / 닫기 / 제안 없음. */
@Composable
private fun choiceLine(r: EffortRecord, ui: EffortUi): String {
    val method = stringResource(if (r.method == Settings.METHOD_B) R.string.effort_record_method_b else R.string.effort_record_method_a)
    return when {
        r.choice == com.mist.pleos_app.effort.CHOICE_UNSETTLED -> stringResource(R.string.effort_record_choice_unsettled)
        r.suggestion == null && (r.n ?: 2) < 2 -> stringResource(R.string.effort_record_choice_solo, method)
        r.suggestion == null -> stringResource(R.string.effort_record_choice_none)
        r.choice == CHOICE_ACCEPT -> stringResource(R.string.effort_record_choice_accept, method, r.suggestion)
        r.choice == CHOICE_CLOSE -> stringResource(R.string.effort_record_choice_close, method)
        else -> stringResource(R.string.effort_record_choice_item, method, ui.items.firstOrNull { it.key == r.choice }?.phraseText ?: r.choice)
    }
}

/** 그날의 노고 금액 한 줄: 진한 글자 + 밑줄 2px(출력 연출 뒤 1.5초에 0.6초 동안 그어짐, 도착 제안 카드와 같은 .ul). */
@Composable
private fun DayAmountUnderline(text: String, narrow: Boolean) {
    val c = Chagok.colors
    val reduce = rememberReduceMotion()
    val k = remember { androidx.compose.animation.core.Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduce) { kotlinx.coroutines.delay(1500); k.animateTo(1f, androidx.compose.animation.core.tween(600, easing = androidx.compose.animation.core.CubicBezierEasing(.32f, .72f, 0f, 1f))) } }
    Text(
        text,
        Modifier.drawBehind {
            val h = 2.dp.toPx() * 1f
            drawRect(c.textPrimary, androidx.compose.ui.geometry.Offset(0f, size.height - h), androidx.compose.ui.geometry.Size(size.width * k.value, h))
        }.padding(bottom = 3.gpx),
        style = if (narrow) Chagok.type.labelM else Chagok.type.bodyM, color = c.textPrimary,
    )
}
