package com.mist.pleos_app.app

import android.content.Context
import com.mist.pleos_app.engine.Constants

/**
 * S1 설정(명세 7절). 기기 안 SharedPreferences에만 둔다(서버로 보내지 않음).
 * - 단가가 비었거나 앱 내장 기본값과 같으면 null → 엔진이 기본값을 쓰고 "평균 단가"(priceSource "default")
 * - 차종은 용량·연비 대체값에만 쓴다(전기/내연 불일치는 홈이 따로 알림)
 * - 모의 연료 %는 내연 차종인데 연료 %를 못 읽을 때(에뮬)만 쓴다
 */
data class Settings(
    val model: String = DEFAULT_MODEL,
    val priceGas: Int? = null,
    val priceHome: Int? = null,
    val priceFast: Int? = null,
    val mockFuelPct: Int = DEFAULT_MOCK_FUEL_PCT,
    val seatInvert: Boolean = true,
    /** 데모 고정 모드(명세 S1, 기본 켜짐): 네트워크 없이 내장 후보(DemoServer). 끄면 서버 실서비스(app/README 3절). */
    val demoFixed: Boolean = true,
) {
    /** 잔고·카드·정산에 쓰는 단가. EV는 집 충전 단가(엔진 기본값과 같은 기준), 급속은 F9 충전 카드에서. */
    fun priceFor(c: Constants?): Double? =
        (if (c?.vehicles?.get(model)?.fuelType == "ev") priceHome else priceGas)?.toDouble()

    companion object {
        const val DEFAULT_MODEL = "ioniq5_lr"          // 에뮬 기본(명세 S1)
        const val DEFAULT_MOCK_FUEL_PCT = 50
        val MODELS = listOf("grandeur_25", "grandeur_hev", "avante_hev", "tucson_hev", "ioniq5_lr", "ioniq6_lr")
    }
}

/** 기본값과 같으면 null(= 평균 단가). 0 이하·빈 값도 null. */
fun normalizePrice(input: Int?, default: Double): Int? = input?.takeIf { it > 0 && it.toDouble() != default }

interface SettingsStore {
    fun load(): Settings
    fun save(s: Settings)
}

class MemorySettingsStore(private var s: Settings = Settings()) : SettingsStore {
    override fun load() = s
    override fun save(s: Settings) { this.s = s }
}

class PrefsSettingsStore(context: Context) : SettingsStore {
    private val p = context.getSharedPreferences("chagok_settings", Context.MODE_PRIVATE)

    override fun load() = Settings(
        model = p.getString("vehicle_model", null)?.takeIf { it in Settings.MODELS } ?: Settings.DEFAULT_MODEL,
        priceGas = p.getInt("price_gas", 0).takeIf { it > 0 },
        priceHome = p.getInt("price_home", 0).takeIf { it > 0 },
        priceFast = p.getInt("price_fast", 0).takeIf { it > 0 },
        mockFuelPct = p.getInt("mock_fuel_pct", Settings.DEFAULT_MOCK_FUEL_PCT),
        seatInvert = p.getBoolean("seat_invert", true),
        demoFixed = p.getBoolean("demo_fixed_mode", true),
    )

    override fun save(s: Settings) {
        p.edit()
            .putString("vehicle_model", s.model)
            .putInt("price_gas", s.priceGas ?: 0)
            .putInt("price_home", s.priceHome ?: 0)
            .putInt("price_fast", s.priceFast ?: 0)
            .putInt("mock_fuel_pct", s.mockFuelPct)
            .putBoolean("seat_invert", s.seatInvert)
            .putBoolean("demo_fixed_mode", s.demoFixed)
            .apply()
    }
}
