package com.mist.pleos_app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mist.pleos_app.R
import com.mist.pleos_app.app.AppViewModel
import com.mist.pleos_app.app.HomeBalance
import com.mist.pleos_app.app.RefuelUi
import com.mist.pleos_app.app.Screen
import com.mist.pleos_app.engine.Card
import com.mist.pleos_app.engine.Place
import com.mist.pleos_app.navi.DepartState
import com.mist.pleos_app.vehicle.VehicleSnapshot
import com.mist.pleos_app.ui.components.CgBody
import com.mist.pleos_app.ui.components.CgButton
import com.mist.pleos_app.ui.components.CgButtonKind
import com.mist.pleos_app.ui.components.CgCaption
import com.mist.pleos_app.ui.components.CgCard
import com.mist.pleos_app.ui.components.CgNotice
import com.mist.pleos_app.ui.components.CgTitle
import com.mist.pleos_app.ui.components.DemoMark
import com.mist.pleos_app.ui.theme.Chagok
import com.mist.pleos_app.ui.theme.ChagokTheme
import com.mist.pleos_app.ui.theme.NARROW_MAX_PX
import com.mist.pleos_app.ui.theme.gpx

/** "추가 0원" 대신 화면에 쓰는 말: "추가 주유 없이"(EV "추가 충전 없이")(PRD v1.6 5-3). */
@Composable
fun zeroText(ev: Boolean): String = stringResource(if (ev) R.string.zero_ev else R.string.zero_ice)

/** 창 폭이 900px 이하면 한 단(1:1 분할·세로 창). 화면마다 이 값으로 배치를 바꾼다(AC-C-9). */
val LocalNarrow = staticCompositionLocalOf { false }

/**
 * 앱 뼈대: 화면 스택(AppViewModel) + Back 처리 + 창 폭에 따른 배치 + "시연용 값" 표시.
 * 화면 문구는 strings.xml, 계산은 engine 패키지(HomeModel)만 쓴다.
 */
@Composable
fun ChagokApp(
    vm: AppViewModel,
    snapshot: VehicleSnapshot,
    model: String,
    balance: HomeBalance,
    driving: Boolean,
    depart: DepartState,
    onDepart: (Card) -> Unit,
    fetchRecent: ((Place?) -> Unit) -> Unit,
    onExit: () -> Unit,
) {
    val stack by vm.stack.collectAsStateWithLifecycle()
    val screen = stack.last()
    val conditions by vm.conditions.collectAsStateWithLifecycle()
    val card by vm.cardUi.collectAsStateWithLifecycle()
    val ledger by vm.ledgerState.collectAsStateWithLifecycle()
    val visit by vm.visit.collectAsStateWithLifecycle()
    val payer by vm.payerState.collectAsStateWithLifecycle()
    val refuel by vm.refuelUi.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val avgGas by vm.avgGasPrice.collectAsStateWithLifecycle()
    val vehicle = remember(snapshot, settings, avgGas) { vm.vehicleOf(snapshot, model) }
    val ev = vm.constants?.vehicles?.get(model)?.fuelType != "ice"
    // CCP Back: 최상단(홈)에서는 finish 하지 않고 숨긴다(Pleos 앱 개발 가이드, 심사 반려 사유)
    BackHandler { if (!driving && !vm.back()) onExit() }   // 주행 중 Back은 받아서 무시(가림 화면 유지, 앱이 뒤로 숨지 않게)
    if (screen == Screen.CARD && !driving) LaunchedEffect(Unit) { vm.ensureCard(snapshot, model) }
    // 차량값·착석·나눠 낼 인원이 바뀌면 한 장의 배지·1인 몫을 1초 디바운스로 갱신(F5 유지·재계산, F11)
    LaunchedEffect(snapshot, visit, payer, settings) {
        if (!driving) {
            vm.onVehicle(snapshot, model)
            vm.onEnergy(snapshot, model)                   // F9: 충전 스위치 ON→OFF, 연료 % 3%p 상승(모의 값 포함)
            vm.maybeSettle(snapshot, model, fetchRecent)
            vm.refreshPrices()                             // 서버 모드: 평균 휘발유 단가(복귀마다 1회, 기다리지 않음)   // F10: 화면이 보일 때 정차면 이번 방문 정산 1번
        }
        vm.onDoor(snapshot, model)                         // F17: trip이 있고 정차 중 운전석 문 열림 = 도착
    }
    val seats = vm.seatsOf(snapshot)
    val content: @Composable (Screen) -> Unit = { s ->
        when (s) {
            Screen.HOME -> HomeScreen(
                balance, ledger.settleCard, ev = ev, today = { vm.openToday(snapshot, model) }, open = vm::open, closeSettle = vm::dismissSettle,
                charged = refuel as? RefuelUi.Charged, chargedOpen = { vm.refuelToCard(snapshot, model) }, chargedClose = vm::dismissRefuel,
            )
            Screen.RANGE -> {
                // 범위 화면에서 고르는 중인 값도 ViewModel에 둔다(주행 가림 뒤 그대로, README 2-1)
                LaunchedEffect(Unit) { vm.setConditions(vm.conditionsFor(snapshot, model)) }
                RangeScreen(
                    vehicle, conditions,
                    seats = seats,
                    onChange = vm::setConditions,
                    onPick = { vm.openPicked(snapshot, model) },
                )
            }
            Screen.CARD -> CardScreen(
                card, depart, onSwap = vm::swap, onRange = { vm.open(Screen.RANGE) }, onDepart = onDepart, ev = ev,
                onRetry = { vm.retryCard(snapshot, model) },
            )
            Screen.RECORD -> RecordScreen(ledger)
            Screen.SETTINGS -> SettingsScreen(settings, vm.constants, onChange = vm::updateSettings, onReset = vm::resetRecords)
        }
    }
    // F11: 2명 이상 + 지난번 확인값과 다르면 위쪽 토스트(정차 화면 어디서나, 주행 중에는 가림 화면만)
    val askPayers = remember(snapshot, payer) { vm.shouldAskPayers(snapshot) }
    val toast: @Composable () -> Unit = {
        if (askPayers) PayerToast(seats, onConfirm = { vm.confirmPayers(seats, it) }, onDismiss = { vm.dismissPayers(seats) })
        // F9 내연 금액 시트: 정차 화면 어디서나(주행 중에는 가림 화면만)
        (refuel as? RefuelUi.Sheet)?.let { sheet ->
            RefuelSheet(sheet, onConfirm = { vm.refuelToCard(snapshot, model, it) }, onDismiss = vm::dismissRefuel)
        }
    }
    AppFrame(screen, balance.demoValues, driving, toast, content)
}

@Composable
private fun AppFrame(screen: Screen, demoValues: Boolean, driving: Boolean, toast: @Composable () -> Unit = {}, content: @Composable (Screen) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Chagok.colors.bgPrimary)) {
        val narrow = with(LocalDensity.current) { maxWidth.toPx() } <= NARROW_MAX_PX
        CompositionLocalProvider(LocalNarrow provides narrow) {
            // X1 주행 중: 앱 안에서도 모든 화면을 가린다(시스템 가림과 별개). 화면 스택은 그대로라 정차하면 같은 화면으로 돌아온다(AC-X1-1).
            if (driving) DrivingCover() else Box(Modifier.fillMaxSize().padding(horizontal = 40.gpx, vertical = 32.gpx)) {
                content(screen)
                Box(Modifier.align(Alignment.TopCenter)) { toast() }
                if (demoValues) {
                    // 화면 구석(AC-C-7). 위쪽은 카드와 겹쳐서 오른쪽 아래에 둔다.
                    DemoMark(stringResource(R.string.demo_values), Modifier.align(Alignment.BottomEnd))
                }
            }
        }
    }
}

// ------------------------------------------------------------ 홈 (F1 잔고 · 오늘의 한 곳 · 조건 바꾸기)
// 정산 카드(F10, 맨 위·닫기)와 상황 카드는 P6에서 이 자리에 붙인다.
@Composable
private fun HomeScreen(
    balance: HomeBalance,
    settle: String?,
    today: () -> Unit,
    open: (Screen) -> Unit,
    closeSettle: () -> Unit = {},
    ev: Boolean = true,
    charged: RefuelUi.Charged? = null,
    chargedOpen: () -> Unit = {},
    chargedClose: () -> Unit = {},
) {
    val narrow = LocalNarrow.current
    val gap = Arrangement.spacedBy(24.gpx)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = gap) {
        // F10 정산 카드: 홈 맨 위, 닫기 가능, 탭하면 기록(DECISIONS '정산 카드 위치')
        settle?.let { SettleCard(it, onOpen = { open(Screen.RECORD) }, onClose = closeSettle, modifier = Modifier.fillMaxWidth()) }
        // F9 충전 직후 카드: 탭 또는 3초 뒤 한 장(AC-F9-2)
        charged?.let { ChargeCard(it, onOpen = chargedOpen, onClose = chargedClose, modifier = Modifier.fillMaxWidth()) }
        if (narrow) {
            BalanceCard(balance, Modifier.fillMaxWidth())
            TodayPickCard(today, ev, Modifier.fillMaxWidth())
        } else {
            Row(horizontalArrangement = gap) {
                BalanceCard(balance, Modifier.weight(1f))
                TodayPickCard(today, ev, Modifier.weight(1f))
            }
        }
        CgButton(stringResource(R.string.change_conditions), { open(Screen.RANGE) }, Modifier.fillMaxWidth(), kind = CgButtonKind.Basic)
        Row(horizontalArrangement = gap, modifier = Modifier.fillMaxWidth()) {
            CgButton(stringResource(R.string.screen_record), { open(Screen.RECORD) }, Modifier.weight(1f), kind = CgButtonKind.Basic)
            CgButton(stringResource(R.string.screen_settings), { open(Screen.SETTINGS) }, Modifier.weight(1f), kind = CgButtonKind.Basic)
        }
    }
}

@Composable
private fun BalanceCard(balance: HomeBalance, modifier: Modifier) {
    CgCard(modifier) {
        CgCaption(stringResource(R.string.balance_label))
        if (balance.balanceText != null) {
            CgTitle(balance.balanceText)
            if (balance.averagePrice) CgCaption(stringResource(R.string.price_average))
        } else {
            CgNotice(stringResource(R.string.reading_vehicle))
        }
    }
}

/** 오늘의 한 곳: 기본 조건(추가 0원 · 당일 · 테마 없음 · 인원 자동, 화면은 "추가 주유 없이 · 당일 · 인원 자동"), 탭하면 범위 화면 없이 한 장. */
@Composable
private fun TodayPickCard(onClick: () -> Unit, ev: Boolean, modifier: Modifier) {
    CgCard(modifier, onClick = onClick) {
        CgCaption(stringResource(R.string.today_pick))
        CgBody(stringResource(R.string.today_pick_conditions, zeroText(ev)), secondary = true)
    }
}

// ------------------------------------------------------------ 주행 중 가림 (X1). 금액·카드 없음(AC-C-8 주행 중 금액 미터 금지)
@Composable
private fun DrivingCover() {
    Box(Modifier.fillMaxSize().background(Chagok.colors.bgSecondary), contentAlignment = Alignment.Center) {
        CgBody(stringResource(R.string.driving_hidden), secondary = true)
    }
}

// ------------------------------------------------------------ 미리보기: 가로·세로(1:1 분할)·주간
private val previewBalance = HomeBalance("배터리에 1만 9천 원어치 · 약 245km", averagePrice = true, demoValues = true, fuelTypeMismatch = false)

@Preview(name = "가로", widthDp = 1280, heightDp = 720)
@Preview(name = "세로·분할", widthDp = 640, heightDp = 720)
@Composable
private fun HomePreview() {
    ChagokTheme(dark = true) { AppFrame(Screen.HOME, demoValues = true, driving = false) { HomeScreen(previewBalance, "지난번 이후 132km · 약 8천 원 · 마지막 목적지 양평 두물머리", {}, {}) } }
}

@Preview(name = "주간·읽는 중", widthDp = 640, heightDp = 720)
@Composable
private fun HomeReadingPreview() {
    ChagokTheme(dark = false) { AppFrame(Screen.HOME, demoValues = true, driving = false) { HomeScreen(previewBalance.copy(balanceText = null), null, {}, {}) } }
}

@Preview(name = "주행 중 가림", widthDp = 640, heightDp = 720)
@Composable
private fun DrivingPreview() {
    ChagokTheme(dark = true) { AppFrame(Screen.CARD, demoValues = true, driving = true) {} }
}
