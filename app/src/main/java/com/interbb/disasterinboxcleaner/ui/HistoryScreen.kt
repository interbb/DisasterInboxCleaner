package com.interbb.disasterinboxcleaner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.interbb.disasterinboxcleaner.DisasterMessageItem
import com.interbb.disasterinboxcleaner.HistoryError
import com.interbb.disasterinboxcleaner.HistorySource
import com.interbb.disasterinboxcleaner.HistoryUiState

private enum class DeleteConfirmation {
    SELECTED,
    ALL,
}

/** Screen copy that differs between the disaster-copy list and the ad-SMS list. */
private class HistoryCopy(
    val title: String,
    val countLabel: (Int) -> String,
    val deleteAllLabel: String,
    val emptyText: String,
    val dialogTarget: (count: Int, allRestricted: Boolean) -> String,
    val dialogSuffix: String,
)

private fun copyFor(source: HistorySource): HistoryCopy = when (source) {
    HistorySource.DISASTER -> HistoryCopy(
        title = "기존 재난문자 정리",
        countLabel = { "찾은 복사본 ${it}개" },
        deleteAllLabel = "메시지함의 재난문자 모두 삭제",
        emptyText = "삼성 메시지함에 보이는 재난문자 복사본이 없습니다.",
        dialogTarget = { count, allRestricted ->
            if (allRestricted) "삼성 메시지함의 #CMAS# 재난문자 복사본을 모두" else "${count}개의 재난문자 복사본을"
        },
        dialogSuffix = "시스템 재난문자 기록과 알림에는 영향을 주지 않습니다.",
    )
    HistorySource.AD -> HistoryCopy(
        title = "기존 광고 문자 정리",
        countLabel = { "찾은 광고 문자 ${it}개" },
        deleteAllLabel = "메시지함의 광고 문자 모두 삭제",
        emptyText = "삼성 메시지함에 (광고)/[광고] 표기 문자가 없습니다([Web발신] 같은 통신사 표시 뒤에 오는 표기 포함).",
        dialogTarget = { count, _ -> "${count}개의 광고 문자를" },
        dialogSuffix = "삭제한 문자는 되돌릴 수 없습니다.",
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    state: HistoryUiState,
    canDelete: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggleSelection: (Long) -> Unit,
    onToggleSelectAll: () -> Unit,
    onDeleteSelected: () -> Unit,
    onDeleteAll: () -> Unit,
    onOpenMessagingApp: () -> Unit,
) {
    val copy = copyFor(state.source)
    var confirmation by remember { mutableStateOf<DeleteConfirmation?>(null) }

    confirmation?.let { action ->
        val count = if (action == DeleteConfirmation.ALL) state.messages.size else state.selectedIds.size
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text("메시지함에서 삭제할까요?") },
            text = {
                val target = copy.dialogTarget(
                    count,
                    action == DeleteConfirmation.ALL && state.individualListingRestricted,
                )
                Text("$target 삭제합니다. ${copy.dialogSuffix}")
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmation = null
                        if (action == DeleteConfirmation.ALL) onDeleteAll() else onDeleteSelected()
                    },
                ) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { confirmation = null }) { Text("취소") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(copy.title) },
                navigationIcon = { TextButton(onClick = onBack) { Text("뒤로") } },
            )
        },
    ) { padding ->
        // state.loading is a real "in progress" signal (set synchronously before the IO load, and
        // shared by the initial open, the existing "새로고침" controls, and this gesture), so it
        // drives the indicator directly with no fake delay.
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = onRefresh,
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
                    "이 화면에서만 본문을 기기 메모리에 불러옵니다. 앱을 나가면 목록을 비웁니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!state.loading) {
                state.error?.let { error ->
                    item {
                        Text(historyErrorLabel(error), color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                            Text("다시 불러오기")
                        }
                    }
                }
                if (state.error == null) {
                    state.notice?.let { notice ->
                        item {
                            Text(notice, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (state.individualListingRestricted) {
                                    "개별 목록 제한됨"
                                } else {
                                    copy.countLabel(state.messages.size)
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            TextButton(onClick = onRefresh) { Text("새로고침") }
                        }
                    }
                    if (state.individualListingRestricted) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text(
                                        "개별 목록은 삼성 메시지에서 확인",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        "삼성 메시지가 기본 문자 앱인 동안 Android가 다른 앱에는 제한된 " +
                                            "목록을 제공하며, 이 기기에서는 재난문자 항목이 표시되지 않습니다. " +
                                            "개별 선택 삭제는 삼성 메시지에서 진행하세요.",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    OutlinedButton(
                                        onClick = onOpenMessagingApp,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text("삼성 메시지 열기")
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = onToggleSelectAll,
                                enabled = state.messages.isNotEmpty(),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(if (state.allSelected) "선택 해제" else "모두 선택")
                            }
                            Button(
                                onClick = { confirmation = DeleteConfirmation.SELECTED },
                                enabled = canDelete && state.selectedIds.isNotEmpty(),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("선택 삭제")
                            }
                        }
                    }
                    item {
                        OutlinedButton(
                            onClick = { confirmation = DeleteConfirmation.ALL },
                            enabled = canDelete && state.canRequestDeleteAll,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(copy.deleteAllLabel)
                        }
                    }
                    if (!canDelete && state.messages.isNotEmpty()) {
                        item {
                            Text(
                                "삭제하려면 설정에서 PC용 ADB 메시지함 삭제 권한을 승인하세요.",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (state.messages.isEmpty() && !state.individualListingRestricted) {
                        item {
                            Text(
                                copy.emptyText,
                                modifier = Modifier.padding(vertical = 32.dp),
                            )
                        }
                    }
                    items(state.messages, key = { it.id }) { message ->
                        MessageSelectionCard(
                            message = message,
                            selected = message.id in state.selectedIds,
                            onToggle = { onToggleSelection(message.id) },
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun MessageSelectionCard(
    message: DisasterMessageItem,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(checked = selected, onCheckedChange = null)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    formatTime(message.receivedAt),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (message.address.isNotBlank()) {
                    Text(
                        message.address,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = message.body.ifBlank { "내용 없음" },
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun historyErrorLabel(error: HistoryError): String = when (error) {
    HistoryError.READ_PERMISSION_REQUIRED -> "목록을 보려면 문자 읽기 권한이 필요합니다."
    HistoryError.DELETE_PERMISSION_REQUIRED -> "메시지함 삭제 권한이 필요합니다."
    HistoryError.PROVIDER_REJECTED -> "삼성 메시지함을 불러오거나 변경하지 못했습니다."
}
