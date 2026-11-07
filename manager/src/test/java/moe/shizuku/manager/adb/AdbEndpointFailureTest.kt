package moe.shizuku.manager.adb

import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.*
import org.junit.Test

class AdbEndpointFailureTest {
    @Test fun authorizationTimeoutDoesNotExcludeTheRealEndpoint() {
        val kind = classifyAdbFailure(AdbConnectPhase.AUTH, SocketTimeoutException("waiting for approval"))
        assertEquals(AdbEndpointException.Kind.AUTH_PENDING, kind)
        val excluded = AdbCandidateExclusions { 0L }
        excluded.record(AdbEndpointException(40269, kind, SocketTimeoutException()))
        assertFalse(40269 in excluded)
    }

    @Test fun tlsRejectionIsNotMisreportedAsUserApprovalWait() {
        assertEquals(AdbEndpointException.Kind.TLS_AUTH,
            classifyAdbFailure(AdbConnectPhase.TLS, SSLHandshakeException("bad certificate")))
    }

    @Test fun transientPortsBecomeCandidatesAgainButBadProtocolStaysExcluded() {
        var time = 0L
        val excluded = AdbCandidateExclusions { time }
        excluded.record(AdbEndpointException(5555,
            classifyAdbFailure(AdbConnectPhase.CONNECT, ConnectException()), ConnectException()))
        excluded.record(AdbEndpointException(1234, AdbEndpointException.Kind.PROTOCOL, AdbProtocolException("HTTP")))
        assertEquals(setOf(5555, 1234), excluded.toSet())
        time = 2_001L
        assertFalse(5555 in excluded)
        assertEquals(setOf(1234), excluded.toSet())
    }
}
