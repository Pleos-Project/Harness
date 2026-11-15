package com.mist.pleos_app.ledger

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.mist.pleos_app.engine.LedgerState
import com.mist.pleos_app.engine.parseLedgerState
import com.mist.pleos_app.engine.toJson
import com.mist.pleos_app.util.ChagokLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 장부 저장소. 테스트는 [MemoryLedgerStore]. */
interface LedgerStore {
    fun load(): LedgerState
    fun save(state: LedgerState)
    fun clear()
}

class MemoryLedgerStore(private var state: LedgerState = LedgerState()) : LedgerStore {
    override fun load() = state
    override fun save(state: LedgerState) { this.state = state }
    override fun clear() { state = LedgerState() }
}

/**
 * 장부(스냅샷·trip·기록·핀)를 앱 내부 파일에 **암호화**해 둔다(AC-N-4: 장소 좌표는 장소당 1개만 암호화 저장,
 * 현재 위치·궤적은 저장하지 않음 — 장부에는 원래 그런 값이 없다).
 * 키는 Android Keystore의 AES-256-GCM(앱 밖으로 나가지 않음). 새 의존성 없이 플랫폼 API만 쓴다.
 * 파일이 깨졌거나 키가 바뀌었으면 빈 장부로 시작한다(스냅샷만 다시 잡고 다음 열기부터 정산, AC-S1-2와 같은 동작).
 */
class EncryptedLedgerStore(context: Context) : LedgerStore {
    private val file = File(context.filesDir, "ledger.bin")

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    override fun load(): LedgerState {
        if (!file.exists()) return LedgerState()
        return runCatching {
            val bytes = file.readBytes()
            val iv = bytes.copyOfRange(0, IV_LEN)
            val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
            val text = c.doFinal(bytes, IV_LEN, bytes.size - IV_LEN).decodeToString()
            parseLedgerState(Json.parseToJsonElement(text).jsonObject)
        }.onFailure { ChagokLog.event("ledger_load_failed", "err" to it.javaClass.simpleName) }
            .getOrDefault(LedgerState())
    }

    override fun save(state: LedgerState) {
        runCatching {
            val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
            val body = c.doFinal(state.toJson().toString().encodeToByteArray())
            val tmp = File(file.parentFile, "ledger.bin.tmp")
            tmp.writeBytes(c.iv + body)
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }.onFailure { ChagokLog.event("ledger_save_failed", "err" to it.javaClass.simpleName) }
    }

    override fun clear() { file.delete() }

    private companion object {
        const val ALIAS = "chagok_ledger"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
    }
}
