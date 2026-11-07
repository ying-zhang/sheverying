package moe.shizuku.manager.adb

import java.io.IOException
import java.io.EOFException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

/** Connection failure before any starter command was sent. */
internal class AdbEndpointException(val port: Int, val kind: Kind, cause: Exception) : IOException(
    "ADB endpoint $port: $kind", cause) {
    enum class Kind { PROTOCOL, TRANSIENT, AUTH_PENDING, TLS_AUTH }
}

internal class AdbProtocolException(message: String) : IOException(message)

internal enum class AdbConnectPhase { CONNECT, HELLO, AUTH, TLS }

internal fun classifyAdbFailure(phase: AdbConnectPhase, cause: Exception): AdbEndpointException.Kind = when {
    cause is AdbProtocolException -> AdbEndpointException.Kind.PROTOCOL
    phase == AdbConnectPhase.TLS && cause is SSLHandshakeException -> AdbEndpointException.Kind.TLS_AUTH
    (phase == AdbConnectPhase.AUTH || phase == AdbConnectPhase.TLS) &&
        (cause is SocketTimeoutException || cause is EOFException) -> AdbEndpointException.Kind.AUTH_PENDING
    else -> AdbEndpointException.Kind.TRANSIENT
}
