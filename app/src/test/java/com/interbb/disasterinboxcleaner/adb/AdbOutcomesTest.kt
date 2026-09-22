package com.interbb.disasterinboxcleaner.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbOutcomesTest {
    @Test
    fun pairSucceedsOnlyWithExitZeroAndSuccessLine() {
        assertTrue(AdbOutcomes.pairSucceeded(AdbResult(0, "Successfully paired to 127.0.0.1:34103 [guid=adb-X]")))
        assertFalse(AdbOutcomes.pairSucceeded(AdbResult(0, "Failed: Wrong password or connection was dropped.")))
        assertFalse(AdbOutcomes.pairSucceeded(AdbResult(1, "Successfully paired")))
    }

    @Test
    fun connectSucceedsOnConnectedOrAlreadyConnected() {
        assertTrue(AdbOutcomes.connectSucceeded(AdbResult(0, "connected to 127.0.0.1:46069")))
        assertTrue(AdbOutcomes.connectSucceeded(AdbResult(0, "already connected to 127.0.0.1:46069")))
        assertFalse(AdbOutcomes.connectSucceeded(AdbResult(0, "failed to connect to '127.0.0.1:46069': Connection refused")))
        assertFalse(AdbOutcomes.connectSucceeded(AdbResult(-1, "timeout")))
    }

    @Test
    fun grantSucceedsOnExitZeroWithoutErrorText() {
        assertTrue(AdbOutcomes.grantSucceeded(AdbResult(0, "")))
        assertFalse(AdbOutcomes.grantSucceeded(AdbResult(0, "Error: Unknown package: com.x")))
        assertFalse(AdbOutcomes.grantSucceeded(AdbResult(0, "java.lang.SecurityException: uid 10556 ...")))
        assertFalse(AdbOutcomes.grantSucceeded(AdbResult(255, "AppOps service (appops) commands:")))
    }

    @Test
    fun maskCodeHidesSixDigitRunsOnly() {
        assertEquals("pair 127.0.0.1:34103 ******", AdbOutcomes.maskCode("pair 127.0.0.1:34103 440184"))
        assertEquals("port 46069 ok", AdbOutcomes.maskCode("port 46069 ok"))
        assertEquals("id 1234567", AdbOutcomes.maskCode("id 1234567"))
    }
}
