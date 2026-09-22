package com.interbb.disasterinboxcleaner.adb

/** Result of parsing one notification reply, given which phase the service is waiting on. */
sealed interface CodeInput {
    data class PairingAndCode(val port: Int, val code: String) : CodeInput
    /** A lone 6-digit code, with no port typed alongside it — see [AdbCodeInput.parse]. */
    data class Code(val code: String) : CodeInput
    data class ConnectPort(val port: Int) : CodeInput
    data class Invalid(val message: String) : CodeInput
}

/**
 * Parses a notification reply while WAITING_CODE into a port+code pair or a bare code, and while
 * WAITING_CONNECT_PORT into a connect port. Pure Kotlin, no Android dependency beyond [GrantPhase],
 * so it is unit-tested directly. Never includes the parsed pairing code in any [CodeInput.Invalid]
 * message.
 *
 * Both WAITING_CODE shapes are always accepted, regardless of whether the caller already knows a
 * pairing port: the pairing dialog's port is only visible while the dialog is open, which can
 * happen either before or after the user is asked, so the reply — not any mode flag — decides
 * which shape was typed. It is the caller's job to decide what to do with a [CodeInput.Code] when
 * it does not actually know a pairing port yet (see `AdbGrantService.handleCodeOnly`).
 */
object AdbCodeInput {
    private val PAIRING_AND_CODE_MESSAGE =
        "포트와 코드를 함께 입력하거나 코드만 입력하세요. ${GrantMessages.PORT_CODE_EXAMPLE}"
    private const val CONNECT_PORT_MESSAGE = "연결 포트 숫자만 입력하세요."
    private const val PORT_RANGE_MESSAGE = "포트 번호가 올바르지 않습니다."
    private const val CODE_FORMAT_MESSAGE = "코드는 6자리입니다."
    private val PORT_RANGE = 1024..65535
    private const val CODE_LENGTH = 6
    private val DIGIT_RUN_SEPARATOR = Regex("\\D+")

    fun parse(raw: String?, expecting: GrantPhase): CodeInput {
        val runs = raw.orEmpty().split(DIGIT_RUN_SEPARATOR).filter { it.isNotEmpty() }
        return when (expecting) {
            GrantPhase.WAITING_CODE -> parseWaitingForCode(runs)
            GrantPhase.WAITING_CONNECT_PORT -> parseConnectPort(runs)
            else -> CodeInput.Invalid(PAIRING_AND_CODE_MESSAGE)
        }
    }

    /**
     * One run of exactly 6 digits is the code alone; two runs are the port and the code together
     * (in either order — whichever run is 6 digits is the code, the other the port). Anything else
     * is invalid. A typed port always wins over a discovered one at the call site, because the user
     * is reading it off the dialog on screen.
     */
    private fun parseWaitingForCode(runs: List<String>): CodeInput = when (runs.size) {
        1 -> if (runs[0].length == CODE_LENGTH) CodeInput.Code(runs[0]) else CodeInput.Invalid(CODE_FORMAT_MESSAGE)
        2 -> parsePairingAndCode(runs)
        else -> CodeInput.Invalid(PAIRING_AND_CODE_MESSAGE)
    }

    private fun parsePairingAndCode(runs: List<String>): CodeInput {
        val (first, second) = runs
        val firstIsCode = first.length == CODE_LENGTH
        val secondIsCode = second.length == CODE_LENGTH
        val (portRun, codeRun) = when {
            // Both six digits: the rule picks the second run as the code, the first as the port
            // (the port will then fail range validation below, since a real port is never six
            // digits — this still exercises the disambiguation order correctly).
            secondIsCode -> first to second
            firstIsCode -> second to first
            else -> return CodeInput.Invalid(CODE_FORMAT_MESSAGE)
        }
        val port = portRun.toIntOrNull()
        if (port == null || port !in PORT_RANGE) return CodeInput.Invalid(PORT_RANGE_MESSAGE)
        return CodeInput.PairingAndCode(port, codeRun)
    }

    private fun parseConnectPort(runs: List<String>): CodeInput {
        if (runs.size != 1) return CodeInput.Invalid(CONNECT_PORT_MESSAGE)
        val port = runs[0].toIntOrNull()
        if (port == null || port !in PORT_RANGE) return CodeInput.Invalid(PORT_RANGE_MESSAGE)
        return CodeInput.ConnectPort(port)
    }
}
