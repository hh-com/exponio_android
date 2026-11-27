package org.exponio.app.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Verschlüsselte Ablage für das Token der Website (Backend → System → App-Zugänge).
 *
 * Der AES-256-GCM-Schlüssel liegt im Android Keystore (hardwaregestützt, nicht exportierbar);
 * in den SharedPreferences stehen nur IV + Chiffretext. Die WebView kann Secrets setzen und
 * löschen, aber niemals zurücklesen – gelesen wird ausschließlich nativ beim Verbindungsaufbau.
 */
class SecretStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("pt_secrets", Context.MODE_PRIVATE)

    init {
        // Zugangsdaten früherer Versionen (SFTP/FTPS) werden nicht mehr gebraucht.
        if (LEGACY_NAMES.any { prefs.contains(it) }) {
            prefs.edit().apply { LEGACY_NAMES.forEach { remove(it) } }.apply()
        }
    }

    @Synchronized
    fun put(name: String, value: String?) {
        if (value.isNullOrEmpty()) {
            prefs.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val blob = ByteArray(1 + iv.size + encrypted.size)
        blob[0] = iv.size.toByte()
        System.arraycopy(iv, 0, blob, 1, iv.size)
        System.arraycopy(encrypted, 0, blob, 1 + iv.size, encrypted.size)
        prefs.edit().putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)).commit()
    }

    @Synchronized
    fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return try {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            val ivLength = blob[0].toInt()
            val iv = blob.copyOfRange(1, 1 + ivLength)
            val encrypted = blob.copyOfRange(1 + ivLength, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            // z. B. Keystore-Schlüssel nach Geräte-Reset ungültig → Secret gilt als nicht vorhanden.
            Log.w(TAG, "Secret '$name' nicht entschlüsselbar, wird verworfen: ${e.javaClass.simpleName}")
            prefs.edit().remove(name).apply()
            null
        }
    }

    fun has(name: String): Boolean = get(name) != null

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val TAG = "PtSecrets"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "exponio_secrets_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        const val TOKEN = "token"
        val NAMES = setOf(TOKEN)
        private val LEGACY_NAMES = listOf("password", "privateKey", "passphrase")

        @Volatile
        private var instance: SecretStore? = null

        fun get(context: Context): SecretStore =
            instance ?: synchronized(this) {
                instance ?: SecretStore(context.applicationContext).also { instance = it }
            }
    }
}
