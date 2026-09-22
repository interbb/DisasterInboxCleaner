package com.interbb.disasterinboxcleaner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.interbb.disasterinboxcleaner.AdCleanupEntry
import com.interbb.disasterinboxcleaner.AdCleanupHistory
import com.interbb.disasterinboxcleaner.AdSmsPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdLogScreen(
    entries: List<AdCleanupEntry>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    // loadAdLog() has no exposed "in progress" signal (it's a fast local-file read), so a short,
    // fixed indicator window acknowledges the pull gesture without faking a longer refresh.
    var isRefreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("이력을 지울까요?") },
            text = { Text("광고 삭제 이력 ${entries.size}건을 앱에서 지웁니다. 문자함에는 영향이 없습니다.") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmClear = false
                        onClear()
                    },
                ) { Text("지우기") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("취소") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("광고 삭제 이력") },
                navigationIcon = { TextButton(onClick = onBack) { Text("뒤로") } },
                actions = { TextButton(onClick = onRefresh) { Text("새로고침") } },
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
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        "최근 ${AdCleanupHistory.MAX_ENTRIES}건, " +
                            "${AdCleanupHistory.RETENTION_MS / 86_400_000L}일까지 보관합니다. " +
                            "본문은 앞 ${AdSmsPolicy.SNIPPET_LENGTH}자만 남깁니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    OutlinedButton(
                        onClick = { confirmClear = true },
                        enabled = entries.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("이력 지우기")
                    }
                }
                if (entries.isEmpty()) {
                    item {
                        Text(
                            "아직 삭제한 광고 문자가 없습니다.",
                            modifier = Modifier.padding(vertical = 32.dp),
                        )
                    }
                }
                itemsIndexed(entries) { _, entry ->
                    AdLogCard(entry)
                }
            }
        }
    }
}

@Composable
private fun AdLogCard(entry: AdCleanupEntry) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    formatTime(entry.at),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    ruleLabel(entry.rule),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                entry.address.ifBlank { "발신번호 없음" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                entry.snippet.ifBlank { "내용 없음" },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun ruleLabel(rule: String): String = when (rule) {
    AdSmsPolicy.RULE_MANUAL -> "수동 삭제"
    else -> "자동 삭제"
}
