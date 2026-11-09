package com.mist.pleos_app.effort

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 출발 미리보기 장소 사진(#118 HANDOFF_v3 4-1): 시안 PLACE_IMG와 같은 규칙 · 같은 순서. */
class PlacePhotoTest {
    @Test
    fun sameRuleAsDesign() {
        assertEquals(PlacePhoto.TAEAN, placePhotoOf("태안 꽃지해수욕장"))
        assertEquals(PlacePhoto.TAEAN, placePhotoOf("꽃지"))
        assertEquals(PlacePhoto.GAPYEONG, placePhotoOf("가평 아침고요수목원"))
        assertEquals(PlacePhoto.YANGPYEONG, placePhotoOf("양평 두물머리"))
        assertEquals(PlacePhoto.HOME, placePhotoOf("집"))
        assertEquals(PlacePhoto.OFFICE, placePhotoOf("회사"))
        assertNull(placePhotoOf("우리 집"))                  // 집 · 회사는 이름이 정확히 같을 때만
        assertNull(placePhotoOf("코엑스"))                    // 그 밖 = 딥 잉크 바탕
        assertNull(placePhotoOf(null))
        assertEquals(PlacePhoto.TAEAN, placePhotoOf("태안 가평 휴게소"))   // 앞 규칙 먼저(시안 순서)
    }
}
