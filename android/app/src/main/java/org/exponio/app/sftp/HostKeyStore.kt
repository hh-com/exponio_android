package org.exponio.app.sftp

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/**
 * Trust-On-First-Use-Speicher für SSH-Host-Keys, getrennt pro "host:port".
 * Gespeichert wird der vollständige öffentliche Schlüssel (Blob), nicht nur der Fingerprint.
 */
class HostKeyStore private constructor(context: Context) {

    data class StoredKey(val type: String, val blob: ByteArray) {
        val fingerprint: String get() = fingerprintOf(blob)
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences("pt_host_keys", Context.MODE_PRIVATE)

    @Synchronized
    fun get(host: String, port: Int): StoredKey? {
        val raw = prefs.getString(keyOf(host, port), null) ?: return null
        val parts = raw.split(' ', limit = 2)
        if (parts.size != 2) return null
        return try {
            StoredKey(parts[0], Base64.decode(parts[1], Base64.NO_WRAP))
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    @Synchronized
    fun put(host: String, port: Int, blob: ByteArray) {
        val type = keyTypeOf(blob)
        prefs.edit()
            .putString(keyOf(host, port), type + " " + Base64.encodeToString(blob, Base64.NO_WRAP))
            .commit()
    }

    @Synchronized
    fun remove(host: String, port: Int) {
        prefs.edit().remove(keyOf(host, port)).commit()
    }

    companion object {
        @Volatile
        private var instance: HostKeyStore? = null

        fun get(context: Context): HostKeyStore =
            instance ?: synchronized(this) {
                instance ?: HostKeyStore(context.applicationContext).also { instance = it }
            }

        fun normalizeHost(host: String): String = host.trim().lowercase(Locale.ROOT)

        private fun keyOf(host: String, port: Int) = normalizeHost(host) + ":" + port

        /** OpenSSH-kompatibler Fingerprint: "SHA256:<base64 ohne Padding>". */
        fun fingerprintOf(blob: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(blob)
            return "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING)
        }

        /** Schlüsseltyp aus dem SSH-Wire-Format lesen (erster String im Blob, z. B. "ssh-ed25519"). */
        fun keyTypeOf(blob: ByteArray): String = try {
            val buffer = ByteBuffer.wrap(blob)
            val length = buffer.int
            if (length <= 0 || length > 64 || length > blob.size - 4) {
                "unbekannt"
            } else {
                String(blob, 4, length, StandardCharsets.US_ASCII)
            }
        } catch (e: Exception) {
            "unbekannt"
        }
    }
}
