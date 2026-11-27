package org.exponio.app.transfer

/** Fehler mit stabilem Code für die Web-UI und einer verständlichen deutschen Meldung. */
class TransferException(
    val code: String,
    message: String,
    val details: Map<String, Any?> = emptyMap(),
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Codes, die die App selbst vergibt. Fehler der Website (z. B. VERIFY_FAILED, FILE_TOO_LARGE, STORAGE_UNAVAILABLE)
 * kommen mit ihrem eigenen Code unverändert durch, siehe App\Import\UploadInboxException in exponio_website.
 */
object ErrorCode {
    const val NOT_CONFIGURED = "NOT_CONFIGURED"
    const val NO_CREDENTIALS = "NO_CREDENTIALS"
    const val TOKEN_INVALID = "TOKEN_INVALID"
    const val INSECURE_URL = "INSECURE_URL"
    const val HOST_NOT_FOUND = "HOST_NOT_FOUND"
    const val UNREACHABLE = "UNREACHABLE"
    const val TIMEOUT = "TIMEOUT"
    const val TLS_ERROR = "TLS_ERROR"
    const val TLS_CERT_INVALID = "TLS_CERT_INVALID"
    const val CONNECTION_LOST = "CONNECTION_LOST"
    const val AUTH_FAILED = "AUTH_FAILED"
    const val REDIRECT = "REDIRECT"
    const val NOT_EXPONIO = "NOT_EXPONIO"
    const val SERVER_ERROR = "SERVER_ERROR"
    const val UPLOAD_NOT_FOUND = "UPLOAD_NOT_FOUND"
    const val VERIFY_FAILED = "VERIFY_FAILED"
    const val PROCESSING_FAILED = "PROCESSING_FAILED"
    const val PHOTO_MISSING = "PHOTO_MISSING"
    const val NOTHING_TO_UPLOAD = "NOTHING_TO_UPLOAD"
    const val CANCELLED = "CANCELLED"
    const val BUSY = "BUSY"
    const val UNKNOWN = "UNKNOWN"
}
