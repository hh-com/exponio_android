package org.exponio.app.api

import org.exponio.app.transfer.ErrorCode
import org.exponio.app.transfer.TransferException
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * Adresse der Exponio-Website, z. B. https://exponio.example.org (auch mit Unterordner).
 * Wohin die Fotos auf dem Server kommen, entscheidet die Website – die App kennt nur diese Adresse und das Token.
 */
data class ServerConfig(val baseUrl: String) {

    val host: String get() = URI(baseUrl).host

    companion object {
        /**
         * Normalisiert eine eingetippte oder eingefügte Adresse: ergänzt https://, entfernt Schrägstriche am Ende
         * und versehentlich mitkopierte Pfade (/admin…, /api/app/v1…). Unverschlüsseltes http:// wird abgelehnt,
         * weil sonst das Token im Klartext übertragen würde.
         */
        @Throws(TransferException::class)
        fun parse(raw: String?): ServerConfig {
            var text = raw.orEmpty().trim()
            if (text.isEmpty()) {
                throw TransferException(ErrorCode.NOT_CONFIGURED, "Die Adresse der Website fehlt.")
            }
            if (!text.contains("://")) text = "https://$text"
            val uri = try {
                URI(text)
            } catch (e: URISyntaxException) {
                throw TransferException(ErrorCode.NOT_CONFIGURED, "„$raw“ ist keine gültige Adresse.", cause = e)
            }
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            if (scheme == "http") {
                throw TransferException(
                    ErrorCode.INSECURE_URL,
                    "Nur verschlüsselte Adressen (https://) sind erlaubt – sonst ginge das Token im Klartext übers Netz.",
                )
            }
            val host = uri.host?.lowercase(Locale.ROOT)
            if (scheme != "https" || host.isNullOrEmpty() || uri.userInfo != null) {
                throw TransferException(ErrorCode.NOT_CONFIGURED, "„$raw“ ist keine gültige Website-Adresse.")
            }
            var segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
            segments.indexOf("admin").takeIf { it >= 0 }?.let { segments = segments.subList(0, it) }
            (0 until segments.size - 1).firstOrNull { segments[it] == "api" && segments[it + 1] == "app" }
                ?.let { segments = segments.subList(0, it) }
            val path = segments.joinToString("") { "/$it" }
            val port = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
            return ServerConfig("https://$host$port$path")
        }
    }
}
