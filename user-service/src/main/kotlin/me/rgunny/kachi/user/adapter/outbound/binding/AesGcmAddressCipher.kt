package me.rgunny.kachi.user.adapter.outbound.binding

import me.rgunny.kachi.user.application.port.outbound.binding.AddressCipherPort
import org.springframework.security.crypto.encrypt.AesBytesEncryptor
import org.springframework.security.crypto.keygen.KeyGenerators
import java.util.Base64
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM 주소 암호화.
 *
 * 키는 base64로 표기한 32바이트 하나를 받는다.
 *
 * IV(Initialization Vector, 초기화 벡터)는 암호화마다 새로 뽑는 12바이트 무작위 값이다.
 * 같은 키·같은 평문이 매번 다른 암호문이 되게 해, 저장된 암호문끼리 비교해 같은 주소인지 알아내는 것을 막는다.
 * 비밀은 아니며 암호문 앞에 붙여 저장하므로 별도 컬럼이 없다. GCM은 같은 키로 IV를 재사용하면 안 되므로 무작위로 뽑는다.
 * 다른 키로 만든 암호문은 GCM 태그 검증에서 실패한다.
 */
class AesGcmAddressCipher(
    base64Key: String,
    override val keyVersion: Int = 1
) : AddressCipherPort {

    private val encryptor: AesBytesEncryptor

    init {
        val key = try {
            Base64.getDecoder().decode(base64Key)
        } catch (exception: IllegalArgumentException) {
            throw IllegalArgumentException("암호화 키는 base64 문자열이어야 합니다", exception)
        }

        require(key.size == KEY_BYTES) { "암호화 키는 ${KEY_BYTES}바이트여야 합니다" }

        encryptor = AesBytesEncryptor(
            SecretKeySpec(key, "AES"),
            // IV 생성기. 암호화마다 호출되어 새 IV를 만든다.
            KeyGenerators.secureRandom(IV_BYTES),
            AesBytesEncryptor.CipherAlgorithm.GCM
        )
    }

    override fun encrypt(plaintext: String): ByteArray {
        return encryptor.encrypt(plaintext.toByteArray(Charsets.UTF_8))
    }

    override fun decrypt(ciphertext: ByteArray): String {
        return String(encryptor.decrypt(ciphertext), Charsets.UTF_8)
    }

    companion object {
        private const val KEY_BYTES = 32
        private const val IV_BYTES = 12
    }
}
