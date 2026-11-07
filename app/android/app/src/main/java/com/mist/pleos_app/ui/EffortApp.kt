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
 * 노고 정산 앱 뼈대(명세 v0.5 3절): 홈 → 출발 → (내비 안내) → 도착 → 영수증 → 제안 → 홈.
 * 지금은 홈·출발·설정만 있고, 영수증·제안 화면과 실제 출발·도착은 다음 단계에서 붙인다.
 * 화면 코드에는 금액·배수·시간 계산을 두지 않는다(engine 패키지·constants effort.copy만).
 */
@Composable
fun EffortApp(vm: EffortViewModel, driving: Boolean, onExit: () -> Unit) {
    val stack by vm.stack.collectAsStateWithLifecycle()
    val screen = stack.last()
    val ui by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    // CCP Back: 최상단(홈)에서는 finish 하지 않고 숨긴다. 주행 중 Back은 받아서 무시(가림 화면 유지)
    BackHandler { if (!driving && !vm.back()) onExit() }
    EffortFrame(demoValues = ui.demo, driving = driving) {
        when (screen) {
            EffortScreen.HOME -> EffortHome(
                ui,
                onDepart = { vm.open(EffortScreen.DEPART) },
                onDiscard = vm::discardTrip,
                onSettings = { vm.open(EffortScreen.SETTINGS) },
            )
            EffortScreen.DEPART -> EffortDepart(ui, (vm.source as? DemoEffortSource)?.scene, onGo = { vm.depart() })
            EffortScreen.SETTINGS -> EffortSettings(settings, onChange = vm::updateSettings, onReset = vm::resetRecords)
        }
    }
}

/** 창 폭(AC-C-9)·주행 중 가림(X1, AC-C-1)·"시연용 값"(AC-C-7). 화면 스택은 그대로라 정차하면 같은 화면으로 돌아온다. */
@Composable
private fun EffortFrame(demoValues: Boolean, driving: Boolean, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Chagok.colors.bgPrimary)) {
        val narrow = with(LocalDensity.current) { maxWidth.toPx() } <= NARROW_MAX_PX
        CompositionLocalProvider(LocalNarrow provides narrow) {
            if (driving) DrivingCover() else Box(Modifier.fillMaxSize().padding(horizontal = 40.gpx, vertical = 32.gpx)) {
                content()
                if (demoValues) DemoMark(stringResource(R.string.demo_values), Modifier.align(Alignment.BottomEnd))
            }
        }
    }
}

// ------------------------------------------------------------ 홈: [동승 출발] · 진행 중 · 최근 기록 3건
@Composable
private fun EffortHome(ui: EffortUi, onDepart: () -> Unit, onDiscard: () -> Unit, onSettings: () -> Unit) {
    val gap = Arrangement.spacedBy(24.gpx)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = gap) {
        val trip = ui.openTrip
        when (ui.phase) {
            EffortPhase.IDLE -> CgButton(stringResource(R.string.effort_depart_start), onDepart, Modifier.fillMaxWidth())
            EffortPhase.DRIVING -> trip?.let { TripCard(it, arrived = false, onDiscard = onDiscard) }
            EffortPhase.ARRIVED -> trip?.let { TripCard(it, arrived = true, onDiscard = onDiscard) }
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
private fun TripCard(trip: OpenTrip, arrived: Boolean, onDiscard: () -> Unit) {
    CgCard(Modifier.fillMaxWidth()) {
        CgTitle(stringResource(if (arrived) R.string.effort_arrived else R.string.effort_driving, trip.destName))
        if (!arrived) CgBody(stringResource(R.string.effort_driving_hint), secondary = true)
        CgButton(stringResource(R.string.effort_discard), onDiscard, kind = CgButtonKind.Basic)
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

// ------------------------------------------------------------ 출발(F20). 지금은 시연 데이터로만 출발한다(2단계에서 목적지·인원·requestTrip)
@Composable
private fun EffortDepart(ui: EffortUi, demo: DemoScene?, onGo: () -> Unit) {
    val gap = Arrangement.spacedBy(24.gpx)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = gap) {
        CgTitle(stringResource(R.string.effort_depart_title))
        if (ui.demo && demo != null) {
            CgCard(Modifier.fillMaxWidth()) {
                CgTitle(demo.destName)
                CgBody(stringResource(R.string.effort_depart_people, demo.n), secondary = true)
            }
            CgButton(stringResource(R.string.effort_depart_go), onGo, Modifier.fillMaxWidth())
        } else {
            CgNotice(stringResource(R.string.effort_depart_live_pending))
            if (!ui.demoAvailable) CgNotice(stringResource(R.string.effort_depart_no_demo))
        }
    }
}

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
            EffortHome(EffortUi(EffortPhase.DRIVING, previewTrip, listOf(previewRecord), demo = true, demoAvailable = true), {}, {}, {})
        }
    }
}

@Preview(name = "홈·처음", widthDp = 640, heightDp = 720)
@Composable
private fun EffortHomeEmptyPreview() {
    ChagokTheme(dark = false) {
        EffortFrame(demoValues = false, driving = false) {
            EffortHome(EffortUi(EffortPhase.IDLE, null, emptyList(), demo = false, demoAvailable = false), {}, {}, {})
        }
    }
}
