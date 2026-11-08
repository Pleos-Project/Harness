package com.mist.pleos_app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.mist.pleos_app.R
import com.mist.pleos_app.app.PayerFlow
import com.mist.pleos_app.effort.DemoScene
import com.mist.pleos_app.effort.DepartUi
import com.mist.pleos_app.effort.DestOption
import com.mist.pleos_app.engine.DeparturePreview
import com.mist.pleos_app.navi.Destination
import com.mist.pleos_app.net.PlacesStatus
import com.mist.pleos_app.ui.theme.Chagok
import com.mist.pleos_app.ui.theme.gpx
import kotlinx.coroutines.delay

/*
 * 출발 화면(Design v2 `design/screens/depart.html`·`search.html`, #75 명준).
 * 위: 뒤로 · "어디로 갈까요?" · 함께 N명 알약 / 검색 한 줄 / 즐겨찾기·최근 카드 4장(분할 2×2) / 아래 미리보기 칸 + [출발 →].
 * 인원 확인은 토스트 대신 위 바 줄에 겹치는 얇은 알림 바(저절로 사라지지 않음).
 * 숫자(도착 예상·거리·정체)는 엔진 departurePreview 문구 그대로(화면에서 계산하지 않음).
 */

private val EaseIn = CubicBezierEasing(.32f, .72f, 0f, 1f)

@Composable
internal fun EffortDepartScreen(
    ui: DepartUi,
    demo: DemoScene?,
    demoPreview: DeparturePreview?,
    demoMissing: Boolean,
    seats: Int,
    payers: Int,
    askPayers: Boolean,
    onBack: () -> Unit,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onClearSearch: () -> Unit,
    onSelect: (DestOption) -> Unit,
    onUnselect: () -> Unit,
    onGo: () -> Unit,
    onConfirmPayers: (Int) -> Unit,
    onDismissPayers: () -> Unit,
) {
    val narrow = LocalNarrow.current
    val c = Chagok.colors
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val search = { keyboard?.hide(); focus.clearFocus(); onSearch() }
    var peopleOpen by rememberSaveable { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    if (sending) LaunchedEffect(Unit) { delay(700); onGo(); sending = false }   // "내비로 보내는 중" 0.7초(프로토타입)

    // 시연: 정해 둔 목적지 하나를 고른 상태로
    val demoOption = demo?.let { DestOption(Destination("demo", it.destName, 0.0, 0.0, null), DestOption.Kind.RECENT) }
    val selected = demoOption ?: ui.selected
    val searchMode = demo == null && (ui.query.isNotBlank() && (ui.searching || ui.searchStatus != null))

    // 들어올 때: 아래 24px → 제자리 + 투명 → 보임 0.7초(카드는 0.06초씩 차례, 0.16초부터)
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(700, easing = EaseIn)) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (narrow) 16.gpx else 24.gpx)) {
            DepartBar(narrow, demo?.n ?: payers, needCheck = demo == null && seats <= 0, onBack = onBack, onPeople = { if (demo == null) peopleOpen = true })
            if (demo == null) {
                SearchField(ui.query, narrow, enabled = true, onQuery, search, onClear = onClearSearch)
            }
            if (demoMissing) Banner(stringResource(R.string.effort_depart_no_demo))
            if (searchMode) {
                SearchResults(ui, narrow, onPick = { keyboard?.hide(); focus.clearFocus(); onSelect(it); onClearSearch() }, recent = ui.naviOptions.take(2))
            } else {
                Column(
                    Modifier.fillMaxWidth().weight(1f).graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 24f * density },
                    verticalArrangement = Arrangement.spacedBy(if (narrow) 12.gpx else 16.gpx),
                ) {
                    if (ui.searchStatus == PlacesStatus.UNAVAILABLE) Banner(stringResource(R.string.effort_search_unavailable))
                    Text(stringResource(R.string.effort_navi_places), style = Chagok.type.bodyS, color = c.textTertiary)
                    val options = demoOption?.let { listOf(it) } ?: ui.naviOptions.take(4)
                    when {
                        demo == null && ui.naviLoading -> Text(stringResource(R.string.effort_navi_loading), style = Chagok.type.bodyM, color = c.textTertiary)
                        options.isEmpty() -> EmptyCards(narrow)
                        else -> DestCards(options, selected, narrow, onClick = { o -> if (demo == null) { if (ui.selected == o) onUnselect() else onSelect(o) } })
                    }
                    PreviewPanel(
                        Modifier.fillMaxWidth().weight(1f, fill = !narrow),
                        narrow, selected, previewing = demo == null && ui.previewing,
                        text = if (demo != null) demoPreview else ui.previewText,
                        failed = demo == null && selected != null && !ui.previewing && ui.previewText == null,
                        sending = sending,
                        enabled = demo != null || ui.canDepart,
                        onRetry = { ui.selected?.let(onSelect) },
                        onGo = { if (!sending) sending = true },
                        people = if (demo != null) demo.n else payers,
                    )
                }
            }
        }
        // 인원 확인 알림 바(위 바 줄에 겹침)
        if (demo == null && (askPayers || peopleOpen) && seats >= 1) {
            PeopleBar(
                seats = seats, initial = if (peopleOpen) payers else seats, narrow = narrow,
                modifier = Modifier.align(Alignment.TopCenter),
                onConfirm = { n -> peopleOpen = false; onConfirmPayers(n) },
                onDismiss = { peopleOpen = false; onDismissPayers() },
            )
        }
    }
}

/** 위 바: 뒤로 · 가운데 제목 · 오른쪽 "함께 N명" 알약(누르면 알림 바 다시). */
@Composable
private fun DepartBar(narrow: Boolean, payers: Int, needCheck: Boolean, onBack: () -> Unit, onPeople: () -> Unit) {
    val c = Chagok.colors
    Box(Modifier.fillMaxWidth().height(80.gpx)) {
        Box(
            Modifier.align(Alignment.CenterStart).size(80.gpx).clickable(role = Role.Button, onClick = onBack)
                .semantics { contentDescription = "back" },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(40.gpx)) {
                val w = size.width
                drawLine(c.textPrimary, Offset(w * .62f, w * .18f), Offset(w * .3f, w * .5f), strokeWidth = w * .1f, cap = StrokeCap.Round)
                drawLine(c.textPrimary, Offset(w * .3f, w * .5f), Offset(w * .62f, w * .82f), strokeWidth = w * .1f, cap = StrokeCap.Round)
            }
        }
        Text(stringResource(R.string.effort_depart_title), Modifier.align(Alignment.Center), style = if (narrow) Chagok.type.titleM else Chagok.type.titleL, color = c.textPrimary)
        Row(
            Modifier.align(Alignment.CenterEnd).height(if (narrow) 56.gpx else 64.gpx).background(c.btnBasic, RoundedCornerShape(999.gpx))
                .clickable(role = Role.Button, onClick = onPeople).padding(horizontal = if (narrow) 16.gpx else 22.gpx),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.gpx),
        ) {
            Text(
                if (needCheck) stringResource(R.string.effort_depart_people_check) else stringResource(R.string.effort_depart_people_pill, payers),
                style = if (narrow) Chagok.type.bodyMB else Chagok.type.bodyL, color = c.textPrimary,
            )
            Chev(12.gpx, c.textTertiary)
        }
    }
}

@Composable
private fun Chev(size: Dp, color: androidx.compose.ui.graphics.Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        drawLine(color, Offset(0f, w * .3f), Offset(w * .5f, w * .8f), strokeWidth = w * .25f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * .5f, w * .8f), Offset(w, w * .3f), strokeWidth = w * .25f, cap = StrokeCap.Round)
    }
}

/** 검색 한 줄(Text Field 100 알약). 입력 중이면 테두리 3 + [지우기]. */
@Composable
private fun SearchField(query: String, narrow: Boolean, enabled: Boolean, onQuery: (String) -> Unit, onSearch: () -> Unit, onClear: () -> Unit) {
    val c = Chagok.colors
    val active = query.isNotEmpty()
    val shape = RoundedCornerShape(50.gpx)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Row(
        (if (narrow) Modifier.fillMaxWidth() else Modifier.width(1040.gpx))
            .height(if (narrow) 88.gpx else 100.gpx)
            .shadow(6.gpx, shape).background(c.surfaceCard, shape)
            .border(if (active) 3.gpx else 2.gpx, if (active) c.textPrimary else c.line, shape)
            .graphicsLayer { alpha = if (enabled) 1f else .45f }
            .padding(start = if (narrow) 26.gpx else 32.gpx, end = 14.gpx),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.gpx),
    ) {
        val hint = stringResource(R.string.effort_search_hint)
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text(hint, style = Chagok.type.bodyL.copy(fontWeight = FontWeight.Normal), color = c.textTertiary)
            BasicTextField(
                value = query, onValueChange = { onQuery(it.take(100)) }, singleLine = true, enabled = enabled,
                textStyle = Chagok.type.bodyL.copy(color = c.textPrimary),
                cursorBrush = SolidColor(c.textPrimary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint },
            )
        }
        if (active) SmallPill(stringResource(R.string.effort_search_clear), onClear)
    } }
}

@Composable
private fun SmallPill(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    val c = Chagok.colors
    Box(
        Modifier.height(64.gpx).background(c.btnBasic, RoundedCornerShape(16.gpx))
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 22.gpx),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Chagok.type.bodyS, color = if (enabled) c.textPrimary else c.textTertiary) }
}

@Composable
private fun Banner(text: String) {
    val c = Chagok.colors
    Box(Modifier.fillMaxWidth().background(c.btnBasic, RoundedCornerShape(999.gpx)).padding(horizontal = 28.gpx, vertical = 16.gpx)) {
        Text(text, style = Chagok.type.bodyM, color = c.textSecondary)
    }
}

/** 즐겨찾기·최근 카드 4장(가로 4열 높이 320, 분할 2×2 높이 200). 고르면 바깥 테두리가 진해진다. */
@Composable
private fun DestCards(options: List<DestOption>, selected: DestOption?, narrow: Boolean, onClick: (DestOption) -> Unit) {
    val cols = if (narrow) 2 else 4
    Column(verticalArrangement = Arrangement.spacedBy(if (narrow) 16.gpx else 24.gpx)) {
        options.chunked(cols).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (narrow) 16.gpx else 24.gpx)) {
                row.forEach { o -> DestCard(o, on = selected?.dest?.name == o.dest.name, narrow = narrow, onClick = { onClick(o) }, modifier = Modifier.weight(1f)) }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun DestCard(o: DestOption, on: Boolean, narrow: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = Chagok.colors
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val outer = RoundedCornerShape(40.gpx)
    val inner = RoundedCornerShape(32.gpx)
    Box(
        modifier.height(if (narrow) 200.gpx else 320.gpx).background(if (on) c.textPrimary else c.placeholder, outer)
            .clickable(src, indication = null, role = Role.Button, onClick = onClick).padding(8.gpx),
    ) {
        Column(
            Modifier.fillMaxSize().graphicsLayer { val s = if (pressed) .985f else 1f; scaleX = s; scaleY = s }
                .shadow(4.gpx, inner).background(c.surfaceCard, inner)
                .padding(start = 28.gpx, end = 28.gpx, top = if (narrow) 18.gpx else 28.gpx, bottom = if (narrow) 14.gpx else 22.gpx),
            verticalArrangement = Arrangement.spacedBy(6.gpx),
        ) {
            Text(kindText(o), style = Chagok.type.bodyS.copy(fontWeight = FontWeight.Normal), color = c.textTertiary, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Text(o.dest.name, style = if (narrow) Chagok.type.titleS else Chagok.type.titleL, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            (o.distanceText ?: o.dest.address?.takeIf { it.isNotBlank() && it != o.dest.name })?.let {   // 내비 최근은 주소가 이름과 같을 때가 많음
                Text(it, style = Chagok.type.bodyM, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun kindText(o: DestOption): String = when (o.kind) {
    DestOption.Kind.HOME, DestOption.Kind.OFFICE, DestOption.Kind.BOOKMARK -> stringResource(R.string.effort_kind_bookmark)
    DestOption.Kind.RECENT -> stringResource(R.string.effort_kind_recent)
    DestOption.Kind.SEARCH -> o.category ?: stringResource(R.string.effort_kind_search)
}

@Composable
private fun EmptyCards(narrow: Boolean) {
    val c = Chagok.colors
    Column(
        Modifier.fillMaxWidth().height(if (narrow) 200.gpx else 300.gpx).dashed(32.gpx, c.lineStrong),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.gpx, Alignment.CenterVertically),
    ) {
        Text(stringResource(R.string.effort_navi_empty_title), style = Chagok.type.titleS, color = c.textPrimary)
        Text(stringResource(R.string.effort_navi_empty_hint), style = Chagok.type.bodyM, color = c.textTertiary)
    }
}

/** 점선 테두리(3px). */
private fun Modifier.dashed(radius: Dp, color: androidx.compose.ui.graphics.Color): Modifier = this.drawBehind {
    drawRoundRect(color, cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius.toPx()),
        style = Stroke(3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
}

/** 미리보기 칸: 빈 자리(점선) / 불러오는 중(회색 막대) / 준비(도착 예상·거리·정체 + [출발 →]) / 실패(다시 시도, 출발은 켜 둠). */
@Composable
private fun PreviewPanel(
    modifier: Modifier, narrow: Boolean, selected: DestOption?, previewing: Boolean, text: DeparturePreview?,
    failed: Boolean, sending: Boolean, enabled: Boolean, onRetry: () -> Unit, onGo: () -> Unit, people: Int,
) {
    val c = Chagok.colors
    val shape = RoundedCornerShape(40.gpx)
    if (selected == null) {
        Box(modifier.heightIn(min = 240.gpx).dashed(40.gpx, c.lineStrong).padding(28.gpx), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.effort_preview_empty), style = Chagok.type.bodyL.copy(fontWeight = FontWeight.Normal), color = c.textTertiary)
        }
        return
    }
    val body: @Composable ColumnScope.() -> Unit = {
        Text(selected.dest.name, style = Chagok.type.titleL, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        when {
            previewing -> Row(horizontalArrangement = Arrangement.spacedBy(48.gpx)) { repeat(3) { Skeleton(Modifier.width(150.gpx).height(30.gpx)) } }
            failed -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.gpx)) {
                Text(stringResource(R.string.effort_preview_failed), style = Chagok.type.bodyL.copy(fontWeight = FontWeight.Normal), color = c.textSecondary)
                TextButton(stringResource(R.string.effort_route_retry), primary = true, onClick = onRetry)
            }
            text != null -> Row(horizontalArrangement = Arrangement.spacedBy(if (narrow) 20.gpx else 48.gpx)) {
                Stat(stringResource(R.string.effort_preview_eta), text.etaText, narrow)
                Stat(stringResource(R.string.effort_preview_distance), text.routeText, narrow)
                text.congestionText?.let { Stat(stringResource(R.string.effort_preview_jam), it, narrow) }
            }
        }
        Text(
            stringResource(R.string.effort_preview_note, people), style = if (narrow) Chagok.type.labelM else Chagok.type.bodyM, color = c.textTertiary,
        )
    }
    if (!narrow) {
        Row(
            modifier.shadow(6.gpx, shape).background(c.surfaceCard, shape).border(2.gpx, c.line, shape).padding(start = 44.gpx, end = 28.gpx, top = 28.gpx, bottom = 28.gpx),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.gpx),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.gpx), content = body)
            Cta(sending, enabled = enabled && !sending, narrow = false, onGo = onGo)
        }
    } else {
        Column(
            modifier.shadow(6.gpx, shape).background(c.surfaceCard, shape).border(2.gpx, c.line, shape).padding(24.gpx),
            verticalArrangement = Arrangement.spacedBy(10.gpx),
        ) {
            body()
            Spacer(Modifier.height(12.gpx))
            Cta(sending, enabled = enabled && !sending, narrow = true, onGo = onGo, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Stat(k: String, v: String, narrow: Boolean) {
    Column(Modifier.widthIn(min = if (narrow) 0.gpx else 150.gpx), verticalArrangement = Arrangement.spacedBy(2.gpx)) {
        Text(k, style = Chagok.type.labelM, color = Chagok.colors.textTertiary)
        Text(v, style = if (narrow) Chagok.type.bodyL else Chagok.type.titleS, color = Chagok.colors.textPrimary, maxLines = 1)
    }
}

@Composable
private fun Skeleton(modifier: Modifier) {
    val inf = rememberInfiniteTransition(label = "skel")
    val a by inf.animateFloat(.45f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "a")
    Box(modifier.graphicsLayer { alpha = a }.background(Chagok.colors.placeholderStrong, RoundedCornerShape(8.gpx)))
}

/** [출발 →] = Basic Button L 채움 변형(알약 + 화살표 원). 보내는 중이면 "내비로 보내는 중" + 점. */
@Composable
private fun Cta(sending: Boolean, enabled: Boolean, narrow: Boolean, onGo: () -> Unit, modifier: Modifier = Modifier) {
    val c = Chagok.colors
    val shape = RoundedCornerShape(999.gpx)
    val on = enabled || sending
    Row(
        modifier.height(if (narrow) 80.gpx else 100.gpx)
            .background(if (on && !sending) c.btnFilled else c.btnDisabled, shape)
            .then(if (!on || sending) Modifier.border(2.gpx, c.line, shape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onGo)
            .padding(start = if (narrow) 28.gpx else 40.gpx, end = if (narrow) 8.gpx else 12.gpx),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.gpx),
    ) {
        val fg = if (on && !sending) c.btnFilledText else c.textTertiary
        Text(stringResource(if (sending) R.string.effort_depart_sending else R.string.effort_depart_go), Modifier.then(if (narrow) Modifier.weight(1f) else Modifier),
            style = if (narrow) Chagok.type.bodyL else Chagok.type.titleS, color = fg)
        Box(Modifier.size(if (narrow) 60.gpx else 76.gpx).background(fg.copy(alpha = .15f), CircleShape), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(32.gpx)) {
                val w = size.width
                drawLine(fg, Offset(w * .35f, w * .15f), Offset(w * .7f, w * .5f), strokeWidth = w * .12f, cap = StrokeCap.Round)
                drawLine(fg, Offset(w * .7f, w * .5f), Offset(w * .35f, w * .85f), strokeWidth = w * .12f, cap = StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun TextButton(text: String, primary: Boolean, narrow: Boolean = false, onClick: () -> Unit) {
    val c = Chagok.colors
    Box(
        Modifier.height(72.gpx).clickable(role = Role.Button, onClick = onClick).padding(horizontal = if (narrow) 12.gpx else 22.gpx),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = (if (narrow) Chagok.type.bodyL else Chagok.type.titleS).copy(fontWeight = if (primary) FontWeight.ExtraBold else FontWeight.Bold), color = if (primary) c.textPrimary else c.textTertiary)
    }
}

/** 검색 결과(search.html): 결과 목록 · 찾는 중(회색 막대 3줄) · 결과 없음 · 안 됨(최근 목적지로) · 위치 못 받음. */
@Composable
private fun SearchResults(ui: DepartUi, narrow: Boolean, onPick: (DestOption) -> Unit, recent: List<DestOption>) {
    val c = Chagok.colors
    val shape = RoundedCornerShape(32.gpx)
    Column(
        Modifier.fillMaxWidth().background(c.surfaceCard, shape).border(2.gpx, c.line, shape).padding(horizontal = 16.gpx, vertical = 16.gpx)
            .verticalScroll(rememberScrollState()),
    ) {
        when {
            ui.searching -> {
                Row(Modifier.padding(horizontal = 12.gpx, vertical = 8.gpx), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.effort_searching), style = Chagok.type.bodyM, color = c.textTertiary)
                }
                repeat(3) { Skeleton(Modifier.padding(12.gpx).fillMaxWidth(if (it == 0) 1f else if (it == 1) .78f else .64f).height(40.gpx)) }
            }
            ui.searchStatus == PlacesStatus.EMPTY -> Column(Modifier.padding(12.gpx), verticalArrangement = Arrangement.spacedBy(6.gpx)) {
                Text(stringResource(R.string.effort_search_empty_title, ui.query.trim()), style = Chagok.type.titleS, color = c.textPrimary)
                Text(stringResource(R.string.effort_search_empty_hint), style = Chagok.type.bodyM, color = c.textTertiary)
            }
            ui.searchStatus == PlacesStatus.UNAVAILABLE -> Column(Modifier.padding(12.gpx), verticalArrangement = Arrangement.spacedBy(6.gpx)) {
                Text(stringResource(R.string.effort_search_fail_title), style = Chagok.type.titleS, color = c.textPrimary)
                Text(stringResource(R.string.effort_search_fail_hint), style = Chagok.type.bodyM, color = c.textTertiary)
                recent.forEach { ResultRow(it, onPick) }
            }
            else -> {
                Text(stringResource(R.string.effort_search_results), Modifier.padding(horizontal = 12.gpx, vertical = 6.gpx), style = Chagok.type.bodyS.copy(fontWeight = FontWeight.Normal), color = c.textTertiary)
                if (ui.searchResults.isNotEmpty() && ui.searchResults.all { it.distanceM == null }) {
                    Text(stringResource(R.string.effort_search_noloc), Modifier.padding(horizontal = 12.gpx, vertical = 4.gpx), style = Chagok.type.labelM, color = c.textTertiary)
                }
                ui.searchResults.forEach { ResultRow(it, onPick) }
            }
        }
    }
}

@Composable
private fun ResultRow(o: DestOption, onPick: (DestOption) -> Unit) {
    val c = Chagok.colors
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(o) }.padding(horizontal = 12.gpx, vertical = 14.gpx),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.gpx),
    ) {
        Column(Modifier.weight(1f)) {
            Text(o.dest.name, style = Chagok.type.bodyL, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(o.dest.address?.takeIf { it.isNotBlank() }, o.category).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = Chagok.type.labelM, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        o.distanceText?.let { Text(it, style = Chagok.type.bodyM, color = c.textSecondary) }
    }
}

/** 인원 확인 알림 바(1120 × 120, 위 바 줄에 겹침). 확인 = 진한 글자, 닫기 = 옅은 글자. 저절로 안 사라짐. */
@Composable
private fun PeopleBar(seats: Int, initial: Int, narrow: Boolean, modifier: Modifier, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    val c = Chagok.colors
    var n by rememberSaveable(seats) { mutableIntStateOf(initial.coerceIn(PayerFlow.MIN, PayerFlow.MAX)) }
    val drop = remember { Animatable(0f) }
    LaunchedEffect(Unit) { drop.animateTo(1f, tween(420, easing = EaseIn)) }
    val shape = RoundedCornerShape(60.gpx)
    Row(
        modifier.then(if (narrow) Modifier.fillMaxWidth().padding(start = 96.gpx) else Modifier.width(1120.gpx))
            .height(if (narrow) 104.gpx else 120.gpx)
            .graphicsLayer { alpha = drop.value; translationY = -(1f - drop.value) * 16f * density; val s = .98f + .02f * drop.value; scaleX = s; scaleY = s }
            .shadow(16.gpx, shape).background(c.surfaceCard, shape).border(1.gpx, c.lineStrong, shape)
            .padding(start = if (narrow) 26.gpx else 40.gpx, end = 10.gpx),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (narrow) 12.gpx else 20.gpx),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.gpx)) {
            Text(stringResource(R.string.payer_bar_title, seats), style = if (narrow) Chagok.type.bodyL else Chagok.type.titleM.copy(fontWeight = FontWeight.ExtraBold), color = c.textPrimary, maxLines = 1)
            if (!narrow) Text(
                stringResource(if (n <= 1) R.string.payer_bar_solo else R.string.payer_bar_hint), style = Chagok.type.labelS, color = c.textTertiary, maxLines = 1,
            )
        }
        Row(Modifier.height(if (narrow) 64.gpx else 80.gpx).background(c.btnBasic, RoundedCornerShape(40.gpx)), verticalAlignment = Alignment.CenterVertically) {
            StepBtn("−", stringResource(R.string.payer_minus), enabled = n > PayerFlow.MIN, narrow = narrow) { n-- }
            Text(stringResource(R.string.payer_bar_value, n), Modifier.widthIn(min = if (narrow) 64.gpx else 96.gpx), style = if (narrow) Chagok.type.bodyL else Chagok.type.titleM, color = c.textPrimary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            StepBtn("+", stringResource(R.string.payer_plus), enabled = n < PayerFlow.MAX, narrow = narrow) { n++ }
        }
        Box(Modifier.width(2.gpx).height(56.gpx).background(c.line))
        TextButton(stringResource(R.string.confirm), primary = true, narrow = narrow) { onConfirm(n) }
        TextButton(stringResource(R.string.close), primary = false, narrow = narrow, onClick = onDismiss)
    }
}

@Composable
private fun StepBtn(glyph: String, label: String, enabled: Boolean, narrow: Boolean, onClick: () -> Unit) {
    val c = Chagok.colors
    Box(
        Modifier.size(if (narrow) 56.gpx else 80.gpx).clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Text(glyph, style = Chagok.type.titleM, color = if (enabled) c.textPrimary else c.textTertiary) }
}
