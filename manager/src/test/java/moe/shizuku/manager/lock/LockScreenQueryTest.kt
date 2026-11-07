package moe.shizuku.manager.lock

import org.junit.Assert.*
import org.junit.Test

class LockScreenQueryTest {
    @Test fun timeoutThenSuccessfulReadIsRetriedWithoutChangingSettings() {
        var reads = 0
        val delays = mutableListOf<Long>()
        val logs = mutableListOf<String>()
        val value = LockScreenQuery(
            read = { if (reads++ == 0) LockScreenCommandResult(null, "", 8_000, timedOut = true)
                else LockScreenCommandResult(0, "true\n", 25) },
            pause = { delays += it }, log = { logs += it },
        ).disabled()
        assertEquals(true, value)
        assertEquals(2, reads)
        assertEquals(listOf(1_000L), delays)
        assertTrue(logs.single().contains("durationMs=8000"))
    }

    @Test fun credentialOrPermissionFailureStopsImmediately() {
        var reads = 0
        val value = LockScreenQuery(
            read = { reads++; LockScreenCommandResult(1, "SecurityException: permission denied", 12) },
            pause = { error("permanent failure must not retry") }, log = {},
        ).disabled()
        assertNull(value)
        assertEquals(1, reads)
    }

    @Test fun repeatedTransientFailureStopsAfterThreeReads() {
        var reads = 0
        val value = LockScreenQuery(
            read = { reads++; LockScreenCommandResult(0, "", 2) }, pause = {}, log = {},
        ).disabled()
        assertNull(value)
        assertEquals(3, reads)
    }

    @Test fun diagnosticsDoNotTruncateTheValueBeforeParsing() {
        val output = "vendor diagnostic ".repeat(30) + "\nfalse\n"
        val result = LockScreenCommandResult(0, output, 4)
        assertEquals(false, result.value())
        assertTrue(result.diagnostic().length < output.length)
        assertFalse(LockScreenCommandResult(0, "unknown vendor response", 4).retryable())
    }
}
