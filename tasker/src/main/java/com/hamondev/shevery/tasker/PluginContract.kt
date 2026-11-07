package com.hamondev.shevery.tasker

import androidx.annotation.StringRes

object PluginContract {

    const val ACTION_EDIT_SETTING = "com.twofortyfouram.locale.intent.action.EDIT_SETTING"
    const val ACTION_EDIT_CONDITION = "com.twofortyfouram.locale.intent.action.EDIT_CONDITION"
    const val ACTION_FIRE_SETTING = "com.twofortyfouram.locale.intent.action.FIRE_SETTING"
    const val ACTION_QUERY_CONDITION = "com.twofortyfouram.locale.intent.action.QUERY_CONDITION"

    const val EXTRA_STRING_BLURB = "com.twofortyfouram.locale.intent.extra.BLURB"
    const val EXTRA_BUNDLE = "com.twofortyfouram.locale.intent.extra.BUNDLE"
    const val EXTRA_STRING_JSON = "com.twofortyfouram.locale.extra.STRING"

    const val RESULT_CONDITION_SATISFIED = 16
    const val RESULT_CONDITION_UNSATISFIED = 17
    const val RESULT_CONDITION_UNKNOWN = 18

    const val KEY_COMMAND = "command"
    const val KEY_CONDITION = "condition"
    const val KEY_CONNECTOR_ENABLED = "shizuku_connector_enabled"
    // Legacy random-token preference is intentionally ignored; retained for migration diagnostics.
    const val KEY_AUTH_TOKEN = "auth_token"
    /** Generated once per source checkout and shared by all builds from it. */
    val BUILTIN_AUTH_TOKEN: String get() = GeneratedAutomationToken.VALUE
    const val EXTRA_AUTH = "auth"
    const val EXTRA_REJECTION_REASON = "cn.ying.sherverying.extra.REJECTION_REASON"
    const val REJECTION_CONNECTOR_DISABLED = "connector_disabled"
    const val REJECTION_TOKEN_MISSING = "token_missing"
    const val REJECTION_TOKEN_INVALID = "token_invalid"
    const val VALUE_CONDITION_RUNNING = "running"

    const val MANAGER_PACKAGE = "cn.ying.sherverying"
    const val PLUGIN_RECEIVER = "com.hamondev.shevery.tasker.PluginReceiver"
    const val MANAGER_CONTROL_RECEIVER = "moe.shizuku.manager.receiver.SheveryControlReceiver"
    const val ACTION_START_SERVER = "moe.shizuku.manager.action.START_SERVER"
    const val ACTION_STOP_SERVER = "moe.shizuku.manager.action.STOP_SERVER"

    const val ACTION_DIRECT_START = "cn.ying.sherverying.action.START_SERVER"
    const val ACTION_DIRECT_STOP = "cn.ying.sherverying.action.STOP_SERVER"
    const val ACTION_DIRECT_RESTART = "cn.ying.sherverying.action.RESTART_SERVER"
    const val ACTION_DIRECT_TOGGLE = "cn.ying.sherverying.action.TOGGLE_SERVER"

    const val CHANNEL_ID_ALERTS = "automation_alerts"
    const val NOTIFICATION_ID_AUTH = 1450
    const val NOTIFICATION_ID_CONNECTOR = 1451

    const val EXTRA_TARGET_SECTION = "target_section"
    const val TARGET_SECTION_AUTOMATION = "automation"
}

enum class Command(
    val value: String,
    @StringRes val labelRes: Int,
    @StringRes val blurbRes: Int
) {
    START("start", R.string.tasker_command_start, R.string.tasker_blurb_start),
    STOP("stop", R.string.tasker_command_stop, R.string.tasker_blurb_stop),
    RESTART("restart", R.string.tasker_command_restart, R.string.tasker_blurb_restart),
    TOGGLE("toggle", R.string.tasker_command_toggle, R.string.tasker_blurb_toggle);

    companion object {
        fun from(value: String?): Command? = entries.firstOrNull { it.value == value }
    }
}
