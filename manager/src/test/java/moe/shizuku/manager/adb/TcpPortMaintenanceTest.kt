package moe.shizuku.manager.adb

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TcpPortMaintenanceTest {
    @Test fun shellSkipsRestrictedWriteAndStillRestoresListener() = runBlocking {
        var live = true
        var writes = 0
        var restores = 0
        val maintenance = TcpPortMaintenance({ "" }, { writes++ }, { 2000 }, { live },
            { restores++; live = true }, {})
        maintenance.ensure(5555)
        live = false
        maintenance.ensure(5555)
        assertEquals(0, writes)
        assertEquals(1, restores)
    }

    @Test fun failedReadbackIsNotRetriedContinuously() = runBlocking {
        var writes = 0
        val messages = mutableListOf<String>()
        val maintenance = TcpPortMaintenance({ "" }, { writes++ }, { 0 }, { true }, {},
            { messages.add(it) })
        maintenance.ensure(5555)
        maintenance.ensure(5555)
        assertEquals(1, writes)
        assertTrue(messages.single().contains("differs"))
    }

    @Test fun existingPersistentPortNeedsNoWrite() = runBlocking {
        TcpPortMaintenance({ "5555" }, { error("unexpected write") }, { 2000 }, { true },
            { error("unexpected recovery") }, {}).ensure(5555)
    }

    @Test fun deniedPropertyDoesNotPreventRecovery() = runBlocking {
        var live = false
        TcpPortMaintenance({ "" }, { throw SecurityException() }, { 0 }, { live },
            { live = true }, {}).ensure(5555)
        assertTrue(live)
    }

    @Test fun recoveryMustActuallyRestoreListener() = runBlocking {
        try {
            TcpPortMaintenance({ "" }, {}, { 2000 }, { false }, {}, {}).ensure(5555)
            fail("missing listener must be reported")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("still unavailable"))
        }
    }
}
