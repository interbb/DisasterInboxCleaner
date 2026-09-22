package com.interbb.disasterinboxcleaner.ui

import android.content.Context
import android.provider.Settings
import androidx.annotation.DrawableRes
import com.interbb.disasterinboxcleaner.R
import com.interbb.disasterinboxcleaner.adb.AdbGrantService
import com.interbb.disasterinboxcleaner.adb.GrantMessages
import com.interbb.disasterinboxcleaner.adb.GrantPhase

/** A Settings screen the tutorial can send the user to. */
enum class GuideTarget {
    DEVICE_INFO,
    DEVELOPER_OPTIONS,
    WIRELESS_DEBUGGING,
}

/**
 * The only place the tutorial reads Settings.Global. Neither value is part of MonitorUiState, so
 * the screen re-reads both on every ON_RESUME tick (see TutorialScreen).
 */
object GuideSteps {
    fun developerOptionsOn(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    fun wirelessDebuggingOn(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, AdbGrantService.SETTING_ADB_WIFI_ENABLED, 0) == 1
}

/** The eight tutorial screens, in flow order. */
enum class TutorialStep {
    WELCOME,
    SMS_READ,
    NOTIFICATION_ACCESS,
    DEVELOPER_OPTIONS,
    WIRELESS_DEBUGGING,
    DELETE_GRANT,
    BATTERY_EXEMPTION,
    DONE,
}

/**
 * The faces of the single DELETE_GRANT screen. Pairing never advances the step: the notification
 * takes the user out of the app, so the screen they come back to must be the one they left.
 */
enum class GrantFace {
    READY,
    NOTIFICATIONS_BLOCKED,
    WAITING_CODE,
    WAITING_PORT,
    WORKING,
    FAILED,
    GRANTED,
}

/** One screenshot plate plus its caption; the caption doubles as the contentDescription. */
data class TutorialPlate(
    val key: String,
    @param:DrawableRes val res: Int,
    val caption: String,
    /** guide_07 is the only landscape capture; it lays out full-width instead of column-capped. */
    val landscape: Boolean = false,
)

/**
 * Everything the tutorial needs to decide what to show, as plain data so the whole model is
 * testable without Robolectric.
 */
data class TutorialContext(
    val smsReadGranted: Boolean = false,
    val notificationAccessGranted: Boolean = false,
    val smsDeleteGranted: Boolean = false,
    val batteryExempt: Boolean = false,
    val developerOptionsOn: Boolean = false,
    val wirelessDebuggingOn: Boolean = false,
    val grantPhase: GrantPhase = GrantPhase.IDLE,
    val grantMessage: String = "",
    val notificationsBlocked: Boolean = false,
) {
    val allPermissionsReady: Boolean
        get() = smsReadGranted && notificationAccessGranted && smsDeleteGranted
}

/** The eight One UI captures the tutorial shows, with the caption each one carries. */
object TutorialPlates {
    val softwareInfo = TutorialPlate(
        key = "guide_01",
        res = R.drawable.guide_01_software_info,
        caption = "'폰 정보'에서 '소프트웨어 정보'",
    )
    val buildNumber = TutorialPlate(
        key = "guide_02",
        res = R.drawable.guide_02_build_number,
        caption = "'소프트웨어 정보'에서 '빌드번호'",
    )
    val wirelessSwitch = TutorialPlate(
        key = "guide_03",
        res = R.drawable.guide_03_dev_wireless_switch,
        caption = "'개발자 옵션'의 '무선 디버깅'",
    )
    val wirelessAllow = TutorialPlate(
        key = "guide_04",
        res = R.drawable.guide_04_wireless_allow,
        caption = "확인 창의 '허용'",
    )
    val pairingEntry = TutorialPlate(
        key = "guide_05",
        res = R.drawable.guide_05_wireless_page,
        caption = "'페어링 코드로 기기 페어링'",
    )
    val connectPortSource = TutorialPlate(
        key = "guide_05",
        res = R.drawable.guide_05_wireless_page,
        caption = "'무선 디버깅' 화면 맨 위 'IP 주소 및 포트'",
    )
    val pairingDialog = TutorialPlate(
        key = "guide_06",
        res = R.drawable.guide_06_pairing_dialog,
        caption = "'기기 페어링' 창의 코드와 포트",
    )
    val notificationInput = TutorialPlate(
        key = "guide_07",
        res = R.drawable.guide_07_notification_input,
        caption = "알림의 '코드 입력'으로 열리는 입력칸",
        landscape = true,
    )
    val toggleOff = TutorialPlate(
        key = "guide_08",
        res = R.drawable.guide_08_toggle_off,
        caption = "켜져 있는 '무선 디버깅' 끄기",
    )
}

/**
 * Turns the grant service's own messages into tutorial wording. The service is off limits to this
 * screen, and its failure strings point at numbered steps ("2단계를 확인하세요") that this flow does
 * not have — so every failure is restated here instead of being shown raw.
 */
object TutorialGrantText {
    const val GENERIC_FAILURE = "권한 부여에 실패했습니다. 아래에서 다시 시도하세요."
    const val WIRELESS_OFF = "무선 디버깅이 꺼져 있습니다. '무선 디버깅 켜기'로 돌아가 스위치를 켠 뒤 다시 시도하세요."
    const val PAIR_FAILED = "코드가 틀렸거나 '기기 페어링' 창이 닫혔습니다. 창을 새로 열어 새 코드를 받은 뒤 다시 시도하세요."
    const val CONNECT_FAILED = "연결에 실패했습니다. '무선 디버깅'을 껐다 켠 뒤 다시 시도하세요."
    const val NO_WIFI = "Wi-Fi에 연결한 뒤 다시 시도하세요."
    const val BINARY_MISSING = "이 기기에서는 폰 안에서 권한을 받을 수 없습니다. 아래 '다른 방법 (PC에서 adb)'을 쓰세요."
    const val TIMEOUT = "시간이 지나 중단했습니다. 다시 시도하세요."
    const val STALE_SESSION = "이전 진행이 중단됐습니다. 다시 시작하세요."
    const val CANCELLED = "취소했습니다."
    const val NOT_READY = "지금은 코드를 처리할 수 없습니다. 아래에서 다시 시작하세요."

    const val PAIRING = "페어링 중입니다."
    const val CONNECTING = "연결 중입니다."
    const val GRANTING = "권한을 부여하는 중입니다."

    /** A leftover mention of a numbered step is the one thing that must never reach the screen. */
    private const val STEP_WORD = "단계"

    fun failure(message: String): String = when {
        message.isBlank() -> GENERIC_FAILURE
        message == GrantMessages.WIRELESS_OFF -> WIRELESS_OFF
        message == GrantMessages.PAIR_FAILED -> PAIR_FAILED
        message == GrantMessages.CONNECT_FAILED -> CONNECT_FAILED
        message == GrantMessages.NO_WIFI -> NO_WIFI
        message == GrantMessages.BINARY_MISSING -> BINARY_MISSING
        message == GrantMessages.TIMEOUT -> TIMEOUT
        message == GrantMessages.STALE_SESSION -> STALE_SESSION
        message == GrantMessages.CANCELLED -> CANCELLED
        message == GrantMessages.NOT_WAITING || message == GrantMessages.SERVICE_START_FAILED -> NOT_READY
        message.startsWith(GrantMessages.GRANT_FAILED_PREFIX) -> GENERIC_FAILURE
        message.contains(STEP_WORD) -> GENERIC_FAILURE
        else -> message
    }

    /** The adb output behind a GRANT_FAILED, shown under [failure] in a quieter style. */
    fun failureDetail(message: String): String? =
        message.removePrefix(GrantMessages.GRANT_FAILED_PREFIX)
            .takeIf { message.startsWith(GrantMessages.GRANT_FAILED_PREFIX) && it.isNotBlank() }

    /** True only for a failure the user fixes by going back to the wireless-debugging screen. */
    fun failureIsWirelessOff(message: String): Boolean = message == GrantMessages.WIRELESS_OFF

    fun progress(phase: GrantPhase): String? = when (phase) {
        GrantPhase.PAIRING -> PAIRING
        GrantPhase.CONNECTING -> CONNECTING
        GrantPhase.GRANTING -> GRANTING
        else -> null
    }

    /**
     * Whether the notification is asking for the pairing port alongside the code. The service now
     * starts at [GrantMessages.WAITING_CODE_ONLY] and escalates to [GrantMessages.WAITING] only
     * when a code arrives and discovery still cannot find the port, so code-only is the default and
     * this is the exception.
     *
     * Matching is by suffix, not equality, because an invalid reply is composed in front of the
     * base message (see [GrantMessages.composeInvalidReply]). That composition also drops a
     * duplicated "예: ..." tail from the base, so the trimmed form is matched too.
     */
    fun asksForPort(message: String): Boolean {
        val trimmed = GrantMessages.WAITING.removeSuffix(GrantMessages.PORT_CODE_EXAMPLE).trimEnd()
        return message.endsWith(GrantMessages.WAITING) || message.endsWith(trimmed)
    }
}

/** Every user-facing string of the tutorial, kept out of the composables so tests can read it. */
object TutorialCopy {
    const val BACK = "뒤로"
    const val NEXT = "다음"
    const val SATISFIED = "확인됨"

    const val TITLE_WELCOME = "문자함 정리 설정"
    const val TITLE_SMS_READ = "문자 읽기 허용"
    const val TITLE_NOTIFICATION_ACCESS = "알림 접근 허용"
    const val TITLE_DEVELOPER_OPTIONS = "개발자 옵션 켜기"
    const val TITLE_WIRELESS_DEBUGGING = "무선 디버깅 켜기"
    const val TITLE_DELETE_GRANT = "삭제 권한 받기"
    const val TITLE_BATTERY_EXEMPTION = "배터리 최적화 제외"
    const val TITLE_DONE = "설정 완료"

    const val WELCOME_BODY =
        "이 앱은 삼성 메시지함에 쌓이는 재난문자 복사본과 광고 문자를 지웁니다. " +
            "재난문자의 시스템 알림과 경고음, '안전 및 긴급'에 남는 원본 기록은 그대로 둡니다." +
            "\n\n" +
            "권한 세 가지가 필요합니다. 문자 읽기와 알림 접근은 버튼 한 번이면 되고, " +
            "문자를 지우는 권한은 안드로이드에 설정 화면이 없어 이 폰 안에서 한 번 직접 켜야 합니다." +
            "\n\n" +
            "약 3분 걸립니다. PC는 필요 없습니다. 폰이 Wi-Fi에 연결돼 있어야 합니다."
    const val WELCOME_PRIMARY = "시작"

    const val SMS_READ_BODY =
        "지울 문자를 찾으려면 메시지함을 읽을 수 있어야 합니다. 재난문자 감시는 본문을 조회하지 않고, " +
            "광고 판정은 본문 앞부분만 확인합니다. 읽은 내용은 폰 밖으로 나가지 않습니다." +
            "\n\n" +
            "아래를 누르면 안드로이드가 문자 권한을 묻습니다. '허용'을 누르세요."
    const val SMS_READ_SATISFIED = "문자 읽기 권한이 있습니다."
    const val SMS_READ_HINT = "권한 창이 뜨지 않으면 앱 정보의 '권한'에서 문자를 직접 허용하세요."
    const val SMS_READ_PRIMARY = "문자 권한 허용"
    const val SMS_READ_SECONDARY = "앱 정보 열기"

    const val NOTIFICATION_ACCESS_BODY =
        "문자가 도착한 순간을 알아채고, 광고 문자 알림을 닫는 데 씁니다. 재난문자 알림은 그대로 둡니다." +
            "\n\n" +
            "아래를 누르면 시스템 설정의 앱 목록이 열립니다. 목록에서 '문자함 정리'를 찾아 스위치를 켜고, " +
            "확인 창이 나오면 허용을 선택한 뒤 뒤로 돌아오세요."
    const val NOTIFICATION_ACCESS_SATISFIED = "알림 접근이 켜져 있습니다."
    const val NOTIFICATION_ACCESS_HINT = "목록에는 설치된 앱이 모두 나옵니다. 화면을 내려 '문자함 정리'를 찾으세요."
    const val NOTIFICATION_ACCESS_PRIMARY = "알림 접근 설정 열기"

    const val DEVELOPER_OPTIONS_BODY =
        "문자를 지우는 권한은 '개발자 옵션' 안의 기능을 통해서만 켤 수 있습니다. " +
            "개발자 옵션을 켜도 폰이 달라지지는 않고, 설정 목록에 항목이 하나 늘어납니다." +
            "\n\n" +
            "아래를 누르면 '폰 정보'가 열립니다. '소프트웨어 정보'를 누르고, '빌드번호'를 일곱 번 연속으로 누르세요. " +
            "잠금 비밀번호나 패턴을 물으면 평소 쓰던 것을 입력하세요." +
            "\n\n" +
            "다 되면 설정 목록 아래쪽에 '개발자 옵션'이 생깁니다. 안내 문구를 놓쳐도 괜찮습니다. " +
            "이 화면으로 돌아오면 앱이 직접 확인합니다."
    const val DEVELOPER_OPTIONS_SATISFIED = "개발자 옵션이 켜져 있습니다."
    const val PLATE_HINT = "그림을 누르면 크게 볼 수 있습니다."
    const val DEVELOPER_OPTIONS_PRIMARY = "폰 정보 열기"

    const val WIRELESS_DEBUGGING_BODY =
        "앱이 이 폰 자신에게 권한을 주는 동안만 필요합니다. 끝나면 다시 꺼도 됩니다. " +
            "이 스위치는 지금 연결된 Wi-Fi 안에서만 동작합니다." +
            "\n\n" +
            "아래를 누르면 '개발자 옵션'이 열립니다. '무선 디버깅' 스위치를 켜고, " +
            "'이 네트워크에서 무선 디버깅을 허용하시겠습니까?' 창에서 '허용'을 누르세요. " +
            "같은 창의 '이 네트워크에서 항상 허용'을 함께 선택하면 다음부터 이 창이 뜨지 않습니다. 다 하면 뒤로 돌아오세요."
    const val WIRELESS_DEBUGGING_SATISFIED = "무선 디버깅이 켜져 있습니다."
    const val WIRELESS_DEBUGGING_PRIMARY = "개발자 옵션 열기"

    const val GRANT_READY_BODY =
        "아래를 누르면 '페어링 코드 입력' 알림이 옵니다. 알림이 오면 그대로 두고, 이 화면에 나오는 안내를 따르세요. " +
            "이 과정은 이 폰과 같은 Wi-Fi 안에서만 이루어지며, 인터넷으로 나가지 않습니다."
    const val GRANT_READY_PRIMARY = "권한 부여 시작"

    const val GRANT_BLOCKED_BODY =
        "이 앱의 알림이 꺼져 있습니다. 코드를 넣는 칸이 알림 안에 있어서, 알림을 켜야 진행할 수 있습니다." +
            "\n\n" +
            "아래에서 알림 설정을 열고 '문자함 정리'의 알림을 켠 뒤 돌아오세요."
    const val GRANT_BLOCKED_PRIMARY = "알림 설정 열기"

    const val GRANT_CODE_ONLY_BODY =
        "앱이 페어링 포트를 스스로 찾습니다. 여섯 자리 코드만 넣으면 됩니다." +
            "\n\n" +
            "아래를 눌러 '무선 디버깅' 화면을 열고 '페어링 코드로 기기 페어링'을 누르세요. " +
            "'기기 페어링' 창에 여섯 자리 'Wi-Fi 페어링 코드'가 나옵니다." +
            "\n\n" +
            "그 창을 닫지 말고 화면 위에서 아래로 쓸어내려 '문자함 정리' 알림을 여세요. " +
            "알림의 '코드 입력'을 누르면 '코드' 칸이 열립니다. 여섯 자리를 넣고 '전송'을 누르세요."
    const val GRANT_PORT_AND_CODE_BODY =
        "아래를 눌러 '무선 디버깅' 화면을 열고 '페어링 코드로 기기 페어링'을 누르세요. " +
            "'기기 페어링' 창에 여섯 자리 'Wi-Fi 페어링 코드'와 'IP 주소 및 포트'가 함께 나옵니다." +
            "\n\n" +
            "그 창을 닫지 말고 화면 위에서 아래로 쓸어내려 '문자함 정리' 알림을 여세요. " +
            "알림의 '코드 입력'을 누르면 '포트 코드' 칸이 열립니다. " +
            "그 창의 'IP 주소 및 포트'에서 콜론 뒤 숫자와 여섯 자리 코드를 띄어 쓴 다음 '전송'을 누르세요. 예: 43419 123456" +
            "\n\n" +
            "알림의 '취소'를 누르면 처음부터 다시 해야 합니다. 창이 저절로 닫혔으면 '페어링 코드로 기기 페어링'을 다시 누르세요. " +
            "코드는 새로 나옵니다."
    const val GRANT_CODE_HINT =
        "그림을 누르면 크게 볼 수 있습니다. 그림에서 코드와 주소는 가려져 있습니다. 실제 화면에는 숫자가 보입니다."
    const val GRANT_CODE_PRIMARY = "무선 디버깅 화면 열기"
    const val GRANT_CANCEL = "진행 취소"

    const val GRANT_PORT_BODY =
        "페어링이 끝났습니다. 알림이 이번에는 연결 포트를 묻습니다." +
            "\n\n" +
            "'무선 디버깅' 화면 맨 위 'IP 주소 및 포트'에서 콜론 뒤 숫자만 알림의 '연결 포트' 칸에 넣고 '전송'을 누르세요. " +
            "앞에서 본 '기기 페어링' 창의 포트와는 다른 숫자입니다. 이 숫자는 잘 바뀌지 않아 한 번만 넣으면 됩니다."

    const val GRANT_WORKING_BODY = "알림에서 이어서 진행 중입니다. 잠시 기다리세요. 이 화면을 떠나도 진행은 계속됩니다."
    const val GRANT_WORKING_PRIMARY = "진행 중…"

    const val GRANT_FAILED_BODY = "아래에서 다시 시작할 수 있습니다."
    const val GRANT_FAILED_PRIMARY = "다시 시도"
    const val GRANT_FAILED_BACK_TO_WIRELESS = "무선 디버깅 켜기로 돌아가기"

    const val GRANT_GRANTED_BODY = "메시지함 삭제 권한을 받았습니다."

    const val BATTERY_EXEMPTION_BODY =
        "배터리 최적화에서 제외해야 문자가 와도 앱이 멈추지 않습니다. 제외하지 않으면 문자가 와도 한참 뒤에야 정리됩니다."
    const val BATTERY_EXEMPTION_SATISFIED = "배터리 최적화에서 제외돼 있습니다."
    const val BATTERY_EXEMPTION_HINT =
        "삼성 기기는 설정 > 배터리 > 백그라운드 사용 제한 > 절전 예외 앱에도 추가하면 더 확실합니다."
    const val BATTERY_EXEMPTION_PRIMARY = "배터리 최적화 제외"

    const val MANUAL_PORT_BLOCK = "포트 직접 입력"
    const val MANUAL_PORT_FIELD = "연결 포트"
    const val MANUAL_PORT_APPLY = "포트 적용"
    const val PC_BLOCK = "다른 방법 (PC에서 adb)"
    const val PC_BLOCK_BODY = "PC에 USB로 연결하고 다음 명령을 한 번 실행하세요."
    const val PC_BLOCK_COPY = "명령 복사"
    const val BLOCK_COLLAPSE_SUFFIX = " 접기"

    const val DONE_SMS_READ_ROW = "문자 읽기 — 지울 문자를 찾습니다"
    const val DONE_NOTIFICATION_ROW = "알림 접근 — 문자 도착을 알아챕니다"
    const val DONE_DELETE_ROW = "메시지함 삭제 권한 — 찾은 문자를 지웁니다"
    const val DONE_BODY = "정리는 홈 화면의 스위치로 켜고 끕니다. 재난문자 복사본과 광고 문자는 각각 따로 켭니다."
    const val DONE_WIRELESS_OFF =
        "무선 디버깅은 이제 꺼도 됩니다. '개발자 옵션'에서 '무선 디버깅' 스위치를 한 번 더 눌러 끄세요."
    const val DONE_WIRELESS_OFF_ACTION = "개발자 옵션 열기"

    const val DONE_MISSING_PREFIX = "아직 남은 것이 있습니다: "
    const val DONE_PRIMARY = "시작하기"
    const val DONE_PRIMARY_INCOMPLETE = "남은 단계로 돌아가기"

    const val LABEL_SMS_READ = "문자 읽기"
    const val LABEL_NOTIFICATION_ACCESS = "알림 접근"
    const val LABEL_SMS_DELETE = "메시지함 삭제 권한"
    const val LABEL_BATTERY_EXEMPTION = "배터리 최적화 제외"

    fun title(step: TutorialStep): String = when (step) {
        TutorialStep.WELCOME -> TITLE_WELCOME
        TutorialStep.SMS_READ -> TITLE_SMS_READ
        TutorialStep.NOTIFICATION_ACCESS -> TITLE_NOTIFICATION_ACCESS
        TutorialStep.DEVELOPER_OPTIONS -> TITLE_DEVELOPER_OPTIONS
        TutorialStep.WIRELESS_DEBUGGING -> TITLE_WIRELESS_DEBUGGING
        TutorialStep.DELETE_GRANT -> TITLE_DELETE_GRANT
        TutorialStep.BATTERY_EXEMPTION -> TITLE_BATTERY_EXEMPTION
        TutorialStep.DONE -> TITLE_DONE
    }

    /** The main paragraph of a screen; DONE assembles its own blocks around [DONE_BODY]. */
    fun body(step: TutorialStep, face: GrantFace, ctx: TutorialContext): String {
        val satisfied = TutorialModel.isSatisfied(step, ctx)
        return when (step) {
            TutorialStep.WELCOME -> WELCOME_BODY
            TutorialStep.SMS_READ -> if (satisfied) SMS_READ_SATISFIED else SMS_READ_BODY
            TutorialStep.NOTIFICATION_ACCESS -> if (satisfied) NOTIFICATION_ACCESS_SATISFIED else NOTIFICATION_ACCESS_BODY
            TutorialStep.DEVELOPER_OPTIONS -> if (satisfied) DEVELOPER_OPTIONS_SATISFIED else DEVELOPER_OPTIONS_BODY
            TutorialStep.WIRELESS_DEBUGGING -> if (satisfied) WIRELESS_DEBUGGING_SATISFIED else WIRELESS_DEBUGGING_BODY
            TutorialStep.DELETE_GRANT -> grantBody(face, ctx)
            TutorialStep.BATTERY_EXEMPTION -> if (satisfied) BATTERY_EXEMPTION_SATISFIED else BATTERY_EXEMPTION_BODY
            TutorialStep.DONE -> DONE_BODY
        }
    }

    fun grantBody(face: GrantFace, ctx: TutorialContext): String = when (face) {
        GrantFace.READY -> GRANT_READY_BODY
        GrantFace.NOTIFICATIONS_BLOCKED -> GRANT_BLOCKED_BODY
        GrantFace.WAITING_CODE ->
            if (TutorialGrantText.asksForPort(ctx.grantMessage)) GRANT_PORT_AND_CODE_BODY else GRANT_CODE_ONLY_BODY
        GrantFace.WAITING_PORT -> GRANT_PORT_BODY
        GrantFace.WORKING -> GRANT_WORKING_BODY
        GrantFace.FAILED -> GRANT_FAILED_BODY
        GrantFace.GRANTED -> GRANT_GRANTED_BODY
    }

    fun hint(step: TutorialStep, face: GrantFace, ctx: TutorialContext): String? {
        if (TutorialModel.isSatisfied(step, ctx) && step != TutorialStep.DONE) return null
        return when (step) {
            TutorialStep.SMS_READ -> SMS_READ_HINT
            TutorialStep.NOTIFICATION_ACCESS -> NOTIFICATION_ACCESS_HINT
            TutorialStep.DEVELOPER_OPTIONS -> PLATE_HINT
            TutorialStep.DELETE_GRANT -> if (face == GrantFace.WAITING_CODE) GRANT_CODE_HINT else null
            TutorialStep.BATTERY_EXEMPTION -> BATTERY_EXEMPTION_HINT
            else -> null
        }
    }

    fun primaryLabel(step: TutorialStep, face: GrantFace, ctx: TutorialContext): String {
        val satisfied = TutorialModel.isSatisfied(step, ctx)
        return when (step) {
            TutorialStep.WELCOME -> WELCOME_PRIMARY
            TutorialStep.SMS_READ -> if (satisfied) NEXT else SMS_READ_PRIMARY
            TutorialStep.NOTIFICATION_ACCESS -> if (satisfied) NEXT else NOTIFICATION_ACCESS_PRIMARY
            TutorialStep.DEVELOPER_OPTIONS -> if (satisfied) NEXT else DEVELOPER_OPTIONS_PRIMARY
            TutorialStep.WIRELESS_DEBUGGING -> if (satisfied) NEXT else WIRELESS_DEBUGGING_PRIMARY
            TutorialStep.DELETE_GRANT -> when (face) {
                GrantFace.READY -> GRANT_READY_PRIMARY
                GrantFace.NOTIFICATIONS_BLOCKED -> GRANT_BLOCKED_PRIMARY
                GrantFace.WAITING_CODE, GrantFace.WAITING_PORT -> GRANT_CODE_PRIMARY
                GrantFace.WORKING -> GRANT_WORKING_PRIMARY
                GrantFace.FAILED -> GRANT_FAILED_PRIMARY
                GrantFace.GRANTED -> NEXT
            }
            TutorialStep.BATTERY_EXEMPTION -> if (satisfied) NEXT else BATTERY_EXEMPTION_PRIMARY
            TutorialStep.DONE -> if (ctx.allPermissionsReady) DONE_PRIMARY else DONE_PRIMARY_INCOMPLETE
        }
    }

    fun secondaryLabel(step: TutorialStep, face: GrantFace, ctx: TutorialContext): String? = when (step) {
        TutorialStep.SMS_READ -> if (ctx.smsReadGranted) null else SMS_READ_SECONDARY
        TutorialStep.DELETE_GRANT -> when (face) {
            GrantFace.WAITING_CODE, GrantFace.WAITING_PORT, GrantFace.WORKING -> GRANT_CANCEL
            GrantFace.FAILED ->
                if (TutorialGrantText.failureIsWirelessOff(ctx.grantMessage)) GRANT_FAILED_BACK_TO_WIRELESS else null
            else -> null
        }
        else -> null
    }

    /** The remaining-items sentence of the DONE screen. */
    fun missingSentence(ctx: TutorialContext): String? {
        val missing = TutorialModel.missingLabels(ctx)
        return if (missing.isEmpty()) null else DONE_MISSING_PREFIX + missing.joinToString(", ") + "."
    }
}

/** The whole flow as pure functions: which screens exist, where a button goes, which face shows. */
object TutorialModel {
    val allSteps: List<TutorialStep> = TutorialStep.entries.toList()

    /**
     * A screen this phone has nothing to do on. Skippable screens are dropped only while they have
     * never been shown, so the screen a user is standing on never vanishes under them.
     */
    fun isSkippable(step: TutorialStep, ctx: TutorialContext): Boolean = when (step) {
        TutorialStep.WELCOME, TutorialStep.DONE -> false
        TutorialStep.SMS_READ -> ctx.smsReadGranted
        TutorialStep.NOTIFICATION_ACCESS -> ctx.notificationAccessGranted
        TutorialStep.DEVELOPER_OPTIONS -> ctx.developerOptionsOn || ctx.smsDeleteGranted
        TutorialStep.WIRELESS_DEBUGGING -> ctx.wirelessDebuggingOn || ctx.smsDeleteGranted
        TutorialStep.DELETE_GRANT -> ctx.smsDeleteGranted
        TutorialStep.BATTERY_EXEMPTION -> ctx.batteryExempt
    }

    fun isSatisfied(step: TutorialStep, ctx: TutorialContext): Boolean = when (step) {
        TutorialStep.WELCOME, TutorialStep.DONE -> true
        else -> isSkippable(step, ctx)
    }

    fun visibleSteps(ctx: TutorialContext, shown: Set<TutorialStep>): List<TutorialStep> =
        allSteps.filter { it in shown || !isSkippable(it, ctx) }

    /** The screen a forward press lands on, or null when there is none (DONE). */
    fun next(current: TutorialStep, ctx: TutorialContext, shown: Set<TutorialStep>): TutorialStep? {
        val steps = visibleSteps(ctx, shown + current)
        return steps.getOrNull(steps.indexOf(current) + 1)
    }

    /** The screen a back press lands on, or null when back leaves the tutorial (WELCOME). */
    fun previous(current: TutorialStep, ctx: TutorialContext, shown: Set<TutorialStep>): TutorialStep? {
        val steps = visibleSteps(ctx, shown + current)
        val index = steps.indexOf(current)
        return if (index <= 0) null else steps[index - 1]
    }

    /**
     * Screens counted toward the progress bar, grown by one merge per navigation (see
     * TutorialScreen, which calls this exactly when the user's current screen changes, never on a
     * live Settings re-read while they stand still). The result only ever grows: a screen that
     * enters here stays counted even if it becomes skippable again later, so the denominator
     * cannot shrink or grow underneath a user who has not moved.
     */
    fun growVisible(current: TutorialStep, ctx: TutorialContext, everVisible: Set<TutorialStep>): Set<TutorialStep> =
        everVisible + visibleSteps(ctx, everVisible + current)

    /**
     * Position within [everVisible], the screens counted so far in this run (see [growVisible]).
     * Because that set is refreshed only once per navigation, a screen reviving ahead of the user
     * cannot move the bar until they actually walk to it, at which point the numerator advances by
     * the same step that reveals the extra denominator, so the fraction never drops.
     */
    fun progress(current: TutorialStep, everVisible: Set<TutorialStep>): Float {
        val steps = allSteps.filter { it in everVisible }
        if (steps.isEmpty()) return 1f
        val index = steps.indexOf(current)
        return if (index < 0) 1f else (index + 1).toFloat() / steps.size
    }

    /**
     * The blocked face is gated on an idle session so a live pairing/connecting/granting phase is
     * never hidden by notifications turning off mid-flow - a live session always keeps its own
     * face (and so its 진행 취소 secondary), never NOTIFICATIONS_BLOCKED underneath it.
     */
    fun grantFace(ctx: TutorialContext): GrantFace = when {
        ctx.smsDeleteGranted -> GrantFace.GRANTED
        ctx.notificationsBlocked && ctx.grantPhase == GrantPhase.IDLE -> GrantFace.NOTIFICATIONS_BLOCKED
        ctx.grantPhase == GrantPhase.WAITING_CODE -> GrantFace.WAITING_CODE
        ctx.grantPhase == GrantPhase.WAITING_CONNECT_PORT -> GrantFace.WAITING_PORT
        ctx.grantPhase == GrantPhase.PAIRING ||
            ctx.grantPhase == GrantPhase.CONNECTING ||
            ctx.grantPhase == GrantPhase.GRANTING -> GrantFace.WORKING
        ctx.grantPhase == GrantPhase.FAILED -> GrantFace.FAILED
        else -> GrantFace.READY
    }

    fun plates(step: TutorialStep, face: GrantFace, ctx: TutorialContext): List<TutorialPlate> = when (step) {
        TutorialStep.WELCOME,
        TutorialStep.SMS_READ,
        TutorialStep.NOTIFICATION_ACCESS,
        TutorialStep.BATTERY_EXEMPTION,
        -> emptyList()
        // A screenshot of a task already done is noise; the satisfied line replaces it.
        TutorialStep.DEVELOPER_OPTIONS ->
            if (isSatisfied(step, ctx)) emptyList()
            else listOf(TutorialPlates.softwareInfo, TutorialPlates.buildNumber)
        TutorialStep.WIRELESS_DEBUGGING ->
            if (isSatisfied(step, ctx)) emptyList()
            else listOf(TutorialPlates.wirelessSwitch, TutorialPlates.wirelessAllow)
        TutorialStep.DELETE_GRANT -> when (face) {
            GrantFace.READY -> listOf(TutorialPlates.notificationInput)
            GrantFace.WAITING_CODE -> listOf(
                TutorialPlates.pairingEntry,
                TutorialPlates.pairingDialog,
                TutorialPlates.notificationInput,
            )
            GrantFace.WAITING_PORT -> listOf(TutorialPlates.connectPortSource)
            else -> emptyList()
        }
        TutorialStep.DONE -> if (showsWirelessOffNote(ctx)) listOf(TutorialPlates.toggleOff) else emptyList()
    }

    fun firstUnsatisfied(ctx: TutorialContext, shown: Set<TutorialStep>): TutorialStep? =
        visibleSteps(ctx, shown).firstOrNull { !isSatisfied(it, ctx) }

    /**
     * Includes the battery exemption alongside the three permissions: the dedicated
     * BATTERY_EXEMPTION screen normally resolves it before DONE is ever reached, but if it somehow
     * is not, this is the only thing left on DONE explaining why the finish button stays disabled.
     */
    fun missingLabels(ctx: TutorialContext): List<String> = buildList {
        if (!ctx.smsReadGranted) add(TutorialCopy.LABEL_SMS_READ)
        if (!ctx.notificationAccessGranted) add(TutorialCopy.LABEL_NOTIFICATION_ACCESS)
        if (!ctx.smsDeleteGranted) add(TutorialCopy.LABEL_SMS_DELETE)
        if (!ctx.batteryExempt) add(TutorialCopy.LABEL_BATTERY_EXEMPTION)
    }

    /** Telling someone to turn off a switch they still need would strand them. */
    fun showsWirelessOffNote(ctx: TutorialContext): Boolean = ctx.smsDeleteGranted && ctx.wirelessDebuggingOn

    fun showsMissingNote(ctx: TutorialContext): Boolean = !canComplete(ctx)

    /**
     * Mirrors MonitorUiState.setupReady: the battery exemption is required to finish setup. The
     * dedicated BATTERY_EXEMPTION screen normally gets it before DONE is ever reached, but this
     * keeps the finish button disabled in the rare case DONE is reached without it anyway.
     */
    fun canComplete(ctx: TutorialContext): Boolean = ctx.allPermissionsReady && ctx.batteryExempt

    /**
     * The finish button is pressable either to finish or to go back for a missing permission; it is
     * inert only while the battery exemption alone is missing, where [missingLabels] names it.
     */
    fun donePrimaryEnabled(ctx: TutorialContext): Boolean = !ctx.allPermissionsReady || canComplete(ctx)
}
