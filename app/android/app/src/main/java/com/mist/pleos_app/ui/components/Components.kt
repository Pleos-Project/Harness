package com.mist.pleos_app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.coerceAtLeast
import com.mist.pleos_app.ui.theme.Chagok
import com.mist.pleos_app.ui.theme.MinTouch
import com.mist.pleos_app.ui.theme.gpx

/*
 * Pleos Connect UI Design Guide(Foundations·Basic button)를 따르는 공통 부품.
 * 명준 시안이 나오면 모양은 여기서만 바꾼다. 화면 코드는 색·크기를 직접 적지 않는다.
 */

/** 버튼 종류: 가이드 Basic button의 filled(주요)·basic(보조). */
enum class CgButtonKind { Filled, Basic }

@Composable
fun CgButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: CgButtonKind = CgButtonKind.Filled,
    enabled: Boolean = true,
) {
    val c = Chagok.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val (bg, fg) = when {
        !enabled -> c.btnDisabled to c.textTertiary
        kind == CgButtonKind.Filled -> (if (pressed) c.btnFilledPressed else c.btnFilled) to c.btnFilledText
        else -> (if (pressed) c.btnBasicPressed else c.btnBasic) to c.textPrimary
    }
    Box(
        modifier
            .defaultMinSize(minWidth = MinTouch, minHeight = 80.gpx.coerceAtLeast(MinTouch))
            .background(bg, RoundedCornerShape(16.gpx))
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 32.gpx, vertical = 16.gpx),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Chagok.type.titleS, color = fg, textAlign = TextAlign.Center)
    }
}

/** 프리셋·당일/1박·인원·테마 칩. 비활성(예: 추가 0원 불가)은 enabled=false. */
@Composable
fun CgChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = Chagok.colors
    val bg = when {
        !enabled -> c.btnDisabled
        selected -> c.btnFilled
        else -> c.btnBasic
    }
    val fg = when {
        !enabled -> c.textTertiary
        selected -> c.btnFilledText
        else -> c.textPrimary
    }
    Box(
        modifier
            .defaultMinSize(minWidth = MinTouch, minHeight = 64.gpx.coerceAtLeast(MinTouch))
            .background(bg, RoundedCornerShape(32.gpx))
            .semantics { this.selected = selected }
            .clickable(enabled = enabled, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 28.gpx, vertical = 12.gpx),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Chagok.type.bodyS, color = fg)
    }
}

/** 카드 틀(잔고·정산·오늘의 한 곳·상황 카드). */
@Composable
fun CgCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Chagok.colors
    val base = modifier
        .background(c.surfaceHigh, RoundedCornerShape(24.gpx))
        .border(1.gpx, c.line, RoundedCornerShape(24.gpx))
    Column(
        (if (onClick != null) base.clickable(role = Role.Button, onClick = onClick) else base).padding(32.gpx),
        verticalArrangement = Arrangement.spacedBy(12.gpx),
        content = content,
    )
}

/** 안내 문구(X2 예외 9종 등). 문구는 strings.xml에서 넘긴다. */
@Composable
fun CgNotice(text: String, modifier: Modifier = Modifier) {
    val c = Chagok.colors
    Box(
        modifier
            .background(c.btnBasic, RoundedCornerShape(16.gpx))
            .padding(horizontal = 24.gpx, vertical = 16.gpx),
    ) {
        Text(text, style = Chagok.type.bodyS, color = c.textSecondary)
    }
}

/** 화면 구석 "시연용 값"(AC-C-7). 에뮬 임의 값(차종 표·모의 값)을 쓰는 동안 고정 표시. */
@Composable
fun DemoMark(text: String, modifier: Modifier = Modifier) {
    val c = Chagok.colors
    Text(
        text,
        modifier
            .background(c.surfaceAccent, RoundedCornerShape(8.gpx))
            .padding(horizontal = 12.gpx, vertical = 4.gpx),
        style = Chagok.type.labelS,
        color = c.textSecondary,
    )
}

/** 섹션 제목·본문 줄. 문구 크기를 화면마다 다르게 적지 않도록. */
@Composable
fun CgTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, style = Chagok.type.titleM, color = Chagok.colors.textPrimary)

@Composable
fun CgBody(text: String, modifier: Modifier = Modifier, secondary: Boolean = false) =
    Text(text, modifier, style = Chagok.type.bodyM, color = if (secondary) Chagok.colors.textSecondary else Chagok.colors.textPrimary)

@Composable
fun CgCaption(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, style = Chagok.type.labelM, color = Chagok.colors.textTertiary)
