package moe.shizuku.manager.module

import moe.shizuku.manager.ShizukuSettings

object ModuleSettings {

    private const val KEY_CONNECTOR_ENABLED = "shizuku_connector_enabled"
    private const val KEY_VERBOSE_LOGGING = "shizuku_verbose_logging"
    private const val KEY_NOTIFY_DEATH = "shizuku_notify_service_death"
    private const val KEY_NOTIFY_RECOVERY = "shizuku_notify_recovery"
    private const val KEY_ERROR_PROTECT = "shizuku_error_protect"
    // Legacy watchdog prefs from before the toggle consolidation (PR #186).
    private const val KEY_LEGACY_KEEP_ALIVE = "shizuku_keep_alive"
    private const val KEY_LEGACY_AUTO_RESTART = "shizuku_auto_restart_on_crash"
    private const val KEY_WIFI_REASSERT = "shizuku_wifi_adb_reassert"


    fun isConnectorEnabled(): Boolean {
        return ShizukuSettings.getPreferences().getBoolean(KEY_CONNECTOR_ENABLED, false)
    }

    fun setConnectorEnabled(value: Boolean) {
        ShizukuSettings.getPreferences().edit().putBoolean(KEY_CONNECTOR_ENABLED, value).apply()
    }

    fun isVerboseLogging(): Boolean {
        return ShizukuSettings.getPreferences().getBoolean(KEY_VERBOSE_LOGGING, false)
    }

    fun setVerboseLogging(value: Boolean) {
        ShizukuSettings.getPreferences().edit().putBoolean(KEY_VERBOSE_LOGGING, value).apply()
    }

    fun isNotifyOnServiceDeath(): Boolean {
        return ShizukuSettings.getPreferences().getBoolean(KEY_NOTIFY_DEATH, true)
    }

    fun setNotifyOnServiceDeath(value: Boolean) {
        ShizukuSettings.getPreferences().edit().putBoolean(KEY_NOTIFY_DEATH, value).apply()
    }

    fun isNotifyOnRecovery(): Boolean {
        return ShizukuSettings.getPreferences().getBoolean(KEY_NOTIFY_RECOVERY, false)
    }

    fun setNotifyOnRecovery(value: Boolean) {
        ShizukuSettings.getPreferences().edit().putBoolean(KEY_NOTIFY_RECOVERY, value).apply()
    }

    fun isWatchdogEnabled(): Boolean {
        return ShizukuSettings.getPreferences().getBoolean(KEY_ERROR_PROTECT, true)
    }

    fun setWatchdogEnabled(value: Boolean) {
        ShizukuSettings.getPreferences().edit().putBoolean(KEY_ERROR_PROTECT, value).apply()
        if (!value) moe.shizuku.manager.service.WatchdogManager.onWatchdogDisabled(moe.shizuku.manager.application)
    }

    // Maps pre-consolidation watchdog prefs (PR #186) into the single master toggle.
    // Users who had the legacy keep-alive or auto-restart prefs enabled but Watchdog
    // off would otherwise silently lose watchdog coverage after updating.

    fun migrateLegacyWatchdogPrefs() {
        val prefs = ShizukuSettings.getPreferences()
        if (prefs.getBoolean(KEY_ERROR_PROTECT, true)) return
        val legacyEnabled = prefs.getBoolean(KEY_LEGACY_KEEP_ALIVE, false) ||
            prefs.getBoolean(KEY_LEGACY_AUTO_RESTART, false)
        if (legacyEnabled) {
            prefs.edit().putBoolean(KEY_ERROR_PROTECT, true)
                .remove(KEY_LEGACY_KEEP_ALIVE)
                .remove(KEY_LEGACY_AUTO_RESTART)
                .apply()
        }
    }

    // On by default: ROMs that clear adb_wifi_enabled need the flag restored
    // during ADB start and after Wi-Fi reconnect. The write never sets 0.
    fun isWifiReassertEnabled(): Boolean {
        return ShizukuSettings.getPreferences().getBoolean(KEY_WIFI_REASSERT, true)
    }

    fun setWifiReassertEnabled(value: Boolean) {
        ShizukuSettings.getPreferences().edit().putBoolean(KEY_WIFI_REASSERT, value).apply()
    }

}
