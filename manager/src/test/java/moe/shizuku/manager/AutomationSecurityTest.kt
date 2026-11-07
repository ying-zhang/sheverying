package moe.shizuku.manager

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.hamondev.shevery.tasker.PluginContract
import com.hamondev.shevery.tasker.PluginReceiver
import com.hamondev.shevery.tasker.PluginSecurity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBroadcastPendingResult
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AutomationSecurityTest {
    private lateinit var app: Application
    private fun prefs() = app.createDeviceProtectedStorageContext().getSharedPreferences("settings", Context.MODE_PRIVATE)

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        prefs().edit().clear().commit()
    }

    private fun controlCount() = shadowOf(app).broadcastIntents.count {
        it.component?.className == PluginContract.MANAGER_CONTROL_RECEIVER
    }
    private fun direct(token: String?) = Intent(PluginContract.ACTION_DIRECT_START)
        .putExtra(PluginContract.EXTRA_AUTH, token)

    private var rejectionReason: String? = null

    private fun receive(intent: Intent): Int {
        val receiver = PluginReceiver()
        val pending = ReflectionHelpers.callStaticMethod<BroadcastReceiver.PendingResult>(
            ShadowBroadcastPendingResult::class.java, "create",
            ClassParameter.from(Int::class.javaPrimitiveType, 0),
            ClassParameter.from(String::class.java, null),
            ClassParameter.from(Bundle::class.java, null),
            ClassParameter.from(Boolean::class.javaPrimitiveType, true))
        ReflectionHelpers.callInstanceMethod<Unit>(receiver, "setPendingResult",
            ClassParameter.from(BroadcastReceiver.PendingResult::class.java, pending))
        receiver.onReceive(app, intent)
        rejectionReason = pending.getResultExtras(false)?.getString(PluginContract.EXTRA_REJECTION_REASON)
        return pending.resultCode
    }

    @Test fun tokenUsesStableBuildTokenAndIgnoresLegacyPreference() {
        prefs().edit().putString(PluginContract.KEY_AUTH_TOKEN, "obsolete-random-token").commit()
        val token = PluginSecurity.getOrCreateToken(app)
        assertEquals(32, token.length)
        assertTrue(token.matches(Regex("[0-9a-f]{32}")))
        assertEquals(token, PluginSecurity.getOrCreateToken(app))
        assertTrue(PluginSecurity.accepts(token, token))
        assertFalse(PluginSecurity.accepts(token, null))
        assertFalse(PluginSecurity.accepts(token, "different-token"))
    }

    @Test fun connectorDefaultsOffEvenWithValidToken() {
        receive(direct(PluginSecurity.getOrCreateToken(app)))
        assertEquals(PluginContract.REJECTION_CONNECTOR_DISABLED, rejectionReason)
        assertEquals(0, controlCount())
    }

    @Test fun directBroadcastRequiresBuiltinToken() {
        prefs().edit().putBoolean(PluginContract.KEY_CONNECTOR_ENABLED, true).commit()
        receive(direct(null))
        assertEquals(PluginContract.REJECTION_TOKEN_MISSING, rejectionReason)
        receive(direct("wrong-token"))
        assertEquals(PluginContract.REJECTION_TOKEN_INVALID, rejectionReason)
        assertEquals(0, controlCount())
        receive(direct(PluginSecurity.getOrCreateToken(app)))
        assertNull(rejectionReason)
        assertEquals(1, controlCount())
    }

    @Test fun explicitTaskerFireAlsoRequiresSavedToken() {
        prefs().edit().putBoolean(PluginContract.KEY_CONNECTOR_ENABLED, true).commit()
        val bundle = Bundle().apply { putString(PluginContract.EXTRA_STRING_JSON, """{"command":"start"}""") }
        val intent = Intent(PluginContract.ACTION_FIRE_SETTING)
            .setComponent(ComponentName(app.packageName, PluginReceiver::class.java.name))
            .putExtra(PluginContract.EXTRA_BUNDLE, bundle)
        receive(intent)
        assertEquals(0, controlCount())
        bundle.putString(PluginContract.EXTRA_AUTH, PluginSecurity.getOrCreateToken(app))
        intent.putExtra(PluginContract.EXTRA_BUNDLE, bundle)
        receive(intent)
        assertEquals(1, controlCount())
    }

    @Test fun taskerQueryWithoutTokenFailsClosed() {
        prefs().edit().putBoolean(PluginContract.KEY_CONNECTOR_ENABLED, true).commit()
        assertEquals(PluginContract.RESULT_CONDITION_UNKNOWN, receive(Intent(PluginContract.ACTION_QUERY_CONDITION)
            .setComponent(ComponentName(app.packageName, PluginReceiver::class.java.name))))
        assertEquals(0, controlCount())
    }
}
