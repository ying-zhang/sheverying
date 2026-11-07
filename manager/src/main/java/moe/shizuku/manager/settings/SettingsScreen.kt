@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package moe.shizuku.manager.settings

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import moe.shizuku.manager.about.AboutActivity
import android.os.Build
import android.text.TextUtils
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.Alignment
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import moe.shizuku.manager.ui.compose.LocalFloatingNavBarVisible
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.LANGUAGE
import moe.shizuku.manager.ShizukuSettings.NIGHT_MODE
import moe.shizuku.manager.app.ThemeHelper
import moe.shizuku.manager.app.ThemeHelper.KEY_BLACK_NIGHT_THEME
import moe.shizuku.manager.app.ThemeHelper.KEY_USE_SYSTEM_COLOR
import moe.shizuku.manager.ktx.setComponentEnabled
import androidx.compose.material3.TextField
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.module.ModuleSettings
import moe.shizuku.manager.receiver.BootCompleteReceiver
import moe.shizuku.manager.adb.AdbArm
import moe.shizuku.manager.adb.AdbStarter
import moe.shizuku.manager.service.WatchdogManager
import moe.shizuku.manager.starter.StarterActivity
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.ui.compose.GroupDivider
import moe.shizuku.manager.ui.compose.MonospaceLog
import moe.shizuku.manager.ui.compose.SectionHeader
import moe.shizuku.manager.ui.compose.SettingsGroup
import moe.shizuku.manager.ui.compose.SettingsRow
import moe.shizuku.manager.ui.compose.ShizukuLazyScaffold
import moe.shizuku.manager.ui.compose.SwitchSettingsRow
import moe.shizuku.manager.ui.compose.htmlToPlainText
import rikka.core.util.ResourceUtils
import rikka.core.util.ClipboardUtils
import rikka.material.app.LocaleDelegate
import rikka.shizuku.Shizuku
import java.util.Locale
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Lifecycle.State
import android.widget.Toast

enum class SettingsSection(
    @param:StringRes val titleRes: Int,
    @param:StringRes val summaryRes: Int,
    @param:DrawableRes val iconRes: Int,
) {
    APPEARANCE(
        R.string.settings_appearance,
        R.string.settings_section_appearance_summary,
        R.drawable.ic_outline_dark_mode_24
    ),
    APPLICATION(
        R.string.settings_application,
        R.string.settings_section_startup_summary,
        R.drawable.ic_settings_outline_24dp
    ),
    AUTOMATION(
        R.string.automation_settings_title,
        R.string.automation_section_summary,
        R.drawable.ic_outline_play_arrow_24
    ),
    EXTRAS(
        R.string.settings_extras_title,
        R.string.settings_section_extras_summary,
        R.drawable.ic_system_icon
    ),
    ABOUT(
        R.string.action_about,
        R.string.settings_section_about_summary,
        R.drawable.ic_outline_info_24
    ),
}

private sealed interface SettingsNav {
    data object Hub : SettingsNav
    data class Section(val section: SettingsSection) : SettingsNav
}


@Composable
fun SettingsScreen(
    listState: LazyListState = rememberLazyListState(),
    targetSection: SettingsSection? = null,
    onTargetSectionConsumed: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val activity = context as? Activity
    val packageManager = context.packageManager
    val componentName = ComponentName(context.packageName, BootCompleteReceiver::class.java.name)

    val prefs = ShizukuSettings.getPreferences()

    var startOnBoot by remember {
        mutableStateOf(ShizukuSettings.getStartOnBoot())
    }
    var rooted by remember { mutableStateOf(false) }
    var watchdog by remember {
        mutableStateOf(ModuleSettings.isWatchdogEnabled())
    }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Root check + one-time stale-pref cleanup on first composition
    LaunchedEffect(Unit) {
        rooted = withContext(Dispatchers.IO) { EnvironmentUtils.isRooted() }
        if (!rooted && ShizukuSettings.getStartOnBoot()) {
            withContext(Dispatchers.IO) {
                ShizukuSettings.setStartOnBoot(false)
                startOnBoot = false
                packageManager.setComponentEnabled(
                    componentName,
                    ShizukuSettings.getStartOnBoot() || ShizukuSettings.getStartOnBootAdb()
                )
            }
        }
    }

    // Re-check root and watchdog state whenever the activity is in the foreground
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            watchdog = ModuleSettings.isWatchdogEnabled()
            withContext(Dispatchers.IO) {
                rooted = EnvironmentUtils.isRooted()
            }
        }
    }
    var adbStartOnBoot by remember {
        mutableStateOf(ShizukuSettings.getStartOnBootAdb())
    }
    var notifyDeath by remember {
        mutableStateOf(ModuleSettings.isNotifyOnServiceDeath())
    }
    var wifiReassert by remember {
        mutableStateOf(ModuleSettings.isWifiReassertEnabled())
    }
    var keepUsbDebugging by remember {
        mutableStateOf(ShizukuSettings.isKeepUsbDebugging())
    }
    var disableLockScreen by remember {
        mutableStateOf(ShizukuSettings.isDisableLockScreen())
    }
    var autoPairWirelessDebug by remember {
        mutableStateOf(ShizukuSettings.isAutoPairWirelessDebug())
    }
    var showRebootConfirm by remember { mutableStateOf(false) }
    var tcpMode by remember {
        mutableStateOf(ShizukuSettings.isTcpMode())
    }
    var languageTag by remember {
        mutableStateOf(prefs.getString(LANGUAGE, "SYSTEM") ?: "SYSTEM")
    }
    var nightMode by remember {
        mutableIntStateOf(ShizukuSettings.getNightMode())
    }
    var blackNightTheme by remember {
        mutableStateOf(ThemeHelper.isBlackNightTheme(context))
    }
    var useSystemColor by remember {
        mutableStateOf(ThemeHelper.isUsingSystemColor())
    }
    var classicNav by remember {
        mutableStateOf(ShizukuSettings.isClassicNav())
    }
    var swipeBetweenTabs by remember {
        mutableStateOf(ShizukuSettings.isSwipeBetweenTabs())
    }
    var motionBlur by remember {
        mutableStateOf(ShizukuSettings.isMotionBlurEnabled())
    }
    var motionBlurIntensity by remember {
        mutableIntStateOf(ShizukuSettings.getMotionBlurIntensity())
    }
    var motionBlurStrength by remember {
        mutableIntStateOf(ShizukuSettings.getMotionBlurStrength())
    }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showNightDialog by remember { mutableStateOf(false) }
    var pendingTcpModeChange by remember { mutableStateOf<Boolean?>(null) }
    var showMissingPermissionDialog by remember { mutableStateOf(false) }
    var connectorEnabled by remember { mutableStateOf(ModuleSettings.isConnectorEnabled()) }
    val authToken = ShizukuSettings.getAuthToken()
    var wirelessDebugA11y by remember {
        mutableStateOf(moe.shizuku.manager.accessibility.WirelessDebugAccess.isEnabled())
    }
    var notifyRecovery by remember { mutableStateOf(ModuleSettings.isNotifyOnRecovery()) }
    var showUnsafeDialog by remember { mutableStateOf(false) }
    var recreateTick by remember { mutableIntStateOf(0) }
    var nav by remember {
        mutableStateOf<SettingsNav>(
            if (targetSection != null) SettingsNav.Section(targetSection) else SettingsNav.Hub
        )
    }

    LaunchedEffect(targetSection) {
        if (targetSection != null) {
            nav = SettingsNav.Section(targetSection)
            onTargetSectionConsumed?.invoke()
        }
    }

    fun tcpModeNeedsRestart(enabled: Boolean): Boolean {
        val currentPort = EnvironmentUtils.getActiveAdbPort()
        return Shizuku.pingBinder() && currentPort > 0 && when {
            enabled -> currentPort != AdbStarter.TCP_MODE_PORT
            else -> currentPort == AdbStarter.TCP_MODE_PORT
        }
    }

    fun restartAdbForTcpMode() {
        val port = EnvironmentUtils.getActiveAdbPort().takeIf { it > 0 } ?: return
        WatchdogManager.clearUserStopRequest(context)
        activity?.startActivity(
            Intent(context, StarterActivity::class.java).apply {
                putExtra(StarterActivity.EXTRA_IS_ROOT, false)
                putExtra(StarterActivity.EXTRA_HOST, "127.0.0.1")
                putExtra(StarterActivity.EXTRA_PORT, port)
            }
        )
    }

    fun applyTcpMode(enabled: Boolean, restart: Boolean = false) {
        ShizukuSettings.setTcpMode(enabled)
        tcpMode = ShizukuSettings.isTcpMode()
        if (restart && enabled) {
            restartAdbForTcpMode()
        }
    }

    val scope = rememberCoroutineScope()

    val localeOptions = remember(languageTag) {
        buildLocaleOptions(context)
    }
    val languageSummary = localeOptions.firstOrNull { it.tag == languageTag }
        ?.let { it.summary ?: it.title }
        ?: stringResource(rikka.core.R.string.follow_system)
    val nightValues = context.resources.getIntArray(R.array.night_mode_value).toList()
    val nightLabels = stringArrayResource(R.array.night_mode).toList()
    val nightSummary = when (nightMode) {
        AppCompatDelegate.MODE_NIGHT_NO -> nightLabels.getOrElse(0) {
            stringResource(rikka.core.R.string.follow_system)
        }
        AppCompatDelegate.MODE_NIGHT_YES -> nightLabels.getOrElse(1) {
            stringResource(rikka.core.R.string.follow_system)
        }
        else -> nightLabels.getOrElse(2) {
            stringResource(rikka.core.R.string.follow_system)
        }
    }
    LaunchedEffect(recreateTick) {
        if (recreateTick > 0) {
            delay(260)
            activity?.recreate()
        }
    }

    AnimatedContent(
        targetState = nav,
        transitionSpec = {
            val forward = when {
                targetState is SettingsNav.Hub -> false
                initialState is SettingsNav.Hub -> true
                else -> true
            }
            if (forward) {
                (slideInHorizontally(animationSpec = tween(300)) { fullWidth -> fullWidth } +
                    fadeIn(animationSpec = tween(300)))
                    .togetherWith(
                        slideOutHorizontally(animationSpec = tween(300)) { fullWidth -> -fullWidth / 4 } +
                            fadeOut(animationSpec = tween(300))
                    )
            } else {
                (slideInHorizontally(animationSpec = tween(300)) { fullWidth -> -fullWidth / 4 } +
                    fadeIn(animationSpec = tween(300)))
                    .togetherWith(
                        slideOutHorizontally(animationSpec = tween(300)) { fullWidth -> fullWidth } +
                            fadeOut(animationSpec = tween(300))
                    )
            }
        },
        label = "settings-nav"
    ) { current ->
        when (current) {
            is SettingsNav.Section -> {
                BackHandler { nav = SettingsNav.Hub }
                val sectionListState = remember(current.section) { LazyListState() }
                ShizukuLazyScaffold(
                    title = stringResource(current.section.titleRes),
                    onNavigateUp = { nav = SettingsNav.Hub },
                    bottomInset = 112.dp,
                    listState = sectionListState
                ) {
                    when (current.section) {
                        SettingsSection.APPLICATION -> applicationSectionContent(
                            rooted = rooted,
                            startOnBoot = startOnBoot,
                            adbStartOnBoot = adbStartOnBoot,
                            tcpMode = tcpMode,
                            watchdog = watchdog,
                            notifyDeath = notifyDeath,
                            wifiReassert = wifiReassert,
                            keepUsbDebugging = keepUsbDebugging,
                            disableLockScreen = disableLockScreen,
                            autoPairWirelessDebug = autoPairWirelessDebug,
                            onStartOnBootChange = { enabled ->
                                ShizukuSettings.setStartOnBoot(enabled)
                                startOnBoot = ShizukuSettings.getStartOnBoot()
                                packageManager.setComponentEnabled(
                                    componentName,
                                    ShizukuSettings.getStartOnBoot() || ShizukuSettings.getStartOnBootAdb()
                                )
                                if (enabled) {
                                    EnvironmentUtils.requestIgnoreBatteryOptimizations(context)
                                }
                            },
                            onAdbStartOnBootChange = { enabled ->
                                if (enabled) {
                                    val hasPermission = AdbArm.canArm(context)
                                    if (hasPermission) {
                                        ShizukuSettings.setStartOnBootAdb(true)
                                        adbStartOnBoot = true
                                        packageManager.setComponentEnabled(
                                            componentName,
                                            ShizukuSettings.getStartOnBoot() || ShizukuSettings.getStartOnBootAdb()
                                        )
                                        if (!tcpMode) {
                                            Toast.makeText(
                                                context,
                                                R.string.settings_start_on_boot_adb_warning_no_tcp,
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    } else {
                                        showMissingPermissionDialog = true
                                    }
                                } else {
                                    ShizukuSettings.setStartOnBootAdb(false)
                                    moe.shizuku.manager.worker.AdbStartWorker.cancelPending(context, automaticOnly = true)
                                    adbStartOnBoot = false
                                    packageManager.setComponentEnabled(
                                        componentName,
                                        ShizukuSettings.getStartOnBoot() || ShizukuSettings.getStartOnBootAdb()
                                    )
                                }
                            },
                            onWatchdogChange = { enabled ->
                                ModuleSettings.setWatchdogEnabled(enabled)
                                watchdog = ModuleSettings.isWatchdogEnabled()
                                moe.shizuku.manager.service.WatchdogManager.reconcileService(context)
                            },
                            onNotifyDeathChange = { enabled ->
                                ModuleSettings.setNotifyOnServiceDeath(enabled)
                                notifyDeath = ModuleSettings.isNotifyOnServiceDeath()
                            },
                            onWifiReassertChange = { enabled ->
                                ModuleSettings.setWifiReassertEnabled(enabled)
                                wifiReassert = ModuleSettings.isWifiReassertEnabled()
                            },
                            onKeepUsbDebuggingChange = { enabled ->
                                ShizukuSettings.setKeepUsbDebugging(enabled)
                                keepUsbDebugging = enabled
                                if (enabled) {
                                    moe.shizuku.manager.adb.UsbDebugKeep.reassert(context)
                                }
                            },
                            onAutoPairWirelessDebugChange = { enabled ->
                                ShizukuSettings.setAutoPairWirelessDebug(enabled)
                                autoPairWirelessDebug = enabled
                            },
                            onDisableLockScreenChange = { enabled ->
                                ShizukuSettings.setDisableLockScreen(enabled)
                                disableLockScreen = enabled
                                moe.shizuku.manager.lock.LockScreenKeep.apply { outcome ->
                                    val message = when (outcome) {
                                        moe.shizuku.manager.lock.LockScreenKeep.Outcome.APPLIED ->
                                            if (enabled) R.string.settings_lock_screen_hidden
                                            else R.string.settings_lock_screen_restored
                                        moe.shizuku.manager.lock.LockScreenKeep.Outcome.SERVICE_DOWN ->
                                            R.string.settings_lock_screen_pending
                                        moe.shizuku.manager.lock.LockScreenKeep.Outcome.FAILED ->
                                            R.string.settings_lock_screen_failed
                                    }
                                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                }
                            },
                            onTcpModeChange = { enabled ->
                                if (tcpModeNeedsRestart(enabled)) {
                                    pendingTcpModeChange = enabled
                                } else {
                                    applyTcpMode(enabled)
                                }
                            }
                        )
                        SettingsSection.APPEARANCE -> appearanceSectionContent(
                            languageSummary = languageSummary,
                            nightSummary = nightSummary,
                            nightMode = nightMode,
                            blackNightTheme = blackNightTheme,
                            useSystemColor = useSystemColor,
                            classicNav = classicNav,
                            onLanguageClick = { showLanguageDialog = true },
                            onNightClick = { showNightDialog = true },
                            onBlackNightChange = { enabled ->
                                prefs.edit().putBoolean(KEY_BLACK_NIGHT_THEME, enabled).apply()
                                blackNightTheme = enabled
                                if (ResourceUtils.isNightMode(configuration)) {
                                    recreateTick++
                                }
                            },
                            onUseSystemColorChange = { enabled ->
                                prefs.edit().putBoolean(KEY_USE_SYSTEM_COLOR, enabled).apply()
                                useSystemColor = enabled
                                recreateTick++
                            },
                            onClassicNavChange = { enabled ->
                                ShizukuSettings.setClassicNav(enabled)
                                classicNav = enabled
                            },
                            swipeBetweenTabs = swipeBetweenTabs,
                            motionBlur = motionBlur,
                            motionBlurIntensity = motionBlurIntensity,
                            motionBlurStrength = motionBlurStrength,
                            onSwipeBetweenTabsChange = { enabled ->
                                ShizukuSettings.setSwipeBetweenTabs(enabled)
                                swipeBetweenTabs = enabled
                            },
                            onMotionBlurChange = { enabled ->
                                ShizukuSettings.setMotionBlurEnabled(enabled)
                                motionBlur = enabled
                            },
                            onMotionBlurIntensityChange = { value ->
                                ShizukuSettings.setMotionBlurIntensity(value)
                                motionBlurIntensity = value
                            },
                            onMotionBlurStrengthChange = { value ->
                                ShizukuSettings.setMotionBlurStrength(value)
                                motionBlurStrength = value
                            }
                        )
                        SettingsSection.AUTOMATION -> automationSectionContent(
                            context = context,
                            connectorEnabled = connectorEnabled,
                            authToken = authToken,
                            onConnectorToggle = { enabled ->
                                if (enabled) {
                                    showUnsafeDialog = true
                                } else {
                                    connectorEnabled = false
                                    ModuleSettings.setConnectorEnabled(false)
                                }
                            },
                            onCopy = { text ->
                                ClipboardUtils.put(context, text)
                                Toast.makeText(context, R.string.automation_copied_to_clipboard, Toast.LENGTH_SHORT).show()
                            }
                        )
                        SettingsSection.EXTRAS -> extrasSectionContent(
                            context = context,
                            notifyRecovery = notifyRecovery,
                            onNotifyRecoveryChange = { value ->
                                notifyRecovery = value
                                ModuleSettings.setNotifyOnRecovery(value)
                            },
                            wirelessDebugA11y = wirelessDebugA11y,
                            onWirelessDebugA11yChange = { enabled ->
                                wirelessDebugA11y = enabled
                                moe.shizuku.manager.accessibility.WirelessDebugAccess.setEnabled(context, enabled)
                                if (enabled && !moe.shizuku.manager.accessibility.AccessibilityManager.isServiceEnabled(
                                        context,
                                        moe.shizuku.manager.accessibility.WirelessDebugAccess.serviceId(context)
                                    )
                                ) {
                                    runCatching {
                                        context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                    }
                                }
                            },
                            onRestartService = {
                                WatchdogManager.attemptRestart(context)
                                Toast.makeText(context, R.string.lab_restart_service_title, Toast.LENGTH_SHORT).show()
                            },
                            onStopService = {
                                val activity = context as? androidx.fragment.app.FragmentActivity
                                if (activity != null) {
                                    activity.lifecycleScope.launch {
                                        moe.shizuku.manager.service.WatchdogManager.stopServerAndWait(context, userInitiated = true)
                                        Toast.makeText(context, R.string.service_already_stopped, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                        SettingsSection.ABOUT -> aboutSectionContent(
                            context = context,
                            onOpenAbout = {
                                context.startActivity(Intent(context, AboutActivity::class.java))
                            }
                        )
                    }
                }
            }
            SettingsNav.Hub -> {
                ShizukuLazyScaffold(
                    title = stringResource(R.string.settings_title),
                    onNavigateUp = null,
                    bottomInset = 112.dp,
                    listState = listState
                ) {
                    item {
                        SettingsGroup(title = "") {
                            SettingsSection.entries.forEachIndexed { index, section ->
                                if (index > 0) GroupDivider()
                                SettingsRow(
                                    icon = section.iconRes,
                                    title = stringResource(section.titleRes),
                                    summary = stringResource(section.summaryRes),
                                    onClick = {
                                        nav = SettingsNav.Section(section)
                                    },
                                    trailing = {
                                        Icon(
                                            imageVector = Icons.Rounded.ChevronRight,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                )
                            }
                            GroupDivider()
                            SettingsRow(
                                icon = R.drawable.ic_server_restart,
                                title = stringResource(R.string.settings_reboot_now),
                                summary = stringResource(R.string.settings_reboot_now_summary),
                                onClick = { showRebootConfirm = true }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showRebootConfirm) {
        AlertDialog(
            onDismissRequest = { showRebootConfirm = false },
            title = { Text(stringResource(R.string.settings_reboot_confirm_title)) },
            text = { Text(stringResource(R.string.settings_reboot_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRebootConfirm = false
                        moe.shizuku.manager.system.PhoneReboot.request { started ->
                            if (!started) {
                                Toast.makeText(
                                    context,
                                    R.string.settings_reboot_failed,
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.settings_reboot_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRebootConfirm = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.extraLarge
        )
    }

    if (showLanguageDialog) {
        ChoiceDialog(
            title = stringResource(R.string.settings_language),
            choices = localeOptions.map {
                ChoiceOption(
                    title = it.title,
                    summary = it.summary,
                    icon = R.drawable.ic_outline_translate_24
                )
            },
            selectedIndex = localeOptions.indexOfFirst { it.tag == languageTag },
            onDismiss = { showLanguageDialog = false },
            onSelect = { index ->
                val tag = localeOptions[index].tag
                prefs.edit().putString(LANGUAGE, tag).apply()
                languageTag = tag
                LocaleDelegate.defaultLocale = if (tag == "SYSTEM") {
                    LocaleDelegate.systemLocale
                } else {
                    Locale.forLanguageTag(tag)
                }
                showLanguageDialog = false
                activity?.recreate()
            }
        )
    }

    if (showNightDialog) {
        ChoiceDialog(
            title = stringResource(rikka.core.R.string.dark_theme),
            choices = nightValues.mapIndexed { index, _ ->
                ChoiceOption(
                    title = nightLabels[index],
                    icon = when (nightValues[index]) {
                        AppCompatDelegate.MODE_NIGHT_NO -> R.drawable.ic_outline_light_mode_24
                        AppCompatDelegate.MODE_NIGHT_YES -> R.drawable.ic_outline_dark_mode_24
                        else -> R.drawable.ic_settings_outline_24dp
                    }
                )
            },
            selectedIndex = when (nightMode) {
                AppCompatDelegate.MODE_NIGHT_NO -> 0
                AppCompatDelegate.MODE_NIGHT_YES -> 1
                else -> 2
            },
            onDismiss = { showNightDialog = false },
            onSelect = { index ->
                val value = nightValues[index]
                prefs.edit().putInt(NIGHT_MODE, value).apply()
                nightMode = value
                AppCompatDelegate.setDefaultNightMode(value)
                showNightDialog = false
                activity?.recreate()
            }
        )
    }

    pendingTcpModeChange?.let { enabled ->
        AlertDialog(
            onDismissRequest = { pendingTcpModeChange = null },
            title = { Text(stringResource(R.string.settings_restart_dialog_title)) },
            text = {
                Text(
                    htmlToPlainText(
                        stringResource(R.string.settings_restart_dialog_message) +
                            if (enabled) stringResource(R.string.settings_restart_dialog_message_wifi_required) else ""
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingTcpModeChange = null
                        applyTcpMode(enabled, restart = true)
                    }
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingTcpModeChange = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.extraLarge
        )
    }

    if (showUnsafeDialog) {
        AlertDialog(
            onDismissRequest = { showUnsafeDialog = false },
            title = { Text(stringResource(R.string.unsafe_warning_title)) },
            text = { Text(stringResource(R.string.unsafe_warning_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUnsafeDialog = false
                        connectorEnabled = true
                        ModuleSettings.setConnectorEnabled(true)
                    }
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnsafeDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.extraLarge
        )
    }

    if (showMissingPermissionDialog) {
        val grantCommand = "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
        AlertDialog(
            onDismissRequest = { showMissingPermissionDialog = false },
            title = { Text(stringResource(R.string.settings_start_on_boot_adb_missing_permission_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_start_on_boot_adb_not_running))
                    Text(stringResource(R.string.settings_start_on_boot_adb_grant_failed))
                    Text(grantCommand)
                    Text(stringResource(R.string.settings_start_on_boot_adb_missing_permission_instruction))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        ClipboardUtils.put(context, grantCommand)
                        Toast.makeText(context, R.string.automation_copied_to_clipboard, Toast.LENGTH_SHORT).show()
                        showMissingPermissionDialog = false
                    }
                ) {
                    Text(stringResource(R.string.settings_start_on_boot_adb_missing_permission_copy))
                }
            },
            dismissButton = {
                TextButton(onClick = { showMissingPermissionDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.extraLarge
        )
    }
}

private fun LazyListScope.applicationSectionContent(
    rooted: Boolean,
    startOnBoot: Boolean,
    adbStartOnBoot: Boolean,
    tcpMode: Boolean,
    watchdog: Boolean,
    notifyDeath: Boolean,
    wifiReassert: Boolean,
    keepUsbDebugging: Boolean,
    disableLockScreen: Boolean,
    autoPairWirelessDebug: Boolean,
    onStartOnBootChange: (Boolean) -> Unit,
    onAdbStartOnBootChange: (Boolean) -> Unit,
    onWatchdogChange: (Boolean) -> Unit,
    onNotifyDeathChange: (Boolean) -> Unit,
    onWifiReassertChange: (Boolean) -> Unit,
    onKeepUsbDebuggingChange: (Boolean) -> Unit,
    onAutoPairWirelessDebugChange: (Boolean) -> Unit,
    onDisableLockScreenChange: (Boolean) -> Unit,
    onTcpModeChange: (Boolean) -> Unit
) {
    item {
        SettingsGroup(title = stringResource(R.string.settings_application)) {
            SectionHeader(stringResource(R.string.settings_startup))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                SwitchSettingsRow(
                    icon = R.drawable.ic_wadb_24,
                    title = stringResource(R.string.settings_start_on_boot_adb),
                    summary = stringResource(
                        if (tcpMode) R.string.settings_start_on_boot_adb_summary
                        else R.string.settings_start_on_boot_adb_summary_no_tcp
                    ),
                    checked = adbStartOnBoot,
                    onCheckedChange = onAdbStartOnBootChange
                )
                GroupDivider()
            }
            SwitchSettingsRow(
                icon = R.drawable.ic_security_24dp,
                title = stringResource(R.string.settings_disable_lock_screen),
                summary = stringResource(R.string.settings_disable_lock_screen_summary),
                checked = disableLockScreen,
                onCheckedChange = onDisableLockScreenChange
            )
            GroupDivider()
            SwitchSettingsRow(
                icon = R.drawable.ic_wadb_24,
                title = stringResource(R.string.settings_auto_pair_wireless_debug),
                summary = stringResource(R.string.settings_auto_pair_wireless_debug_summary),
                checked = autoPairWirelessDebug,
                onCheckedChange = onAutoPairWirelessDebugChange
            )
            GroupDivider()
            SectionHeader(stringResource(R.string.settings_service_group))
            SwitchSettingsRow(
                icon = R.drawable.ic_server_restart,
                title = stringResource(R.string.error_protect_title),
                summary = stringResource(R.string.error_protect_summary),
                checked = watchdog,
                onCheckedChange = onWatchdogChange
            )
            GroupDivider()
            SwitchSettingsRow(
                icon = R.drawable.ic_outline_notifications_active_24,
                title = stringResource(R.string.lab_notify_death_title),
                summary = stringResource(R.string.lab_notify_death_summary),
                checked = notifyDeath,
                onCheckedChange = onNotifyDeathChange
            )
            GroupDivider()
            SwitchSettingsRow(
                icon = R.drawable.ic_adb_24dp,
                title = stringResource(R.string.settings_wifi_reassert_title),
                summary = stringResource(R.string.settings_wifi_reassert_summary),
                checked = wifiReassert,
                onCheckedChange = onWifiReassertChange
            )
            GroupDivider()
            SwitchSettingsRow(
                icon = R.drawable.ic_adb_24dp,
                title = stringResource(R.string.settings_keep_usb_debugging),
                summary = stringResource(R.string.settings_keep_usb_debugging_summary),
                checked = keepUsbDebugging,
                onCheckedChange = onKeepUsbDebuggingChange
            )
            GroupDivider()
            SwitchSettingsRow(
                icon = R.drawable.ic_baseline_link_24,
                title = stringResource(R.string.settings_tcp_mode),
                summary = stringResource(R.string.settings_tcp_mode_summary),
                checked = tcpMode,
                onCheckedChange = onTcpModeChange
            )
        }
    }
}

private fun LazyListScope.appearanceSectionContent(
    languageSummary: String,
    nightSummary: String,
    nightMode: Int,
    blackNightTheme: Boolean,
    useSystemColor: Boolean,
    classicNav: Boolean,
    onLanguageClick: () -> Unit,
    onNightClick: () -> Unit,
    onBlackNightChange: (Boolean) -> Unit,
    onUseSystemColorChange: (Boolean) -> Unit,
    onClassicNavChange: (Boolean) -> Unit,
    swipeBetweenTabs: Boolean,
    motionBlur: Boolean,
    motionBlurIntensity: Int,
    motionBlurStrength: Int,
    onSwipeBetweenTabsChange: (Boolean) -> Unit,
    onMotionBlurChange: (Boolean) -> Unit,
    onMotionBlurIntensityChange: (Int) -> Unit,
    onMotionBlurStrengthChange: (Int) -> Unit
) {
    item {
        SettingsGroup(title = stringResource(R.string.settings_language)) {
            SettingsRow(
                icon = R.drawable.ic_outline_translate_24,
                title = stringResource(R.string.settings_language),
                summary = languageSummary,
                onClick = onLanguageClick
            )
        }
    }
    item {
        SettingsGroup(title = stringResource(R.string.settings_appearance)) {
            SettingsRow(
                icon = R.drawable.ic_outline_dark_mode_24,
                title = stringResource(rikka.core.R.string.dark_theme),
                summary = nightSummary,
                onClick = onNightClick
            )
            if (nightMode != AppCompatDelegate.MODE_NIGHT_NO) {
                GroupDivider()
                SwitchSettingsRow(
                    icon = R.drawable.ic_outline_dark_mode_24,
                    title = stringResource(R.string.settings_black_night_theme),
                    summary = stringResource(R.string.settings_black_night_theme_summary),
                    checked = blackNightTheme,
                    onCheckedChange = onBlackNightChange
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                GroupDivider()
                SwitchSettingsRow(
                    icon = R.drawable.ic_settings_outline_24dp,
                    title = stringResource(R.string.settings_use_system_color),
                    checked = useSystemColor,
                    onCheckedChange = onUseSystemColorChange
                )
            }
            GroupDivider()
            SwitchSettingsRow(
                icon = R.drawable.ic_system_icon,
                title = stringResource(R.string.settings_classic_nav),
                summary = stringResource(R.string.settings_classic_nav_summary),
                checked = classicNav,
                onCheckedChange = onClassicNavChange
            )
            GroupDivider()
            SwitchSettingsRow(
                icon = R.drawable.ic_outline_play_arrow_24,
                title = stringResource(R.string.settings_swipe_between_tabs),
                summary = stringResource(R.string.settings_swipe_between_tabs_summary),
                checked = swipeBetweenTabs,
                onCheckedChange = onSwipeBetweenTabsChange
            )
            GroupDivider()
            SwitchSettingsRow(
                icon = R.drawable.ic_code_24dp,
                title = stringResource(R.string.settings_motion_blur),
                summary = stringResource(R.string.settings_motion_blur_summary),
                checked = motionBlur,
                onCheckedChange = onMotionBlurChange
            )
            if (motionBlur) {
                GroupDivider()
                MotionBlurSliderRow(
                    icon = R.drawable.ic_code_24dp,
                    title = stringResource(R.string.settings_motion_blur_intensity),
                    summary = stringResource(R.string.settings_motion_blur_intensity_summary, motionBlurIntensity),
                    value = motionBlurIntensity,
                    onValueChange = onMotionBlurIntensityChange
                )
                GroupDivider()
                MotionBlurSliderRow(
                    icon = R.drawable.ic_code_24dp,
                    title = stringResource(R.string.settings_motion_blur_strength),
                    summary = stringResource(R.string.settings_motion_blur_strength_summary, motionBlurStrength),
                    value = motionBlurStrength,
                    onValueChange = onMotionBlurStrengthChange
                )
            }
        }
    }
}

@Composable
private fun MotionBlurSliderRow(
    icon: Int,
    title: String,
    summary: String,
    value: Int,
    onValueChange: (Int) -> Unit
) {
    var sliderValue by remember(value) { mutableStateOf(value.toFloat()) }
    SettingsRow(
        icon = icon,
        title = title,
        summary = summary,
        trailing = {
            Text(
                text = "$value%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
    )
    androidx.compose.material3.Slider(
        value = sliderValue,
        onValueChange = { sliderValue = it },
        onValueChangeFinished = { onValueChange(sliderValue.toInt()) },
        valueRange = 0f..100f,
        steps = 19,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 12.dp)
    )
}

private fun LazyListScope.guideSectionContent(context: Context) {
    item { GuideSections(context) }
}

@Composable
private fun GuideSections(context: Context) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsGroup(title = stringResource(R.string.home_terminal_title)) {
            Text(
                text = stringResource(R.string.home_terminal_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            GroupDivider()
            SettingsRow(
                icon = R.drawable.ic_terminal_24,
                title = stringResource(R.string.home_terminal_title),
                summary = stringResource(R.string.home_terminal_description),
                onClick = {
                    context.startActivity(Intent(context, moe.shizuku.manager.shell.ShellTutorialActivity::class.java))
                }
            )
        }
        SettingsGroup(title = stringResource(R.string.home_adb_title)) {
            SelectionContainer {
                Text(
                    text = stringResource(R.string.home_adb_description, moe.shizuku.manager.starter.Starter.adbCommand),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

private fun LazyListScope.automationSectionContent(
    context: Context,
    connectorEnabled: Boolean,
    authToken: String,
    onConnectorToggle: (Boolean) -> Unit,
    onCopy: (String) -> Unit
) {
    val targetComponent = "${context.packageName}/${com.hamondev.shevery.tasker.PluginContract.PLUGIN_RECEIVER}"
    val shellCommand = "am broadcast -a ${com.hamondev.shevery.tasker.PluginContract.ACTION_DIRECT_START} -p ${context.packageName} -e auth $authToken"

    item {
        SettingsGroup(title = stringResource(R.string.shizuku_connectors_title)) {
            SwitchSettingsRow(
                icon = R.drawable.ic_baseline_link_24,
                title = stringResource(R.string.shizuku_connectors_title),
                summary = stringResource(R.string.shizuku_connectors_summary),
                checked = connectorEnabled,
                onCheckedChange = onConnectorToggle
            )
        }
    }
    item {
        SettingsGroup(title = stringResource(R.string.automation_security_group_title)) {
            Text(
                text = stringResource(R.string.automation_security_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            GroupDivider()
            SettingsRow(
                icon = R.drawable.ic_code_24dp,
                title = stringResource(R.string.home_automation_label_extras),
                summary = authToken,
                onClick = { onCopy(authToken) }
            )
        }
    }
    item {
        SettingsGroup(title = stringResource(R.string.automation_broadcast_title)) {
            Text(
                text = stringResource(R.string.automation_broadcast_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            GroupDivider()
            SectionHeader(stringResource(R.string.automation_dialog_actions_header))
            listOf(
                com.hamondev.shevery.tasker.PluginContract.ACTION_DIRECT_START to stringResource(R.string.automation_action_start),
                com.hamondev.shevery.tasker.PluginContract.ACTION_DIRECT_STOP to stringResource(R.string.automation_action_stop),
                com.hamondev.shevery.tasker.PluginContract.ACTION_DIRECT_RESTART to stringResource(R.string.automation_action_restart),
                com.hamondev.shevery.tasker.PluginContract.ACTION_DIRECT_TOGGLE to stringResource(R.string.automation_action_toggle),
            ).forEach { (action, label) ->
                SettingsRow(
                    icon = R.drawable.ic_outline_play_arrow_24,
                    title = label,
                    summary = action,
                    onClick = { onCopy(action) }
                )
                GroupDivider()
            }
            SectionHeader(stringResource(R.string.automation_dialog_target_header))
            SettingsRow(
                icon = R.drawable.ic_baseline_link_24,
                title = stringResource(R.string.automation_package_label),
                summary = context.packageName,
                onClick = { onCopy(context.packageName) }
            )
            GroupDivider()
            SettingsRow(
                icon = R.drawable.ic_baseline_link_24,
                title = stringResource(R.string.automation_target_component_label),
                summary = targetComponent,
                onClick = { onCopy(targetComponent) }
            )
            GroupDivider()
            SettingsRow(
                icon = R.drawable.ic_terminal_24,
                title = stringResource(R.string.home_automation_label_shell),
                summary = shellCommand,
                onClick = { onCopy(shellCommand) }
            )
        }
    }
}

private fun LazyListScope.extrasSectionContent(
    context: Context,
    notifyRecovery: Boolean,
    wirelessDebugA11y: Boolean,
    onWirelessDebugA11yChange: (Boolean) -> Unit,
    onNotifyRecoveryChange: (Boolean) -> Unit,
    onRestartService: () -> Unit,
    onStopService: () -> Unit
) {
    item {
        SettingsGroup(title = stringResource(R.string.accessibility_manager_lab_group)) {
            SwitchSettingsRow(
                icon = R.drawable.ic_wadb_24,
                title = stringResource(R.string.a11y_wireless_debug_title),
                summary = stringResource(R.string.a11y_wireless_debug_summary),
                checked = wirelessDebugA11y,
                onCheckedChange = onWirelessDebugA11yChange
            )
            GroupDivider()
        }
    }

    item {
        SettingsGroup(title = stringResource(R.string.lab_service_behavior_title)) {
            SwitchSettingsRow(
                icon = R.drawable.ic_outline_notifications_active_24,
                title = stringResource(R.string.lab_notify_recovery_title),
                summary = stringResource(R.string.lab_notify_recovery_summary),
                checked = notifyRecovery,
                onCheckedChange = onNotifyRecoveryChange
            )
        }
    }

    item {
        SettingsGroup(title = stringResource(R.string.lab_maintenance_title)) {
            SettingsRow(
                icon = R.drawable.ic_server_restart,
                title = stringResource(R.string.lab_restart_service_title),
                summary = stringResource(R.string.lab_restart_service_summary),
                onClick = onRestartService
            )
            GroupDivider()
            SettingsRow(
                icon = R.drawable.ic_close_24,
                title = stringResource(R.string.lab_stop_service_title),
                summary = stringResource(R.string.lab_stop_service_summary),
                onClick = onStopService
            )
        }
    }
}

private fun LazyListScope.aboutSectionContent(
    context: Context,
    onOpenAbout: () -> Unit
) {
    item {
        SettingsGroup(title = stringResource(R.string.action_about)) {
            val context = LocalContext.current
            val versionName = remember(context) {
                try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
                } catch (e: Exception) { "" }
            }
            SettingsRow(
                icon = R.drawable.ic_outline_info_24,
                title = stringResource(R.string.app_name),
                summary = if (versionName.isNotBlank()) "v$versionName" else null,
                onClick = onOpenAbout
            )
            Text(
                text = stringResource(R.string.about_project_credit),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
    guideSectionContent(context)
}

private data class ChoiceOption(
    val title: String,
    val summary: String? = null,
    @param:androidx.annotation.DrawableRes val icon: Int? = null
)

@Composable
private fun ChoiceDialog(
    title: String,
    choices: List<ChoiceOption>,
    selectedIndex: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                choices.forEachIndexed { index, choice ->
                    SettingsRow(
                        icon = choice.icon,
                        title = choice.title,
                        summary = choice.summary,
                        onClick = { onSelect(index) },
                        trailing = {
                            RadioButton(
                                selected = index == selectedIndex,
                                onClick = { onSelect(index) }
                            )
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge
    )
}

private data class LocaleOption(val tag: String, val title: String, val summary: String?)

private fun buildLocaleOptions(context: Context): List<LocaleOption> {
    val follow = context.getString(rikka.core.R.string.follow_system)
    val tags = listOf("SYSTEM") + context.assets.locales
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()
    return tags.map { tag ->
        if (tag == "SYSTEM") {
            LocaleOption(tag, follow, null)
        } else {
            val locale = Locale.forLanguageTag(tag.replace('_', '-'))
            LocaleOption(
                tag,
                locale.getDisplayName(locale).replaceFirstChar { it.uppercase(locale) },
                locale.getDisplayName(Locale.getDefault())
            )
        }
    }
}
