package com.mist.pleos_app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mist.pleos_app.engine.computeRange
import com.mist.pleos_app.engine.parseConstants
import com.mist.pleos_app.engine.vehicleInputs
import com.mist.pleos_app.ui.theme.ChagokTheme
import com.mist.pleos_app.ui.theme.gsp

/**
 * 빌드 환경 확인용 빈 화면(Compose). 엔진과 assets/data(syncChagokData)가 실렸는지만 보여 준다.
 * 앱 화면은 여기서부터 짓는다. 화면 문구는 strings.xml, 계산은 engine 패키지 함수만 쓴다.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ChagokTheme { EngineCheckScreen() }
        }
    }
}

/** 엔진 확인용 샘플(아이오닉 5 롱레인지, 80%). 실제 값은 차량 리스너에서 받는다. */
private const val SAMPLE_MODEL = "ioniq5_lr"
private const val SAMPLE_LEVEL_PCT = 80.0
private const val SAMPLE_RANGE_M = 326_000.0

@Composable
fun EngineCheckScreen() {
    val context = LocalContext.current
    val balance: String? = remember {
        runCatching {
            val text = context.assets.open("data/constants.json").bufferedReader().use { it.readText() }
            val v = vehicleInputs(parseConstants(text), SAMPLE_MODEL, SAMPLE_LEVEL_PCT, remainingRangeM = SAMPLE_RANGE_M)
            computeRange(v).balanceText
        }.getOrNull()
    }
    EngineCheckContent(balance)
}

@Composable
private fun EngineCheckContent(balance: String?) {
    val body = TextStyle(fontSize = 26.gsp, lineHeight = 34.gsp)   // Body S(26/34)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.build_check), style = body)
            Text(
                if (balance != null) stringResource(R.string.engine_ok, balance)
                else stringResource(R.string.engine_data_missing),
                style = body,
            )
        }
    }
}

@Preview(name = "가로", widthDp = 1280, heightDp = 720)
@Preview(name = "세로·분할", widthDp = 640, heightDp = 720)
@Composable
private fun EngineCheckPreview() {
    ChagokTheme { EngineCheckContent(balance = null) }
}
