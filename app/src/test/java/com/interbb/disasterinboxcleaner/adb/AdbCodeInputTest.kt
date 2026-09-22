package com.interbb.disasterinboxcleaner.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbCodeInputTest {
    @Test
    fun spaceCommaColonAndNewlineSeparatorsAllParse() {
        val expected = CodeInput.PairingAndCode(43419, "123456")
        assertEquals(expected, AdbCodeInput.parse("43419 123456", GrantPhase.WAITING_CODE))
        assertEquals(expected, AdbCodeInput.parse("43419,123456", GrantPhase.WAITING_CODE))
        assertEquals(expected, AdbCodeInput.parse("43419:123456", GrantPhase.WAITING_CODE))
        assertEquals(expected, AdbCodeInput.parse("43419\n123456", GrantPhase.WAITING_CODE))
        assertEquals(expected, AdbCodeInput.parse("  43419   123456  ", GrantPhase.WAITING_CODE))
    }

    @Test
    fun sixDigitCodeIsDetectedRegardlessOfPosition() {
        assertEquals(
            CodeInput.PairingAndCode(43419, "123456"),
            AdbCodeInput.parse("43419 123456", GrantPhase.WAITING_CODE),
        )
        assertEquals(
            CodeInput.PairingAndCode(43419, "123456"),
            AdbCodeInput.parse("123456 43419", GrantPhase.WAITING_CODE),
        )
    }

    @Test
    fun bothRunsSixDigitsPicksSecondAsCodeAndFirstAsPort() {
        // Neither run is a realistic port (max port is 65535, five digits), but the rule is still
        // exercised: the first run (999999) is treated as the port and rejected for being out of
        // range, which only happens if the second run (123456) was picked as the code.
        val result = AdbCodeInput.parse("999999 123456", GrantPhase.WAITING_CODE)
        assertTrue(result is CodeInput.Invalid)
        assertEquals("포트 번호가 올바르지 않습니다.", (result as CodeInput.Invalid).message)
    }

    @Test
    fun emptyOrNullReplyWhileExpectingCodeIsInvalid() {
        assertTrue(AdbCodeInput.parse("", GrantPhase.WAITING_CODE) is CodeInput.Invalid)
        assertTrue(AdbCodeInput.parse(null, GrantPhase.WAITING_CODE) is CodeInput.Invalid)
    }

    @Test
    fun moreThanTwoRunsWhileExpectingCodeIsInvalid() {
        assertTrue(AdbCodeInput.parse("43419 123456 1", GrantPhase.WAITING_CODE) is CodeInput.Invalid)
    }

    @Test
    fun connectPortBranchAcceptsOneRunAndRejectsTwo() {
        assertEquals(CodeInput.ConnectPort(43419), AdbCodeInput.parse("43419", GrantPhase.WAITING_CONNECT_PORT))
        assertTrue(AdbCodeInput.parse("43419 1", GrantPhase.WAITING_CONNECT_PORT) is CodeInput.Invalid)
        assertTrue(AdbCodeInput.parse("", GrantPhase.WAITING_CONNECT_PORT) is CodeInput.Invalid)
    }

    @Test
    fun outOfRangePortIsInvalidForBothBranches() {
        assertTrue(AdbCodeInput.parse("80", GrantPhase.WAITING_CONNECT_PORT) is CodeInput.Invalid)
        assertTrue(AdbCodeInput.parse("70000", GrantPhase.WAITING_CONNECT_PORT) is CodeInput.Invalid)
        assertTrue(AdbCodeInput.parse("80 123456", GrantPhase.WAITING_CODE) is CodeInput.Invalid)
        assertTrue(AdbCodeInput.parse("70000 123456", GrantPhase.WAITING_CODE) is CodeInput.Invalid)
    }

    @Test
    fun fiveAndSevenDigitCodesAreRejected() {
        assertTrue(AdbCodeInput.parse("43419 12345", GrantPhase.WAITING_CODE) is CodeInput.Invalid)
        assertTrue(AdbCodeInput.parse("43419 1234567", GrantPhase.WAITING_CODE) is CodeInput.Invalid)
    }

    @Test
    fun invalidMessagesNeverContainTheInputCode() {
        val secretCode = "998877"
        val result = AdbCodeInput.parse("70000 $secretCode", GrantPhase.WAITING_CODE) as CodeInput.Invalid
        assertFalse(result.message.contains(secretCode))

        val otherSecret = "554433"
        val formatResult = AdbCodeInput.parse("11 $otherSecret", GrantPhase.WAITING_CODE)
        assertTrue(formatResult is CodeInput.Invalid)
        assertFalse((formatResult as CodeInput.Invalid).message.contains(otherSecret))
    }

    // --- both WAITING_CODE shapes are always accepted (C1): a lone 6-digit run is the code alone,
    // and a port+code pair is accepted even once a pairing port is already known — the caller
    // (AdbGrantService), not this pure parser, decides which known port a bare code pairs against. ---

    @Test
    fun loneSixDigitRunIsTheCodeAlone() {
        assertEquals(
            CodeInput.Code("123456"),
            AdbCodeInput.parse("123456", GrantPhase.WAITING_CODE),
        )
    }

    @Test
    fun loneRunOfWrongLengthIsInvalid() {
        assertTrue(AdbCodeInput.parse("12345", GrantPhase.WAITING_CODE) is CodeInput.Invalid)
        assertTrue(AdbCodeInput.parse("1234567", GrantPhase.WAITING_CODE) is CodeInput.Invalid)
    }

    @Test
    fun loneRunInvalidMessageIsExact() {
        val result = AdbCodeInput.parse("12345", GrantPhase.WAITING_CODE) as CodeInput.Invalid
        assertEquals("코드는 6자리입니다.", result.message)
    }

    @Test
    fun portAndCodeAcceptedEvenWhenAPairingPortIsAlreadyKnown() {
        // Regression for C1: before this fix, once mDNS had found the pairing port, a port+code
        // reply — exactly what the notification had just asked the user for, before discovery
        // caught up — was rejected as "6자리 코드만 입력하세요." Both shapes are now always accepted
        // while WAITING_CODE, regardless of anything the caller already knows.
        assertEquals(
            CodeInput.PairingAndCode(43419, "123456"),
            AdbCodeInput.parse("43419 123456", GrantPhase.WAITING_CODE),
        )
    }
}
