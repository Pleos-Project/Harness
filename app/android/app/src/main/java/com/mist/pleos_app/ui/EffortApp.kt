package com.mist.pleos_app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import com.mist.pleos_app.effort.DepartUi
import com.mist.pleos_app.effort.DestOption
import com.mist.pleos_app.navi.DepartState
import com.mist.pleos_app.navi.Destination
import com.mist.pleos_app.net.PlacesStatus
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mist.pleos_app.R
import com.mist.pleos_app.app.Settings
import com.mist.pleos_app.effort.EffortPhase
import com.mist.pleos_app.effort.EffortRecord
import com.mist.pleos_app.effort.EffortScreen
import com.mist.pleos_app.effort.EffortUi
import com.mist.pleos_app.effort.EffortViewModel
import com.mist.pleos_app.effort.OpenTrip
import com.mist.pleos_app.effort.DemoEffortSource
import com.mist.pleos_app.effort.DemoScene
import com.mist.pleos_app.ui.components.CgBody
import com.mist.pleos_app.ui.components.CgButton
import com.mist.pleos_app.ui.components.CgButtonKind
import com.mist.pleos_app.ui.components.CgCaption
import com.mist.pleos_app.ui.components.CgCard
import com.mist.pleos_app.ui.components.CgChip
import com.mist.pleos_app.ui.components.CgNotice
import com.mist.pleos_app.ui.components.CgTitle
import com.mist.pleos_app.ui.components.DemoMark
import com.mist.pleos_app.ui.theme.Chagok
import com.mist.pleos_app.ui.theme.ChagokTheme
import com.mist.pleos_app.ui.theme.NARROW_MAX_PX
import com.mist.pleos_app.ui.theme.gpx
import java.time.LocalDate

/**
 * 노고 정산 앱 뼈대(명세 v0.5 3절): 홈 → 출발 → (내비 안내) → 도착(영수증 + 제안 한 화면, Design v2 #75) → 홈.
 * 화면 코드에는 금액·배수·시간 계산을 두지 않는다(engine 패키지·constants effort.copy만).
 */
@Composable
fun EffortApp(
    vm: EffortViewModel,
    driving: Boolean,
    parked: Boolean,
    doorOpen: Boolean?,
    seats: Int,
    route: DepartState,
    onExit: () -> Unit,
) {
    val stack by vm.stack.collectAsStateWithLifecycle()
    val screen = stack.last()
    val ui by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val depart by vm.departUi.collectAsStateWithLifecycle()
    val payer by vm.payerState.collectAsStateWithLifecycle()
    // CCP Back: 최상단(홈)에서는 finish 하지 않고 숨긴다. 주행 중 Back은 받아서 무시(가림 화면 유지)
    BackHandler { if (!driving && !vm.back()) onExit() }
    // F22 도착: 기어·운전석 문이 바뀔 때, 그리고 화면이 다시 보일 때(이벤트, 폴링 없음). 기어 P + 문 열림이면 도착인지 본다
    val shown by vm.shown.collectAsStateWithLifecycle()
    LaunchedEffect(parked, doorOpen, shown) { vm.onCar(parked, doorOpen) }
    // 인원 확인 알림(PR #34·#40 재사용): 출발 화면에서 2명 이상 + 지난번 확인값과 다르면(노고 PRD v1.3 8장)
    val askPayers = remember(seats, payer, screen) { screen == EffortScreen.DEPART && vm.shouldAskPayers(seats) }
    EffortFrame(demoValues = ui.demo, driving = driving, toast = {
        if (askPayers) PayerToast(seats, onConfirm = { vm.confirmPayers(seats, it) }, onDismiss = { vm.dismissPayers(seats) })
    }) {
        when (screen) {
            EffortScreen.HOME -> EffortHome(
                ui,
                routeFailed = route == DepartState.FAILED && vm.canRetryRoute,
                onDepart = vm::openDepart,
                onDiscard = vm::discardTrip,
                onRetry = { vm.retryRoute() },
                onSettings = { vm.open(EffortScreen.SETTINGS) },
                onReceipt = { vm.open(EffortScreen.RECEIPT) },
                onArrive = vm::arriveNow,
            )
            EffortScreen.DEPART -> {
                val demo = (vm.source as? DemoEffortSource)?.scene
                if (demo != null) DemoDepart(demo, onGo = { vm.depart() })
                else EffortDepart(
                    depart, payers = vm.payersFor(seats), demoMissing = settings.demoFixed && !ui.demoAvailable,
                    onQuery = vm::setQuery, onSearch = vm::search, onSelect = vm::select, onGo = { vm.depart(seats) },
                )
            }
            EffortScreen.SETTINGS -> EffortSettings(settings, onChange = vm::updateSettings, onReset = vm::resetRecords)
            EffortScreen.RECEIPT -> EffortArriveScreen(ui, onRest = vm::answerRest, onPick = vm::pick, onClose = vm::closeArrive)
        }
    }
}

/** 창 폭(AC-C-9)·주행 중 가림(X1, AC-C-1)·"시연용 값"(AC-C-7)·위쪽 알림. 화면 스택은 그대로라 정차하면 같은 화면으로 돌아온다. */
@Composable
private fun EffortFrame(demoValues: Boolean, driving: Boolean, toast: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Chagok.colors.bgPrimary)) {
        val narrow = with(LocalDensity.current) { maxWidth.toPx() } <= NARROW_MAX_PX
        CompositionLocalProvider(LocalNarrow provides narrow) {
            if (driving) DrivingCover() else Box(Modifier.fillMaxSize().padding(horizontal = 40.gpx, vertical = 32.gpx)) {
                content()
                Box(Modifier.align(Alignment.TopCenter)) { toast() }
                if (demoValues) DemoMark(stringResource(R.string.demo_values), Modifier.align(Alignment.BottomEnd))
            }
        }
    }
}

// ------------------------------------------------------------ 홈: [동승 출발] · 진행 중 · 최근 기록 3건
@Composable
private fun EffortHome(
    ui: EffortUi,
    routeFailed: Boolean,
    onDepart: () -> Unit,
    onDiscard: () -> Unit,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
    onReceipt: () -> Unit = {},
    onArrive: () -> Unit = {},
) {
    val gap = Arrangement.spacedBy(24.gpx)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = gap) {
        val trip = ui.openTrip
        when (ui.phase) {
            EffortPhase.IDLE -> CgButton(stringResource(R.string.effort_depart_start), onDepart, Modifier.fillMaxWidth())
            EffortPhase.DRIVING -> trip?.let { TripCard(it, arrived = false, routeFailed = routeFailed, onDiscard = onDiscard, onRetry = onRetry, onArrive = onArrive) }
            EffortPhase.ARRIVED -> trip?.let { TripCard(it, arrived = true, routeFailed = false, onDiscard = onDiscard, onRetry = onRetry, onReceipt = onReceipt) }
        }
        CgCaption(stringResource(R.string.effort_recent))
        if (ui.recent.isEmpty()) {
            CgBody(stringResource(R.string.effort_recent_empty), secondary = true)
        } else {
            ui.recent.forEach { RecordCard(it) }
        }
        CgButton(stringResource(R.string.screen_settings), onSettings, Modifier.fillMaxWidth(), kind = CgButtonKind.Basic)
    }
}

/** 진행 중인 출발(X2 '재시작 중 열린 출발 기록'). 목적지 이름만 보여 준다(좌표 없음). */
@Composable
private fun TripCard(
    trip: OpenTrip,
    arrived: Boolean,
    routeFailed: Boolean,
    onDiscard: () -> Unit,
    onRetry: () -> Unit,
    onReceipt: () -> Unit = {},
    onArrive: () -> Unit = {},
) {
    CgCard(Modifier.fillMaxWidth()) {
        CgTitle(stringResource(if (arrived) R.string.effort_arrived else R.string.effort_driving, trip.destName))
        if (routeFailed) CgNotice(stringResource(R.string.effort_route_failed))
        else if (!arrived) CgBody(stringResource(R.string.effort_driving_hint), secondary = true)
        Row(horizontalArrangement = Arrangement.spacedBy(24.gpx)) {
            if (arrived) CgButton(stringResource(R.string.effort_receipt_open), onReceipt)
            else CgButton(stringResource(R.string.effort_arrive_now), onArrive)   // 내비가 안내를 끝내지 않아도 직접 도착(#68)
            if (routeFailed) CgButton(stringResource(R.string.effort_route_retry), onRetry)
            CgButton(stringResource(R.string.effort_discard), onDiscard, kind = CgButtonKind.Basic)
        }
    }
}

@Composable
private fun RecordCard(r: EffortRecord) {
    CgCard(Modifier.fillMaxWidth()) {
        CgCaption(stringResource(R.string.effort_record_line, dayText(r.date), r.destName))
        CgBody(r.headlineText)
    }
}

/** "2026-11-17" → "11월 17일". 날짜 표기만(계산 없음). */
private fun dayText(date: String): String =
    runCatching { LocalDate.parse(date).let { "${it.monthValue}월 ${it.dayOfMonth}일" } }.getOrDefault(date)

// ------------------------------------------------------------ 출발(F20): 목적지(검색·즐겨찾기·최근) → requestTrip 미리보기 → [출발]
/** 시연(데모 고정 + demo.json): 목적지·인원이 정해져 있다(명세 v0.5 12절). */
@Composable
private fun DemoDepart(demo: DemoScene, onGo: () -> Unit) {
    val gap = Arrangement.spacedBy(24.gpx)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = gap) {
        CgTitle(stringResource(R.string.effort_depart_title))
        CgCard(Modifier.fillMaxWidth()) {
            CgTitle(demo.destName)
            CgBody(stringResource(R.string.effort_depart_people, demo.n), secondary = true)
        }
        CgButton(stringResource(R.string.effort_depart_go), onGo, Modifier.fillMaxWidth())
    }
}

/**
 * 실제 출발 화면. 숫자(ETA·거리·정체)는 엔진 departurePreview가 만든 문구만 보여 준다(화면에서 계산하지 않음).
 * 검색은 키보드 [검색]이나 버튼을 눌렀을 때만(자동 완성 없음).
 */
@Composable
private fun EffortDepart(
    ui: DepartUi,
    payers: Int,
    demoMissing: Boolean,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onSelect: (DestOption) -> Unit,
    onGo: () -> Unit,
) {
    val gap = Arrangement.spacedBy(24.gpx)
    val c = Chagok.colors
    // 검색하면 키보드를 닫는다(차량 키보드는 Back을 먼저 받지 않아 열린 채로 Back을 누르면 화면이 닫힘, 에뮬 11/7)
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val search = { keyboard?.hide(); focus.clearFocus(); onSearch() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = gap) {
        CgTitle(stringResource(R.string.effort_depart_title))
        if (demoMissing) CgNotice(stringResource(R.string.effort_depart_no_demo))

        // 고른 목적지 + [출발]
        ui.selected?.let { sel ->
            CgCard(Modifier.fillMaxWidth()) {
                CgCaption(stringResource(R.string.effort_selected))
                CgTitle(sel.dest.name)
                sel.dest.address?.takeIf { it.isNotBlank() }?.let { CgBody(it, secondary = true) }
                CgBody(stringResource(R.string.effort_depart_people, payers), secondary = true)
                if (ui.previewing) CgCaption(stringResource(R.string.effort_checking_route))
                // 미리보기 문구는 엔진(departurePreview)이 만든 그대로(명세 v0.5 F20·12절 시연 순서 2)
                ui.previewText?.let { p ->
                    CgBody(stringResource(R.string.effort_preview_route, p.etaText, p.routeText))
                    p.congestionText?.let { CgBody(stringResource(R.string.effort_preview_congestion, it), secondary = true) }
                }
                CgButton(stringResource(R.string.effort_depart_go), onGo, Modifier.fillMaxWidth(), enabled = ui.canDepart)
            }
        }

        // 검색(서버 카카오 로컬, #57)
        Row(horizontalArrangement = gap, verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .weight(1f)
                    .background(c.btnBasic, RoundedCornerShape(16.gpx))
                    .border(1.gpx, c.line, RoundedCornerShape(16.gpx))
                    .padding(horizontal = 24.gpx, vertical = 20.gpx),
            ) {
                val hint = stringResource(R.string.effort_search_hint)
                if (ui.query.isEmpty()) Text(hint, style = Chagok.type.bodyM, color = c.textTertiary)
                BasicTextField(
                    value = ui.query,
                    onValueChange = { onQuery(it.take(100)) },
                    singleLine = true,
                    textStyle = Chagok.type.bodyM.copy(color = c.textPrimary),
                    cursorBrush = SolidColor(c.active),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint },
                )
            }
            CgButton(stringResource(R.string.effort_search), { search() }, enabled = ui.query.isNotBlank() && !ui.searching)
        }
        when {
            ui.searching -> CgCaption(stringResource(R.string.effort_searching))
            ui.searchStatus == PlacesStatus.EMPTY -> CgNotice(stringResource(R.string.effort_search_empty))
            ui.searchStatus == PlacesStatus.UNAVAILABLE -> CgNotice(stringResource(R.string.effort_search_unavailable))
        }
        ui.searchResults.forEach { DestRow(it, selected = ui.selected == it, onClick = { onSelect(it) }) }

        // 즐겨찾기·최근 목적지(내비)
        CgCaption(stringResource(R.string.effort_navi_places))
        when {
            ui.naviLoading -> CgBody(stringResource(R.string.effort_navi_loading), secondary = true)
            ui.naviOptions.isEmpty() -> CgBody(stringResource(R.string.effort_navi_empty), secondary = true)
            else -> ui.naviOptions.forEach { DestRow(it, selected = ui.selected == it, onClick = { onSelect(it) }) }
        }
    }
}

@Composable
private fun DestRow(o: DestOption, selected: Boolean, onClick: () -> Unit) {
    CgCard(Modifier.fillMaxWidth(), onClick = onClick) {
        val tag = when (o.kind) {
            DestOption.Kind.HOME -> stringResource(R.string.effort_kind_home)
            DestOption.Kind.OFFICE -> stringResource(R.string.effort_kind_office)
            DestOption.Kind.BOOKMARK -> stringResource(R.string.effort_kind_bookmark)
            DestOption.Kind.RECENT -> stringResource(R.string.effort_kind_recent)
            DestOption.Kind.SEARCH -> o.category
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.gpx), verticalAlignment = Alignment.CenterVertically) {
            Text(o.dest.name, Modifier.weight(1f), style = Chagok.type.titleS, color = if (selected) Chagok.colors.active else Chagok.colors.textPrimary)
            tag?.let { CgCaption(it) }
        }
        o.dest.address?.takeIf { it.isNotBlank() }?.let { CgBody(it, secondary = true) }
    }
}

// ------------------------------------------------------------ 도착: 영수증(F23) + 쉰 시간(F21) + 제안(F24·F25) 한 화면 → EffortArrive.kt

// ------------------------------------------------------------ 설정(S20): 제안 방식 A/B · 데모 고정 모드 · 기록 초기화
@Composable
private fun EffortSettings(s: Settings, onChange: (Settings) -> Unit, onReset: () -> Unit) {
    val gap = Arrangement.spacedBy(24.gpx)
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    var resetDone by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = gap) {
        CgCaption(stringResource(R.string.effort_settings_method))
        CgCard(Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = gap, verticalAlignment = Alignment.CenterVertically) {
                CgChip(stringResource(R.string.effort_settings_method_a), selected = s.proposalMethod == Settings.METHOD_A, onClick = { onChange(s.copy(proposalMethod = Settings.METHOD_A)) })
                CgChip(stringResource(R.string.effort_settings_method_b), selected = s.proposalMethod == Settings.METHOD_B, onClick = { onChange(s.copy(proposalMethod = Settings.METHOD_B)) })
            }
            CgCaption(stringResource(R.string.effort_settings_method_hint))
        }
        CgCaption(stringResource(R.string.settings_mock))
        CgCard(Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = gap, verticalAlignment = Alignment.CenterVertically) {
                CgBody(stringResource(R.string.settings_demo_fixed), Modifier.weight(1f))
                CgChip(stringResource(R.string.on), selected = s.demoFixed, onClick = { onChange(s.copy(demoFixed = true)) })
                CgChip(stringResource(R.string.off), selected = !s.demoFixed, onClick = { onChange(s.copy(demoFixed = false)) })
            }
            CgCaption(stringResource(R.string.effort_settings_demo_hint))
        }
        CgCaption(stringResource(R.string.settings_records))
        CgButton(stringResource(R.string.settings_reset), { confirmReset = true }, Modifier.fillMaxWidth(), kind = CgButtonKind.Basic)
        if (resetDone) CgBody(stringResource(R.string.settings_reset_done), secondary = true)
    }
    if (confirmReset) {
        Dialog(onDismissRequest = { confirmReset = false }) {
            CgCard(Modifier.width(720.gpx)) {
                CgTitle(stringResource(R.string.settings_reset_title))
                CgBody(stringResource(R.string.effort_reset_body), secondary = true)
                Row(horizontalArrangement = gap, modifier = Modifier.fillMaxWidth()) {
                    CgButton(stringResource(R.string.cancel), { confirmReset = false }, Modifier.weight(1f), kind = CgButtonKind.Basic)
                    CgButton(stringResource(R.string.settings_reset_confirm), { onReset(); confirmReset = false; resetDone = true }, Modifier.weight(1f))
                }
            }
        }
    }
}

// ------------------------------------------------------------ 미리보기: 가로·세로(1:1 분할)
private val previewTrip = OpenTrip("t1", "2026-11-17T16:00:00+09:00", "태안 꽃지해수욕장", 4, 6300.0, demo = true)
private val previewRecord = EffortRecord("t0", "2026-11-10", "양평 두물머리", 60.0, 1.0, 60.0, "A", "약 5천 원", "accept", "오늘 운전 1시간 · 평소와 같은 길")

@Preview(name = "홈·진행 중", widthDp = 1280, heightDp = 720)
@Preview(name = "홈·세로", widthDp = 640, heightDp = 720)
@Composable
private fun EffortHomePreview() {
    ChagokTheme(dark = true) {
        EffortFrame(demoValues = true, driving = false) {
            EffortHome(EffortUi(EffortPhase.DRIVING, previewTrip, listOf(previewRecord), demo = true, demoAvailable = true), routeFailed = true, {}, {}, {}, {})
        }
    }
}

@Preview(name = "홈·처음", widthDp = 640, heightDp = 720)
@Composable
private fun EffortHomeEmptyPreview() {
    ChagokTheme(dark = false) {
        EffortFrame(demoValues = false, driving = false) {
            EffortHome(EffortUi(EffortPhase.IDLE, null, emptyList(), demo = false, demoAvailable = false), routeFailed = false, {}, {}, {}, {})
        }
    }
}
