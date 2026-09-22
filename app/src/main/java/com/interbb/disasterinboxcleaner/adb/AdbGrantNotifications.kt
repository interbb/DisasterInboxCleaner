package com.interbb.disasterinboxcleaner.adb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import com.interbb.disasterinboxcleaner.MainActivity
import com.interbb.disasterinboxcleaner.R

/** One fixed-id notification that carries the grant flow: code input, progress, done, failed. */
class AdbGrantNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "폰에서 권한 부여", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "메시지함 삭제 권한을 폰에서 받는 동안 코드 입력과 진행 상태를 보여줍니다."
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(phase: GrantPhase, message: String): Notification {
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(title(phase))
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(openApp())
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        when (phase) {
            GrantPhase.WAITING_CODE, GrantPhase.WAITING_CONNECT_PORT -> {
                // Drive the label from the message actually shown, not just the phase: the flow
                // normally shows the code-only message from the start, so only the code is
                // wanted, unless it has escalated to asking for the port too.
                // GrantMessages.composeInvalidReply always appends the phase's base message
                // verbatim at the end (in the common case), so this suffix check also covers a
                // composed invalid-reply message shown while in code-only mode.
                val label = when {
                    phase == GrantPhase.WAITING_CONNECT_PORT -> "연결 포트"
                    message.endsWith(GrantMessages.WAITING_CODE_ONLY) -> "코드"
                    else -> "포트 코드"
                }
                val remoteInput = RemoteInput.Builder(KEY_CODE).setLabel(label).build()
                val reply = Notification.Action.Builder(null, "코드 입력", codeIntent())
                    .addRemoteInput(remoteInput)
                    .build()
                val cancel = Notification.Action.Builder(null, "취소", cancelIntent()).build()
                builder.addAction(reply).addAction(cancel).setOngoing(true)
            }
            GrantPhase.PAIRING, GrantPhase.CONNECTING, GrantPhase.GRANTING ->
                builder.setOngoing(true).setProgress(0, 0, true)
            GrantPhase.DONE, GrantPhase.FAILED, GrantPhase.IDLE ->
                builder.setOngoing(false).setAutoCancel(true)
        }
        return builder.build()
    }

    fun show(phase: GrantPhase, message: String) {
        if (phase == GrantPhase.IDLE) {
            manager.cancel(NOTIFICATION_ID)
        } else {
            manager.notify(NOTIFICATION_ID, build(phase, message))
        }
    }

    /**
     * Clears a grant notification that no live session owns any more. A foreground-service
     * notification survives its process, so one orphaned mid-flow (process killed, teardown
     * interrupted) would otherwise sit in the shade reading "권한 부여 진행 중" forever. The caller
     * must only invoke this when no session can still need it — see [AdbGrantService.isRunning].
     */
    fun clearOrphan() {
        manager.cancel(NOTIFICATION_ID)
    }

    private fun title(phase: GrantPhase): String = when (phase) {
        GrantPhase.WAITING_CODE -> "페어링 코드 입력"
        GrantPhase.WAITING_CONNECT_PORT -> "연결 포트 입력"
        GrantPhase.PAIRING, GrantPhase.CONNECTING, GrantPhase.GRANTING -> "권한 부여 진행 중"
        GrantPhase.DONE -> "메시지함 삭제 권한 완료"
        GrantPhase.FAILED -> "권한 부여 실패"
        GrantPhase.IDLE -> "문자함 정리"
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun codeIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        1,
        Intent(context, AdbCodeReceiver::class.java).setAction(AdbCodeReceiver.ACTION_CODE),
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancelIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        2,
        Intent(context, AdbCodeReceiver::class.java).setAction(AdbCodeReceiver.ACTION_CANCEL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_ID = "adb_grant"
        const val NOTIFICATION_ID = 4101
        const val KEY_CODE = "code"
    }
}
