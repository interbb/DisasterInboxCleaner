package com.interbb.disasterinboxcleaner.adb

/** User-facing messages for every phase and failure. Single source so the UI and notification agree. */
object GrantMessages {
    /** Shared with [AdbCodeInput]'s own messages; see [composeInvalidReply]. */
    const val PORT_CODE_EXAMPLE = "예: 43419 123456"
    /**
     * The escalation path: shown only once a lone code has arrived and [AdbGrantService] could not
     * find the pairing port for it (discovered or typed), never the flow's opening message.
     */
    val WAITING = "페어링 창의 포트와 6자리 코드를 함께 넣으세요. $PORT_CODE_EXAMPLE"
    /**
     * The flow's normal message from the very start (see [AdbGrantMachine]'s Start transition):
     * the app discovers its own pairing port via mDNS in the background, so only the code is
     * needed. Falls back to [WAITING] only if that discovery keeps finding nothing.
     */
    const val WAITING_CODE_ONLY = "페어링 창의 6자리 코드를 입력하세요."
    const val WAITING_CONNECT_PORT = "무선 디버깅 화면의 'IP 주소 및 포트'에서 콜론 뒤 숫자를 넣으세요."
    const val PAIRING = "페어링 중…"
    const val CONNECTING = "연결 중…"
    const val GRANTING = "권한 부여 중…"
    const val DONE = "완료. 이제 무선 디버깅을 꺼도 됩니다."
    const val CANCELLED = "취소했습니다."
    const val BINARY_MISSING = "이 기기에서는 지원되지 않습니다. 아래 'PC에서 adb' 방법을 쓰세요."
    // These two are read on the notification, where the guide's screen numbering is not visible and
    // where the tutorial's own numbering may not exist at all: say what to do, never "N단계".
    const val WIRELESS_OFF = "무선 디버깅이 꺼져 있습니다. 설정 > 개발자 옵션에서 켜고 다시 시도하세요."
    const val NO_WIFI = "Wi-Fi에 연결한 뒤 다시 시도하세요."
    const val PAIR_FAILED = "코드가 틀렸거나 페어링 창이 닫혔습니다. 페어링 창을 다시 열고 새 코드로 시도하세요."
    const val CONNECT_FAILED = "연결에 실패했습니다. 무선 디버깅을 껐다 켜고 다시 시도하세요."
    const val GRANT_FAILED_PREFIX = "권한 부여에 실패했습니다: "
    const val TIMEOUT = "시간이 지나 중단했습니다. 다시 시도하세요."
    const val NOT_WAITING = "진행 중인 작업이 없습니다. 앱에서 '권한 부여 시작'을 다시 누르세요."
    const val SERVICE_START_FAILED = "지금은 코드를 처리할 수 없습니다. 앱을 열어 처음부터 다시 시도하세요."
    const val STALE_SESSION = "이전 진행이 중단됐습니다. 다시 시작하세요."

    /**
     * Combines an invalid-reply message (from [AdbCodeInput.parse]) with the phase's base guidance
     * for the notification body. Both can independently end in [PORT_CODE_EXAMPLE]; when they do,
     * it is shown once rather than twice.
     */
    fun composeInvalidReply(invalidMessage: String, base: String): String {
        val trimmedBase = if (invalidMessage.endsWith(PORT_CODE_EXAMPLE) && base.endsWith(PORT_CODE_EXAMPLE)) {
            base.removeSuffix(PORT_CODE_EXAMPLE).trimEnd()
        } else {
            base
        }
        return if (trimmedBase.isEmpty()) invalidMessage else "$invalidMessage $trimmedBase"
    }
}

sealed interface GrantEvent {
    data object Start : GrantEvent
    data object BinaryMissing : GrantEvent
    data object WirelessDebuggingOff : GrantEvent
    data object NoWifi : GrantEvent
    data object CodeEntered : GrantEvent
    data class PairResult(val ok: Boolean, val connectPortKnown: Boolean = false) : GrantEvent
    data object ConnectPortEntered : GrantEvent
    data class ConnectResult(val ok: Boolean) : GrantEvent
    data class GrantResult(val verified: Boolean, val detail: String) : GrantEvent
    data object Timeout : GrantEvent
    data object Cancel : GrantEvent
}

data class Transition(val phase: GrantPhase, val message: String)

/** Pure state machine for the grant flow; returns null when the event does not apply to the phase. */
object AdbGrantMachine {
    /**
     * True when the persisted status claims an in-progress phase but the service process that owned it is
     * no longer alive (killed/force-stopped while WAITING_CODE etc.) — the 10-minute timeout that would
     * normally fail the session lives in that dead process and will never fire.
     */
    fun shouldResetStale(status: GrantStatus, serviceRunning: Boolean): Boolean =
        status.inProgress && !serviceRunning

    fun next(current: GrantPhase, event: GrantEvent): Transition? {
        if (event is GrantEvent.Cancel) return Transition(GrantPhase.IDLE, GrantMessages.CANCELLED)
        return when (current) {
            GrantPhase.IDLE, GrantPhase.DONE, GrantPhase.FAILED -> when (event) {
                // Ask for the code alone. The pairing dialog is not open yet when the user presses
                // start, so discovery cannot know the port at this moment — but by the time a code
                // arrives the dialog is open, and the service discovers the port then. Only if that
                // still fails does it escalate to GrantMessages.WAITING and ask for the port too.
                GrantEvent.Start -> Transition(GrantPhase.WAITING_CODE, GrantMessages.WAITING_CODE_ONLY)
                GrantEvent.BinaryMissing -> failed(GrantMessages.BINARY_MISSING)
                GrantEvent.WirelessDebuggingOff -> failed(GrantMessages.WIRELESS_OFF)
                GrantEvent.NoWifi -> failed(GrantMessages.NO_WIFI)
                else -> null
            }
            GrantPhase.WAITING_CODE -> when (event) {
                GrantEvent.CodeEntered -> Transition(GrantPhase.PAIRING, GrantMessages.PAIRING)
                GrantEvent.Timeout -> failed(GrantMessages.TIMEOUT)
                GrantEvent.WirelessDebuggingOff -> failed(GrantMessages.WIRELESS_OFF)
                else -> null
            }
            GrantPhase.PAIRING -> when (event) {
                is GrantEvent.PairResult -> when {
                    !event.ok -> failed(GrantMessages.PAIR_FAILED)
                    event.connectPortKnown -> Transition(GrantPhase.CONNECTING, GrantMessages.CONNECTING)
                    else -> Transition(GrantPhase.WAITING_CONNECT_PORT, GrantMessages.WAITING_CONNECT_PORT)
                }
                else -> null
            }
            GrantPhase.WAITING_CONNECT_PORT -> when (event) {
                GrantEvent.ConnectPortEntered -> Transition(GrantPhase.CONNECTING, GrantMessages.CONNECTING)
                GrantEvent.Timeout -> failed(GrantMessages.TIMEOUT)
                GrantEvent.WirelessDebuggingOff -> failed(GrantMessages.WIRELESS_OFF)
                else -> null
            }
            GrantPhase.CONNECTING -> when (event) {
                is GrantEvent.ConnectResult ->
                    if (event.ok) Transition(GrantPhase.GRANTING, GrantMessages.GRANTING) else failed(GrantMessages.CONNECT_FAILED)
                else -> null
            }
            GrantPhase.GRANTING -> when (event) {
                is GrantEvent.GrantResult ->
                    if (event.verified) Transition(GrantPhase.DONE, GrantMessages.DONE)
                    else failed(GrantMessages.GRANT_FAILED_PREFIX + event.detail.ifBlank { "원인 불명" })
                else -> null
            }
        }
    }

    private fun failed(message: String) = Transition(GrantPhase.FAILED, message)
}
