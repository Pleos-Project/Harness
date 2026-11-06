package com.mist.pleos_app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.mist.pleos_app.R
import com.mist.pleos_app.app.Conditions
import com.mist.pleos_app.app.PresetUi
import com.mist.pleos_app.app.presets
import com.mist.pleos_app.app.toggleTheme
import com.mist.pleos_app.app.RANGE_THEMES
import com.mist.pleos_app.engine.THEME_KO
import com.mist.pleos_app.engine.Vehicle
import com.mist.pleos_app.ui.components.CgButton
import com.mist.pleos_app.ui.components.CgCaption
import com.mist.pleos_app.ui.components.CgChip
import com.mist.pleos_app.ui.components.CgNotice
import com.mist.pleos_app.ui.components.CgTitle
import com.mist.pleos_app.ui.theme.gpx

/**
 * F2 범위 · F3 테마. "조건 바꾸기"와 교체 3회째로만 들어온다(PRD v1.3 6-1).
 * 반경은 엔진 computeRange(app/CardModel.presets), 화면은 숫자만 끼워 넣는다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RangeScreen(
    vehicle: Vehicle?,
    c: Conditions,
    seats: Int,
    onChange: (Conditions) -> Unit,
    onPick: () -> Unit,
) {
    if (vehicle == null) {
        Column(verticalArrangement = Arrangement.spacedBy(24.gpx)) {
            CgTitle(stringResource(R.string.screen_range))
            CgNotice(stringResource(R.string.reading_vehicle))
        }
        return
    }
    val ps = remember(vehicle, c.days) { presets(vehicle, c.days) }
    // 고른 프리셋이 비활성이 되면(잔고 부족) 고를 수 있는 첫 프리셋으로(AC-F2-2: +1만 자동)
    val extra = ps.firstOrNull { it.extraKrw == c.extraKrw && it.enabled }?.extraKrw ?: ps.first { it.enabled }.extraKrw
    val gap = Arrangement.spacedBy(16.gpx)
    val zero = zeroText(vehicle.fuelType == "ev")

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(28.gpx)) {
        CgTitle(stringResource(R.string.screen_range))

        CgCaption(stringResource(R.string.range_extra_label))
        if (!ps.first().enabled) CgNotice(stringResource(R.string.range_zero_unavailable, zero))
        FlowRow(horizontalArrangement = gap, verticalArrangement = gap) {
            ps.forEach { p -> CgChip(presetText(p, zero), selected = p.extraKrw == extra, onClick = { onChange(c.copy(extraKrw = p.extraKrw)) }, enabled = p.enabled) }
        }
        // 프리셋은 주유·충전에 더 쓰는 돈 기준, 통행료·주차는 따로(PRD v1.6 F2·6-3)
        CgCaption(stringResource(R.string.range_preset_note))

        CgCaption(stringResource(R.string.range_days_label))
        FlowRow(horizontalArrangement = gap, verticalArrangement = gap) {
            CgChip(stringResource(R.string.range_day_trip), selected = c.days == 1, onClick = { onChange(c.copy(days = 1)) })
            CgChip(stringResource(R.string.range_overnight), selected = c.days == 2, onClick = { onChange(c.copy(days = 2)) })
        }

        CgCaption(stringResource(R.string.range_people_label))
        CgChip(stringResource(R.string.range_people_auto, seats), selected = true, onClick = {})

        CgCaption(stringResource(R.string.range_theme_label))
        FlowRow(horizontalArrangement = gap, verticalArrangement = gap) {
            RANGE_THEMES.forEach { key -> CgChip(THEME_KO[key] ?: key, selected = key in c.themes, onClick = { onChange(c.copy(themes = toggleTheme(c.themes, key))) }) }
        }

        CgButton(stringResource(R.string.range_pick), { onChange(c.copy(extraKrw = extra)); onPick() }, Modifier.fillMaxWidth())
    }
}

@Composable
private fun presetText(p: PresetUi, zero: String): String = when {
    // 비활성(잔고 < 안전 여유)이면 반경이 0 이하라 km를 붙이지 않는다
    !p.enabled -> stringResource(R.string.preset_zero_only, zero)
    p.extraKrw == 0 -> stringResource(R.string.preset_zero, zero, p.radiusKm)
    p.extraKrw == 10_000 -> stringResource(R.string.preset_10k, p.radiusKm)
    else -> stringResource(R.string.preset_30k, p.radiusKm)
}
