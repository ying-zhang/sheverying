@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package moe.shizuku.manager.home

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import moe.shizuku.manager.ui.compose.LocalFloatingNavBarVisible
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.hamondev.shevery.tasker.Command
import com.hamondev.shevery.tasker.PluginContract
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import moe.shizuku.manager.ui.compose.scrollMotionBlur
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.Helps
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbArm
import moe.shizuku.manager.adb.AdbStarter
import moe.shizuku.manager.settings.SettingsSection
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.management.ApplicationManagementActivity
import moe.shizuku.manager.management.appsViewModel
import moe.shizuku.manager.model.ServiceStatus
import moe.shizuku.manager.shell.ShellTutorialActivity
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.starter.StarterActivity
import moe.shizuku.manager.worker.WifiDebugReassert
import moe.shizuku.manager.ui.compose.HtmlText
import moe.shizuku.manager.ui.compose.htmlToPlainText
import moe.shizuku.manager.ui.compose.ShizukuIcon
import moe.shizuku.manager.ui.compose.ShizukuExpressiveTheme
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import moe.shizuku.manager.utils.CustomTabsHelper
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.UserHandleCompat
import moe.shizuku.manager.ui.compose.ExpressiveFloatingNavigationBar
import moe.shizuku.manager.ui.compose.RefreshedNavigationBar
import moe.shizuku.manager.ui.compose.MonospaceLog
import moe.shizuku.manager.ui.compose.NavItem
import rikka.core.util.ClipboardUtils
import rikka.lifecycle.Resource
import rikka.lifecycle.Status
import rikka.lifecycle.viewModels
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants
import moe.shizuku.manager.module.ModuleSettings
import moe.shizuku.manager.utils.ShizukuStateMachine
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

abstract class HomeActivity : AppActivity() {

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        checkServerStatus()
        appsModel.load()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        checkServerStatus()
    }

    private val homeModel by viewModels { HomeViewModel() }
    private val appsModel by appsViewModel()
    private val permissionRefreshTick = mutableIntStateOf(0)
    private val isAppUnlocked = mutableStateOf(true)

    private var pendingLocalNetworkAction: (() -> Unit)? = null

    private val localNetworkPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionRefreshTick.intValue++
        val action = pendingLocalNetworkAction
        pendingLocalNetworkAction = null
        if (buildLocalNetworkPermissionState().granted) {
            EnvironmentUtils.requestIgnoreBatteryOptimizations(this@HomeActivity)
            action?.invoke()
        } else {
            Toast.makeText(this, R.string.home_local_network_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            moe.shizuku.manager.service.SheveryNotificationManager.updateNotification(this)
        }
    }

    private val manageAppsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        appsModel.load(onlyCount = true)
    }

    private var onNavigateAutomationSettings: (() -> Unit)? = null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getStringExtra(PluginContract.EXTRA_TARGET_SECTION) ==
            PluginContract.TARGET_SECTION_AUTOMATION
        ) {
            onNavigateAutomationSettings?.invoke()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Arm adbd on launch: WRITE_SECURE_SETTINGS when granted, otherwise
                // Device Owner setGlobalSetting (AdbArm decides between them).
                AdbArm.arm(applicationContext)
            } catch (_: Throwable) {
            }
        }

        setContent {
            val serviceResource by homeModel.serviceStatus.observeAsState()
            val serverState by homeModel.serverState.collectAsState()
            val grantedResource by appsModel.grantedCount.observeAsState()
            val localNetworkPermissionState = remember(permissionRefreshTick.intValue) {
                buildLocalNetworkPermissionState()
            }

            var showTcpPromptDialog by rememberSaveable { mutableStateOf(false) }
            var doNotRemindChecked by rememberSaveable { mutableStateOf(false) }

            var showWadbNotEnabledDialog by rememberSaveable { mutableStateOf(false) }
            var showAdbDiscoveryDialog by rememberSaveable { mutableStateOf(false) }
            var showAdbPairDialog by rememberSaveable { mutableStateOf(false) }

            LaunchedEffect(serviceResource?.status, serviceResource?.data?.uid) {
                val status = serviceResource?.data ?: return@LaunchedEffect
                if (serviceResource?.status == Status.SUCCESS && status.isRunning) {
                    ShizukuSettings.setLastLaunchMode(
                        if (status.uid == 0) {
                            ShizukuSettings.LaunchMethod.ROOT
                        } else {
                            ShizukuSettings.LaunchMethod.ADB
                        }
                    )
                    try {
                        WifiDebugReassert.reassertIfEnabled(applicationContext)
                    } catch (_: Throwable) {
                    }

                    val isAdbRunning = status.uid != 0
                    val needsTcpPrompt = isAdbRunning &&
                        !ShizukuSettings.isTcpMode() &&
                        !ShizukuSettings.isTcpModePromptSuppressed()

                    if (!hasPromptedTcpDialogThisSession && needsTcpPrompt) {
                        hasPromptedTcpDialogThisSession = true
                        showTcpPromptDialog = true
                    }
                }
            }

            val isTargetAutomation = remember(intent) {
                intent?.getStringExtra(PluginContract.EXTRA_TARGET_SECTION) ==
                    PluginContract.TARGET_SECTION_AUTOMATION
            }
            var selectedTab by rememberSaveable { mutableIntStateOf(if (isTargetAutomation) 1 else 0) }
            var settingsTargetSection by remember {
                mutableStateOf(if (isTargetAutomation) SettingsSection.AUTOMATION else null)
            }

            DisposableEffect(Unit) {
                onNavigateAutomationSettings = {
                    settingsTargetSection = SettingsSection.AUTOMATION
                    selectedTab = 1
                }
                onDispose {
                    onNavigateAutomationSettings = null
                }
            }

            // Hoisted above the AnimatedContent tab switch: tab screens leave
            // composition on change, so state kept here survives; saveable
            // so it also survives rotation.
            val settingsListState = rememberSaveable(saver = LazyListState.Saver) {
                LazyListState()
            }
            val homeListState = rememberSaveable(saver = LazyListState.Saver) {
                LazyListState()
            }
            var useClassicNav by remember { mutableStateOf(ShizukuSettings.isClassicNav()) }
            var swipeBetweenTabs by remember { mutableStateOf(ShizukuSettings.isSwipeBetweenTabs()) }
            var motionBlurEnabled by remember { mutableStateOf(ShizukuSettings.isMotionBlurEnabled()) }
            var motionBlurIntensity by remember { mutableIntStateOf(ShizukuSettings.getMotionBlurIntensity()) }
            var motionBlurStrength by remember { mutableIntStateOf(ShizukuSettings.getMotionBlurStrength()) }
            DisposableEffect(Unit) {
                val prefs = ShizukuSettings.getPreferences()
                val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == ShizukuSettings.USE_CLASSIC_NAV) {
                        useClassicNav = ShizukuSettings.isClassicNav()
                    }
                    if (key == ShizukuSettings.SWIPE_BETWEEN_TABS) {
                        swipeBetweenTabs = ShizukuSettings.isSwipeBetweenTabs()
                    }
                    if (key == ShizukuSettings.MOTION_BLUR_ENABLED) {
                        motionBlurEnabled = ShizukuSettings.isMotionBlurEnabled()
                    }
                    if (key == ShizukuSettings.MOTION_BLUR_INTENSITY) {
                        motionBlurIntensity = ShizukuSettings.getMotionBlurIntensity()
                    }
                    if (key == ShizukuSettings.MOTION_BLUR_STRENGTH) {
                        motionBlurStrength = ShizukuSettings.getMotionBlurStrength()
                    }
                }
                prefs.registerOnSharedPreferenceChangeListener(listener)
                onDispose {
                    prefs.unregisterOnSharedPreferenceChangeListener(listener)
                }
            }

            val floatingNavBarVisible = remember { mutableStateOf(true) }

            LaunchedEffect(selectedTab) {
                if (selectedTab != 1) {
                    floatingNavBarVisible.value = true
                }
            }

            ShizukuExpressiveTheme {
                CompositionLocalProvider(
                    LocalFloatingNavBarVisible provides floatingNavBarVisible
                ) {
                        BackHandler(enabled = selectedTab != 0) {
                            selectedTab = 0
                        }
                        Box(Modifier.fillMaxSize()) {
                        Scaffold(
                        contentWindowInsets = WindowInsets(0.dp)
                    ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                        val pagerState = rememberPagerState(
                            initialPage = selectedTab.coerceIn(0, 1),
                            pageCount = { 2 }
                        )
                        LaunchedEffect(selectedTab, swipeBetweenTabs) {
                            if (swipeBetweenTabs && pagerState.currentPage != selectedTab) {
                                try {
                                    pagerState.animateScrollToPage(selectedTab.coerceIn(0, 1))
                                } catch (_: Exception) {
                                }
                            }
                        }
                        LaunchedEffect(
                            pagerState.currentPage,
                            pagerState.isScrollInProgress,
                            swipeBetweenTabs
                        ) {
                            if (swipeBetweenTabs && !pagerState.isScrollInProgress &&
                                selectedTab != pagerState.currentPage
                            ) {
                                selectedTab = pagerState.currentPage
                            }
                        }
                        val tabContent: @Composable (Int) -> Unit = { targetTab ->
                            // Status scrolls a raw list, so blur is applied here.
                            // Settings scrolls inside ShizukuLazyScaffold.
                            val needsOuterBlur = targetTab == 0
                            Box(
                                modifier = Modifier.fillMaxSize()
                                    .then(
                                        if (needsOuterBlur) {
                                            Modifier.scrollMotionBlur(
                                                listState = homeListState,
                                                enabled = motionBlurEnabled,
                                                intensity = motionBlurIntensity,
                                                strength = motionBlurStrength
                                            )
                                        } else {
                                            Modifier
                                        }
                                    )
                            ) {
                            when (targetTab) {
                                0 -> HomeScreen(
                                    serviceResource = serviceResource,
                                    serverState = serverState,
                                    grantedResource = grantedResource,
                                    localNetworkPermissionState = localNetworkPermissionState,
                                    isPrimaryUser = UserHandleCompat.myUserId() == 0,
                                    onRefresh = {
                                        checkServerStatus()
                                        appsModel.load()
                                    },
                                    onManageApps = {
                                        manageAppsLauncher.launch(Intent(this@HomeActivity, ApplicationManagementActivity::class.java))
                                    },
                                    onTerminal = { startActivity(Intent(this@HomeActivity, ShellTutorialActivity::class.java)) },
                                    onStartWirelessAdb = {
                                        runWithLocalNetworkAccess {
                                            startWirelessAdb(
                                                onShowDiscoveryDialog = { showAdbDiscoveryDialog = true },
                                                onWadbNotEnabled = { showWadbNotEnabledDialog = true }
                                            )
                                        }
                                    },
                                    onPairWirelessAdb = {
                                        runWithLocalNetworkAccess {
                                            pairWirelessAdb(
                                                onShowPairDialog = { showAdbPairDialog = true }
                                            )
                                        }
                                    },

                                    onOpenAdbPermissionHelp = { CustomTabsHelper.launchUrlOrCopy(this@HomeActivity, Helps.ADB_PERMISSION.get()) },
                                    onCopyDiagnostics = { copyDiagnostics(it) },
                                    onRequestLocalNetworkPermission = {
                                        requestLocalNetworkPermission { permissionRefreshTick.intValue++ }
                                    },
                                    listState = homeListState
                                )
                                else -> moe.shizuku.manager.settings.SettingsScreen(
                                    listState = settingsListState,
                                    targetSection = settingsTargetSection,
                                    onTargetSectionConsumed = { settingsTargetSection = null }
                                )
                            }
                                }
                        }
                        if (swipeBetweenTabs) {
                            HorizontalPager(
                                state = pagerState,
                                userScrollEnabled = true,
                                beyondViewportPageCount = 1,
                                modifier = Modifier.fillMaxSize()
                                    .scrollMotionBlur(
                                        pagerState = pagerState,
                                        enabled = motionBlurEnabled && swipeBetweenTabs,
                                        intensity = motionBlurIntensity,
                                        strength = motionBlurStrength
                                    )
                            ) { page ->
                                val pageOffset = pagerState.currentPage - page +
                                    pagerState.currentPageOffsetFraction
                                val horizontalBlur = pagerOffsetBlur(
                                    enabled = motionBlurEnabled && swipeBetweenTabs,
                                    offsetFraction = pageOffset,
                                    intensity = motionBlurIntensity,
                                    strength = motionBlurStrength
                                )
                                Box(
                                    modifier = Modifier.fillMaxSize()
                                        .then(if (horizontalBlur > 0.dp) Modifier.blur(horizontalBlur) else Modifier)
                                ) {
                                    tabContent(page)
                                }
                            }
                        } else {
                            androidx.compose.animation.AnimatedContent(
                                targetState = selectedTab,
                                transitionSpec = {
                                    fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
                                },
                                label = "tab_transition"
                            ) { targetTab ->
                                tabContent(targetTab)
                            }
                        }
                    }
                }
                val navItems = listOf(
                    NavItem(stringResource(R.string.home_tab_status), Icons.Rounded.Home),
                    NavItem(stringResource(R.string.settings_title), Icons.Rounded.Settings)
                )

                if (useClassicNav) {
                    RefreshedNavigationBar(
                        items = navItems,
                        selectedIndex = selectedTab,
                        onItemSelected = { selectedTab = it },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                } else {
                    ExpressiveFloatingNavigationBar(
                        items = navItems,
                        selectedIndex = selectedTab,
                        onItemSelected = { selectedTab = it },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }

                if (showTcpPromptDialog && !ShizukuSettings.isTcpMode()) {
                    AlertDialog(
                        onDismissRequest = {
                            hasPromptedTcpDialogThisSession = true
                            if (doNotRemindChecked) {
                                ShizukuSettings.setTcpModePromptSuppressed(true)
                            }
                            showTcpPromptDialog = false
                        },
                        title = {
                            DialogTitleText(
                                text = stringResource(R.string.tcp_prompt_dialog_title)
                            )
                        },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text(
                                    text = stringResource(R.string.tcp_prompt_dialog_message),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(MaterialTheme.shapes.small)
                                        .clickable { doNotRemindChecked = !doNotRemindChecked }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = doNotRemindChecked,
                                        onCheckedChange = { doNotRemindChecked = it }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.tcp_prompt_dialog_do_not_remind),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    hasPromptedTcpDialogThisSession = true
                                    if (doNotRemindChecked) {
                                        ShizukuSettings.setTcpModePromptSuppressed(true)
                                    }
                                    showTcpPromptDialog = false
                                    ShizukuSettings.setTcpMode(true)

                                    lifecycleScope.launch(Dispatchers.IO) {
                                        val isAlreadyOn5555 = EnvironmentUtils.isAdbPortLive(AdbStarter.TCP_MODE_PORT)
                                        if (isAlreadyOn5555) {
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(this@HomeActivity, R.string.settings_tcp_mode, Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            val port = EnvironmentUtils.getActiveAdbPort().takeIf { it > 0 }
                                            if (port != null) {
                                                withContext(Dispatchers.Main) {
                                                    moe.shizuku.manager.service.WatchdogManager.clearUserStopRequest(this@HomeActivity)
                                                    startActivity(
                                                        Intent(this@HomeActivity, StarterActivity::class.java).apply {
                                                            putExtra(StarterActivity.EXTRA_IS_ROOT, false)
                                                            putExtra(StarterActivity.EXTRA_HOST, "127.0.0.1")
                                                            putExtra(StarterActivity.EXTRA_PORT, port)
                                                        }
                                                    )
                                                }
                                            } else {
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(this@HomeActivity, R.string.dialog_wireless_adb_not_enabled, Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    }
                                }
                            ) {
                                Text(stringResource(R.string.tcp_prompt_dialog_enable))
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    hasPromptedTcpDialogThisSession = true
                                    if (doNotRemindChecked) {
                                        ShizukuSettings.setTcpModePromptSuppressed(true)
                                    }
                                    showTcpPromptDialog = false
                                }
                            ) {
                                Text(stringResource(R.string.tcp_prompt_dialog_later))
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.extraLarge
                    )
                }

                if (showWadbNotEnabledDialog) {
                    AlertDialog(
                        onDismissRequest = { showWadbNotEnabledDialog = false },
                        text = {
                            Text(
                                text = stringResource(R.string.dialog_wireless_adb_not_enabled),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showWadbNotEnabledDialog = false
                                    val intent = Intent(this@HomeActivity, StarterActivity::class.java).apply {
                                        putExtra(StarterActivity.EXTRA_IS_ROOT, false)
                                        putExtra(StarterActivity.EXTRA_HOST, "127.0.0.1")
                                        putExtra(StarterActivity.EXTRA_PORT, AdbStarter.TCP_MODE_PORT)
                                    }
                                    startActivity(intent)
                                }
                            ) {
                                Text(stringResource(R.string.home_quick_tcp))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showWadbNotEnabledDialog = false }) {
                                Text(stringResource(android.R.string.ok))
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.extraLarge
                    )
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (showAdbDiscoveryDialog) {
                        AdbDiscoveryDialog(
                            onDismissRequest = { showAdbDiscoveryDialog = false },
                            onStartService = { port ->
                                showAdbDiscoveryDialog = false
                                val intent = Intent(this@HomeActivity, StarterActivity::class.java).apply {
                                    putExtra(StarterActivity.EXTRA_IS_ROOT, false)
                                    putExtra(StarterActivity.EXTRA_HOST, "127.0.0.1")
                                    putExtra(StarterActivity.EXTRA_PORT, port)
                                }
                                startActivity(intent)
                            }
                        )
                    }

                    if (showAdbPairDialog) {
                        val inPairingWindow = (display?.displayId ?: -1) > 0 || isInMultiWindowMode
                        AdbPairDialog(
                            inPairingWindow = inPairingWindow,
                            onDismissRequest = { showAdbPairDialog = false },
                            onPairSuccess = {
                                showAdbPairDialog = false
                                showAdbDiscoveryDialog = true
                            }
                        )
                    }
                }
            }
        }
    }
    }

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
    }

    override fun onResume() {
        super.onResume()
        checkServerStatus()
        permissionRefreshTick.intValue++
    }

    private fun checkServerStatus() {
        homeModel.reload()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.clear()
        return false
    }

    private fun startWirelessAdb(
        onShowDiscoveryDialog: () -> Unit,
        onWadbNotEnabled: () -> Unit
    ) {
        moe.shizuku.manager.service.WatchdogManager.clearUserStopRequest(this@HomeActivity)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            onShowDiscoveryDialog()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val livePort = EnvironmentUtils.getActiveAdbPort().takeIf { it > 0 }
            withContext(Dispatchers.Main) {
                if (livePort != null) {
                    startActivity(
                        Intent(this@HomeActivity, StarterActivity::class.java).apply {
                            putExtra(StarterActivity.EXTRA_IS_ROOT, false)
                            putExtra(StarterActivity.EXTRA_HOST, "127.0.0.1")
                            putExtra(StarterActivity.EXTRA_PORT, livePort)
                        }
                    )
                } else {
                    onWadbNotEnabled()
                }
            }
        }
    }

    private fun pairWirelessAdb(onShowPairDialog: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        if ((display?.displayId ?: -1) > 0 || isInMultiWindowMode) {
            onShowPairDialog()
        } else {
            startActivity(Intent(this, moe.shizuku.manager.adb.AdbPairingTutorialActivity::class.java))
        }
    }

    private fun runWithLocalNetworkAccess(action: () -> Unit) {
        val state = buildLocalNetworkPermissionState()
        if (!state.required || state.granted) {
            EnvironmentUtils.requestIgnoreBatteryOptimizations(this@HomeActivity)
            action()
            return
        }

        pendingLocalNetworkAction = action
        localNetworkPermissionLauncher.launch(state.missingPermissions.toTypedArray())
    }

    private fun requestLocalNetworkPermission(onGranted: () -> Unit) {
        val state = buildLocalNetworkPermissionState()
        if (!state.required || state.granted) {
            onGranted()
            return
        }

        pendingLocalNetworkAction = onGranted
        localNetworkPermissionLauncher.launch(state.missingPermissions.toTypedArray())
    }

    private fun buildLocalNetworkPermissionState(): LocalNetworkPermissionState {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= SDK_ANDROID_17) {
                add(PERMISSION_ACCESS_LOCAL_NETWORK)
                if (isPermissionDefined(PERMISSION_USE_LOOPBACK_INTERFACE)) {
                    add(PERMISSION_USE_LOOPBACK_INTERFACE)
                }
            }
            if (Build.VERSION.SDK_INT >= SDK_ANDROID_13) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        return LocalNetworkPermissionState(
            permissions = permissions,
            missingPermissions = missingPermissions
        )
    }

    private fun copyDiagnostics(text: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(getString(R.string.home_diagnostics_title), text))
        Toast.makeText(this, R.string.home_diagnostics_copied, Toast.LENGTH_SHORT).show()
    }
    private fun isPermissionDefined(permission: String): Boolean {
        return try {
            packageManager.getPermissionInfo(permission, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val SDK_ANDROID_13 = 33
        private const val SDK_ANDROID_17 = 37
        private const val PERMISSION_ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
        private const val PERMISSION_USE_LOOPBACK_INTERFACE = "android.permission.USE_LOOPBACK_INTERFACE"
        private var hasPromptedTcpDialogThisSession = false
    }
}

private data class LocalNetworkPermissionState(
    val permissions: List<String>,
    val missingPermissions: List<String>
) {
    val required: Boolean
        get() = permissions.isNotEmpty()
    val granted: Boolean
        get() = missingPermissions.isEmpty()
    val label: String
        get() = permissions.takeIf { it.isNotEmpty() }
            ?.joinToString { it.substringAfterLast('.') }
            ?: "none"
}

private fun pagerOffsetBlur(
    enabled: Boolean,
    offsetFraction: Float,
    intensity: Int,
    strength: Int
): androidx.compose.ui.unit.Dp {
    if (!enabled) return 0.dp
    val offset = kotlin.math.abs(offsetFraction).coerceIn(0f, 1f)
    if (offset <= 0.001f) return 0.dp
    val maxRadius = (intensity.coerceIn(0, 100) / 100f) * 24f
    if (maxRadius <= 0.01f) return 0.dp
    val sensitivity = 0.5f + (strength.coerceIn(0, 100) / 100f)
    return (offset * maxRadius * sensitivity).dp
}

private data class HomeButtonSpec(
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
    val primary: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

@Composable
private fun HomeScreen(
    serviceResource: Resource<ServiceStatus>?,
    serverState: ShizukuStateMachine.State = ShizukuStateMachine.State.STOPPED,
    grantedResource: Resource<Int>?,
    localNetworkPermissionState: LocalNetworkPermissionState,
    isPrimaryUser: Boolean,
    onRefresh: () -> Unit,
    onManageApps: () -> Unit,
    onTerminal: () -> Unit,
    onStartWirelessAdb: () -> Unit,
    onPairWirelessAdb: () -> Unit,
    onOpenAdbPermissionHelp: () -> Unit,
    onCopyDiagnostics: (String) -> Unit,
    onRequestLocalNetworkPermission: () -> Unit,
    listState: LazyListState = rememberLazyListState()
) {
    val context = LocalContext.current
    val status = serviceResource?.data ?: ServiceStatus()
    val grantedCount = grantedResource?.data ?: 0
    val running = serverState == ShizukuStateMachine.State.RUNNING || status.isRunning
    val adbPermission = status.permission
    val canUseWirelessAdb by produceState(
        initialValue = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R || ShizukuSettings.isTcpMode(),
        key1 = status.isRunning
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R || ShizukuSettings.isTcpMode()) {
            value = true
        } else {
            value = withContext(Dispatchers.IO) {
                EnvironmentUtils.isAdbPortLive(AdbStarter.TCP_MODE_PORT) ||
                    EnvironmentUtils.getLiveAdbTcpPort() > 0
            }
        }
    }
    val watchdogEnabled = ModuleSettings.isWatchdogEnabled()
    val tcpMode = ShizukuSettings.isTcpMode()
    val launchMode = ShizukuSettings.getLastLaunchMode()
    val diagnostics = buildDiagnostics(
        context,
        status,
        localNetworkPermissionState,
        watchdogEnabled,
        tcpMode,
        launchMode
    )
    val scope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }
    val pullToRefreshState = rememberPullToRefreshState()

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            PullToRefreshBox(
                modifier = Modifier.fillMaxSize(),
                isRefreshing = isRefreshing,
                onRefresh = {
                    scope.launch {
                        isRefreshing = true
                        onRefresh()
                        delay(500L)
                        isRefreshing = false
                    }
                },
                state = pullToRefreshState,
                indicator = {}
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 112.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
            item {
                StatusHero(
                    serviceResource = serviceResource,
                    status = status,
                    running = running,
                    serverState = serverState,
                    adbPermission = adbPermission,
                    isPrimaryUser = isPrimaryUser,
                    canUseWirelessAdb = canUseWirelessAdb,
                    onStartWirelessAdb = onStartWirelessAdb,
                    onManageApps = onManageApps
                )
            }

            item {
                QuickActionsPills(
                    running = running,
                    isPrimaryUser = isPrimaryUser,
                    onTerminal = onTerminal,
                    onStartWirelessAdb = onStartWirelessAdb,
                    onPairWirelessAdb = onPairWirelessAdb
                )
            }

            if (running && !adbPermission) {
                item {
                    HomeCard(
                        icon = R.drawable.ic_warning_24,
                        title = stringResource(R.string.home_adb_is_limited_title),
                        body = stringResource(R.string.home_adb_is_limited_description)
                    ) {
                        HomeButtons(
                            listOf(
                                HomeButtonSpec(
                                    label = R.string.home_adb_button_view_help,
                                    icon = R.drawable.ic_help_outline_24dp,
                                    primary = true,
                                    onClick = onOpenAdbPermissionHelp
                                )
                            )
                        )
                    }
                }
            }

            if (localNetworkPermissionState.required && !localNetworkPermissionState.granted) {
                item {
                    LocalNetworkPermissionCard(
                        localNetworkPermissionState = localNetworkPermissionState,
                        onRequestLocalNetworkPermission = onRequestLocalNetworkPermission
                    )
                }
            }

            if (adbPermission) {
                item {
                    ManageAppsCard(
                        status = status,
                        grantedCount = grantedCount,
                        onClick = onManageApps
                    )
                }
            }

            item {
                DiagnosticsCard(
                    diagnostics = diagnostics,
                    onCopyDiagnostics = onCopyDiagnostics
                )
            }
        }
    }
    PullToRefreshDefaults.LoadingIndicator(
        state = pullToRefreshState,
        isRefreshing = isRefreshing,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 8.dp)
    )

}
}
}

@Composable
private fun StatusHero(
    serviceResource: Resource<ServiceStatus>?,
    status: ServiceStatus,
    running: Boolean,
    serverState: ShizukuStateMachine.State = ShizukuStateMachine.State.STOPPED,
    adbPermission: Boolean,
    isPrimaryUser: Boolean,
    canUseWirelessAdb: Boolean,
    onStartWirelessAdb: () -> Unit,
    onManageApps: () -> Unit
) {
    val context = LocalContext.current
    val title = if (running) {
        stringResource(R.string.home_status_service_is_running, stringResource(R.string.app_name))
    } else {
        stringResource(R.string.home_status_service_not_running, stringResource(R.string.app_name))
    }
    val summary = remember(status, running) {
        buildServiceSummary(context, status)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = if (running) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(56.dp),
                    shape = CircleShape,
                    color = if (running) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        ShizukuIcon(
                            icon = if (running) R.drawable.ic_server_ok_24dp else R.drawable.ic_server_error_24dp,
                            contentDescription = null,
                            tint = if (running) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLargeEmphasized,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (running) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                    if (summary.isNotBlank()) {
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (running) {
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }

            if (serviceResource == null || serverState == ShizukuStateMachine.State.STARTING) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(Modifier.size(24.dp))
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (running) {
                } else if (isPrimaryUser) {
                    if (canUseWirelessAdb) {
                        FilledTonalButton(onClick = onStartWirelessAdb) {
                            ButtonIcon(R.drawable.ic_wadb_24)
                            Text(stringResource(R.string.home_wireless_adb_title))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManageAppsCard(
    status: ServiceStatus,
    grantedCount: Int,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val running = status.isRunning
    val title = if (running) {
        context.resources.getQuantityString(
            R.plurals.home_app_management_authorized_apps_count,
            grantedCount,
            grantedCount
        )
    } else {
        stringResource(R.string.home_app_management_title)
    }
    val body = if (running) {
        stringResource(R.string.home_app_management_view_authorized_apps)
    } else {
        stringResource(R.string.home_status_service_not_running, stringResource(R.string.app_name))
    }

    SimpleActionCard(
        icon = R.drawable.ic_system_icon,
        title = title,
        body = body,
        enabled = running,
        onClick = onClick
    )
}

@Composable
private fun QuickActionsPills(
    running: Boolean,
    isPrimaryUser: Boolean,
    onTerminal: () -> Unit,
    onStartWirelessAdb: () -> Unit,
    onPairWirelessAdb: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (isPrimaryUser) {
                QuickActionPill(
                    icon = Icons.Rounded.Wifi,
                    label = stringResource(R.string.home_quick_wireless),
                    onClick = onStartWirelessAdb
                )
                QuickActionPill(
                    icon = Icons.Rounded.NearMe,
                    label = stringResource(R.string.adb_pairing),
                    onClick = onPairWirelessAdb
                )
            }
        }
    }
}

@Composable
private fun QuickActionPill(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(percent = 50),
        color = if (enabled) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
private fun LocalNetworkPermissionCard(
    localNetworkPermissionState: LocalNetworkPermissionState,
    onRequestLocalNetworkPermission: () -> Unit
) {
    HomeCard(
        icon = R.drawable.ic_warning_24,
        title = stringResource(R.string.home_local_network_title),
        body = stringResource(
            R.string.home_local_network_description,
            localNetworkPermissionState.label
        )
    ) {
        HomeButtons(
            listOf(
                HomeButtonSpec(
                    label = R.string.home_local_network_grant,
                    icon = R.drawable.ic_settings_outline_24dp,
                    primary = true,
                    onClick = onRequestLocalNetworkPermission
                )
            )
        )
    }
}

@Composable
private fun DiagnosticsCard(
    diagnostics: String,
    onCopyDiagnostics: (String) -> Unit
) {
    HomeCard(
        icon = R.drawable.ic_outline_info_24,
        title = stringResource(R.string.home_diagnostics_title),
        body = diagnostics,
        onBodyLongClick = { onCopyDiagnostics(diagnostics) }
    )
}

@Composable
private fun SimpleActionCard(
    @DrawableRes icon: Int,
    title: String,
    body: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    HomeCard(
        icon = icon,
        title = title,
        body = body,
        enabled = enabled,
        onClick = onClick
    )
}

@Composable
private fun HomeCard(
    @DrawableRes icon: Int,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onBodyLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit = {}
) {
    val clickableModifier = if (onClick != null) {
        Modifier.clickable(enabled = enabled, onClick = onClick)
    } else {
        Modifier
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(clickableModifier)
            .alpha(if (enabled) 1f else 0.56f),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    ShizukuIcon(
                        icon = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (body.isNotBlank()) {
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = if (onBodyLongClick != null) {
                            Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = onBodyLongClick
                            )
                        } else {
                            Modifier
                        }
                    )
                }
                content()
            }
        }
    }
}

@Composable
private fun HomeButtons(buttons: List<HomeButtonSpec>) {
    if (buttons.isEmpty()) return

    Spacer(Modifier.height(8.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        buttons.forEach { button ->
            if (button.primary) {
                Button(
                    enabled = button.enabled,
                    onClick = button.onClick,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    ButtonIcon(button.icon)
                    Text(stringResource(button.label))
                }
            } else if (button.enabled) {
                FilledTonalButton(
                    onClick = button.onClick,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    ButtonIcon(button.icon)
                    Text(stringResource(button.label))
                }
            } else {
                OutlinedButton(
                    enabled = false,
                    onClick = button.onClick,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    ButtonIcon(button.icon)
                    Text(stringResource(button.label))
                }
            }
        }
    }
}

@Composable
private fun ButtonIcon(@DrawableRes icon: Int) {
    ShizukuIcon(
        icon = icon,
        contentDescription = null,
        modifier = Modifier
            .padding(end = 8.dp)
            .size(18.dp)
    )
}

private fun buildServiceSummary(context: android.content.Context, status: ServiceStatus): String {
    if (!status.isRunning) return ""

    val user = if (status.uid == 0) "root" else "adb"
    val version = "${status.apiVersion}.${status.patchVersion}"
    val latestVersion = "${Shizuku.getLatestServiceVersion()}.${ShizukuApiConstants.SERVER_PATCH_VERSION}"
    val raw = if (
        status.apiVersion != Shizuku.getLatestServiceVersion() ||
        status.patchVersion != ShizukuApiConstants.SERVER_PATCH_VERSION
    ) {
        context.getString(R.string.home_status_service_version_update, user, version, latestVersion)
    } else {
        context.getString(R.string.home_status_service_version, user, version)
    }
    return htmlToPlainText(raw)
}

private fun buildDiagnostics(
    context: android.content.Context,
    status: ServiceStatus,
    localNetworkPermissionState: LocalNetworkPermissionState,
    watchdogEnabled: Boolean,
    tcpMode: Boolean,
    launchMode: Int
): String {
    fun mark(enabled: Boolean) = context.getString(
        if (enabled) R.string.diagnostics_mark_on else R.string.diagnostics_mark_off
    )
    val permission = context.getString(
        if (status.permission) R.string.diagnostics_perm_full else R.string.diagnostics_perm_limited
    )
    val identity = when {
        status.isRunning && status.uid == 0 ->
            context.getString(R.string.diagnostics_brief_root, permission)
        status.isRunning ->
            context.getString(R.string.diagnostics_brief_adb, status.uid, permission)
        launchMode == ShizukuSettings.LaunchMethod.ROOT ->
            context.getString(R.string.diagnostics_brief_last_root)
        launchMode == ShizukuSettings.LaunchMethod.ADB ->
            context.getString(R.string.diagnostics_brief_last_adb)
        else ->
            context.getString(R.string.diagnostics_brief_not_started)
    }
    val resolver = context.contentResolver
    val usbOn = runCatching {
        Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0) == 1
    }.getOrDefault(false)
    val wifiOn = runCatching {
        Settings.Global.getInt(resolver, "adb_wifi_enabled", 0) == 1
    }.getOrDefault(false)
    val lines = mutableListOf(
        context.getString(R.string.diagnostics_brief_device, Build.VERSION.RELEASE, Build.MODEL),
        identity,
        context.getString(
            R.string.diagnostics_brief_switches,
            mark(usbOn),
            mark(wifiOn),
            mark(tcpMode),
            mark(ShizukuSettings.getStartOnBootAdb())
        ),
        context.getString(
            R.string.diagnostics_brief_options,
            mark(ShizukuSettings.isDisableLockScreen()),
            mark(ShizukuSettings.isAutoPairWirelessDebug()),
            mark(watchdogEnabled),
            if (AdbArm.hasWriteSecureSettings(context)) {
                context.getString(R.string.diagnostics_state_granted)
            } else {
                context.getString(R.string.diagnostics_state_not_granted)
            }
        )
    )
    if (localNetworkPermissionState.required && !localNetworkPermissionState.granted) {
        lines += context.getString(
            R.string.diagnostics_local_network_missing,
            localNetworkPermissionState.label
        )
    }
    return lines.joinToString("\n")
}


@Composable
private fun RootCard(onStartRoot: () -> Unit) {
    HomeCard(
        icon = R.drawable.ic_server_start_24dp,
        title = HtmlText(R.string.home_root_title),
        body = HtmlText(R.string.home_root_description, Helps.SUI.get())
    ) {
        HomeButtons(
            listOf(
                HomeButtonSpec(
                    label = R.string.home_root_button_start,
                    icon = R.drawable.ic_server_start_24dp,
                    primary = true,
                    onClick = onStartRoot
                )
            )
        )
    }
}

@Composable
private fun DialogTitleText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun LockedScreen(onUnlock: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(R.drawable.ic_security_24dp),
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.security_locked_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.security_locked_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onUnlock,
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(stringResource(R.string.security_unlock_button))
            }
        }
    }
}
