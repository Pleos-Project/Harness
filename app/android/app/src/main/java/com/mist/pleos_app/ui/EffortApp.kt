package com.mist.pleos_app.ui

import com.mist.pleos_app.ui.components.CgSize
import com.mist.pleos_app.ui.components.CgIconMark
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import com.mist.pleos_app.ui.components.CgBottomSheet
import com.mist.pleos_app.ui.components.CgSheetButtons
import com.mist.pleos_app.ui.components.CgSwitch
import com.mist.pleos_app.ui.components.CgSegmented
import com.mist.pleos_app.ui.components.CgIcon
import com.mist.pleos_app.ui.components.CgIconButton
import com.mist.pleos_app.ui.components.CgTitleBar
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mist.pleos_app.R
import com.mist.pleos_app.app.Settings
import com.mist.pleos_app.effort.WageSource
import com.mist.pleos_app.effort.wageSourceOf
import com.mist.pleos_app.effort.EffortPhase
import com.mist.pleos_app.effort.EffortRecord
import com.mist.pleos_app.effort.EffortScreen
import com.mist.pleos_app.effort.EffortUi
import com.mist.pleos_app.effort.EffortViewModel
import com.mist.pleos_app.effort.OpenTrip
import com.mist.pleos_app.effort.DemoScene
import com.mist.pleos_app.ui.components.CgBody
import com.mist.pleos_app.ui.components.CgButton
import com.mist.pleos_app.ui.components.CgButtonKind
import com.mist.pleos_app.ui.components.CgCaption
import com.mist.pleos_app.ui.components.CgCard
import com.mist.pleos_app.ui.components.CgChip
import com.mist.pleos_app.ui.components.CgNotice
import com.mist.pleos_app.ui.components.CgTitle
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
    BackHandler { if (driving) return@BackHandler; if (!vm.back()) onExit() }
    // F22 도착: 기어가 바뀔 때, 그리고 화면이 다시 보일 때(이벤트, 폴링 없음). 운전석 문은 보지 않는다(DECISIONS 11/13)
    val shown by vm.shown.collectAsStateWithLifecycle()
    LaunchedEffect(parked, shown) { vm.onCar(parked) }
    // F28 앰비언트 라이트(구두 합의 11/9): 앱이 보이는 동안(정차) 늘 브랜드 색으로 켜 둔다. 주행으로 바뀌면(X1)·가려지면(onStop)
    // 차량 쪽이 그 자리에서 되돌리고, 기어 P로 다시 보이면 다시 켠다. 설정(S20)을 다시 켜도 다시 켠다
    LaunchedEffect(shown, driving, settings.ambientLight) {
        if (!driving && settings.ambientLight) vm.ambient?.acquire(AMBIENT_APP)
    }
    // 인원 확인 알림(June 11/9): 평소 · 시연 모두 출발 화면에 들어올 때마다 바로(좌석 센서 감지 인원, 못 읽으면 직접 고르기)
    val askEnter by vm.askPayersOnEnter.collectAsStateWithLifecycle()
    val askPayers = screen == EffortScreen.DEPART && askEnter
    // 앱 시작 화면(Design v2 home app-splash, 1.6초) — 처음 켤 때 한 번
    var splash by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
    // 알림으로 도착·돌아보기 화면이 먼저 열렸으면 시작 화면은 건너뛴다(나중에 홈으로 와도 1.6초 시작 화면이 다시 뜨지 않게)
    LaunchedEffect(screen) { if (screen != EffortScreen.HOME) splash = false }
    EffortFrame(driving = driving) {
        when (screen) {
            EffortScreen.HOME -> if (splash) EffortSplash { splash = false } else EffortHomeScreen(
                ui,
                onDepart = vm::openDepart,
                onDiscard = vm::discardTrip,
                onSettings = { vm.open(EffortScreen.SETTINGS) },
                onReceipt = vm::openArrived,
                onArrive = vm::arriveNow,
                onLog = { vm.open(EffortScreen.LOG) },
                onMidway = vm::startMidway,
                onRecord = vm::openRecord,
                onNoti = vm::toggleNoti, onNotiClose = vm::closeNoti,
                onArriveAnswer = vm::answerArrive,
            )
            EffortScreen.DEPART -> {
                // Design v2 출발 화면(#75). 시연 모드도 같은 화면 · 기능(데이터만 시연 목록, June 11/9)
                EffortDepartScreen(
                    depart,
                    demoMissing = settings.demoFixed && !ui.demoAvailable,
                    seats = seats, payers = vm.payersFor(seats), askPayers = askPayers,
                    onBack = { vm.back() }, onQuery = vm::setQuery, onSearch = vm::search, onClearSearch = vm::clearSearch,
                    onSelect = vm::select, onUnselect = vm::unselect,
                    onGo = { vm.depart(seats) },
                    onConfirmPayers = { vm.confirmPayers(seats, it) }, onDismissPayers = { vm.dismissPayers(seats) },
                )
            }
            EffortScreen.SETTINGS -> EffortSettings(settings, ui.constants?.let(::wageSourceOf), vm.demoSummary, ui.recordCount, onBack = { vm.back() }, onChange = vm::updateSettings, onReset = vm::resetRecords)
            EffortScreen.REVIEW -> if (ui.reviewItems.isEmpty()) LaunchedEffect(Unit) { vm.consent() }
                else EffortReviewScreen(ui, ui.reviewItems, onCounted = vm::reviewCounted, onConsent = vm::consent, onLater = vm::later)
            EffortScreen.RECEIPT -> EffortArriveScreen(ui, onRest = vm::answerRest, onPick = vm::pick, onClose = vm::closeArrive, onSettleSwap = vm::toggleSettle)
            EffortScreen.LOG -> EffortLogScreen(ui, onBack = { vm.back() }, onRecord = vm::openRecord)
            EffortScreen.RECORD -> EffortRecordScreen(ui, onBack = { vm.back() })
            EffortScreen.MIDWAY -> EffortMidwayScreen(ui, onPick = vm::midwayPick, onClose = vm::closeMidway, onRest = vm::answerMidwayRest)
        }
    }
}

/**
 * 창 폭(AC-C-9)·주행 중 가림(X1, AC-C-1)·위쪽 알림. 화면 스택은 그대로라 정차하면 같은 화면으로 돌아온다.
 * "시연용 값" 알약(AC-C-7)은 뺐다(#118 v122 명준 11/12, DECISIONS 11/13). 시연 고정 모드는 설정 스위치 설명으로만 알린다.
 */
@Composable
private fun EffortFrame(driving: Boolean, toast: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Chagok.colors.bgPrimary)) {
        val narrow = with(LocalDensity.current) { maxWidth.toPx() } <= NARROW_MAX_PX
        CompositionLocalProvider(LocalNarrow provides narrow) {
            // 아티팩트 .pg · .home: 사방 40
            if (driving) DrivingCover() else Box(Modifier.fillMaxSize().padding(40.gpx)) {
                content()
                Box(Modifier.align(Alignment.TopCenter)) { toast() }
            }
        }
    }
}

// ------------------------------------------------------------ 홈: Design v2 → EffortHomeV2.kt

// ------------------------------------------------------------ 출발(F20): Design v2 → EffortDepartV2.kt

// ------------------------------------------------------------ 도착: 영수증(F23) + 쉰 시간(F21) + 제안(F24·F25) 한 화면 → EffortArrive.kt

// ------------------------------------------------------------ 설정(S20): Design v2 v41 `screens/settings.html`
// 제안 방식(둘로 나뉜 알약) · 시연 고정 모드(스위치) · 단가 출처(펼침) · 기록 초기화(확인 시트). 바꾼 값은 바로 다른 화면에 적용
@Composable
private fun EffortSettings(
    s: Settings, wage: WageSource?, demoSummary: Pair<String, String>?, recordCount: Int,
    onBack: () -> Unit, onChange: (Settings) -> Unit, onReset: () -> Unit,
) {
    val narrow = LocalNarrow.current
    val c = Chagok.colors
    val t = Chagok.type
    var wipe by rememberSaveable { mutableStateOf(false) }
    var wageOpen by rememberSaveable { mutableStateOf(false) }
    var photoOpen by rememberSaveable { mutableStateOf(false) }
    val isA = s.proposalMethod != Settings.METHOD_B
    val side = if (narrow) 24.gpx else 40.gpx
    // .srow: 높이 136 · 여백 20 40 · 사이 32 · 제목 title-s(분할 .pg.p .srow: 112 · 여백 16 24 · 사이 16 · 제목 body-l)
    val row: @Composable (String, String, @Composable () -> Unit) -> Unit = { title, sub, end ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = if (narrow) 112.gpx else 136.gpx).padding(horizontal = side, vertical = if (narrow) 16.gpx else 20.gpx),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (narrow) 16.gpx else 32.gpx),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.gpx)) {
                Text(title, style = if (narrow) t.bodyL else t.titleS, color = c.textPrimary)
                Text(sub, style = if (narrow) t.labelM else t.bodyM, color = c.textTertiary)
            }
            end()
        }
    }
    val line: @Composable () -> Unit = { Box(Modifier.fillMaxWidth().height(1.gpx).background(c.dividerDefault)) }
    // 펼침 꺾쇠(.schev 80 · 모서리 24, 안 .chev 16 · 선 4 · txt3, 눌림 tertiary-pressed, 0.26초 회전)
    val chev: @Composable (String, Boolean, () -> Unit) -> Unit = { label, open, onClick ->
        val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        val pressed by src.collectIsPressedAsState()
        val rot by androidx.compose.animation.core.animateFloatAsState(if (open) 180f else 0f, androidx.compose.animation.core.tween(260), label = "chev")
        Box(
            Modifier.size(80.gpx).background(if (pressed) c.btnTertiaryPressed else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(24.gpx))
                .clickable(src, indication = null, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) { CgIconMark(CgIcon.Chevron, 16.gpx, c.textTertiary, Modifier.graphicsLayer { rotationZ = rot }) }
    }
    // 펼친 근거 상자(.swy): --ph 면 · 모서리 16 · 바깥 0 40 24 · 안 16 22 · body-m txt2(분할 0 24 16 · label-m)
    val swy: @Composable (@Composable () -> Unit) -> Unit = { content ->
        Column(
            Modifier.padding(start = side, end = side, bottom = if (narrow) 16.gpx else 24.gpx).fillMaxWidth()
                .background(c.placeholder, RoundedCornerShape(16.gpx)).padding(horizontal = 22.gpx, vertical = 16.gpx),
            verticalArrangement = Arrangement.spacedBy(0.gpx),   // 아티팩트는 <br> 줄바꿈이라 두 줄 사이 간격 없음
        ) { content() }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.gpx)) {
            CgTitleBar(
                stringResource(R.string.screen_settings), compact = narrow,
                leading = { CgIconButton(stringResource(R.string.back), CgIcon.Back, onBack, plain = true) },
            )
            val card = RoundedCornerShape(32.gpx)   // .card 모서리 32 · 테두리 2
            Column(Modifier.fillMaxWidth().background(c.surfaceCard, card).border(2.gpx, c.line, card)) {
                // 제안 방식(개발·시험용 A/B). 1차는 방식 A만이라 숨긴다(명세 v0.6 S20, Settings.METHOD_B_SHOWN)
                if (Settings.METHOD_B_SHOWN) {
                val method = stringResource(R.string.effort_settings_method)
                val methodSub = stringResource(
                    R.string.effort_settings_method_now,
                    stringResource(if (isA) R.string.effort_settings_a_short else R.string.effort_settings_b_short),
                )
                val seg: @Composable (Modifier) -> Unit = { m ->
                    CgSegmented(
                        listOf(stringResource(R.string.effort_settings_method_a), stringResource(R.string.effort_settings_method_b)), if (isA) 0 else 1,
                        { onChange(s.copy(proposalMethod = if (it == 0) Settings.METHOD_A else Settings.METHOD_B)) }, m, compact = narrow,
                    )
                }
                if (narrow) Column(Modifier.padding(bottom = 16.gpx)) {
                    row(method, methodSub) {}
                    seg(Modifier.fillMaxWidth().padding(horizontal = side))
                } else row(method, methodSub) { seg(Modifier) }
                line()
                }
                // 시연 고정 모드
                val demoOn = demoSummary?.let { (dest, mult) -> stringResource(R.string.effort_settings_demo_on, dest, mult) }
                    ?: stringResource(R.string.effort_settings_demo_on_plain)
                val demoOff = demoSummary?.let { (dest, mult) -> stringResource(R.string.effort_settings_demo_off_scene, dest, mult) }
                    ?: stringResource(R.string.effort_settings_demo_off)
                row(stringResource(R.string.effort_settings_demo), if (s.demoFixed) demoOn else demoOff) {
                    CgSwitch(s.demoFixed, { onChange(s.copy(demoFixed = it)) }, stringResource(R.string.effort_settings_demo))
                }
                line()
                // 앰비언트 라이트(F28, 기본 켜짐): 끄면 조명을 부르지 않는다(AC-F28-5)
                row(stringResource(R.string.effort_settings_ambient), stringResource(if (s.ambientLight) R.string.effort_settings_ambient_on else R.string.effort_settings_ambient_off)) {
                    CgSwitch(s.ambientLight, { onChange(s.copy(ambientLight = it)) }, stringResource(R.string.effort_settings_ambient))
                }
                // 단가 출처(PRD 5-2): 접힘 = A 화면 근거 줄, 펼침 = 시급 ÷ 60과 "보여 드리기만" 안내. 상수를 못 읽으면 칸을 숨김
                if (wage != null) {
                    line()
                    row(stringResource(R.string.effort_settings_wage), wage.wageText) {
                        chev(stringResource(if (wageOpen) R.string.effort_settings_wage_close else R.string.effort_settings_wage_open), wageOpen) { wageOpen = !wageOpen }
                    }
                    if (wageOpen) swy {
                        Text(stringResource(R.string.effort_settings_wage_how, wage.wageYearLabel, wage.hourlyManText, wage.perMinExactText), style = if (narrow) t.labelM else t.bodyM, color = c.textSecondary)
                        Text(stringResource(R.string.effort_settings_wage_show_only), style = if (narrow) t.labelM else t.bodyM, color = c.textSecondary)
                    }
                }
                line()
                // 사진 출처(#118 HANDOFF_v3 4-3, 임시 자리): 출발 미리보기 장소 사진 CC BY-SA 저작자 표기
                row(stringResource(R.string.effort_settings_photo), stringResource(R.string.effort_settings_photo_sub)) {
                    chev(stringResource(if (photoOpen) R.string.effort_settings_wage_close else R.string.effort_settings_wage_open), photoOpen) { photoOpen = !photoOpen }
                }
                if (photoOpen) swy {
                    stringArrayResource(R.array.effort_photo_credits).forEach { Text(it, style = t.labelM, color = c.textSecondary) }
                }
                line()
                // 기록 초기화
                row(stringResource(R.string.settings_reset), if (recordCount > 0) stringResource(R.string.effort_settings_wipe_sub, recordCount) else stringResource(R.string.effort_settings_wipe_none)) {
                    CgButton(stringResource(R.string.settings_reset_confirm), { wipe = true }, kind = CgButtonKind.Basic, size = CgSize.S, enabled = recordCount > 0)
                }
            }
        }
        // 기록 초기화 확인(settings-wipe): 짧은 하단 시트. 지우면 홈이 첫 사용 모양으로
        if (wipe) {
            CgBottomSheet(Modifier.align(Alignment.BottomCenter).then(if (narrow) Modifier.fillMaxWidth() else Modifier.width(760.gpx)), compact = narrow, confirm = true) {
                Column(verticalArrangement = Arrangement.spacedBy(8.gpx)) {
                    Text(stringResource(R.string.effort_settings_wipe_title), style = if (narrow) t.bodyL else t.titleM, color = c.textPrimary)
                    Text(stringResource(R.string.effort_settings_wipe_body), style = t.bodyM, color = c.textSecondary)
                }
                CgSheetButtons(narrow) { sz ->
                    CgButton(stringResource(R.string.cancel), { wipe = false }, kind = CgButtonKind.Basic, size = sz)
                    CgButton(stringResource(R.string.settings_reset_confirm), { onReset(); wipe = false }, kind = CgButtonKind.Danger, size = sz)
                }
            }
        }
    }
}

/** F28: 앱이 보이는 동안 켜 두는 이유. 되돌리기는 주행 전환·onStop·설정 끔에서 차량 쪽(AmbientLight.restoreAll)이 한다. */
private const val AMBIENT_APP = "app"
