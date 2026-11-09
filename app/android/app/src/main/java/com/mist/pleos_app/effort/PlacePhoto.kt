package com.mist.pleos_app.effort

/**
 * 출발 미리보기 배경 장소 사진(#118 Design v3 HANDOFF_v3 4절, 임시 사진 · CC BY-SA). 시안 `PLACE_IMG`와 같은 순서로 고른다.
 * 그 밖의 목적지는 null = 사진 없이 딥 잉크 바탕. 홈 기록 카드에는 쓰지 않는다(11/9 명준).
 */
enum class PlacePhoto { TAEAN, GAPYEONG, YANGPYEONG, HOME, OFFICE }

fun placePhotoOf(name: String?): PlacePhoto? {
    val n = name ?: return null
    return when {
        "태안" in n || "꽃지" in n -> PlacePhoto.TAEAN
        "아침고요" in n || "가평" in n -> PlacePhoto.GAPYEONG
        "두물머리" in n || "양평" in n -> PlacePhoto.YANGPYEONG
        n == "집" -> PlacePhoto.HOME
        n == "회사" -> PlacePhoto.OFFICE
        else -> null
    }
}
