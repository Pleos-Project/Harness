package com.mist.pleos_app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.mist.pleos_app.R
import com.mist.pleos_app.app.PayerFlow
import com.mist.pleos_app.effort.DepartUi
import com.mist.pleos_app.effort.DestOption
import com.mist.pleos_app.net.PlacesStatus
import com.mist.pleos_app.ui.components.CgBanner
import com.mist.pleos_app.ui.components.CgCta
import com.mist.pleos_app.ui.components.CgDestCard
import com.mist.pleos_app.ui.components.CgDivider
import com.mist.pleos_app.ui.components.CgEmpty
import com.mist.pleos_app.ui.components.CgIcon
import com.mist.pleos_app.ui.components.CgIconButton
import com.mist.pleos_app.ui.components.CgPeopleAlert
import com.mist.pleos_app.ui.components.CgPill
import com.mist.pleos_app.ui.components.CgPreviewPanel
import com.mist.pleos_app.ui.components.CgPreviewState
import com.mist.pleos_app.ui.components.CgSearchField
import com.mist.pleos_app.ui.components.CgSkeleton
import com.mist.pleos_app.ui.components.CgStepper
import com.mist.pleos_app.ui.components.CgTextButton
import com.mist.pleos_app.ui.components.CgTitleBar
import com.mist.pleos_app.ui.theme.Chagok
import com.mist.pleos_app.ui.theme.gpx
import kotlinx.coroutines.delay

/*
 * 출발 화면(Design v2 `design/screens/depart.html`·`search.html`, #75 명준). 부품은 ui/components(Cg*)만 조립한다.
 * 위: 뒤로 · "어디로 갈까요?" · 함께 N명 알약 / 검색 한 줄 / 즐겨찾기·최근 카드 4장(분할 2×2) / 아래 미리보기 칸 + [출발 →].
 * 인원 확인은 위 바 줄에 겹치는 알림 바(저절로 사라지지 않음). 숫자는 엔진 departurePreview 문구 그대로.
 */

private val EaseIn = CubicBezierEasing(.32f, .72f, 0f, 1f)

@Composable
internal fun EffortDepartScreen(
    ui: DepartUi,
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

    // 시연 모드도 화면 · 기능은 같다. 데이터만 ViewModel이 시연 목록으로 채운다(DemoPlaces.kt)
    val selected = ui.selected
    val searchMode = ui.query.isNotBlank() && (ui.searching || ui.searchStatus != null)

    // 들어올 때: 아래 24px → 제자리 + 투명 → 보임 0.7초
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(700, easing = EaseIn)) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (narrow) 16.gpx else 24.gpx)) {
            val people = payers
            CgTitleBar(
                stringResource(R.string.effort_depart_title), centered = true, compact = narrow,
                leading = { CgIconButton(stringResource(R.string.back), CgIcon.Back, onBack, plain = true) },
                trailing = {
                    CgPill(
                        // v104 depart-people: 인원 알림이 떠 있는 동안 "확인 중", 좌석 센서를 못 읽으면 "인원 확인 필요"
                        if (askPayers || peopleOpen) stringResource(R.string.effort_depart_people_checking)
                        else if (seats <= 0) stringResource(R.string.effort_depart_people_check) else stringResource(R.string.effort_depart_people_pill, people),
                        onClick = { peopleOpen = true }, compact = narrow,
                    )
                },
            )
            CgSearchField(ui.query, stringResource(R.string.effort_search_hint), stringResource(R.string.effort_search_clear), onQuery, search, onClearSearch, compact = narrow)
            if (demoMissing) CgBanner(stringResource(R.string.effort_depart_no_demo))
            if (searchMode) {
                SearchResults(ui, onPick = { keyboard?.hide(); focus.clearFocus(); onSelect(it); onClearSearch() }, recent = ui.naviOptions.take(2))
            } else {
                Column(
                    Modifier.fillMaxWidth().weight(1f).graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 24f * density },
                    verticalArrangement = Arrangement.spacedBy(if (narrow) 12.gpx else 16.gpx),
                ) {
                    if (ui.searchStatus == PlacesStatus.UNAVAILABLE) CgBanner(stringResource(R.string.effort_search_unavailable))
                    Text(stringResource(R.string.effort_navi_places), style = Chagok.type.bodyS, color = c.textTertiary)
                    val options = ui.naviOptions.take(4)
                    when {
                        ui.naviLoading -> Text(stringResource(R.string.effort_navi_loading), style = Chagok.type.bodyM, color = c.textTertiary)
                        options.isEmpty() -> CgEmpty(
                            stringResource(R.string.effort_navi_empty_title), stringResource(R.string.effort_navi_empty_hint),
                            Modifier.fillMaxWidth().height(if (narrow) 200.gpx else 300.gpx),
                        )
                        else -> DestCards(options, selected, narrow, onClick = { o -> if (ui.selected == o) onUnselect() else onSelect(o) })
                    }
                    val text = ui.previewText
                    val state = when {
                        selected == null -> CgPreviewState.Empty
                        ui.previewing -> CgPreviewState.Loading
                        text == null -> CgPreviewState.Failed
                        else -> CgPreviewState.Ready
                    }
                    CgPreviewPanel(
                        state, selected?.dest?.name,
                        stats = text?.let { p ->
                            listOfNotNull(
                                stringResource(R.string.effort_preview_eta) to p.etaText,
                                stringResource(R.string.effort_preview_distance) to p.routeText,
                                p.congestionText?.let { stringResource(R.string.effort_preview_jam) to it },
                            )
                        }.orEmpty(),
                        // Q-08-03(명준 11/8): 미리보기를 못 받아도 출발은 막지 않고, 무엇 없이 정리하는지 한 줄로 알린다
                        note = stringResource(if (state == CgPreviewState.Failed) R.string.effort_preview_fail_note else R.string.effort_preview_note),
                        emptyText = stringResource(R.string.effort_preview_empty),
                        failText = stringResource(R.string.effort_preview_failed),
                        retryLabel = stringResource(R.string.effort_route_retry),
                        onRetry = { ui.selected?.let(onSelect) },
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = !narrow),
                        compact = narrow,
                        photo = placePhotoRes(com.mist.pleos_app.effort.placePhotoOf(selected?.dest?.name)),
                        onPhoto = true,
                    ) { m ->
                        CgCta(
                            stringResource(if (sending) R.string.effort_depart_sending else R.string.effort_depart_go),
                            onClick = { if (!sending) sending = true }, modifier = m, compact = narrow,
                            enabled = ui.canDepart, busy = sending, fill = narrow,
                        )
                    }
                }
            }
        }
        // 인원 확인 알림 바(위 바 줄에 겹침)
        // depart-sensor: 좌석 센서를 못 읽으면 감지 인원 없이 "몇 명이 함께 타나요?"로 직접 고르게
        if ((askPayers || peopleOpen) && (seats >= 1 || peopleOpen)) {
            var n by rememberSaveable(seats) { mutableIntStateOf((if (peopleOpen || seats <= 0) payers else seats).coerceIn(PayerFlow.MIN, PayerFlow.MAX)) }
            // v39(명준 11/8): 감지 인원과 다르게 바꾸면 "N명이 낼게요" + "감지는 S명 · N명으로 나눠요", 되돌리면 원래 문구
            CgPeopleAlert(
                if (seats <= 0) stringResource(R.string.payer_bar_title_unknown)
                else if (n == seats) stringResource(R.string.payer_bar_title, seats) else stringResource(R.string.payer_bar_title_manual, n),
                when {
                    n <= 1 -> stringResource(R.string.payer_bar_solo)
                    seats <= 0 -> stringResource(R.string.payer_bar_hint_unknown)
                    n != seats -> stringResource(R.string.payer_bar_hint_manual, seats, n)
                    else -> stringResource(R.string.payer_bar_hint)
                },
                Modifier.align(Alignment.TopCenter), compact = narrow,
            ) {
                CgStepper(
                    stringResource(R.string.payer_bar_value, n), onMinus = { n-- }, onPlus = { n++ },
                    minusLabel = stringResource(R.string.payer_minus), plusLabel = stringResource(R.string.payer_plus),
                    minusEnabled = n > PayerFlow.MIN, plusEnabled = n < PayerFlow.MAX, compact = narrow,
                )
                CgDivider()
                CgTextButton(stringResource(R.string.confirm), { peopleOpen = false; onConfirmPayers(n) }, primary = true, compact = narrow)
                CgTextButton(stringResource(R.string.close), { peopleOpen = false; onDismissPayers() }, compact = narrow)
            }
        }
    }
}

/** 즐겨찾기·최근 카드 4장(가로 4열, 분할 2×2). */
@Composable
private fun DestCards(options: List<DestOption>, selected: DestOption?, narrow: Boolean, onClick: (DestOption) -> Unit) {
    val cols = if (narrow) 2 else 4
    Column(verticalArrangement = Arrangement.spacedBy(if (narrow) 16.gpx else 24.gpx)) {
        options.chunked(cols).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (narrow) 16.gpx else 24.gpx)) {
                row.forEach { o ->
                    CgDestCard(
                        kindText(o), o.dest.name,
                        meta = o.distanceText ?: o.dest.address?.takeIf { it.isNotBlank() && it != o.dest.name },   // 내비 최근은 주소가 이름과 같을 때가 많음
                        selected = selected?.dest?.name == o.dest.name, onClick = { onClick(o) }, modifier = Modifier.weight(1f), compact = narrow,
                    )
                }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
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

/** 검색 결과(search.html): 결과 목록 · 찾는 중(회색 막대 3줄) · 결과 없음 · 안 됨(최근 목적지로) · 위치 못 받음. */
@Composable
private fun SearchResults(ui: DepartUi, onPick: (DestOption) -> Unit, recent: List<DestOption>) {
    val c = Chagok.colors
    val shape = RoundedCornerShape(32.gpx)
    Column(
        Modifier.fillMaxWidth().background(c.surfaceCard, shape).border(2.gpx, c.line, shape).padding(16.gpx)
            .verticalScroll(rememberScrollState()),
    ) {
        when {
            ui.searching -> {
                Text(stringResource(R.string.effort_searching), Modifier.padding(horizontal = 12.gpx, vertical = 8.gpx), style = Chagok.type.bodyM, color = c.textTertiary)
                repeat(3) { CgSkeleton(Modifier.padding(12.gpx).fillMaxWidth(if (it == 0) 1f else if (it == 1) .78f else .64f).height(40.gpx)) }
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
            // 내비 최근 목적지는 주소가 이름과 같을 때가 많아 그때는 빼요(검색 안 됨 → 최근 목적지 줄)
            val sub = listOfNotNull(o.dest.address?.takeIf { it.isNotBlank() && it != o.dest.name }, o.category).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = Chagok.type.labelM, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        o.distanceText?.let { Text(it, style = Chagok.type.bodyM, color = c.textSecondary) }
    }
}

/** 장소 사진 → drawable(res/drawable-nodpi/place_*.jpg, #118 design/places). */
private fun placePhotoRes(p: com.mist.pleos_app.effort.PlacePhoto?): Int? = when (p) {
    com.mist.pleos_app.effort.PlacePhoto.TAEAN -> R.drawable.place_taean
    com.mist.pleos_app.effort.PlacePhoto.GAPYEONG -> R.drawable.place_gapyeong
    com.mist.pleos_app.effort.PlacePhoto.YANGPYEONG -> R.drawable.place_yangpyeong
    com.mist.pleos_app.effort.PlacePhoto.HOME -> R.drawable.place_home
    com.mist.pleos_app.effort.PlacePhoto.OFFICE -> R.drawable.place_office
    null -> null
}
