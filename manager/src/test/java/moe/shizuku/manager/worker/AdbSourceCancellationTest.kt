package moe.shizuku.manager.worker

import android.app.Application
import kotlinx.coroutines.runBlocking
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbLaunchCoordinator
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AdbSourceCancellationTest {
    @Before fun setup() {
        ShizukuSettings.initialize(RuntimeEnvironment.getApplication())
        ShizukuSettings.getPreferences().edit().clear().commit()
    }

    @Test fun cancelledSourceCannotResumeAfterReenableAndManualRevisionSurvives() = runBlocking {
        for (source in listOf(AdbStartPolicy.Source.BOOT, AdbStartPolicy.Source.WATCHDOG)) {
            val oldEpoch = AdbStartWorker.sourceRevision(source)
            val manualEpoch = AdbStartWorker.sourceRevision(AdbStartPolicy.Source.MANUAL)
            AdbStartWorker.cancelSource(RuntimeEnvironment.getApplication(), source)
            assertEquals(oldEpoch + 1, AdbStartWorker.sourceRevision(source))
            assertEquals(manualEpoch, AdbStartWorker.sourceRevision(AdbStartPolicy.Source.MANUAL))
            // The switch may already be back on, but the old request must still fail its guard.
            var dispatched = false
            val coordinator = AdbLaunchCoordinator({}, { true }, { false })
            try {
                coordinator.launch(false, { check(oldEpoch == AdbStartWorker.sourceRevision(source)) }) {
                    dispatched = true
                    it()
                }
                fail("Old source request must not dispatch")
            } catch (_: IllegalStateException) { }
            assertFalse(dispatched)
            coordinator.launch(false, { check(manualEpoch == AdbStartWorker.sourceRevision(AdbStartPolicy.Source.MANUAL)) }) { it() }
        }
    }
}
