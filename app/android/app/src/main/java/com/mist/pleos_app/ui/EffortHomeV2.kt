package com.mist.pleos_app.ui

import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.mist.pleos_app.R
import com.mist.pleos_app.effort.CHOICE_ACCEPT
import com.mist.pleos_app.effort.CHOICE_CLOSE
import com.mist.pleos_app.effort.EffortRecord
import com.mist.pleos_app.effort.EffortUi
import com.mist.pleos_app.effort.OpenTrip
import com.mist.pleos_app.effort.clockText
import com.mist.pleos_app.effort.dayText
import com.mist.pleos_app.effort.euro
import com.mist.pleos_app.ui.components.CgBottomSheet
import com.mist.pleos_app.ui.components.CgButton
import com.mist.pleos_app.ui.components.CgButtonKind
import com.mist.pleos_app.ui.components.CgSize
import com.mist.pleos_app.ui.components.CgCta
import com.mist.pleos_app.ui.components.CgEmpty
import com.mist.pleos_app.ui.components.CgIcon
import com.mist.pleos_app.ui.components.CgAppIcon
import com.mist.pleos_app.ui.components.CgWordmark
import com.mist.pleos_app.ui.components.CgIconButton
import com.mist.pleos_app.ui.components.CgIllustrationCircle
import com.mist.pleos_app.ui.components.CgLoadingDots
import com.mist.pleos_app.ui.components.CgRecordCard
import com.mist.pleos_app.ui.components.CgTextButton
import com.mist.pleos_app.ui.components.CgTitleBar
import com.mist.pleos_app.ui.theme.Chagok
import com.mist.pleos_app.ui.theme.gpx
import kotlinx.coroutines.delay

/*
 * 홈(Design v2 `design/screens/home.html`, #75 명준). 부품은 ui/components(Cg*)만 조립한다.
 * 위 바(왼쪽 아이콘 · 가운데 이름) / 인사 + 내 차 그림 + [출발하기 →] 또는 진행 중 알약(누르면 짧은 하단 시트) /
 * 최근 기록 3장(방금 끝난 기록은 맨 위 굵은 테두리). 앱 시작 때 1.6초 시작 화면. 숫자는 저장된 기록·엔진 표기에서만.
 */

private val EaseIn = CubicBezierEasing(.32f, .72f, 0f, 1f)
private val Idle = CubicBezierEasing(.45f, 0f, .55f, 1f)

@Composable
internal fun EffortHomeScreen(
    ui: EffortUi,
    routeFailed: Boolean,
    onDepart: () -> Unit,
    onDiscard: () -> Unit,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
    onReceipt: () -> Unit,
    onArrive: () -> Unit,
    onLog: () -> Unit = {},
    onRecord: (String) -> Unit = {},
    onMidway: () -> Unit = {},
) {
    val narrow = LocalNarrow.current
    val c = Chagok.colors
    val t = Chagok.type
    val trip = ui.openTrip
    var quit by rememberSaveable { mutableStateOf(false) }   // 그만두기 확인 시트(v32 home-progress-quit)
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(700, easing = EaseIn)) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // 위 바: 왼쪽 기록 전체(주행 기록 목록, Q-08-01) · 가운데 앱 이름 · 오른쪽 설정(#75 v41)
            CgTitleBar(
                stringResource(R.string.effort_home_logo), centered = true,
                titleStyle = (if (narrow) t.titleM else t.titleL).copy(fontWeight = FontWeight.ExtraBold, letterSpacing = t.titleL.fontSize * .08f),
                leading = { CgIconButton(stringResource(R.string.effort_log_open), CgIcon.Receipt, onLog) },
                titleContent = { CgWordmark(if (narrow) 128.gpx else 152.gpx, label = stringResource(R.string.effort_home_logo)) },   // v3 위 바 워드마크
                trailing = { CgIconButton(stringResource(R.string.screen_settings), CgIcon.Gear, onSettings) },
            )
            // 인사 + 내 차 그림 + 출발하기 / 진행 중
            val first = ui.recent.isEmpty() && trip == null
            val (sub, head) = when {
                // v32 home-pending: 고르기 전에 나간 도착 — "○○에 도착했어요 · 16:30" / "오늘 운전 영수증이 여기 있어요"
                trip != null && trip.arrived -> stringResource(R.string.effort_home_arrived_sub, trip.destName, clockText(trip.arrivedAt ?: trip.departedAt)) to stringResource(R.string.effort_home_arrived_head)
                // v32 home-progress: "○○으로 가는 중 · 14:20 출발 · 함께 4명". 머리글은 중간 정산(지금까지의 노고 보기)이 붙을 때 시안 문구로 바꾼다
                // v3 5-3: "~까지 정산함" 표시는 없앰
                trip != null -> stringResource(R.string.effort_home_progress_sub, euro(trip.destName), clockText(trip.departedAt), trip.n) to
                    stringResource(R.string.effort_home_progress_head)
                first -> stringResource(R.string.effort_home_first_sub) to stringResource(R.string.effort_home_first_head)
                else -> stringResource(R.string.effort_home_sub) to stringResource(R.string.effort_home_head)
            }
            Row(
                Modifier.fillMaxWidth().then(if (narrow) Modifier.height(300.gpx) else Modifier.weight(1f))
                    .graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 24f * density },
                horizontalArrangement = Arrangement.spacedBy(if (narrow) 28.gpx else 64.gpx, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // v3 5-3: 가는 중이면 시동 버튼 = 중간 정산(F27). 아래 글자 없음, 접근성 이름 "지금까지의 노고 보기"
                if (ui.midwayAvailable) StartButton(if (narrow) 200.gpx else 360.gpx, stringResource(R.string.effort_mid_open), onMidway)
                else LogoSymbol(if (narrow) 180.gpx else 300.gpx, pin = if (trip?.arrived == true) stringResource(R.string.effort_home_arrived_pin) else null)
                Column(Modifier.widthIn(max = 820.gpx), verticalArrangement = Arrangement.spacedBy(14.gpx)) {
                    Text(sub, style = if (narrow) t.labelM else t.bodyM, color = c.textTertiary)
                    Text(head, style = if (narrow) t.headlineS else t.headlineL, color = c.textPrimary)
                    when {
                        trip == null -> CgCta(stringResource(R.string.effort_home_go), onDepart, compact = narrow)
                        // 영수증 보기 → 고르던 도착 화면(계산 연출 다시 안 봄)
                        trip.arrived -> CgCta(stringResource(R.string.effort_receipt_open), onReceipt, compact = narrow)
                        // v32: 진행 중 알약·시트 대신 [도착했어요](채움) · [그만두기](기본 → 확인 시트)
                        else -> Column(verticalArrangement = Arrangement.spacedBy(8.gpx)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(16.gpx)) {
                                CgButton(stringResource(R.string.effort_arrive_now), onArrive, size = if (narrow) CgSize.M else CgSize.L)
                                CgButton(stringResource(R.string.effort_discard), { quit = true }, kind = CgButtonKind.Basic, size = if (narrow) CgSize.M else CgSize.L)
                            }
                            if (routeFailed) Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.effort_route_failed), style = t.bodyM, color = c.textSecondary)
                                CgTextButton(stringResource(R.string.effort_route_retry), onRetry, primary = true, compact = true)
                            }
                        }
                    }
                }
            }
            // 최근 기록
            Text(stringResource(R.string.effort_recent), Modifier.padding(start = 8.gpx, top = if (narrow) 28.gpx else 40.gpx, bottom = if (narrow) 10.gpx else 14.gpx),
                style = t.bodyS.copy(fontWeight = FontWeight.Normal), color = c.textTertiary)
            if (ui.recent.isEmpty()) {
                CgEmpty(stringResource(R.string.effort_recent_empty), stringResource(R.string.effort_recent_empty_hint), Modifier.fillMaxWidth().height(if (narrow) 200.gpx else 240.gpx))
            } else if (narrow) {
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.gpx)) {
                    ui.recent.forEachIndexed { i, r -> RecordCard(r, ui, narrow, fresh = i == 0 && r.tripId == ui.justFinished, modifier = Modifier.fillMaxWidth().clickable { onRecord(r.tripId) }) }
                }
            } else {
                Row(Modifier.fillMaxWidth().height(340.gpx), horizontalArrangement = Arrangement.spacedBy(24.gpx)) {
                    ui.recent.forEachIndexed { i, r -> RecordCard(r, ui, narrow, fresh = i == 0 && r.tripId == ui.justFinished, modifier = Modifier.weight(1f).height(340.gpx).clickable { onRecord(r.tripId) }) }
                    repeat(3 - ui.recent.size.coerceAtMost(3)) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        // 그만두기 확인(v32 home-progress-quit, 딤 없음): 그만두면 기록 없이 출발이 닫히고 홈 기본으로
        if (quit && trip != null && !trip.arrived) {
            CgBottomSheet(
                Modifier.align(Alignment.BottomCenter).then(if (narrow) Modifier.fillMaxWidth() else Modifier.width(760.gpx)), compact = narrow,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.gpx)) {
                    Text(stringResource(R.string.effort_quit_title), style = if (narrow) t.bodyL else t.titleM, color = c.textPrimary)
                    Text(stringResource(R.string.effort_quit_sub), style = t.bodyM, color = c.textSecondary)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.gpx)) {
                    CgButton(stringResource(R.string.effort_quit_keep), { quit = false }, Modifier.weight(1f), kind = CgButtonKind.Basic)
                    CgButton(stringResource(R.string.effort_discard), { quit = false; onDiscard() }, Modifier.weight(1f))
                }
            }
        }
    }
}

/** 고른 것 한 줄: "한 사람 약 9천 원 · 좋아요" · "닫음" · "커피값에서 운전자 빼기"(홈 카드·주행 기록 목록 공통). */
@Composable
internal fun recordNoteText(r: EffortRecord, ui: EffortUi): String = when {
    r.choice == com.mist.pleos_app.effort.CHOICE_UNSETTLED -> stringResource(R.string.effort_home_rec_unsettled)
    r.choice == CHOICE_ACCEPT -> listOfNotNull(r.suggestion?.removePrefix("한 사람 ")?.let { stringResource(R.string.effort_home_rec_per, it) }, stringResource(R.string.effort_offer_like)).joinToString(" · ")
    r.choice == CHOICE_CLOSE -> stringResource(R.string.effort_home_rec_closed)
    else -> stringResource(R.string.effort_home_rec_item, ui.items.firstOrNull { it.key == r.choice }?.phraseText ?: r.choice)
}

/** 최근 기록 카드(v3 5-2): 날짜(방금) · 배수 · 길 조건 칩 · 목적지 · 시간·인원. 누르면 기록 상세(Q-08-01). */
@Composable
private fun RecordCard(r: EffortRecord, ui: EffortUi, narrow: Boolean, fresh: Boolean, modifier: Modifier) {
    val v = remember(r, ui.constants) { com.mist.pleos_app.effort.recordCardOf(r, ui.constants) }
    CgRecordCard(
        if (fresh) stringResource(R.string.effort_home_just_now) else dayText(r.date + "T"),
        r.destName, v.multiplier, v.flatText, v.chips.map { condChip(it) }, v.more, v.meta, modifier, fresh = fresh, compact = narrow, key = r.tripId,
    )
}

/** 길 조건 칩 = 영수증 줄 key → 아이콘 + 짧은 이름(HANDOFF_v3 3·5-2). */
@Composable
private fun condChip(key: String): Pair<CgIcon, String> = when (key) {
    "rain" -> CgIcon.Rain to stringResource(R.string.effort_cond_rain)
    "snow" -> CgIcon.Snow to stringResource(R.string.effort_cond_snow)
    "congestion" -> CgIcon.Traffic to stringResource(R.string.effort_cond_congestion)
    "delay" -> CgIcon.Delay to stringResource(R.string.effort_cond_delay)
    "night" -> CgIcon.Moon to stringResource(R.string.effort_cond_night)
    "fatigue" -> CgIcon.Hourglass to stringResource(R.string.effort_cond_fatigue)
    else -> CgIcon.Sun to stringResource(R.string.effort_cond_clear)
}

/**
 * 홈 로고 심볼(v3 5-1): 손 + 운전대 평면 심볼, 원 바탕 없음. 주간 잉크 · 야간 라임(손가락 구분선은 반대색).
 * 3.2초 주기로 4px 떠올랐다 내려오고, 바닥 타원 그림자가 같은 주기로 가로 1 → 0.9 · 투명도 1 → 0.75. 움직임 줄이기면 멈춤.
 */
@Composable
internal fun LogoSymbol(size: Dp, pin: String? = null) {
    val c = Chagok.colors
    val reduce = rememberReduceMotion()
    val inf = rememberInfiniteTransition(label = "float")
    // 움직임은 그리기 단계에서만(drawWithContent · Canvas). graphicsLayer로 옮기면 화면 좌표가 매 프레임 바뀌어 uiautomator가 idle을 못 잡는다(시연 스크립트 11/9 실측)
    val k = inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = CubicBezierEasing(.45f, 0f, .55f, 1f)), RepeatMode.Reverse), label = "k")
    fun f() = if (reduce) 0f else k.value
    val lift = 4.gpx
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        val shadow = if (c.isDark) Color(0x80000000) else Color(0x4D242033)
        Canvas(Modifier.align(Alignment.BottomCenter).size(size * .62f, size * .09f)) {
            val f = f()
            scale(1f - .1f * f, 1f) { drawOval(Brush.radialGradient(listOf(shadow.copy(alpha = shadow.alpha * (1f - .25f * f)), Color.Transparent), center = center, radius = this.size.width / 2f), size = this.size) }
        }
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(if (c.isDark) R.drawable.nogo_symbol_night else R.drawable.nogo_symbol_day), contentDescription = null,
            modifier = Modifier.size(size * .78f, size * .84f).offset(y = -size * .04f).drawWithContent { translate(0f, -lift.toPx() * f()) { this@drawWithContent.drawContent() } },
        )
        // v32 home-pending: "도착" 깃발(딥 잉크 알약 + 흰 글자)
        pin?.let {
            Text(it, Modifier.align(Alignment.TopEnd).background(c.textPrimary, RoundedCornerShape(24.gpx)).padding(horizontal = 20.gpx, vertical = 6.gpx),
                style = Chagok.type.labelM.copy(fontWeight = FontWeight.Bold), color = c.surfaceCard)
        }
    }
}

/**
 * 중간 정산 시동 버튼(v3 5-3, `components/mid-button`): Satin Silver 테 + 라임 대각 면 + 로고 심볼(58%).
 * 운전대가 2초마다 한 바퀴(0.9초 돌고 1.1초 쉼), 라임 고리 두 겹이 1.2초 간격으로 퍼짐(2.4초), 누르면 0.985로 눌리고 면이 2 내려감.
 * 움직임 줄이기면 돌기 · 고리를 멈춘다.
 */
@Composable
internal fun StartButton(size: Dp, label: String, onClick: () -> Unit) {
    val c = Chagok.colors
    val reduce = rememberReduceMotion()
    val narrow = size < 300.gpx
    val bezel = if (narrow) 12.gpx else 22.gpx
    val ringOut = if (narrow) 7.gpx else 10.gpx
    val ringW = 3.gpx
    val ringColor = if (c.isDark) Color(0xFFD9F77A) else Color(0xFF8FA930)
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 1f else 0f, tween(220, easing = CubicBezierEasing(.32f, .72f, 0f, 1f)), label = "press")
    val inf = rememberInfiniteTransition(label = "start")
    val turn by inf.animateFloat(0f, 360f, infiniteRepeatable(keyframes {
        durationMillis = 2000
        0f at 0 using CubicBezierEasing(.65f, 0f, .35f, 1f)
        360f at 900
        360f at 2000
    }), label = "wheel")
    val ring by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "ring")
    val lift = 2.gpx
    Box(
        Modifier.size(size + ringOut * 2 + ringW * 2)
            .clickable(src, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        // 울림: 고리 두 겹(1.2초 차이) · 크기 0.96 → 1.14 · 투명도 0.85 → 0
        if (!reduce) Canvas(Modifier.size(size + ringOut * 2)) {
            val ease = CubicBezierEasing(.22f, .7f, .3f, 1f)
            for (off in floatArrayOf(0f, .5f)) {
                val e = ease.transform((ring + off) % 1f)
                val r = this.size.minDimension / 2f * (.96f + .18f * e)
                val a = .85f * (1f - e)
                drawCircle(ringColor.copy(alpha = a * .25f), r, style = Stroke(ringW.toPx() * 5))   // 번짐
                drawCircle(ringColor.copy(alpha = a), r, style = Stroke(ringW.toPx()))
            }
        }
        // 테(Satin Silver, 위 → 아래)
        Box(
            Modifier.size(size).graphicsLayer { val s = 1f - .015f * press; scaleX = s; scaleY = s }
                .shadow(26.gpx * (1f - .6f * press), CircleShape, ambientColor = Color(0x33242033), spotColor = Color(0x33242033))
                .background(Brush.verticalGradient(0f to c.receiptPrinterHighlight, .18f to c.receiptPrinterMidtone, .7f to c.receiptPrinterShade, 1f to c.receiptPrinterMidtone), CircleShape)
                .padding(bezel),
            contentAlignment = Alignment.Center,
        ) {
            // 면: 대각 135° 라임 세 단(로고 lime-gradient와 같음) + 바깥 3px 테두리
            Box(
                Modifier.fillMaxSize().graphicsLayer { translationY = lift.toPx() * press }
                    .shadow(12.gpx * (1f - .7f * press), CircleShape, ambientColor = Color(0x38242033), spotColor = Color(0x38242033))
                    .background(Brush.linearGradient(listOf(Color(0xFFF5FFD2), Color(0xFFD9F77A), Color(0xFFA9C640))), CircleShape)
                    .border(3.gpx, Color(0x38171B22), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                // 심볼(viewBox 14 12 116 120): 손은 그대로, 운전대만 제 중심(95.4, 42.2)을 축으로 돎
                Box(Modifier.fillMaxSize(.58f).padding(top = 0.gpx)) {
                    androidx.compose.foundation.Image(
                        androidx.compose.ui.res.painterResource(R.drawable.nogo_startbtn_wheel), null,
                        Modifier.matchParentSize().drawWithContent {   // 그리기 단계에서 돌림(홈 로고와 같은 이유)
                            rotate(if (reduce) 0f else turn, Offset(this.size.width * .702f, this.size.height * .252f)) { this@drawWithContent.drawContent() }
                        },
                    )
                    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.nogo_startbtn_hand), null, Modifier.matchParentSize())
                }
            }
        }
    }
}

/** 앱 시작 화면(1.6초): 원 안의 내 차 · 이름 · "준비하고 있어요" + 점 세 개. */
@Composable
internal fun EffortSplash(onDone: () -> Unit) {
    val c = Chagok.colors
    LaunchedEffect(Unit) { delay(1600); onDone() }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.gpx, Alignment.CenterVertically)) {
        // v3 시작 화면: 앱 아이콘 192 + 워드마크 228 × 72 + 준비 중(HANDOFF_v3 2절)
        CgAppIcon(192.gpx)
        CgWordmark(228.gpx, label = stringResource(R.string.effort_home_logo))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.gpx)) {
            Text(stringResource(R.string.effort_home_loading), style = Chagok.type.bodyM, color = c.textTertiary)
            CgLoadingDots(c.textTertiary)
        }
    }
}
