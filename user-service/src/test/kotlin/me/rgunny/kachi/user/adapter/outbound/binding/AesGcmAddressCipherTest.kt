package me.rgunny.kachi.user.adapter.outbound.binding

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

@DisplayName("AesGcmAddressCipher")
class AesGcmAddressCipherTest {
    private val key = base64Key(1)
    private val cipher = AesGcmAddressCipher(key)
    private val plaintext = "https://hooks.slack.com/services/T000/B000/XXXX"

    @Test
    @DisplayName("암호화한 값을 같은 키로 복호화하면 원문이다")
    fun roundTrip() {
        val ciphertext = cipher.encrypt(plaintext)

        assertEquals(plaintext, cipher.decrypt(ciphertext))
        assertFalse(String(ciphertext, Charsets.ISO_8859_1).contains("hooks.slack.com"))
    }

    @Test
    @DisplayName("같은 평문도 암호화할 때마다 다른 암호문이 된다")
    fun differentCiphertextPerEncryption() {
        assertFalse(cipher.encrypt(plaintext).contentEquals(cipher.encrypt(plaintext)))
    }

    @Test
    @DisplayName("다른 키로는 복호화할 수 없다")
    fun rejectOtherKey() {
        val ciphertext = cipher.encrypt(plaintext)

        assertFailsWith<IllegalStateException> { AesGcmAddressCipher(base64Key(2)).decrypt(ciphertext) }
    }

    @Test
    @DisplayName("키는 base64 32바이트여야 한다")
    fun rejectInvalidKey() {
        assertFailsWith<IllegalArgumentException> { AesGcmAddressCipher("not-base64!") }
        assertFailsWith<IllegalArgumentException> {
            AesGcmAddressCipher(Base64.getEncoder().encodeToString(ByteArray(16)))
        }
    }

    @Test
    @DisplayName("키 버전은 기본 1이다")
    fun defaultKeyVersion() {
        assertEquals(1, cipher.keyVersion)
    }

    private fun base64Key(seed: Int): String {
        return Base64.getEncoder().encodeToString(ByteArray(32) { (it + seed).toByte() })
    }
}
