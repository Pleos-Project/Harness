package com.mist.pleos_app.effort

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.mist.pleos_app.R
import com.mist.pleos_app.engine.EffortConstants
import com.mist.pleos_app.engine.EffortReceipt
import com.mist.pleos_app.engine.driveText
import com.mist.pleos_app.engine.kmText
import com.mist.pleos_app.engine.roundHalfUp
import com.mist.pleos_app.engine.weatherDetail

/*
 * 오늘 운전 돌아보기(명세 v0.6 F29 · 3절, PRD v2.0 7-5, 명준 11/14 아티팩트 '도착 · 오늘 운전 돌아보기' rv-start ~ rv-solo).
 * 계산 연출과 동의 단계를 한 화면으로 합쳤다. 기준 5줄(날씨 · 정체 구간 · 예상보다 더 · 밤길 · 장시간)을 하나씩 세고,
 * 다 세면 [운전자 챙겨주기] · [다음에](혼자 · 짧은 주행은 [영수증 보기]). **금액 · 배수 · 퍼센트는 없다**(AC-F29-1).
 * 값은 모두 엔진 영수증 · 출발 기록에서 온다(엔진 표기 함수 driveText · kmText 결과). 화면은 그리기만 한다.
 */

/** 줄 안 작은 움직임(시안 rvFx): 비 · 눈 · 차 막힘 · 예상 시각 넘어감 · 시간 막대 · 별. */
enum class ReviewFx { RAIN, SNOW, JAM, LATE, BAR, NIGHT }

/**
 * 노고 표정 → 그림 파일(#162 HANDOFF_v4 6-5, BX 'NOGO 캐릭터 모션' 표정 700 × 754).
 * BX가 상황별 에셋을 다시 만들면 **여기 파일 이름만** 바꿔 끼운다. 기쁨(`nogo_v_joy`)은 누른 뒤 반응용이라 앱에 넣지 않는다.
 */
enum class ReviewPose(@DrawableRes val res: Int, @StringRes val alt: Int) {
    FOCUS(R.drawable.nogo_v_focus, R.string.effort_review_alt_focus),
    SHY(R.drawable.nogo_v_shy, R.string.effort_review_alt_shy),
    RELIEF(R.drawable.nogo_v_relief, R.string.effort_review_alt_relief),
    PROUD(R.drawable.nogo_v_proud, R.string.effort_review_alt_proud),
}

/** 몸짓(시안 rv-slump 0.45초 · rv-dip 0.5초 · rv-jump 0.7초). */
enum class ReviewMotion { SLUMP, DIP, JUMP }

/**
 * 표정 · 몸짓 → 기준 짝(HANDOFF_v4 6-5 표, 지금 짝은 `[가정]`). 짝을 바꿀 때는 이 표만 고친다.
 * 키는 기준 줄 key(weather · congestion · delay · night · fatigue).
 */
object ReviewCast {
    /** 시작 · 없었던 줄: 집중 + 숨쉬기. */
    val idle = ReviewPose.FOCUS
    /** 있었던 줄. */
    val byKey: Map<String, Pair<ReviewPose, ReviewMotion>> = mapOf(
        "weather" to (ReviewPose.SHY to ReviewMotion.SLUMP),
        "congestion" to (ReviewPose.FOCUS to ReviewMotion.DIP),
        "delay" to (ReviewPose.SHY to ReviewMotion.SLUMP),
        "night" to (ReviewPose.FOCUS to ReviewMotion.DIP),
        "fatigue" to (ReviewPose.RELIEF to ReviewMotion.DIP),
    )
    /** 마무리 첫 단계(비 그침 + 땀 닦기). */
    val finish = ReviewPose.RELIEF to ReviewMotion.DIP
    /** 다 셈(0.9초 뒤 뿌듯 점프 + 반짝이). */
    val done = ReviewPose.PROUD to ReviewMotion.JUMP

    fun pose(key: String) = byKey[key]?.first ?: idle
    fun motion(key: String) = byKey[key]?.second ?: ReviewMotion.DIP
}

/** 줄 아이콘(앱 CgIcon으로 바꿔 그림). */
enum class ReviewIcon { RAIN, SNOW, TRAFFIC, DELAY, MOON, HOURGLASS }

/** 값 칸 문구(앱 문구 strings.xml → 화면이 넣어 줌. 엔진 문구가 아닌 것만). */
data class ReviewWords(
    val none: String,          // "없음"
    val unknown: String,       // "정보 없음"
    val whole: String,         // "%s · 내내"(시안 "비 · 내내")
    val depOnly: String,       // "출발할 때 %s"(날씨를 반만 받음, #149 D7)
    val longOver: String,      // "2시간 넘게"
)

data class ReviewItem(
    val key: String,           // weather | congestion | delay | night | fatigue
    val label: String,         // 줄 이름(엔진 line_labels)
    val on: Boolean,           // 더해진 기준이면 라임 원 + 값, 아니면 점선 원 + "없음"(정보 없음 포함)
    val value: String,         // 다 센 뒤 값 칸
    val countTo: Int = 0,      // 값 세기(0 → n): 정체 km · 예상보다 더 · 밤길 분. 0이면 세지 않고 0.7초 뒤 값
    val countKm: Boolean = false,
    val icon: ReviewIcon,
    val fx: ReviewFx,
    val barMark: Float = 0f,   // 시간 막대 눈금 위치(0..1, 시안 70%)
    val lateFrom: String? = null,  // '예상보다 더' 연출: 예상 도착 시각 "HH:mm"(출발 시각 + 출발 때 ETA)
    val lateFromMin: Int = 0,      // 같은 시각(하루 안 분). 큰 숫자가 lateFromMin → lateFromMin + countTo 로 넘어간다
    val pose: ReviewPose = ReviewCast.pose(key),
    val motion: ReviewMotion = ReviewCast.motion(key),
    val say: CalcSay,          // 세는 동안 머리 아래 노고 문구(옛 계산 연출 문구 그대로, 명준 11/14)
)

/** 있는 줄 1.3초 · 없는 줄 0.6초 + 줄 사이 0.3초(시안 playReview). */
const val REVIEW_ON_MS = 1300L
const val REVIEW_OFF_MS = 600L
const val REVIEW_GAP_MS = 300L

/**
 * 영수증 → 기준 5줄. 시안 rvItems와 같은 규칙.
 * 날씨 값: 주행 내내 → "비 · 내내", 날씨를 반만 받음 → "출발할 때 비", 그 밖 → "비".
 */
fun reviewItems(trip: OpenTrip, r: EffortReceipt, c: EffortConstants, w: ReviewWords): List<ReviewItem> {
    val lines = r.lines.associateBy { it.key }
    val label = { k: String -> c.copy.lineLabels[k] ?: k }
    val weatherUnknown = r.notes.contains(c.copy.text["weather_unknown_text"])
    val jamUnknown = r.notes.contains(c.copy.text["congestion_unknown_text"])

    val wKey = listOf("rain", "snow").firstOrNull { it in lines }
    val weather = if (wKey != null) {
        val word = c.copy.precipWords[wKey] ?: ""
        val wholeText = weatherDetail(wKey, whole = true, c = c)
        val v = when {
            weatherUnknown && trip.weatherDep == wKey -> w.depOnly.format(word)   // 도착 날씨를 못 받음(6-2)
            lines.getValue(wKey).detailText == wholeText -> w.whole.format(word)
            else -> word
        }
        ReviewItem("weather", label(wKey), true, v, icon = if (wKey == "snow") ReviewIcon.SNOW else ReviewIcon.RAIN,
            fx = if (wKey == "snow") ReviewFx.SNOW else ReviewFx.RAIN,
            say = if (wKey == "snow") CalcSay.SNOW else CalcSay.RAIN)
    } else ReviewItem("weather", label("rain"), false, if (weatherUnknown) w.unknown else w.none, icon = ReviewIcon.RAIN,
        fx = ReviewFx.RAIN,
        say = if (weatherUnknown) CalcSay.WEATHER_NONE else CalcSay.WEATHER_CLEAR)

    val jamLine = lines["congestion"]
    val jamKm = trip.congestionM?.takeIf { it > 0 }
    val jam = ReviewItem(
        "congestion", jamLine?.label ?: label("congestion"), jamLine != null,
        when { jamLine == null -> if (jamUnknown) w.unknown else w.none; jamKm != null -> kmText(jamKm); else -> jamLine.detailText ?: w.none },
        countTo = if (jamLine != null && jamKm != null && jamKm >= 1000) roundHalfUp(jamKm / 1000) else 0, countKm = true,
        icon = ReviewIcon.TRAFFIC, fx = ReviewFx.JAM,
        say = if (jamLine != null) CalcSay.JAM else CalcSay.JAM_NONE,
    )

    val delayLine = lines["delay"]
    val eta = etaClock(trip)   // 홈 '도착 예상'과 같은 값(출발 + ETA)
    val delay = ReviewItem(
        "delay", delayLine?.label ?: label("delay"), delayLine != null,
        if (delayLine != null) driveText(r.delayMin * 60) else w.none,
        countTo = if (delayLine != null) roundHalfUp(r.delayMin) else 0,
        icon = ReviewIcon.DELAY, fx = ReviewFx.LATE,
        lateFrom = eta, lateFromMin = eta?.let { minOfDay(it) } ?: 0,
        say = if (delayLine != null) CalcSay.LATE else CalcSay.LATE_NONE,
    )

    val nightLine = lines["night"]
    val night = ReviewItem(
        "night", nightLine?.label ?: label("night"), nightLine != null,
        if (nightLine != null) driveText(r.nightMin * 60) else w.none,
        countTo = if (nightLine != null) roundHalfUp(r.nightMin) else 0,
        icon = ReviewIcon.MOON, fx = ReviewFx.NIGHT,
        say = if (nightLine != null) CalcSay.NIGHT else CalcSay.NIGHT_NONE,
    )

    val longLine = lines["fatigue"]
    val long = ReviewItem(
        "fatigue", longLine?.label ?: label("fatigue"), longLine != null,
        if (longLine != null) w.longOver else w.none,
        icon = ReviewIcon.HOURGLASS, fx = ReviewFx.BAR, barMark = .70f,
        say = if (longLine != null) CalcSay.LONG else CalcSay.LONG_NONE,
    )
    return listOf(weather, jam, delay, night, long)
}

/** 다 세기까지 걸리는 시간(시안: 0.6초 뒤 시작, 줄마다 있음 1.3초 · 없음 0.6초 + 0.3초, 마무리 0.9초). 약 10초. */
fun reviewTotalMs(items: List<ReviewItem>): Long =
    600 + items.sumOf { (if (it.on) REVIEW_ON_MS else REVIEW_OFF_MS) + REVIEW_GAP_MS } + 900

/** "HH:mm" → 하루 안 분. */
internal fun minOfDay(hhmm: String): Int = hhmm.take(2).toInt() * 60 + hhmm.takeLast(2).toInt()

/** 하루 안 분 → "HH:mm"(24시 넘으면 0시부터). '예상보다 더' 큰 숫자가 넘어가는 사이 시각. */
fun hhmm(minOfDay: Int): String {
    val m = Math.floorMod(minOfDay, 24 * 60)
    return "%02d:%02d".format(m / 60, m % 60)
}
