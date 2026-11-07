package com.mist.pleos_app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mist.pleos_app.effort.EffortViewModel
import com.mist.pleos_app.ui.EffortApp
import com.mist.pleos_app.ui.theme.ChagokTheme
import com.mist.pleos_app.util.ChagokLog
import com.mist.pleos_app.navi.NaviRepository
import com.mist.pleos_app.vehicle.PleosVehicleRepository
import com.mist.pleos_app.vehicle.isDriving

/**
 * 한 Activity + Compose. 첫 화면은 노고 정산 흐름(명세 v0.5 3절, [EffortApp])이다.
 * 차량값은 [PleosVehicleRepository]가 이 Activity 수명주기에 맞춰 화면이 보일 때만 리스너를 등록한다(AC-C-2).
 * 피봇 전 흐름(잔고·한 장·장부: AppViewModel·ChagokApp)은 CI가 옛 fixture를 대조하는 동안 코드만 남겨 두고,
 * 12/3 전 정리 PR에서 지운다(DECISIONS '기존 기획 코드 정리 순서').
 */
class MainActivity : ComponentActivity() {

    private val vm: EffortViewModel by viewModels()
    private lateinit var vehicle: PleosVehicleRepository
    private lateinit var navi: NaviRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vehicle = PleosVehicleRepository(this)
        lifecycle.addObserver(vehicle)
        navi = NaviRepository(this)                      // 출발 화면(F20, 2단계)에서 목적지·requestTrip·requestRoute에 쓴다
        lifecycle.addObserver(navi)
        ChagokLog.event("app_create", "restored" to (savedInstanceState != null))
        setContent {
            val snapshot by vehicle.state.collectAsStateWithLifecycle()
            ChagokTheme {
                EffortApp(vm, driving = isDriving(snapshot), onExit = {
                    val moved = moveTaskToBack(true)
                    ChagokLog.event("back_to_background", "moved" to moved)
                })
            }
        }
    }
}
