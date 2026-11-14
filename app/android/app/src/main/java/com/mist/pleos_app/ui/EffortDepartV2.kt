package com.mist.pleos_app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.TextStyle
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
import com.mist.pleos_app.ui.components.CgLoadingDots
import com.mist.pleos_app.ui.components.CgStepper
import com.mist.pleos_app.ui.components.CgTextButton
import com.mist.pleos_app.ui.components.CgTitleBar
import com.mist.pleos_app.ui.theme.Chagok
import com.mist.pleos_app.ui.theme.ChagokMotion
import com.mist.pleos_app.ui.theme.ChagokRadius
import com.mist.pleos_app.ui.theme.ChagokSpace
import com.mist.pleos_app.ui.theme.gpx
import kotlinx.coroutines.delay

/*
 * 출발 화면(Design v2 `design/screens/depart.html`·`search.html`, #75 명준). 부품은 ui/components(Cg*)만 조립한다.
 * 위: 뒤로 · "어디로 갈까요?" · 함께 N명 알약 / 검색 한 줄 / 즐겨찾기·최근 카드 4장(분할 2×2) / 아래 미리보기 칸 + [출발 →].
 * 인원 확인은 위 바 줄에 겹치는 알림 바(저절로 사라지지 않음). 숫자는 엔진 departurePreview 문구 그대로.
 */

/** 목적지 카드 등장: 카드마다 0.06초씩 늦게 시작해 0.7초(아티팩트 `.home.enter .hcard`, G1 depart-home). */
private const val CARD_RISE_MS = 700
private const val CARD_STAGGER_MS = 60
private const val CARD_COUNT = 4

@Composable
internal fun EffortDepartScreen(
    ui: DepartUi,
    demoMissing: Boolean,
    offline: Boolean = false,
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
    val searchMode = !offline && ui.query.isNotBlank() && (ui.searching || ui.searchStatus != null)

    // 들어올 때: 카드마다 아래 24px → 제자리 + 투명 → 보임 0.7초, 0.06초씩 지연. 화면에 처음 들어올 때 한 번만(검색에서 돌아와도 다시 안 함)
    val enterTotalMs = CARD_RISE_MS + CARD_STAGGER_MS * (CARD_COUNT - 1)
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(enterTotalMs, easing = LinearEasing)) }
    val cardEnter = { i: Int -> ChagokMotion.Rise.transform(((enter.value * enterTotalMs - i * CARD_STAGGER_MS) / CARD_RISE_MS).coerceIn(0f, 1f)) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            val people = payers
            CgTitleBar(
                stringResource(R.string.effort_depart_title), centered = true, compact = narrow,
                leading = { CgIconButton(stringResource(R.string.back), CgIcon.Back, onBack, plain = true) },
                trailing = {
                    CgPill(
                        // 아티팩트 depart(1942): 알림이 떠 있는 동안 센서를 못 읽었으면 "인원 확인 필요", 읽었으면 "확인 중".
                        // 닫힌 뒤에는 2명 이상 "함께 N명", 1명 "혼자 운전"
                        // 분할 창은 제목과 겹치지 않게 "확인 필요"(#118 D9, 빨간 점 없음)
                        if (askPayers || peopleOpen) stringResource(if (seats <= 0) (if (narrow) R.string.effort_depart_people_check_short else R.string.effort_depart_people_check) else R.string.effort_depart_people_checking)
                        else if (people > 1) stringResource(R.string.effort_depart_people_pill, people) else stringResource(R.string.effort_depart_people_solo),
                        onClick = { peopleOpen = true }, compact = narrow, icon = CgIcon.Users,
                    )
                },
            )
            // 인터넷 없음(depart-offline): 위 바 아래 경고 알약 · 검색 한 줄은 흐리게 막는다
            if (offline) CgBanner(stringResource(R.string.effort_offline_banner), Modifier.padding(top = if (narrow) 20.gpx else ChagokSpace.S28.gpx), warn = true, icon = CgIcon.WifiSlash)
            // 위 바 → 검색 28(분할 20)
            Box(Modifier.padding(top = if (narrow) 20.gpx else ChagokSpace.S28.gpx)) {
                CgSearchField(ui.query, stringResource(R.string.effort_search_hint), stringResource(R.string.effort_search_clear), onQuery, search, onClearSearch, compact = narrow, disabled = offline)
            }
            if (demoMissing) CgBanner(stringResource(R.string.effort_depart_no_demo), Modifier.padding(top = if (narrow) ChagokSpace.S16.gpx else ChagokSpace.S24.gpx))
            if (searchMode) {
                // 검색이 안 될 때는 최근 목적지 2곳(아티팩트 DCARDS.slice(2): 집 · 회사 빼고)
                val recent = ui.naviOptions.filter { it.kind == DestOption.Kind.RECENT }.ifEmpty { ui.naviOptions }.take(2)
                SearchResults(ui, Modifier.padding(top = ChagokSpace.S24.gpx), onPick = { keyboard?.hide(); focus.clearFocus(); onSelect(it); onClearSearch() }, recent = recent)
            } else {
                Column(Modifier.fillMaxWidth().weight(1f)) {
                    if (ui.searchStatus == PlacesStatus.UNAVAILABLE && !offline) CgBanner(stringResource(R.string.effort_search_unavailable), Modifier.padding(top = 16.gpx), warn = true, icon = CgIcon.Warning)
                    // .hlabel: 위 40(분할 28) · 아래 14(분할 10) · 좌우 8
                    Text(stringResource(R.string.effort_navi_places), Modifier.padding(start = ChagokSpace.S8.gpx, end = ChagokSpace.S8.gpx, top = if (narrow) ChagokSpace.S28.gpx else ChagokSpace.S40.gpx, bottom = if (narrow) 10.gpx else 14.gpx), style = Chagok.type.bodyS, color = c.textTertiary)
                    val options = ui.naviOptions.take(4)
                    when {
                        ui.naviLoading -> Text(stringResource(R.string.effort_navi_loading), style = Chagok.type.bodyM, color = c.textTertiary)
                        options.isEmpty() -> CgEmpty(
                            stringResource(R.string.effort_navi_empty_title), stringResource(R.string.effort_navi_empty_hint),
                            Modifier.fillMaxWidth().height(if (narrow) 240.gpx else 300.gpx),
                        )
                        else -> DestCards(options, selected, narrow, cardEnter, onClick = { o -> if (ui.selected == o) onUnselect() else onSelect(o) })
                    }
                    Spacer(Modifier.height(if (narrow) 16.gpx else 28.gpx))   // .pv margin-top 28
                    val text = ui.previewText
                    val state = when {
                        selected == null -> CgPreviewState.Empty
                        ui.routeFailed -> CgPreviewState.RouteFailed
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
                        // 혼자면 정리 안내 줄을 두지 않는다(아티팩트 s.people>1)
                        note = when {
                            state == CgPreviewState.RouteFailed -> stringResource(R.string.effort_route_send_failed_note)
                            state == CgPreviewState.Failed -> stringResource(R.string.effort_preview_fail_note)
                            payers > 1 -> stringResource(R.string.effort_preview_note)
                            else -> null
                        },
                        emptyText = stringResource(R.string.effort_preview_empty),
                        failText = stringResource(if (state == CgPreviewState.RouteFailed) R.string.effort_route_send_failed else R.string.effort_preview_failed),
                        retryLabel = stringResource(R.string.effort_route_retry),
                        onRetry = { ui.selected?.let(onSelect) },
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = !narrow),
                        compact = narrow,
                        photo = placePhotoRes(com.mist.pleos_app.effort.placePhotoOf(selected?.dest?.name)),
                        onPhoto = true,
                    ) { m ->
                        // 경로 실패면 같은 자리가 [다시 시도](시안 rgo: 누르면 다시 requestRoute = 다시 출발)
                        CgCta(
                            stringResource(if (sending) R.string.effort_depart_sending else if (ui.routeFailed) R.string.effort_route_retry else R.string.effort_depart_go),
                            onClick = { if (!sending) sending = true }, modifier = m, compact = narrow,
                            enabled = ui.canDepart, busy = sending, fill = narrow,
                        )
                    }
                }
            }
        }
        // 인원 확인 알림 바(위 바 줄에 겹침)
        // depart-sensor: 좌석 센서를 못 읽으면 감지 인원 없이 "몇 명이 함께 타나요?"로 직접 고르게
        if (askPayers || peopleOpen) {
            var n by rememberSaveable(seats) { mutableIntStateOf((if (peopleOpen || seats <= 0) payers else seats).coerceIn(PayerFlow.MIN, PayerFlow.MAX)) }
            var touched by rememberSaveable(seats) { mutableStateOf(false) }   // 센서를 못 읽은 뒤 −/+를 눌렀는지(아티팩트 splitTouched)
            // v39(명준 11/8): 감지 인원과 다르게 바꾸면 "N명이 낼게요" + "감지는 S명 · N명으로 나눠요", 되돌리면 원래 문구.
            // 센서를 못 읽었으면 "몇 명이 함께 타나요?" → 직접 고르면 "N명이 낼게요" + "직접 고른 인원으로 나눠요"
            CgPeopleAlert(
                if (seats <= 0) (if (touched) stringResource(R.string.payer_bar_title_manual, n) else stringResource(R.string.payer_bar_title_unknown))
                else if (n == seats) stringResource(R.string.payer_bar_title, seats) else stringResource(R.string.payer_bar_title_manual, n),
                when {
                    n <= 1 -> stringResource(R.string.payer_bar_solo)
                    seats <= 0 && touched -> stringResource(R.string.payer_bar_hint_picked)
                    seats <= 0 -> stringResource(R.string.payer_bar_hint_unknown)
                    n != seats -> stringResource(R.string.payer_bar_hint_manual, seats, n)
                    else -> stringResource(R.string.payer_bar_hint)
                },
                Modifier.align(Alignment.TopCenter), compact = narrow,
            ) {
                CgStepper(
                    stringResource(R.string.payer_bar_value, n), onMinus = { n--; touched = true }, onPlus = { n++; touched = true },
                    minusLabel = stringResource(R.string.payer_minus), plusLabel = stringResource(R.string.payer_plus),
                    minusEnabled = n > PayerFlow.MIN, plusEnabled = n < PayerFlow.MAX, compact = narrow, inBar = narrow,
                )
                CgDivider(compact = narrow)
                // [확인] · [닫기] 사이 8(분할 4)(.tbtn + .tbtn margin-left -20). 센서를 못 읽었을 때 [닫기]는 고른 인원을 그대로 쓴다(아티팩트 act 2457)
                Row(horizontalArrangement = Arrangement.spacedBy(if (narrow) ChagokSpace.S4.gpx else ChagokSpace.S8.gpx), verticalAlignment = Alignment.CenterVertically) {
                    CgTextButton(stringResource(R.string.confirm), { peopleOpen = false; onConfirmPayers(n) }, primary = true, compact = narrow)
                    CgTextButton(stringResource(R.string.close), { peopleOpen = false; if (seats <= 0) onConfirmPayers(n) else onDismissPayers() }, compact = narrow)
                }
            }
        }
    }
}

/** 즐겨찾기·최근 카드 4장(가로 4열, 분할 2×2). */
@Composable
private fun DestCards(options: List<DestOption>, selected: DestOption?, narrow: Boolean, enter: (Int) -> Float, onClick: (DestOption) -> Unit) {
    val cols = if (narrow) 2 else 4
    Column(verticalArrangement = Arrangement.spacedBy(if (narrow) 16.gpx else 24.gpx)) {
        options.chunked(cols).forEachIndexed { rowIdx, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (narrow) 16.gpx else 24.gpx)) {
                row.forEachIndexed { colIdx, o ->
                    val idx = rowIdx * cols + colIdx
                    CgDestCard(
                        kindText(o), o.dest.name,
                        meta = o.distanceText ?: o.dest.address?.takeIf { it.isNotBlank() && it != o.dest.name },   // 내비 최근은 주소가 이름과 같을 때가 많음
                        selected = selected?.dest?.name == o.dest.name, onClick = { onClick(o) },
                        modifier = Modifier.weight(1f).graphicsLayer { val e = enter(idx); alpha = e; translationY = (1f - e) * 24f * density },
                        compact = narrow,
                        icon = when (o.kind) { DestOption.Kind.HOME -> CgIcon.House; DestOption.Kind.OFFICE -> CgIcon.Briefcase; DestOption.Kind.BOOKMARK -> CgIcon.Star; else -> CgIcon.Recent },
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

/**
 * 검색 결과(아티팩트 depart 검색 모드): 카드 1040(분할 전체 폭) · 안쪽 12 · 줄 높이 112(분할 88) · 줄 사이 구분선(줄 전체 폭).
 * 찾는 중("찾는 중" + 점 · 두 줄 막대 3줄) · 결과 없음 · 안 됨(경고 카드 + 최근 목적지 카드) · 위치 못 받음(머리 대신 안내 한 줄).
 */
@Composable
private fun SearchResults(ui: DepartUi, modifier: Modifier, onPick: (DestOption) -> Unit, recent: List<DestOption>) {
    val c = Chagok.colors
    val narrow = LocalNarrow.current
    val shape = RoundedCornerShape(ChagokRadius.R32.gpx)
    val box = @Composable { content: @Composable ColumnScope.() -> Unit ->
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                (if (narrow) Modifier.fillMaxWidth() else Modifier.width(1040.gpx)).background(c.surfaceCard, shape).border(2.gpx, c.line, shape).padding(ChagokSpace.S12.gpx)
                    .verticalScroll(rememberScrollState()),
                content = content,
            )
        }
    }
    // .lh: 머리 줄 padding 8 16(분할 4 12)
    val headPad = Modifier.padding(horizontal = if (narrow) ChagokSpace.S12.gpx else ChagokSpace.S16.gpx, vertical = if (narrow) ChagokSpace.S4.gpx else ChagokSpace.S8.gpx)
    val head = @Composable { text: String, style: TextStyle -> Text(text, headPad, style = style, color = c.textTertiary) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ChagokSpace.S24.gpx)) {
        when {
            ui.searching -> box {
                Row(headPad, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ChagokSpace.S12.gpx)) {
                    Text(stringResource(R.string.effort_searching), style = Chagok.type.bodyS, color = c.textTertiary)
                    CgLoadingDots(c.textTertiary)
                }
                repeat(3) { i ->
                    if (i > 0) RowDivider()
                    Row(Modifier.fillMaxWidth().heightIn(min = resultRowHeight(narrow)).padding(horizontal = resultRowPad(narrow)), verticalAlignment = Alignment.CenterVertically) {
                        // .tx 폭 60% 안에서 이름 막대 46% · 주소 막대 28%, 막대 높이는 모두 22, 사이 12
                        Column(Modifier.weight(1f)) {
                            Column(Modifier.fillMaxWidth(.6f), verticalArrangement = Arrangement.spacedBy(ChagokSpace.S12.gpx)) {
                                CgSkeleton(Modifier.fillMaxWidth(.46f).height(22.gpx))
                                CgSkeleton(Modifier.fillMaxWidth(.28f).height(22.gpx))
                            }
                        }
                        CgSkeleton(Modifier.fillMaxWidth(.08f).height(22.gpx))
                    }
                }
            }
            ui.searchStatus == PlacesStatus.EMPTY -> box {
                // 카드 안 합계 36 40(분할 24 28) = 바깥 12 + 안쪽
                Column(
                    Modifier.padding(horizontal = if (narrow) ChagokSpace.S16.gpx else ChagokSpace.S28.gpx, vertical = if (narrow) ChagokSpace.S12.gpx else ChagokSpace.S24.gpx),
                    verticalArrangement = Arrangement.spacedBy(ChagokSpace.S8.gpx),
                ) {
                    Text(stringResource(R.string.effort_search_empty_title, ui.query.trim()), style = if (narrow) Chagok.type.bodyL else Chagok.type.titleM, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.effort_search_empty_hint), style = Chagok.type.bodyM, color = c.textTertiary)
                }
            }
            ui.searchStatus == PlacesStatus.UNAVAILABLE -> {
                // .card.notice.warn: 경고색 안내 카드(padding 36 40, 분할 24 28), 그 아래 최근 목적지 카드
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Column(
                        (if (narrow) Modifier.fillMaxWidth() else Modifier.width(1040.gpx)).background(c.statusWarningBackground, shape)
                            .padding(horizontal = if (narrow) ChagokSpace.S28.gpx else ChagokSpace.S40.gpx, vertical = if (narrow) ChagokSpace.S24.gpx else 36.gpx),
                        verticalArrangement = Arrangement.spacedBy(ChagokSpace.S8.gpx),
                    ) {
                        Text(stringResource(R.string.effort_search_fail_title), style = Chagok.type.titleM, color = c.statusWarningForeground)
                        Text(stringResource(R.string.effort_search_fail_hint), style = Chagok.type.bodyM, color = c.statusWarningForeground)
                    }
                }
                if (recent.isNotEmpty()) box { recent.forEachIndexed { i, o -> if (i > 0) RowDivider(); ResultRow(o, onPick, meta = listOfNotNull(stringResource(kindRes(o)), o.distanceText).joinToString(" · ")) } }
            }
            else -> box {
                // 이번 검색에 위치를 보내지 않았으면 머리 "검색 결과" 대신 안내 한 줄(label-m 400)
                if (ui.searchResults.isNotEmpty() && !ui.searchSentLocation) head(stringResource(R.string.effort_search_noloc), Chagok.type.labelM)
                else head(stringResource(R.string.effort_search_results), Chagok.type.bodyS)
                ui.searchResults.forEachIndexed { i, o -> if (i > 0) RowDivider(); ResultRow(o, onPick) }
            }
        }
    }
}

/** `.dst` 줄: min-height 112(분할 88) · 좌우 24(분할 16). */
@Composable
private fun resultRowHeight(narrow: Boolean) = if (narrow) 88.gpx else 112.gpx
@Composable
private fun resultRowPad(narrow: Boolean) = if (narrow) ChagokSpace.S16.gpx else ChagokSpace.S24.gpx

/** `.dst + .dst` 위쪽 1px 구분선: 줄 전체 폭. */
@Composable
private fun RowDivider() = Box(Modifier.fillMaxWidth().height(1.gpx).background(Chagok.colors.dividerDefault))

private fun kindRes(o: DestOption): Int = when (o.kind) {
    DestOption.Kind.RECENT -> R.string.effort_kind_recent
    DestOption.Kind.SEARCH -> R.string.effort_kind_search
    else -> R.string.effort_kind_bookmark
}

@Composable
private fun ResultRow(o: DestOption, onPick: (DestOption) -> Unit, meta: String? = null) {
    val c = Chagok.colors
    val narrow = LocalNarrow.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = resultRowHeight(narrow)).clickable(role = Role.Button) { onPick(o) }.padding(horizontal = resultRowPad(narrow)),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.gpx),
    ) {
        Column(Modifier.weight(1f)) {
            Text(o.dest.name, style = if (narrow) Chagok.type.bodyMB else Chagok.type.bodyL, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // 내비 최근 목적지는 주소가 이름과 같을 때가 많아 그때는 빼요(검색 안 됨 → 최근 목적지 줄)
            val sub = meta ?: listOfNotNull(o.dest.address?.takeIf { it.isNotBlank() && it != o.dest.name }, o.category).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = if (narrow) Chagok.type.labelS else Chagok.type.labelM, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        o.distanceText?.let { Text(it, style = Chagok.type.bodyM, color = c.textSecondary, maxLines = 1) }
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
