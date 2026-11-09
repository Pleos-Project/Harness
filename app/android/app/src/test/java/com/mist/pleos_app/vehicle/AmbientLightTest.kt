package com.mist.pleos_app.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 앰비언트 라이트(F28): 읽어 두고 켜기 → 모두 놓으면 되돌리기, 가려지면 바로 되돌리기, 실패·설정 끔이면 조명 없이. */
class AmbientLightTest {

    private class FakePort(var current: LightValue? = LightValue("OFF", 0xFFFFFF), var writeOk: Boolean = true, val async: Boolean = false) : AmbientLightPort {
        val writes = mutableListOf<LightValue>()
        var reads = 0
        val pending = mutableListOf<() -> Unit>()
        override fun read(done: (LightValue?) -> Unit) {
            reads++
            val v = current
            if (async) pending += { done(v) } else done(v)
        }
        override fun write(v: LightValue, done: (Boolean) -> Unit) {
            writes += v
            if (writeOk) current = v
            done(writeOk)
        }
        fun flush() { pending.toList().also { pending.clear() }.forEach { it() } }
    }

    private val before = LightValue("OFF", 0xFFFFFF)
    private val brand = LightValue(LIGHT_ON, AMBIENT_BRAND_ARGB)

    @Test
    fun lightThenRestoreWhenAllReleased() {
        val port = FakePort(); val store = MemoryAmbientStore()
        val a = AmbientLight(port, store)
        a.acquire("open")
        assertEquals(listOf(brand), port.writes)
        assertEquals(before, store.load())                     // 켜기 전 값을 기기에 둔다
        a.acquire("settle")                                    // 이미 켜져 있으면 다시 읽거나 쓰지 않음
        assertEquals(1, port.reads)
        a.release("open")
        assertTrue(a.isOn)                                     // settle이 남아 있으면 그대로
        a.release("settle")
        assertEquals(listOf(brand, before), port.writes)       // 모두 놓으면 읽어 둔 값으로
        assertNull(store.load())
        assertFalse(a.isOn)
    }

    @Test
    fun restoreAllOnStopOrDriving() {
        // AC-F28-3: 주행 전환·onStop이면 이유와 상관없이 바로 되돌리고, 그 뒤 놓기는 아무것도 부르지 않음
        val port = FakePort(); val a = AmbientLight(port, MemoryAmbientStore())
        a.acquire("settle"); a.acquire("open")
        a.restoreAll("stop")
        assertEquals(listOf(brand, before), port.writes)
        a.release("settle"); a.release("open")
        assertEquals(2, port.writes.size)
    }

    @Test
    fun releasedWhileReadingNeverLights() {
        // 읽는 사이 가려지면(되돌림) 켜지 않는다 → 가려진 뒤 SDK 쓰기 0건
        val port = FakePort(async = true); val a = AmbientLight(port, MemoryAmbientStore())
        a.acquire("open")
        a.restoreAll("stop")
        port.flush()
        assertTrue(port.writes.isEmpty())
        assertFalse(a.isOn)
    }

    @Test
    fun notAllowedOrUnreadableDoesNothing() {
        // AC-F28-5 설정 끔 · 주행 중: 부르지 않음 / AC-F28-4 지금 값을 못 읽으면 켜지 않음
        var allowed = false
        val port = FakePort(); val a = AmbientLight(port, MemoryAmbientStore(), allowed = { allowed })
        a.acquire("open")
        assertEquals(0, port.reads)
        allowed = true
        port.current = null
        a.acquire("open")
        assertTrue(port.writes.isEmpty())
        assertFalse(a.isOn)
    }

    @Test
    fun writeFailRestoresAndRecoverOnNextStart() {
        // 켜기 쓰기가 실패하면 읽어 둔 값으로 되돌려 둔다
        val port = FakePort(writeOk = false); val store = MemoryAmbientStore()
        val a = AmbientLight(port, store)
        a.acquire("settle")
        assertEquals(listOf(brand, before), port.writes)
        assertNull(store.load())
        // 지난번에 되돌리기 전에 앱이 끝났으면(저장값 남음) 다음에 켤 때 먼저 되돌림
        val port2 = FakePort(current = brand); val store2 = MemoryAmbientStore(before)
        AmbientLight(port2, store2).recover()
        assertEquals(listOf(before), port2.writes)
        assertNull(store2.load())
    }
}
