package moe.shizuku.manager.worker

import org.junit.Assert.*
import org.junit.Test

class AdbStartPolicyTest {
    @Test fun userStopBlocksEverySource() {
        AdbStartPolicy.Source.entries.forEach {
            assertFalse(AdbStartPolicy.allowed(it, true, true, true, true))
        }
    }

    @Test fun manualStartWorksWithBootAndWatchdogDisabled() {
        assertTrue(AdbStartPolicy.allowed(AdbStartPolicy.Source.MANUAL, false, false, false, false))
    }

    @Test fun queuedAutomaticRequestsRespectChangedModeAndToggles() {
        assertFalse(AdbStartPolicy.allowed(AdbStartPolicy.Source.BOOT, false, true, false, true))
        assertFalse(AdbStartPolicy.allowed(AdbStartPolicy.Source.BOOT, false, false, true, true))
        assertFalse(AdbStartPolicy.allowed(AdbStartPolicy.Source.WATCHDOG, false, true, true, false))
        assertTrue(AdbStartPolicy.allowed(AdbStartPolicy.Source.WATCHDOG, false, true, false, true))
    }
}
