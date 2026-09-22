package com.interbb.disasterinboxcleaner

import android.Manifest
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.interbb.disasterinboxcleaner.adb.AdbGrantNotifications
import com.interbb.disasterinboxcleaner.adb.AdbGrantPreferences
import com.interbb.disasterinboxcleaner.adb.AdbGrantService
import com.interbb.disasterinboxcleaner.ui.AdLogScreen
import com.interbb.disasterinboxcleaner.ui.GuideTarget
import com.interbb.disasterinboxcleaner.ui.HistoryScreen
import com.interbb.disasterinboxcleaner.ui.HomeScreen
import com.interbb.disasterinboxcleaner.ui.SettingsScreen
import com.interbb.disasterinboxcleaner.ui.TutorialScreen
import com.interbb.disasterinboxcleaner.ui.theme.DisasterInboxCleanerTheme

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()

    // Mirrors the composable's current screen so onResume can skip the tutorial's own refresh
    // duplicate below; every other screen still needs the call, since none has one of its own.
    private var tutorialActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DisasterInboxCleanerTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val historyState by viewModel.historyState.collectAsStateWithLifecycle()
                val adLogEntries by viewModel.adLogState.collectAsStateWithLifecycle()
                var screenName by rememberSaveable {
                    mutableStateOf(
                        if (state.onboardingComplete) AppScreen.HOME.name else AppScreen.TUTORIAL.name,
                    )
                }
                // Tolerates a saved name this build no longer has (ONBOARDING/GUIDE from an older
                // install); the LaunchedEffect below sends an unfinished setup back to the tutorial.
                val screen = AppScreen.entries.firstOrNull { it.name == screenName } ?: AppScreen.HOME
                tutorialActive = screen == AppScreen.TUTORIAL
                val adbCommand = "adb shell appops set $packageName WRITE_SMS allow"

                val smsPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { viewModel.refreshAndRequestRebind() }
                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    if (granted) {
                        startGrantService()
                    } else {
                        Toast.makeText(this@MainActivity, "알림을 허용해야 코드를 입력할 수 있습니다", Toast.LENGTH_LONG).show()
                        openAppNotificationSettings()
                    }
                }

                var tutorialOrigin by rememberSaveable { mutableStateOf(AppScreen.HOME.name) }

                LaunchedEffect(state.onboardingComplete) {
                    if (!state.onboardingComplete) screenName = AppScreen.TUTORIAL.name
                }

                fun copyAdbCommand() {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(
                        ClipData.newPlainText("ADB 삭제 권한", adbCommand),
                    )
                    Toast.makeText(this@MainActivity, "ADB 명령을 복사했습니다", Toast.LENGTH_SHORT).show()
                }

                val requestSmsPermission = {
                    smsPermissionLauncher.launch(Manifest.permission.READ_SMS)
                }
                val openNotificationAccess = {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                val openMessagingApp = {
                    startActivity(
                        Intent.makeMainSelectorActivity(
                            Intent.ACTION_MAIN,
                            Intent.CATEGORY_APP_MESSAGING,
                        ),
                    )
                }
                val requestBatteryExemption = {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName"),
                        ),
                    )
                }
                val startAdbGrant = {
                    val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    when {
                        needsPermission -> notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        !canShowGrantNotifications() -> {
                            Toast.makeText(this@MainActivity, "알림이 꺼져 있어 코드를 입력할 수 없습니다", Toast.LENGTH_LONG).show()
                            openAppNotificationSettings()
                        }
                        else -> startGrantService()
                    }
                }
                val cancelAdbGrant = {
                    ContextCompat.startForegroundService(this@MainActivity, AdbGrantService.cancelIntent(this@MainActivity))
                }
                val submitConnectPort: (Int) -> Unit = { port ->
                    ContextCompat.startForegroundService(
                        this@MainActivity,
                        AdbGrantService.replyIntent(this@MainActivity, port.toString()),
                    )
                }
                val openTutorial = {
                    tutorialOrigin = screenName
                    screenName = AppScreen.TUTORIAL.name
                }
                val openAppSettings = {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName"),
                        ),
                    )
                }
                val openNotificationSettingsForApp = { openAppNotificationSettings() }
                val notificationsBlockedForGrant = { !canShowGrantNotifications() }
                val goHome = {
                    if (screen == AppScreen.HISTORY) viewModel.clearHistoryFromMemory()
                    screenName = if (state.onboardingComplete) AppScreen.HOME.name else AppScreen.TUTORIAL.name
                }

                // The tutorial runs its own back rule (step by step, then out), so it opts out here.
                BackHandler(enabled = screen != AppScreen.HOME && screen != AppScreen.TUTORIAL) {
                    goHome()
                }

                when (screen) {
                    AppScreen.TUTORIAL -> TutorialScreen(
                        state = state,
                        // During first run LaunchedEffect(onboardingComplete) pulls the screen
                        // straight back, so leaving means leaving the app.
                        onExit = {
                            if (state.onboardingComplete) screenName = tutorialOrigin else finish()
                        },
                        onRequestSmsPermission = requestSmsPermission,
                        onOpenNotificationAccess = openNotificationAccess,
                        onOpenAppSettings = openAppSettings,
                        onOpenAppNotificationSettings = openNotificationSettingsForApp,
                        grantNotificationsBlocked = notificationsBlockedForGrant,
                        onOpenTarget = ::openGuideTarget,
                        onStartGrant = startAdbGrant,
                        onCancelGrant = cancelAdbGrant,
                        onSubmitConnectPort = submitConnectPort,
                        onRequestBatteryExemption = requestBatteryExemption,
                        adbCommand = adbCommand,
                        onCopyAdbCommand = ::copyAdbCommand,
                        onRefresh = viewModel::refreshAndRequestRebind,
                        onComplete = {
                            if (viewModel.completeOnboarding()) {
                                // Mirrors onExit above: re-reading an already-complete tutorial
                                // from Settings returns to Settings; finishing first run goes Home.
                                screenName = if (state.onboardingComplete) tutorialOrigin else AppScreen.HOME.name
                            }
                        },
                    )
                    AppScreen.HOME -> HomeScreen(
                        state = state,
                        onEnabledChange = viewModel::setEnabled,
                        onAdEnabledChange = viewModel::setAdEnabled,
                        onOpenHistory = {
                            screenName = AppScreen.HISTORY.name
                            viewModel.loadHistory(HistorySource.DISASTER)
                        },
                        onOpenAdHistory = {
                            screenName = AppScreen.HISTORY.name
                            viewModel.loadHistory(HistorySource.AD)
                        },
                        onOpenAdLog = {
                            screenName = AppScreen.AD_LOG.name
                            viewModel.loadAdLog()
                        },
                        onOpenSettings = { screenName = AppScreen.SETTINGS.name },
                        onRefresh = viewModel::refreshAndRequestRebind,
                    )
                    AppScreen.HISTORY -> HistoryScreen(
                        state = historyState,
                        canDelete = state.smsDeleteGranted,
                        onBack = goHome,
                        onRefresh = { viewModel.loadHistory() },
                        onToggleSelection = viewModel::toggleHistorySelection,
                        onToggleSelectAll = viewModel::toggleSelectAllHistory,
                        onDeleteSelected = viewModel::deleteSelectedHistory,
                        onDeleteAll = viewModel::deleteAllHistory,
                        onOpenMessagingApp = openMessagingApp,
                    )
                    AppScreen.AD_LOG -> AdLogScreen(
                        entries = adLogEntries,
                        onBack = goHome,
                        onRefresh = viewModel::loadAdLog,
                        onClear = viewModel::clearAdLog,
                    )
                    AppScreen.SETTINGS -> SettingsScreen(
                        state = state,
                        onBack = goHome,
                        onRequestSmsPermission = requestSmsPermission,
                        onOpenNotificationAccess = openNotificationAccess,
                        adbCommand = adbCommand,
                        onCopyAdbCommand = ::copyAdbCommand,
                        onOpenAppSettings = openAppSettings,
                        onRequestBatteryExemption = requestBatteryExemption,
                        onOpenGuide = openTutorial,
                        onStartAdbGrant = startAdbGrant,
                        onRefresh = viewModel::refreshAndRequestRebind,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AdbGrantPreferences(this).reconcileStale(AdbGrantService.isRunning)
        // A grant notification outlives the process that posted it. When no session is running and
        // the permission is already in place, nothing can still need it — clear it, so a teardown
        // interrupted between the last progress post and stopForeground cannot leave
        // "권한 부여 진행 중" pinned to the shade. This does not touch the resume-after-process-death
        // path: there the permission is still missing, so the waiting notification and its reply stay.
        if (!AdbGrantService.isRunning && PermissionState.canDeleteSms(this)) {
            AdbGrantNotifications(this).clearOrphan()
        }
        // The tutorial's own ON_RESUME effect already calls this (it also drives its
        // Settings.Global re-read); every other screen has no refresh of its own and still needs it.
        if (!tutorialActive) viewModel.refreshAndRequestRebind()
    }

    private fun startGrantService() {
        // Ensured here, at the one place the service actually starts, rather than inside the
        // blocked-state probe below - that probe runs during composition and must stay a pure read.
        AdbGrantNotifications(this).ensureChannel()
        ContextCompat.startForegroundService(this, AdbGrantService.startIntent(this))
    }

    /** Opens the Settings page a tutorial screen points at; falls back to Developer options if the target fails to open. */
    private fun openGuideTarget(target: GuideTarget) {
        when (target) {
            GuideTarget.DEVICE_INFO -> openWithDeveloperOptionsFallback(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
            GuideTarget.DEVELOPER_OPTIONS -> openWithDeveloperOptionsFallback(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            GuideTarget.WIRELESS_DEBUGGING -> openWirelessDebuggingSettings()
        }
    }

    private fun openWithDeveloperOptionsFallback(intent: Intent) {
        val fallback = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        // No resolveActivity pre-check: Android 11+ package-visibility filtering can hide a real, resolvable
        // target with no <queries> declared, sending a novice to the fallback screen before it is even needed.
        runCatching { startActivity(intent) }
            .onFailure { runCatching { startActivity(fallback) } }
    }

    /**
     * The dedicated wireless-debugging screen has moved across OEM/Android versions — on this
     * device the plain ComponentName lands on the Security page instead. Try the newer public
     * action first, then the ComponentName this app previously relied on, then Developer options
     * as a last resort. Each attempt is independently guarded; the first that succeeds wins.
     */
    private fun openWirelessDebuggingSettings() {
        val attempts = listOf(
            Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"),
            Intent().setComponent(
                ComponentName("com.android.settings", "com.android.settings.Settings\$WirelessDebuggingActivity"),
            ),
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        )
        for (intent in attempts) {
            if (runCatching { startActivity(intent) }.isSuccess) return
        }
    }

    /** Opens this app's notification settings, e.g. after a POST_NOTIFICATIONS denial or a blocked channel. */
    private fun openAppNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        runCatching { startActivity(intent) }
    }

    /**
     * True unless notifications are off system-wide or the adb_grant channel has been silenced.
     * A pure read: if the channel has not been created yet, [channel] is null and this reports
     * not-blocked, matching what a freshly created channel's default importance would give anyway.
     */
    private fun canShowGrantNotifications(): Boolean {
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannel(AdbGrantNotifications.CHANNEL_ID)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    private enum class AppScreen {
        TUTORIAL,
        HOME,
        HISTORY,
        AD_LOG,
        SETTINGS,
    }
}
