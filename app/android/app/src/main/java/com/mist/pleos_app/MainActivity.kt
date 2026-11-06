package com.mist.pleos_app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mist.pleos_app.app.AppViewModel
import com.mist.pleos_app.app.homeBalance
import com.mist.pleos_app.ui.ChagokApp
import com.mist.pleos_app.ui.theme.ChagokTheme
import com.mist.pleos_app.util.ChagokLog
import com.mist.pleos_app.navi.Destination
import com.mist.pleos_app.navi.NaviRepository
import com.mist.pleos_app.vehicle.PleosVehicleRepository
import com.mist.pleos_app.vehicle.isDriving

/**
 * 한 Activity + Compose. 차량값은 [PleosVehicleRepository]가 이 Activity 수명주기에 맞춰
 * 화면이 보일 때만 리스너를 등록한다(AC-C-2). 화면 이동·복원은 [AppViewModel].
 */
class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()
    private lateinit var vehicle: PleosVehicleRepository
    private lateinit var navi: NaviRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vehicle = PleosVehicleRepository(this)
        lifecycle.addObserver(vehicle)
        navi = NaviRepository(this)
        lifecycle.addObserver(navi)
        // 화면이 가려지면 다음에 보일 때 다시 정산(F10 "다른 앱에서 복귀")
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = vm.onHidden()
        })
        ChagokLog.event("app_create", "restored" to (savedInstanceState != null))
        setContent {
            val snapshot by vehicle.state.collectAsStateWithLifecycle()
            val depart by navi.state.collectAsStateWithLifecycle()
            // 차종·단가·모의 연료 %는 설정(S1). 바꾸고 홈으로 돌아오면 바로 다시 계산(AC-F1-3·AC-S1-1)
            val settings by vm.settings.collectAsStateWithLifecycle()
            val model = settings.model
            val balance = remember(snapshot, settings) {
                homeBalance(vm.constants, model, snapshot, mockFuelPct = settings.mockFuelPct, price = settings.priceFor(vm.constants))
            }
            ChagokTheme {
                ChagokApp(vm, snapshot, model, balance, driving = isDriving(snapshot), depart = depart, onDepart = { card ->
                    // F8: 출발 1탭 → 순정 내비. 최근 방문에 넣고(다음 뽑기에서 빠짐) 경로 요청
                    vm.recordDeparture(card, snapshot)
                    navi.depart(Destination(card.id, card.name, card.lat, card.lng))
                }, fetchRecent = navi::fetchRecent, onExit = {
                    val moved = moveTaskToBack(true)
                    ChagokLog.event("back_to_background", "moved" to moved)
                })
            }
        }
    }

}
