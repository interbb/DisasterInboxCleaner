package com.interbb.disasterinboxcleaner

import android.app.Application
import android.content.ComponentName
import android.content.SharedPreferences
import android.service.notification.NotificationListenerService
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.interbb.disasterinboxcleaner.adb.AdbGrantPreferences
import com.interbb.disasterinboxcleaner.adb.GrantStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class AdUiState(
    val enabled: Boolean = false,
    val deletedCount: Long = 0L,
    val lastDeletedAt: Long = 0L,
    val lastError: MonitorError? = null,
)

@Immutable
data class MonitorUiState(
    val onboardingComplete: Boolean = false,
    val enabled: Boolean = false,
    val notificationAccessGranted: Boolean = false,
    val smsReadGranted: Boolean = false,
    val smsDeleteGranted: Boolean = false,
    val listenerConnected: Boolean = false,
    val batteryExempt: Boolean = false,
    val deletedCount: Long = 0L,
    val lastDeletedAt: Long = 0L,
    val lastEventAt: Long = 0L,
    val lastError: MonitorError? = null,
    val ad: AdUiState = AdUiState(),
    val adbGrant: GrantStatus = GrantStatus(),
) {
    val allPermissionsReady: Boolean
        get() = notificationAccessGranted && smsReadGranted && smsDeleteGranted

    /**
     * Gates first-time setup completion only. Unlike [allPermissionsReady], this also requires the
     * battery-optimisation exemption: monitoring itself still works without it (just slower, since
     * Samsung can freeze the app in the background), so [allPermissionsReady]/[isMonitoring] stay
     * unchanged, but a new user should not be able to finish setup while skipping a step that
     * silently delays cleanup later.
     */
    val setupReady: Boolean
        get() = allPermissionsReady && batteryExempt

    val isMonitoring: Boolean
        get() = enabled && allPermissionsReady && listenerConnected

    val isAdMonitoring: Boolean
        get() = ad.enabled && allPermissionsReady && listenerConnected
}

enum class HistorySource {
    DISASTER,
    AD,
}

/** The "individual listing restricted" notice applies only to the disaster copies of a non-default SMS app. */
fun individualListingRestrictedFor(source: HistorySource, isDefaultSmsApp: Boolean): Boolean =
    source == HistorySource.DISASTER && !isDefaultSmsApp

enum class HistoryError {
    READ_PERMISSION_REQUIRED,
    DELETE_PERMISSION_REQUIRED,
    PROVIDER_REJECTED,
}

@Immutable
data class HistoryUiState(
    val source: HistorySource = HistorySource.DISASTER,
    val loading: Boolean = false,
    val messages: List<DisasterMessageItem> = emptyList(),
    val selectedIds: Set<Long> = emptySet(),
    val individualListingRestricted: Boolean = false,
    val notice: String? = null,
    val error: HistoryError? = null,
) {
    val allSelected: Boolean
        get() = messages.isNotEmpty() && selectedIds.size == messages.size

    val canRequestDeleteAll: Boolean
        get() = messages.isNotEmpty() || individualListingRestricted
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val monitorPreferences = MonitorPreferences(application)
    private val adPreferences = AdMonitorPreferences(application)
    private val adbGrantPreferences = AdbGrantPreferences(application)
    private val messageRepository = DisasterMessageRepository(application)
    private val adHistory = AdCleanupHistory.default(application)
    private val adCleaner = AdInboxCleaner(application, adHistory)
    private val _uiState = MutableStateFlow(MonitorUiState())
    val uiState = _uiState.asStateFlow()
    private val _historyState = MutableStateFlow(HistoryUiState())
    val historyState = _historyState.asStateFlow()
    private val _adLogState = MutableStateFlow<List<AdCleanupEntry>>(emptyList())
    val adLogState = _adLogState.asStateFlow()

    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

    init {
        monitorPreferences.registerListener(preferenceListener)
        adPreferences.registerListener(preferenceListener)
        adbGrantPreferences.registerListener(preferenceListener)
        viewModelScope.launch {
            MonitorRuntime.listenerConnected.collect { refresh() }
        }
        refresh()
    }

    fun setEnabled(enabled: Boolean) {
        monitorPreferences.setEnabled(enabled)
        refreshAndRequestRebind()
    }

    fun setAdEnabled(enabled: Boolean) {
        adPreferences.setEnabled(enabled)
        refreshAndRequestRebind()
    }

    fun completeOnboarding(): Boolean {
        refresh()
        if (!_uiState.value.setupReady) return false
        monitorPreferences.completeOnboarding()
        refreshAndRequestRebind()
        return true
    }

    fun refreshAndRequestRebind() {
        refresh()
        val context = getApplication<Application>()
        if (PermissionState.hasNotificationAccess(context)) {
            NotificationListenerService.requestRebind(
                ComponentName(context, DisasterNotificationListenerService::class.java),
            )
        }
    }

    fun refresh() {
        val context = getApplication<Application>()
        val stored = monitorPreferences.snapshot()
        val storedAd = adPreferences.snapshot()
        _uiState.value = MonitorUiState(
            onboardingComplete = stored.onboardingComplete,
            enabled = stored.enabled,
            notificationAccessGranted = PermissionState.hasNotificationAccess(context),
            smsReadGranted = PermissionState.canReadSms(context),
            smsDeleteGranted = PermissionState.canDeleteSms(context),
            listenerConnected = MonitorRuntime.listenerConnected.value,
            batteryExempt = PermissionState.isIgnoringBatteryOptimizations(context),
            deletedCount = stored.deletedCount,
            lastDeletedAt = stored.lastDeletedAt,
            lastEventAt = stored.lastEventAt,
            lastError = stored.lastError,
            ad = AdUiState(
                enabled = storedAd.enabled,
                deletedCount = storedAd.deletedCount,
                lastDeletedAt = storedAd.lastDeletedAt,
                lastError = storedAd.lastError,
            ),
            adbGrant = adbGrantPreferences.snapshot(),
        )
    }

    fun loadHistory(source: HistorySource = _historyState.value.source) {
        loadHistoryWithNotice(source, null)
    }

    private fun loadHistoryWithNotice(source: HistorySource, notice: String?) {
        viewModelScope.launch {
            _historyState.value = HistoryUiState(source = source, loading = true)
            val result = withContext(Dispatchers.IO) {
                when (source) {
                    HistorySource.DISASTER -> messageRepository.loadMessages()
                    HistorySource.AD -> adCleaner.listCandidates()
                }
            }
            _historyState.value = when (result) {
                is HistoryLoadResult.Success -> HistoryUiState(
                    source = source,
                    messages = result.messages,
                    individualListingRestricted = individualListingRestrictedFor(
                        source,
                        PermissionState.isDefaultSmsApp(getApplication<Application>()),
                    ),
                    notice = notice,
                )
                HistoryLoadResult.ReadPermissionRequired ->
                    HistoryUiState(source = source, error = HistoryError.READ_PERMISSION_REQUIRED)
                HistoryLoadResult.ProviderRejected ->
                    HistoryUiState(source = source, error = HistoryError.PROVIDER_REJECTED)
            }
        }
    }

    fun toggleHistorySelection(id: Long) {
        _historyState.value = _historyState.value.let { state ->
            state.copy(
                selectedIds = if (id in state.selectedIds) {
                    state.selectedIds - id
                } else {
                    state.selectedIds + id
                },
            )
        }
    }

    fun toggleSelectAllHistory() {
        _historyState.value = _historyState.value.let { state ->
            state.copy(
                selectedIds = if (state.allSelected) {
                    emptySet()
                } else {
                    state.messages.mapTo(mutableSetOf()) { it.id }
                },
            )
        }
    }

    fun deleteSelectedHistory() {
        val state = _historyState.value
        val ids = state.selectedIds
        if (ids.isEmpty()) return
        deleteHistory(state.source) {
            when (state.source) {
                HistorySource.DISASTER -> messageRepository.deleteMessages(ids)
                HistorySource.AD -> adCleaner.deleteByIds(ids)
            }
        }
    }

    fun deleteAllHistory() {
        val state = _historyState.value
        if (!state.canRequestDeleteAll) return
        deleteHistory(state.source) {
            when (state.source) {
                HistorySource.DISASTER -> messageRepository.deleteAllMessages()
                HistorySource.AD -> adCleaner.deleteAll()
            }
        }
    }

    private fun deleteHistory(source: HistorySource, operation: suspend () -> ManualDeleteResult) {
        viewModelScope.launch {
            _historyState.value = _historyState.value.copy(loading = true, error = null)
            when (val result = withContext(Dispatchers.IO) { operation() }) {
                is ManualDeleteResult.Success -> {
                    when (source) {
                        HistorySource.DISASTER -> monitorPreferences.recordCleanup(result.deleted)
                        HistorySource.AD -> adPreferences.recordCleanup(result.deleted)
                    }
                    loadHistoryWithNotice(source, "삼성 메시지함에서 ${result.deleted}개를 삭제했습니다.")
                }
                ManualDeleteResult.DeletePermissionRequired -> {
                    _historyState.value = _historyState.value.copy(
                        loading = false,
                        error = HistoryError.DELETE_PERMISSION_REQUIRED,
                    )
                }
                ManualDeleteResult.ProviderRejected -> {
                    _historyState.value = _historyState.value.copy(
                        loading = false,
                        error = HistoryError.PROVIDER_REJECTED,
                    )
                }
            }
        }
    }

    fun loadAdLog() {
        viewModelScope.launch {
            _adLogState.value = withContext(Dispatchers.IO) { adHistory.list() }
        }
    }

    fun clearAdLog() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { adHistory.clear() }
            _adLogState.value = emptyList()
        }
    }

    fun clearHistoryFromMemory() {
        _historyState.value = HistoryUiState()
    }

    override fun onCleared() {
        clearHistoryFromMemory()
        monitorPreferences.unregisterListener(preferenceListener)
        adPreferences.unregisterListener(preferenceListener)
        adbGrantPreferences.unregisterListener(preferenceListener)
    }
}
