package com.mist.pleos_app.ui

import androidx.compose.foundation.clickable
import com.mist.pleos_app.ui.theme.ChagokMotion
import com.mist.pleos_app.ui.theme.ChagokSpace
import com.mist.pleos_app.ui.components.CgBell
import com.mist.pleos_app.ui.components.CgNoti
import com.mist.pleos_app.ui.components.CgPlaceLine
import com.mist.pleos_app.ui.components.CgNotiPanel
import com.mist.pleos_app.ui.theme.gsp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.wrapContentHeight
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
import com.mist.pleos_app.ui.components.CgSheetButtons
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

private val Idle = CubicBezierEasing(.45f, 0f, .55f, 1f)

@Composable
internal fun EffortHomeScreen(
    ui: EffortUi,
    onDepart: () -> Unit,
    onDiscard: () -> Unit,
    onSettings: () -> Unit,
    onReceipt: () -> Unit,
    onArrive: () -> Unit,
    onLog: () -> Unit = {},
    onRecord: (String) -> Unit = {},
    onMidway: () -> Unit = {},
    onNoti: () -> Unit = {},
    onNotiClose: () -> Unit = {},
    onArriveAnswer: (Boolean) -> Unit = {},
) {
    val narrow = LocalNarrow.current
    val reduce = rememberReduceMotion()
    val c = Chagok.colors
    val t = Chagok.type
    val trip = ui.openTrip
    var quit by rememberSaveable { mutableStateOf(false) }   // 그만두기 확인 시트(v32 home-progress-quit)
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(700, easing = ChagokMotion.Rise)) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // 위 바(v93): 왼쪽 기록 · 가운데 워드마크 · 오른쪽 알림 종 + 설정(사이 12)
            CgTitleBar(
                stringResource(R.string.effort_home_logo), centered = true,
                titleStyle = (if (narrow) t.titleM else t.titleL).copy(fontWeight = FontWeight.ExtraBold, letterSpacing = t.titleL.fontSize * .08f),
                leading = { CgIconButton(stringResource(R.string.effort_log_open), CgIcon.Receipt, onLog) },
                titleContent = { CgWordmark(152.gpx, label = stringResource(R.string.effort_home_logo)) },   // .wm svg 152 × 48(분할도 같음)
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.gpx), verticalAlignment = Alignment.CenterVertically) {
                        CgBell(stringResource(R.string.effort_noti_open), ui.notiDot, ui.notiOpen, onNoti, compact = narrow, reduce = reduce)
                        CgIconButton(stringResource(R.string.screen_settings), CgIcon.Gear, onSettings)
                    }
                },
            )
            // v87: 최근 기록은 홈에서 뺌. 인사 한 덩어리(심볼 + 작은 줄 → 큰 인사 → 버튼)를 가운데에 크게
            val (sub, head) = when {
                // 고르기 전에 나간 도착: "○○에 도착했어요 · 16:30" / "오늘 운전 영수증이 여기 있어요"
                trip != null && trip.arrived -> stringResource(R.string.effort_home_arrived_sub, trip.destName, clockText(trip.arrivedAt ?: trip.departedAt)) to stringResource(R.string.effort_home_arrived_head)
                // 가는 중(정차 때만 보임): "○○으로 가는 중 · 14:20 출발 · 함께 4명" / "잠깐 멈췄네요 여기까지 운전을 세어 볼까요?"
                trip != null -> stringResource(R.string.effort_home_progress_sub, euro(trip.destName), clockText(trip.departedAt), trip.n) to
                    stringResource(R.string.effort_home_progress_head)
                // 첫 실행도 기록이 있을 때와 같은 인사(June 11/10)
                else -> stringResource(R.string.effort_home_sub) to stringResource(R.string.effort_home_head)
            }
            val progress = ui.midwayAvailable
            val hero: @Composable () -> Unit = {
                if (progress) StartButton(if (narrow) 300.gpx else 440.gpx, stringResource(R.string.effort_mid_open), onMidway)
                else LogoSymbol(if (narrow) 280.gpx else 420.gpx, pin = if (trip?.arrived == true) stringResource(R.string.effort_home_arrived_pin) else null, compact = narrow)
            }
            val text: @Composable () -> Unit = {
                Column(
                    Modifier.widthIn(max = if (narrow) 760.gpx else 1040.gpx),
                    horizontalAlignment = if (narrow) Alignment.CenterHorizontally else Alignment.Start,
                ) {
                    // 작은 줄은 큰 인사 위 한 줄(500 30/40 · 분할 24/32, textSecondary). 큰 인사와 사이 16(분할 10, .hdesc margin -6)
                    // 장소 이름만 말줄임(#118 D2): 뒤 문구 · 시각 · 인원은 늘 보이게
                    sub?.let {
                        CgPlaceLine(it, trip?.destName, TextStyle(fontSize = (if (narrow) 24 else 30).gsp, lineHeight = (if (narrow) 32 else 40).gsp, fontWeight = FontWeight.Medium, letterSpacing = (if (narrow) 24 else 30).gsp * -.005f),
                            c.textSecondary, Modifier.padding(bottom = if (narrow) 10.gpx else 16.gpx))
                    }
                    // 큰 인사 800 84/108 · 자간 -1.5%(가는 중 68/88, 분할 56/72)
                    val hs = if (narrow) 56 else if (progress) 68 else 84
                    val hl = if (narrow) 72 else if (progress) 88 else 108
                    Text(head, style = TextStyle(fontSize = hs.gsp, lineHeight = hl.gsp, fontWeight = FontWeight.ExtraBold, letterSpacing = hs.gsp * -.015f),
                        color = c.textPrimary, textAlign = if (narrow) TextAlign.Center else TextAlign.Start)
                    // 큰 인사와 버튼 사이 44(분할 32): .tx gap 22 + .cta margin-top 22(분할 14 + 18)
                    Box(Modifier.padding(top = if (narrow) 32.gpx else 44.gpx)) {
                        when {
                            trip == null -> CgCta(stringResource(R.string.effort_home_go), onDepart, compact = narrow, hero = true)
                            trip.arrived -> CgCta(stringResource(R.string.effort_receipt_open), onReceipt, compact = narrow, hero = true)
                            else -> Column(horizontalAlignment = if (narrow) Alignment.CenterHorizontally else Alignment.Start, verticalArrangement = Arrangement.spacedBy(8.gpx)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(20.gpx)) {
                                    CgButton(stringResource(R.string.effort_arrive_now), onArrive, size = if (narrow) CgSize.XLC else CgSize.XL)
                                    CgButton(stringResource(R.string.effort_discard), { quit = true }, kind = CgButtonKind.Basic, size = if (narrow) CgSize.XLC else CgSize.XL)
                                }
                            }
                        }
                    }
                }
            }
            val enterMod = Modifier.graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 24f * density }
            if (narrow) Column(
                Modifier.fillMaxWidth().weight(1f).then(enterMod).padding(bottom = 60.gpx),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(44.gpx, Alignment.CenterVertically),
            ) { hero(); text() }
            else Row(
                Modifier.fillMaxWidth().weight(1f).then(enterMod).padding(bottom = 80.gpx),
                horizontalArrangement = Arrangement.spacedBy(if (progress) 88.gpx else 96.gpx, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) { hero(); text() }
        }
        // 알림 창(v96): 종 아래로 내려옴 · 바깥을 누르면 접힘 · 확인만
        val just = stringResource(R.string.effort_home_just_now)
        val notis = ui.recent.take(3).map { r ->
            CgNoti(stringResource(R.string.effort_noti_saved, r.destName), if (r.tripId == ui.justFinished) just else dayText(r.date + "T"), r.tripId in ui.notiFresh, place = r.destName)
        }
        val freshN = notis.count { it.fresh }
        CgNotiPanel(
            ui.notiOpen, notis, stringResource(R.string.effort_noti_head),
            if (freshN > 0) stringResource(R.string.effort_noti_new, freshN) else null,
            stringResource(R.string.effort_noti_foot), onNotiClose, compact = narrow, reduce = reduce,
        )
        // "도착했나요?"(#145): 안내 종료 · 끊겼다 이어진 주행 · ETA 없는 도착 신호. 예면 물을 때 정한 시각으로 도착
        val ask = ui.arriveAsk
        if (ask != null && trip != null && !trip.arrived && !quit) {
            CgBottomSheet(
                Modifier.align(Alignment.BottomCenter).then(if (narrow) Modifier.fillMaxWidth() else Modifier.width(760.gpx)), compact = narrow, confirm = true,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.gpx)) {
                    Text(stringResource(R.string.effort_arrive_ask_title), style = if (narrow) t.bodyL else t.titleM, color = c.textPrimary)
                    val hm = ask.at.substring(11, 16)
                    Text(
                        when (ask.kind) {
                            com.mist.pleos_app.effort.GUIDE_CANCELLED -> stringResource(R.string.effort_arrive_ask_cancelled, hm)
                            com.mist.pleos_app.effort.GUIDE_ARRIVED -> stringResource(R.string.effort_arrive_ask_arrived, hm)
                            else -> stringResource(R.string.effort_arrive_ask_interrupted)
                        },
                        style = t.bodyM, color = c.textSecondary,
                    )
                }
                CgSheetButtons(narrow) { sz ->
                    CgButton(stringResource(R.string.effort_arrive_ask_no), { onArriveAnswer(false) }, kind = CgButtonKind.Basic, size = sz)
                    CgButton(stringResource(R.string.effort_arrive_now), { onArriveAnswer(true) }, kind = CgButtonKind.Filled, size = sz)
                }
            }
        }
        // 그만두기 확인(v32 home-progress-quit, 딤 없음): 그만두면 기록 없이 출발이 닫히고 홈 기본으로
        if (quit && trip != null && !trip.arrived) {
            CgBottomSheet(
                Modifier.align(Alignment.BottomCenter).then(if (narrow) Modifier.fillMaxWidth() else Modifier.width(760.gpx)), compact = narrow, confirm = true,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.gpx)) {
                    Text(stringResource(R.string.effort_quit_title), style = if (narrow) t.bodyL else t.titleM, color = c.textPrimary)
                    Text(stringResource(R.string.effort_quit_sub), style = t.bodyM, color = c.textSecondary)
                }
                // .ibts: 오른쪽 정렬 · 내용 폭 · [그만두기]는 삭제 색(fill danger)
                CgSheetButtons(narrow) { sz ->
                    CgButton(stringResource(R.string.effort_quit_keep), { quit = false }, kind = CgButtonKind.Basic, size = sz)
                    CgButton(stringResource(R.string.effort_discard), { quit = false; onDiscard() }, kind = CgButtonKind.Danger, size = sz)
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

/**
 * 홈 로고 심볼(v3 5-1): 손 + 운전대 평면 심볼, 원 바탕 없음. 주간 잉크 · 야간 라임(손가락 구분선은 반대색).
 * 3.2초 주기로 4px 떠올랐다 내려오고, 바닥 타원 그림자가 같은 주기로 가로 1 → 0.9 · 투명도 1 → 0.75. 움직임 줄이기면 멈춤.
 */
@Composable
internal fun LogoSymbol(size: Dp, pin: String? = null, compact: Boolean = false) {
    val c = Chagok.colors
    val reduce = rememberReduceMotion()
    val inf = rememberInfiniteTransition(label = "float")
    // 움직임은 그리기 단계에서만(drawWithContent · Canvas). graphicsLayer로 옮기면 화면 좌표가 매 프레임 바뀌어 uiautomator가 idle을 못 잡는다(시연 스크립트 11/9 실측)
    val k = inf.animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = CubicBezierEasing(.45f, 0f, .55f, 1f)), RepeatMode.Reverse), label = "k")   // cg-float 3.2s alternate
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
            // v104: 높이 60 · 안쪽 0 24 · 반지름 30 · 700 30/38(분할 48 · 24px)
            Text(it, Modifier.align(Alignment.TopEnd).offset(x = if (compact) 30.gpx else 24.gpx, y = if (compact) 0.gpx else 10.gpx)
                .height(if (compact) 48.gpx else 60.gpx).background(c.textPrimary, RoundedCornerShape(30.gpx)).padding(horizontal = if (compact) 18.gpx else 24.gpx).wrapContentHeight(Alignment.CenterVertically),
                style = TextStyle(fontSize = (if (compact) 24 else 30).gsp, lineHeight = (if (compact) 30 else 38).gsp, fontWeight = FontWeight.Bold), color = c.surfaceCard)
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
    val narrow = size < 400.gpx
    val bezel = if (narrow) 18.gpx else 26.gpx   // v104: 440 · 26(분할 300 · 18)
    val ringOut = if (narrow) 9.gpx else 10.gpx
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

/**
 * 앱 시작 화면(1.6초): 앱 아이콘 · 워드마크 · "준비하고 있어요" + 점 세 개(글자–점 12, G2 app-splash).
 * 등장(Design v2): 아이콘·워드마크가 각각 아래 24 → 제자리 + 투명 → 보임, 0.6초 [ChagokMotion.Rise], 워드마크는 80ms 늦게.
 * 움직임 줄이기면 끝 모습으로 바로 둔다. 타이머는 시작 화면 길이(1.6초) 하나뿐이다.
 */
@Composable
internal fun EffortSplash(onDone: () -> Unit) {
    val c = Chagok.colors
    val reduce = rememberReduceMotion()
    val icon = remember { Animatable(if (reduce) 1f else 0f) }
    val mark = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) { delay(1600); onDone() }
    LaunchedEffect(Unit) { if (!reduce) icon.animateTo(1f, tween(SPLASH_RISE_MS, easing = ChagokMotion.Rise)) }
    LaunchedEffect(Unit) { if (!reduce) { delay(SPLASH_MARK_DELAY_MS.toLong()); mark.animateTo(1f, tween(SPLASH_RISE_MS, easing = ChagokMotion.Rise)) } }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.gpx, Alignment.CenterVertically)) {
        // v3 시작 화면: 앱 아이콘 192 + 워드마크 228 × 72 + 준비 중(HANDOFF_v3 2절)
        Box(Modifier.graphicsLayer { alpha = icon.value; translationY = (1f - icon.value) * 24f * density }) { CgAppIcon(192.gpx) }
        Box(Modifier.graphicsLayer { alpha = mark.value; translationY = (1f - mark.value) * 24f * density }) {
            CgWordmark(228.gpx, label = stringResource(R.string.effort_home_logo))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ChagokSpace.S12.gpx)) {
            Text(stringResource(R.string.effort_home_loading), style = Chagok.type.bodyM, color = c.textTertiary)
            CgLoadingDots(c.textTertiary)
        }
    }
}

private const val SPLASH_RISE_MS = 600
private const val SPLASH_MARK_DELAY_MS = 80
