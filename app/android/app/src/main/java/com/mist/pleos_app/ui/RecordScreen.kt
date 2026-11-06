package com.mist.pleos_app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.mist.pleos_app.R
import com.mist.pleos_app.app.LedgerUi
import com.mist.pleos_app.engine.MonthSummary
import com.mist.pleos_app.engine.Place
import com.mist.pleos_app.engine.Row
import com.mist.pleos_app.engine.approx
import com.mist.pleos_app.engine.roundHalfUp
import com.mist.pleos_app.ui.components.CgBody
import com.mist.pleos_app.ui.components.CgButton
import com.mist.pleos_app.ui.components.CgButtonKind
import com.mist.pleos_app.ui.components.CgCaption
import com.mist.pleos_app.ui.components.CgCard
import com.mist.pleos_app.ui.components.CgTitle
import com.mist.pleos_app.ui.components.DemoMark
import com.mist.pleos_app.ui.theme.ChagokTheme
import com.mist.pleos_app.ui.theme.gpx

/**
 * F17 기록 · 이달 이동비. "이번 달 이동비 약 7만 원 · 다녀온 곳 3곳"(엔진 monthSummary 문구 그대로) + 기록 목록.
 * 기록 지도(일러스트 위 핀)는 BX 지도 그림이 나오면 목록 위에 붙인다. 비교 문구 없음(AC-C-8).
 */
@Composable
fun RecordScreen(ui: LedgerUi) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.gpx)) {
        CgTitle(stringResource(R.string.screen_record))
        CgCard(Modifier.fillMaxWidth()) {
            CgBody(ui.summary.text)
            if (ui.summary.demo) DemoMark(stringResource(R.string.demo_values))
        }
        ui.rows.forEach { RecordRow(it) }
    }
}

@Composable
private fun RecordRow(r: Row) {
    CgCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(4.gpx)) {
                CgBody(r.place?.name ?: stringResource(R.string.record_no_place))
                CgCaption(stringResource(R.string.record_meta, dateText(r.toTs), roundHalfUp(r.km)))
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.gpx)) {
                CgBody(krwAround(r.krw))
                if (r.demo) DemoMark(stringResource(R.string.demo_values))
            }
        }
    }
}

/** "2026-11-06T…" → "11/6" */
private fun dateText(ts: String): String = ts.take(10).split("-").let { if (it.size == 3) "${it[1].toInt()}/${it[2].toInt()}" else ts.take(10) }

/** 기록·정산은 천 원 어림 "약 8천 원"(PRD 5-3), 0이면 "1천 원 미만". */
private fun krwAround(krw: Int): String = approx(krw.toDouble(), 1_000).let { if (it.under) it.text else "약 ${it.text}" }

/** 홈 맨 위 정산 카드(F10). 닫기 가능, 탭하면 기록(DECISIONS '정산 카드 위치'). */
@Composable
fun SettleCard(text: String, onOpen: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    CgCard(modifier, onClick = onOpen) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            CgBody(text, Modifier.weight(1f))
            CgButton(stringResource(R.string.close), onClose, kind = CgButtonKind.Basic)
        }
    }
}

@Preview(name = "기록", widthDp = 1280, heightDp = 720)
@Composable
private fun RecordPreview() {
    val rows = listOf(
        Row("arrive", "2026-12-03T14:05:00+09:00", "2026-12-03T14:06:00+09:00", 37.2, 2_000, null, Place("양평 두물머리", 37.5, 127.3), true, true),
        Row("settle", "2026-11-08T09:00:00+09:00", "2026-11-08T18:30:00+09:00", 110.0, 16_000, 8_000, null, false, false),
    )
    ChagokTheme(dark = true) { RecordScreen(LedgerUi(null, MonthSummary(18_000, 1, true, "이번 달 이동비 약 1만 8천 원 · 다녀온 곳 1곳"), rows)) }
}
