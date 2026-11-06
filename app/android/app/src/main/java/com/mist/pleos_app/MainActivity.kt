package com.mist.pleos_app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mist.pleos_app.app.AppViewModel
import com.mist.pleos_app.app.homeBalance
import com.mist.pleos_app.ui.ChagokApp
import com.mist.pleos_app.ui.theme.ChagokTheme
import com.mist.pleos_app.util.ChagokLog
import com.mist.pleos_app.vehicle.PleosVehicleRepository
import com.mist.pleos_app.vehicle.isDriving

/**
 * 한 Activity + Compose. 차량값은 [PleosVehicleRepository]가 이 Activity 수명주기에 맞춰
 * 화면이 보일 때만 리스너를 등록한다(AC-C-2). 화면 이동·복원은 [AppViewModel].
 */
class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()
    private lateinit var vehicle: PleosVehicleRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vehicle = PleosVehicleRepository(this)
        lifecycle.addObserver(vehicle)
        ChagokLog.event("app_create", "restored" to (savedInstanceState != null))
        setContent {
            val snapshot by vehicle.state.collectAsStateWithLifecycle()
            // 차종·단가·모의 연료 %는 설정(S1, P7)에서 받는다. 그 전까지 에뮬 기본값 아이오닉 5 LR(명세 S1).
            val balance = remember(snapshot) { homeBalance(vm.constants, DEFAULT_MODEL, snapshot) }
            ChagokTheme {
                ChagokApp(vm, snapshot, DEFAULT_MODEL, balance, driving = isDriving(snapshot), onExit = {
                    val moved = moveTaskToBack(true)
                    ChagokLog.event("back_to_background", "moved" to moved)
                })
            }
        }
    }

    private companion object { const val DEFAULT_MODEL = "ioniq5_lr" }
}
