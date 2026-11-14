package dev.dshn.app.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SecretCipher {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(ciphertext: ByteArray): ByteArray
}

/**
 * Encrypts dormant credentials and ADB keys using AndroidKeyStore AES-256-GCM.
 */
object KeystoreCrypto : SecretCipher {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "dshn_master_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val VERSION_BYTE: Byte = 1
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH_BITS = 128

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        require(iv.size == IV_LENGTH) { "Unexpected GCM IV length: ${iv.size}" }
        val encrypted = cipher.doFinal(plaintext)
        return ByteArray(1 + IV_LENGTH + encrypted.size).apply {
            this[0] = VERSION_BYTE
            System.arraycopy(iv, 0, this, 1, IV_LENGTH)
            System.arraycopy(encrypted, 0, this, 1 + IV_LENGTH, encrypted.size)
        }
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size > 1 + IV_LENGTH) { "Ciphertext too short" }
        require(ciphertext[0] == VERSION_BYTE) { "Unsupported ciphertext version: ${ciphertext[0]}" }
        val iv = ciphertext.copyOfRange(1, 1 + IV_LENGTH)
        val body = ciphertext.copyOfRange(1 + IV_LENGTH, ciphertext.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return cipher.doFinal(body)
    }

    fun encryptString(value: String): ByteArray = encrypt(value.toByteArray(Charsets.UTF_8))

    fun decryptString(blob: ByteArray): String = String(decrypt(blob), Charsets.UTF_8)

    fun randomBytes(count: Int): ByteArray = ByteArray(count).also { SecureRandom().nextBytes(it) }
}
