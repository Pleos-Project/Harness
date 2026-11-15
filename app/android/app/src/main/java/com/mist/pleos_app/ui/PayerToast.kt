package com.mist.pleos_app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.mist.pleos_app.R
import com.mist.pleos_app.app.PayerFlow
import com.mist.pleos_app.ui.components.CgBody
import com.mist.pleos_app.ui.components.CgButton
import com.mist.pleos_app.ui.components.CgButtonKind
import com.mist.pleos_app.ui.components.CgIconButton
import com.mist.pleos_app.ui.theme.Chagok
import com.mist.pleos_app.ui.theme.ChagokTheme
import com.mist.pleos_app.ui.theme.gpx

/**
 * Pleos Toast 틀: 화면 위쪽, 가장자리에서 24 띄우고 최대 폭 792(PRD v1.5 6-3, 디자인 가이드 Toast Popup).
 * 시안이 나오면 색·모서리만 바꾼다.
 */
@Composable
fun CgToast(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = Chagok.colors
    val shape = RoundedCornerShape(24.gpx)
    Column(
        modifier
            .padding(top = 24.gpx)
            .widthIn(max = 792.gpx)
            .fillMaxWidth()
            .shadow(16.gpx, shape)
            .background(c.surfaceHigh, shape)
            .border(1.gpx, c.line, shape)
            .padding(horizontal = 40.gpx, vertical = 24.gpx)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(16.gpx),
        content = content,
    )
}

/** F11 인원 확인: "N명이 탔어요 · 나눠 낼 인원 [−] N [+] [확인]" + 닫기(×). 별도 화면 아님. */
@Composable
fun PayerToast(seats: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    var n by rememberSaveable(seats) { mutableIntStateOf(seats) }
    CgToast(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CgBody(stringResource(R.string.payer_toast, seats), Modifier.weight(1f))
            CgIconButton("×", stringResource(R.string.close), onDismiss)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.gpx), verticalAlignment = Alignment.CenterVertically) {
            CgIconButton("−", stringResource(R.string.payer_minus), { n-- }, enabled = n > PayerFlow.MIN)
            Text("$n", style = Chagok.type.titleM, color = Chagok.colors.textPrimary)
            CgIconButton("+", stringResource(R.string.payer_plus), { n++ }, enabled = n < PayerFlow.MAX)
            Spacer(Modifier.weight(1f))
            CgButton(stringResource(R.string.confirm), { onConfirm(n) }, kind = CgButtonKind.Filled)
        }
    }
}

@Preview(name = "인원 확인 토스트", widthDp = 1280, heightDp = 300)
@Composable
private fun PayerToastPreview() {
    ChagokTheme(dark = true) { PayerToast(3, {}, {}) }
}
