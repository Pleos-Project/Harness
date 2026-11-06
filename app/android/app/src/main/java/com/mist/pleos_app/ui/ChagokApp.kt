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
import androidx.compose.runtime.getValue
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
import com.mist.pleos_app.app.Screen
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

/** 창 폭이 900px 이하면 한 단(1:1 분할·세로 창). 화면마다 이 값으로 배치를 바꾼다(AC-C-9). */
val LocalNarrow = staticCompositionLocalOf { false }

/**
 * 앱 뼈대: 화면 스택(AppViewModel) + Back 처리 + 창 폭에 따른 배치 + "시연용 값" 표시.
 * 화면 문구는 strings.xml, 계산은 engine 패키지(HomeModel)만 쓴다.
 */
@Composable
fun ChagokApp(vm: AppViewModel, balance: HomeBalance, driving: Boolean, onExit: () -> Unit) {
    val stack by vm.stack.collectAsStateWithLifecycle()
    val screen = stack.last()
    // CCP Back: 최상단(홈)에서는 finish 하지 않고 숨긴다(Pleos 앱 개발 가이드, 심사 반려 사유)
    BackHandler(enabled = !driving) { if (!vm.back()) onExit() }
    AppFrame(screen, balance, driving, open = vm::open, back = { if (!vm.back()) onExit() })
}

@Composable
private fun AppFrame(screen: Screen, balance: HomeBalance, driving: Boolean, open: (Screen) -> Unit, back: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Chagok.colors.bgPrimary)) {
        val narrow = with(LocalDensity.current) { maxWidth.toPx() } <= NARROW_MAX_PX
        CompositionLocalProvider(LocalNarrow provides narrow) {
            // X1 주행 중: 앱 안에서도 모든 화면을 가린다(시스템 가림과 별개). 화면 스택은 그대로라 정차하면 같은 화면으로 돌아온다(AC-X1-1).
            if (driving) DrivingCover() else Box(Modifier.fillMaxSize().padding(horizontal = 40.gpx, vertical = 32.gpx)) {
                when (screen) {
                    Screen.HOME -> HomeScreen(balance, open)
                    Screen.RANGE -> Placeholder(stringResource(R.string.screen_range), back)
                    Screen.CARD -> Placeholder(stringResource(R.string.screen_card), back)
                    Screen.RECORD -> Placeholder(stringResource(R.string.screen_record), back)
                    Screen.SETTINGS -> Placeholder(stringResource(R.string.screen_settings), back)
                }
                if (balance.demoValues) {
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
private fun HomeScreen(balance: HomeBalance, open: (Screen) -> Unit) {
    val narrow = LocalNarrow.current
    val gap = Arrangement.spacedBy(24.gpx)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = gap) {
        if (narrow) {
            BalanceCard(balance, Modifier.fillMaxWidth())
            TodayPickCard({ open(Screen.CARD) }, Modifier.fillMaxWidth())
        } else {
            Row(horizontalArrangement = gap) {
                BalanceCard(balance, Modifier.weight(1f))
                TodayPickCard({ open(Screen.CARD) }, Modifier.weight(1f))
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

/** 오늘의 한 곳: 기본 조건(추가 0원 · 당일 · 테마 없음 · 인원 자동), 탭하면 범위 화면 없이 한 장(PRD v1.3 6-1). */
@Composable
private fun TodayPickCard(onClick: () -> Unit, modifier: Modifier) {
    CgCard(modifier, onClick = onClick) {
        CgCaption(stringResource(R.string.today_pick))
        CgBody(stringResource(R.string.today_pick_conditions), secondary = true)
    }
}

// ------------------------------------------------------------ 주행 중 가림 (X1). 금액·카드 없음(AC-C-8 주행 중 금액 미터 금지)
@Composable
private fun DrivingCover() {
    Box(Modifier.fillMaxSize().background(Chagok.colors.bgSecondary), contentAlignment = Alignment.Center) {
        CgBody(stringResource(R.string.driving_hidden), secondary = true)
    }
}

// ------------------------------------------------------------ 아직 안 지은 화면 (P3~P7)
@Composable
private fun Placeholder(title: String, back: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(24.gpx)) {
        CgTitle(title)
        CgNotice(stringResource(R.string.preparing))
        CgButton(stringResource(R.string.back), back, kind = CgButtonKind.Basic)
    }
}

// ------------------------------------------------------------ 미리보기: 가로·세로(1:1 분할)·주간
private val previewBalance = HomeBalance("배터리에 1만 9천 원어치 · 약 245km", averagePrice = true, demoValues = true, fuelTypeMismatch = false)

@Preview(name = "가로", widthDp = 1280, heightDp = 720)
@Preview(name = "세로·분할", widthDp = 640, heightDp = 720)
@Composable
private fun HomePreview() {
    ChagokTheme(dark = true) { AppFrame(Screen.HOME, previewBalance, driving = false, {}, {}) }
}

@Preview(name = "주간·읽는 중", widthDp = 640, heightDp = 720)
@Composable
private fun HomeReadingPreview() {
    ChagokTheme(dark = false) { AppFrame(Screen.HOME, previewBalance.copy(balanceText = null), driving = false, {}, {}) }
}

@Preview(name = "주행 중 가림", widthDp = 640, heightDp = 720)
@Composable
private fun DrivingPreview() {
    ChagokTheme(dark = true) { AppFrame(Screen.CARD, previewBalance, driving = true, {}, {}) }
}
