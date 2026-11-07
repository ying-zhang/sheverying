package moe.shizuku.manager.worker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbRecoveryPolicyTest {
    @Test fun listenerAppearingAfterEnqueueRemovesWifiRequirementAndDisappearanceRestoresIt() {
        assertTrue(AdbRecoveryPolicy.needsWifiLink(false, false))
        assertFalse(AdbRecoveryPolicy.needsWifiLink(false, true))
        assertTrue(AdbRecoveryPolicy.needsWifiLink(false, false))
    }

    @Test fun televisionRecoveryDoesNotRequireWifiTransport() {
        assertFalse(AdbRecoveryPolicy.needsWifiLink(true, false))
        assertFalse(AdbRecoveryPolicy.needsWifiLink(true, true))
    }

    @Test fun readinessOnlyAdvancesDelayedWorkAndDoesNotInterruptDiscovery() {
        assertFalse(AdbRecoveryPolicy.shouldAdvance(true, true, emptyList()))
        assertFalse(AdbRecoveryPolicy.shouldAdvance(true, true,
            listOf(AdbRecoveryPolicy.Attempt(false, 0, null))))
        assertTrue(AdbRecoveryPolicy.shouldAdvance(true, true,
            listOf(AdbRecoveryPolicy.Attempt(false, 1, null))))
        assertFalse(AdbRecoveryPolicy.shouldAdvance(true, true,
            listOf(AdbRecoveryPolicy.Attempt(true, 1, null))))
        assertTrue(AdbRecoveryPolicy.shouldAdvance(true, false, emptyList()))
    }

    @Test fun connectivityWakeAdvancesDelayedRetryWithoutReplacingFreshOrRunningWork() {
        assertTrue(AdbRecoveryPolicy.shouldAdvance(true, true,
            listOf(AdbRecoveryPolicy.Attempt(false, 3, "v61:boot12"))))
        assertFalse(AdbRecoveryPolicy.shouldAdvance(true, true,
            listOf(AdbRecoveryPolicy.Attempt(false, 0, "v61:boot12"))))
        assertFalse(AdbRecoveryPolicy.shouldAdvance(true, true,
            listOf(AdbRecoveryPolicy.Attempt(true, 3, "v61:boot12"))))
    }
    @Test fun clearsPreviousBootAndPreviousVersionBackoff() {
        val current = "v61:boot12"
        for (old in listOf("v61:boot11", "v60:boot12", null)) {
            assertTrue(AdbRecoveryPolicy.shouldResetBackoff(current,
                listOf(AdbRecoveryPolicy.Attempt(false, 5, old))))
        }
    }

    @Test fun repeatedTriggersKeepRetryFromCurrentBoot() {
        val current = "v61:boot12"
        repeat(99) {
            assertFalse(AdbRecoveryPolicy.shouldResetBackoff(current,
                listOf(AdbRecoveryPolicy.Attempt(false, 1, current))))
        }
    }

    @Test fun neverInterruptsAnAlreadyRunningDiscovery() {
        assertFalse(AdbRecoveryPolicy.shouldResetBackoff("v61:boot12",
            listOf(AdbRecoveryPolicy.Attempt(true, 5, "v60:boot11"))))
    }

    @Test fun doesNotReplaceFreshWorkOrEmptyQueue() {
        assertFalse(AdbRecoveryPolicy.shouldResetBackoff("v61:boot12", emptyList()))
        assertFalse(AdbRecoveryPolicy.shouldResetBackoff("v61:boot12",
            listOf(AdbRecoveryPolicy.Attempt(false, 0, "v60:boot11"))))
    }
}
