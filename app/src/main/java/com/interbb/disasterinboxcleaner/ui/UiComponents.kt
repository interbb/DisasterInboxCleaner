package com.interbb.disasterinboxcleaner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.interbb.disasterinboxcleaner.MonitorError
import com.interbb.disasterinboxcleaner.adb.GrantPhase
import com.interbb.disasterinboxcleaner.adb.GrantStatus
import java.text.DateFormat
import java.util.Date

@Composable
internal fun PermissionSetupCard(
    title: String,
    explanation: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (granted) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = if (granted) "승인됨" else "필요",
                    color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(explanation, style = MaterialTheme.typography.bodyMedium)
            if (!granted) {
                OutlinedButton(onClick = onAction, modifier = Modifier.fillMaxWidth()) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
internal fun StatusRow(label: String, ready: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = if (ready) "정상" else "필요",
            color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

internal fun formatTime(timeMillis: Long): String = if (timeMillis <= 0L) {
    "없음"
} else {
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(timeMillis))
}

internal fun monitorErrorLabel(error: MonitorError): String = when (error) {
    MonitorError.SMS_READ_PERMISSION_REQUIRED -> "문자 읽기 권한이 필요합니다."
    MonitorError.SMS_DELETE_PERMISSION_REQUIRED -> "ADB 메시지함 삭제 권한이 필요합니다."
    MonitorError.SMS_PROVIDER_REJECTED -> "삼성 메시지 저장소가 삭제 요청을 거부했습니다."
}

/** Onboarding/settings card for the on-phone WRITE_SMS grant, with the PC adb command folded away. */
@Composable
internal fun AdbSelfGrantCard(
    title: String,
    granted: Boolean,
    status: GrantStatus,
    onOpenGuide: () -> Unit,
    onStart: () -> Unit,
    adbCommand: String,
    onCopyAdbCommand: () -> Unit,
) {
    var showFallback by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (granted) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = if (granted) "승인됨" else "필요",
                    color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                "PC 없이 폰에서 한 번만 받으면 됩니다. 처음이면 '설정 안내 다시 보기'를 눌러 화면대로 따라 하세요.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (status.phase != GrantPhase.IDLE) {
                Text(
                    status.message,
                    color = if (status.phase == GrantPhase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (!granted) {
                Button(onClick = onOpenGuide, modifier = Modifier.fillMaxWidth()) {
                    Text("설정 안내 다시 보기")
                }
                OutlinedButton(
                    onClick = onStart,
                    enabled = !status.inProgress,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (status.inProgress) "진행 중…" else "바로 시작 (해 본 적 있으면)")
                }
            }
            TextButton(onClick = { showFallback = !showFallback }) {
                Text(if (showFallback) "다른 방법 접기" else "다른 방법 (PC에서 adb)")
            }
            if (showFallback) {
                Text("PC에 USB로 연결하고 다음 명령을 한 번 실행하세요.", style = MaterialTheme.typography.bodySmall)
                Text(adbCommand, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onCopyAdbCommand, modifier = Modifier.fillMaxWidth()) {
                    Text("명령 복사")
                }
            }
        }
    }
}
