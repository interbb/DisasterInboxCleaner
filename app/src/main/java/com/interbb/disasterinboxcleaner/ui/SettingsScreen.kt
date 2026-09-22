package com.interbb.disasterinboxcleaner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.interbb.disasterinboxcleaner.BuildConfig
import com.interbb.disasterinboxcleaner.MonitorUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: MonitorUiState,
    onBack: () -> Unit,
    onRequestSmsPermission: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    adbCommand: String,
    onCopyAdbCommand: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onOpenGuide: () -> Unit,
    onStartAdbGrant: () -> Unit,
    onRefresh: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("설정") },
                navigationIcon = { TextButton(onClick = onBack) { Text("뒤로") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text("권한", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            item {
                PermissionSetupCard(
                    title = "알림 접근",
                    explanation = "재난문자·삼성 메시지 알림 감지와 광고 문자 알림 닫기에 사용합니다.",
                    granted = state.notificationAccessGranted,
                    actionLabel = "알림 접근 설정 열기",
                    onAction = onOpenNotificationAccess,
                )
            }
            item {
                PermissionSetupCard(
                    title = "문자 읽기",
                    explanation = "#CMAS# 복사본 식별과 수동 선택 화면에 사용합니다.",
                    granted = state.smsReadGranted,
                    actionLabel = "문자 권한 요청",
                    onAction = onRequestSmsPermission,
                )
            }
            item {
                AdbSelfGrantCard(
                    title = "메시지함 삭제 권한",
                    granted = state.smsDeleteGranted,
                    status = state.adbGrant,
                    onOpenGuide = onOpenGuide,
                    onStart = onStartAdbGrant,
                    adbCommand = adbCommand,
                    onCopyAdbCommand = onCopyAdbCommand,
                )
            }
            item {
                PermissionSetupCard(
                    title = "배터리 최적화 제외",
                    explanation = "백그라운드에서 앱이 얼면 문자함 변경과 알림을 늦게 받아 정리가 미뤄집니다. " +
                        "제외를 요청하고, 삼성 기기는 설정 > 배터리 > 백그라운드 사용 제한 > 절전 예외 앱에도 추가하세요.",
                    granted = state.batteryExempt,
                    actionLabel = "배터리 최적화 제외 요청",
                    onAction = onRequestBatteryExemption,
                )
            }
            item {
                OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                    Text("권한 상태 새로고침")
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("앱 정보", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("버전 ${BuildConfig.VERSION_NAME}")
                        Text("패키지 ${BuildConfig.APPLICATION_ID}")
                        Text("인터넷 접속 없음 (내장 adb는 이 폰 안에서만 통신)")
                        Text("동봉 adb: Apache License 2.0 (AOSP), LADB 빌드")
                        Text("재난문자 감시는 본문을 조회하지 않고, 광고 판정은 본문 앞부분만 확인")
                        Text("선택 정리 화면의 본문은 기기 메모리에만 잠시 표시")
                        OutlinedButton(onClick = onOpenAppSettings, modifier = Modifier.fillMaxWidth()) {
                            Text("Android 앱 정보 열기")
                        }
                    }
                }
            }
        }
    }
}
