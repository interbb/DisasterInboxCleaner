package com.interbb.disasterinboxcleaner.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GrantMessagesTest {
    @Test
    fun sharedExampleSuffixIsShownOnlyOnce() {
        // Both AdbCodeInput's own "invalid" message and GrantMessages.WAITING end in the same
        // "예: 43419 123456" hint (this is the exact pairing-and-code invalid case from
        // AdbGrantService.handleReply).
        val invalidMessage = "포트와 코드를 함께 입력하세요. ${GrantMessages.PORT_CODE_EXAMPLE}"
        val combined = GrantMessages.composeInvalidReply(invalidMessage, GrantMessages.WAITING)

        assertEquals(1, Regex(Regex.escape(GrantMessages.PORT_CODE_EXAMPLE)).findAll(combined).count())
        assertEquals(
            "포트와 코드를 함께 입력하세요. ${GrantMessages.PORT_CODE_EXAMPLE} 페어링 창의 포트와 6자리 코드를 함께 넣으세요.",
            combined,
        )
    }

    @Test
    fun bothPartsKeptWhenOnlyBaseHasTheExample() {
        // e.g. AdbCodeInput's CODE_FORMAT_MESSAGE ("코드는 6자리입니다.") has no example, only the base does.
        val invalidMessage = "코드는 6자리입니다."
        val combined = GrantMessages.composeInvalidReply(invalidMessage, GrantMessages.WAITING)

        assertEquals("$invalidMessage ${GrantMessages.WAITING}", combined)
        assertEquals(1, Regex(Regex.escape(GrantMessages.PORT_CODE_EXAMPLE)).findAll(combined).count())
    }

    @Test
    fun bothPartsConcatenatedWhenNeitherHasTheExample() {
        // WAITING_CONNECT_PORT's base message never contains the example hint.
        val invalidMessage = "연결 포트 숫자만 입력하세요."
        val combined = GrantMessages.composeInvalidReply(invalidMessage, GrantMessages.WAITING_CONNECT_PORT)

        assertEquals("$invalidMessage ${GrantMessages.WAITING_CONNECT_PORT}", combined)
        assertFalse(combined.contains(GrantMessages.PORT_CODE_EXAMPLE))
    }

    @Test
    fun invalidMessageAloneWhenBaseIsNothingButTheExample() {
        // Degenerate case: once the shared suffix is stripped, base has nothing left to add.
        val combined = GrantMessages.composeInvalidReply(GrantMessages.PORT_CODE_EXAMPLE, GrantMessages.PORT_CODE_EXAMPLE)
        assertEquals(GrantMessages.PORT_CODE_EXAMPLE, combined)
    }
}
