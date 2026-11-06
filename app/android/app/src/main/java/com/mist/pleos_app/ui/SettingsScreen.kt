package com.mist.pleos_app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.window.Dialog
import com.mist.pleos_app.R
import com.mist.pleos_app.app.Settings
import com.mist.pleos_app.app.normalizePrice
import com.mist.pleos_app.engine.Constants
import com.mist.pleos_app.ui.components.CgBody
import com.mist.pleos_app.ui.components.CgButton
import com.mist.pleos_app.ui.components.CgButtonKind
import com.mist.pleos_app.ui.components.CgCaption
import com.mist.pleos_app.ui.components.CgCard
import com.mist.pleos_app.ui.components.CgChip
import com.mist.pleos_app.ui.components.CgIconButton
import com.mist.pleos_app.ui.components.CgTitle
import com.mist.pleos_app.ui.theme.Chagok
import com.mist.pleos_app.ui.theme.ChagokTheme
import com.mist.pleos_app.ui.theme.gpx

/**
 * S1 설정(명세 7절): 차종 · 단가 · 안전 여유(표시만) · 인원 · 모의 값(에뮬) · 기록 초기화.
 * 키보드 입력은 단가에만(AC-S1-3). 바꾼 값은 바로 저장되고 홈으로 돌아가면 다시 계산된다(AC-F1-3·AC-S1-1).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    s: Settings,
    constants: Constants?,
    onChange: (Settings) -> Unit,
    onReset: () -> Unit,
) {
    val gap = Arrangement.spacedBy(16.gpx)
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    var resetDone by rememberSaveable { mutableStateOf(false) }

    // 아래 여백: 화면 구석 "시연용 값" 표시가 마지막 줄을 가리지 않게
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 72.gpx), verticalArrangement = Arrangement.spacedBy(28.gpx)) {
        CgTitle(stringResource(R.string.screen_settings))

        // 차종: 용량·연비 대체값에만 씀(VIN·INFO_MODEL로 식별 불가 → 수동, DECISIONS 11/5 '차종')
        CgCaption(stringResource(R.string.settings_model))
        FlowRow(horizontalArrangement = gap, verticalArrangement = gap) {
            Settings.MODELS.forEach { m -> CgChip(modelName(m), selected = s.model == m, onClick = { onChange(s.copy(model = m)) }) }
        }

        // 단가: 비우거나 기본값과 같으면 "평균 단가"
        CgCaption(stringResource(R.string.settings_price))
        CgCard(Modifier.fillMaxWidth()) {
            PriceRow(stringResource(R.string.settings_price_gas), stringResource(R.string.unit_krw_per_l), s.priceGas, constants?.gasolineKrwPerL) {
                onChange(s.copy(priceGas = normalizePrice(it, constants?.gasolineKrwPerL ?: 0.0)))
            }
            PriceRow(stringResource(R.string.settings_price_home), stringResource(R.string.unit_krw_per_kwh), s.priceHome, constants?.homeChargeKrwPerKwh) {
                onChange(s.copy(priceHome = normalizePrice(it, constants?.homeChargeKrwPerKwh ?: 0.0)))
            }
            PriceRow(stringResource(R.string.settings_price_fast), stringResource(R.string.unit_krw_per_kwh), s.priceFast, constants?.fastChargeKrwPerKwh) {
                onChange(s.copy(priceFast = normalizePrice(it, constants?.fastChargeKrwPerKwh ?: 0.0)))
            }
            CgCaption(stringResource(R.string.settings_price_hint))
        }

        CgCaption(stringResource(R.string.settings_margin))
        CgBody(stringResource(R.string.settings_margin_value), secondary = true)

        CgCaption(stringResource(R.string.settings_people))
        CgBody(stringResource(R.string.settings_people_value), secondary = true)

        // 모의 값(에뮬). 쓰는 동안 화면 구석 "시연용 값"(AC-C-7)
        CgCaption(stringResource(R.string.settings_mock))
        CgCard(Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = gap, verticalAlignment = Alignment.CenterVertically) {
                CgBody(stringResource(R.string.settings_mock_fuel), Modifier.weight(1f))
                CgIconButton("−", stringResource(R.string.settings_mock_fuel_minus), { onChange(s.copy(mockFuelPct = (s.mockFuelPct - 10).coerceAtLeast(0))) }, enabled = s.mockFuelPct > 0)
                Text("${s.mockFuelPct}%", style = Chagok.type.titleS, color = Chagok.colors.textPrimary)
                CgIconButton("+", stringResource(R.string.settings_mock_fuel_plus), { onChange(s.copy(mockFuelPct = (s.mockFuelPct + 10).coerceAtMost(100))) }, enabled = s.mockFuelPct < 100)
            }
            CgCaption(stringResource(R.string.settings_mock_fuel_hint))
            Row(horizontalArrangement = gap, verticalAlignment = Alignment.CenterVertically) {
                CgBody(stringResource(R.string.settings_seat_invert), Modifier.weight(1f))
                CgChip(stringResource(R.string.on), selected = s.seatInvert, onClick = { onChange(s.copy(seatInvert = true)) })
                CgChip(stringResource(R.string.off), selected = !s.seatInvert, onClick = { onChange(s.copy(seatInvert = false)) })
            }
        }

        // 기록 초기화: 확인 다이얼로그(명세 S1). PT 리허설 전 실행
        CgCaption(stringResource(R.string.settings_records))
        CgButton(stringResource(R.string.settings_reset), { confirmReset = true }, Modifier.fillMaxWidth(), kind = CgButtonKind.Basic)
        if (resetDone) CgBody(stringResource(R.string.settings_reset_done), secondary = true)
    }

    if (confirmReset) {
        Dialog(onDismissRequest = { confirmReset = false }) {
            CgCard(Modifier.width(720.gpx)) {
                CgTitle(stringResource(R.string.settings_reset_title))
                CgBody(stringResource(R.string.settings_reset_body), secondary = true)
                Row(horizontalArrangement = gap, modifier = Modifier.fillMaxWidth()) {
                    CgButton(stringResource(R.string.cancel), { confirmReset = false }, Modifier.weight(1f), kind = CgButtonKind.Basic)
                    CgButton(stringResource(R.string.settings_reset_confirm), { onReset(); confirmReset = false; resetDone = true }, Modifier.weight(1f))
                }
            }
        }
    }
}

/** 단가 한 줄: 숫자 키보드만(AC-S1-3). 비어 있으면 앱 기본값을 흐리게 보여 준다. */
@Composable
private fun PriceRow(label: String, unit: String, value: Int?, default: Double?, onValue: (Int?) -> Unit) {
    val c = Chagok.colors
    var text by rememberSaveable(value) { mutableStateOf(value?.toString().orEmpty()) }
    Row(horizontalArrangement = Arrangement.spacedBy(16.gpx), verticalAlignment = Alignment.CenterVertically) {
        CgBody(label, Modifier.weight(1f))
        Box(
            Modifier
                .width(280.gpx)
                .background(c.btnBasic, RoundedCornerShape(16.gpx))
                .border(1.gpx, c.line, RoundedCornerShape(16.gpx))
                .padding(horizontal = 24.gpx, vertical = 16.gpx),
        ) {
            if (text.isEmpty() && default != null) {
                Text(stringResource(R.string.settings_price_default, "%,d".format(default.toLong())), style = Chagok.type.bodyM, color = c.textTertiary)
            }
            BasicTextField(
                value = text,
                onValueChange = { t ->
                    val digits = t.filter { it.isDigit() }.take(6)
                    text = digits
                    onValue(digits.toIntOrNull())
                },
                singleLine = true,
                textStyle = Chagok.type.bodyM.copy(color = c.textPrimary),
                cursorBrush = SolidColor(c.active),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            )
        }
        CgBody(unit, Modifier.width(140.gpx), secondary = true)
    }
}

@Composable
private fun modelName(m: String): String = stringResource(
    when (m) {
        "grandeur_25" -> R.string.model_grandeur_25
        "grandeur_hev" -> R.string.model_grandeur_hev
        "avante_hev" -> R.string.model_avante_hev
        "tucson_hev" -> R.string.model_tucson_hev
        "ioniq6_lr" -> R.string.model_ioniq6_lr
        else -> R.string.model_ioniq5_lr
    },
)

@Preview(name = "설정", widthDp = 1280, heightDp = 1400)
@Composable
private fun SettingsPreview() {
    ChagokTheme(dark = true) { SettingsScreen(Settings(priceGas = 1_790), null, {}, {}) }
}
