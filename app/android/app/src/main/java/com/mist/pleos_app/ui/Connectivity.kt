package com.mist.pleos_app.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * 인터넷이 연결돼 있는지(출발 화면 오프라인 배너, 명세 v0.6 F20, #134).
 * 화면이 보이는 동안만 `registerDefaultNetworkCallback` 이벤트로 받고 `onStop`에서 해제한다.
 * 폴링·타이머는 없다(AC-C-2). 권한은 `ACCESS_NETWORK_STATE`(일반 권한, contract/permissions.md).
 * 연결 여부만 보고 네트워크 종류·이름은 읽지 않는다. 연결이 확인되지 않은 상태(검증 전)도 연결로 본다.
 * 막아야 할 때를 놓치는 쪽이 검색을 잘못 막는 쪽보다 낫고, 놓쳐도 검색 실패 안내(`PlacesStatus.UNAVAILABLE`)가 있다.
 */
@Composable
internal fun rememberOnline(): Boolean {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var online by remember { mutableStateOf(true) }
    DisposableEffect(owner, context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { online = true }
            override fun onLost(network: Network) { online = false }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        }
        var registered = false
        fun start() {
            if (cm == null || registered) return
            online = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            registered = runCatching { cm.registerDefaultNetworkCallback(callback) }.isSuccess
        }
        fun stop() {
            if (!registered) return
            registered = false
            runCatching { cm?.unregisterNetworkCallback(callback) }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> start()
                Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        onDispose { owner.lifecycle.removeObserver(observer); stop() }
    }
    return online
}
