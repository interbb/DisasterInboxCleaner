package com.interbb.disasterinboxcleaner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.interbb.disasterinboxcleaner.MonitorUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: MonitorUiState,
    onEnabledChange: (Boolean) -> Unit,
    onAdEnabledChange: (Boolean) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenAdHistory: () -> Unit,
    onOpenAdLog: () -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
) {
    // refreshAndRequestRebind() is synchronous, so there is no real "in progress" signal to show.
    // A short, fixed indicator window acknowledges the pull gesture without faking a longer refresh.
    var isRefreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("문자함 정리") },
                actions = { TextButton(onClick = onOpenSettings) { Text("설정") } },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                onRefresh()
                scope.launch {
                    isRefreshing = true
                    delay(500)
                    isRefreshing = false
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    FeatureSwitchCard(
                        title = "재난문자 복사본 정리",
                        enabled = state.enabled,
                        isMonitoring = state.isMonitoring,
                        enabledText = "새 재난문자 복사본을 자동 정리합니다.",
                        disabledText = "재난문자 복사본 자동 정리를 사용하지 않습니다.",
                        onEnabledChange = onEnabledChange,
                    )
                }
                item {
                    FeatureSwitchCard(
                        title = "광고 문자 자동 삭제",
                        enabled = state.ad.enabled,
                        isMonitoring = state.isAdMonitoring,
                        enabledText = "(광고)/[광고]로 시작하거나 [Web발신] 같은 통신사 표시 뒤에 오는 문자를 수신 즉시 삭제하고 알림을 닫습니다.",
                        disabledText = "광고 문자 자동 삭제를 사용하지 않습니다.",
                        onEnabledChange = onAdEnabledChange,
                    )
                }
                item {
                    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("현재 상태", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            StatusRow("감시 서비스", state.listenerConnected)
                            StatusRow("알림 접근", state.notificationAccessGranted)
                            StatusRow("문자 읽기", state.smsReadGranted)
                            StatusRow("메시지함 삭제", state.smsDeleteGranted)
                            StatusRow("배터리 제외", state.batteryExempt)
                            Text("정리한 재난문자 복사본: ${state.deletedCount}개")
                            Text("마지막 재난문자 감지: ${formatTime(state.lastEventAt)}")
                            Text("마지막 재난문자 정리: ${formatTime(state.lastDeletedAt)}")
                            state.lastError?.let { error ->
                                Text(
                                    "재난문자: " + monitorErrorLabel(error),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            Text("삭제한 광고 문자: ${state.ad.deletedCount}개")
                            Text("마지막 광고 삭제: ${formatTime(state.ad.lastDeletedAt)}")
                            state.ad.lastError?.let { error ->
                                Text(
                                    "광고: " + monitorErrorLabel(error),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
                item {
                    Button(onClick = onOpenHistory, modifier = Modifier.fillMaxWidth()) {
                        Text("기존 재난문자 선택 정리")
                    }
                }
                item {
                    Button(onClick = onOpenAdHistory, modifier = Modifier.fillMaxWidth()) {
                        Text("기존 광고 문자 정리")
                    }
                }
                item {
                    OutlinedButton(onClick = onOpenAdLog, modifier = Modifier.fillMaxWidth()) {
                        Text("광고 삭제 이력")
                    }
                }
                item {
                    Text(
                        "자동 정리는 각 기능을 켠 뒤 새로 수신되는 문자에만 적용됩니다. " +
                            "그 전 문자는 위의 정리 화면에서 직접 지웁니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun FeatureSwitchCard(
    title: String,
    enabled: Boolean,
    isMonitoring: Boolean,
    enabledText: String,
    disabledText: String,
    onEnabledChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEnabledChange(!enabled) },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isMonitoring) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (isMonitoring) "감시 중" else if (enabled) "설정 확인 필요" else "기능 꺼짐",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                Text(text = if (enabled) enabledText else disabledText)
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
    }
}
