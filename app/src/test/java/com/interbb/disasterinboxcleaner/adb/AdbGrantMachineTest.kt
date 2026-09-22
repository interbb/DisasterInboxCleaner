package com.interbb.disasterinboxcleaner.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbGrantMachineTest {
    @Test
    fun happyPathWalksEveryPhase() {
        var phase = GrantPhase.IDLE
        fun step(event: GrantEvent): Transition {
            val t = AdbGrantMachine.next(phase, event)!!
            phase = t.phase
            return t
        }
        assertEquals(GrantPhase.WAITING_CODE, step(GrantEvent.Start).phase)
        assertEquals(GrantPhase.PAIRING, step(GrantEvent.CodeEntered).phase)
        assertEquals(GrantPhase.WAITING_CONNECT_PORT, step(GrantEvent.PairResult(ok = true, connectPortKnown = false)).phase)
        assertEquals(GrantPhase.CONNECTING, step(GrantEvent.ConnectPortEntered).phase)
        assertEquals(GrantPhase.GRANTING, step(GrantEvent.ConnectResult(true)).phase)
        val done = step(GrantEvent.GrantResult(verified = true, detail = ""))
        assertEquals(GrantPhase.DONE, done.phase)
        assertEquals(GrantMessages.DONE, done.message)
    }

    @Test
    fun aRememberedConnectPortSkipsStraightToConnecting() {
        val skip = AdbGrantMachine.next(GrantPhase.PAIRING, GrantEvent.PairResult(ok = true, connectPortKnown = true))!!
        assertEquals(GrantPhase.CONNECTING, skip.phase)
    }

    @Test
    fun failuresLandInFailedWithTheirMessage() {
        assertEquals(GrantMessages.BINARY_MISSING, AdbGrantMachine.next(GrantPhase.IDLE, GrantEvent.BinaryMissing)!!.message)
        assertEquals(GrantMessages.WIRELESS_OFF, AdbGrantMachine.next(GrantPhase.IDLE, GrantEvent.WirelessDebuggingOff)!!.message)
        assertEquals(GrantMessages.NO_WIFI, AdbGrantMachine.next(GrantPhase.IDLE, GrantEvent.NoWifi)!!.message)
        assertEquals(GrantMessages.PAIR_FAILED, AdbGrantMachine.next(GrantPhase.PAIRING, GrantEvent.PairResult(false))!!.message)
        assertEquals(GrantMessages.CONNECT_FAILED, AdbGrantMachine.next(GrantPhase.CONNECTING, GrantEvent.ConnectResult(false))!!.message)
        val grantFail = AdbGrantMachine.next(GrantPhase.GRANTING, GrantEvent.GrantResult(false, "Error: Unknown package"))!!
        assertEquals(GrantPhase.FAILED, grantFail.phase)
        assertTrue(grantFail.message.contains("Error: Unknown package"))
        assertEquals(GrantMessages.TIMEOUT, AdbGrantMachine.next(GrantPhase.WAITING_CODE, GrantEvent.Timeout)!!.message)
        assertEquals(GrantMessages.TIMEOUT, AdbGrantMachine.next(GrantPhase.WAITING_CONNECT_PORT, GrantEvent.Timeout)!!.message)
    }

    @Test
    fun cancelReturnsToIdleAndUnexpectedEventsAreIgnored() {
        assertEquals(GrantPhase.IDLE, AdbGrantMachine.next(GrantPhase.WAITING_CODE, GrantEvent.Cancel)!!.phase)
        assertNull(AdbGrantMachine.next(GrantPhase.IDLE, GrantEvent.CodeEntered))
        assertNull(AdbGrantMachine.next(GrantPhase.DONE, GrantEvent.PairResult(true)))
        assertNull(AdbGrantMachine.next(GrantPhase.PAIRING, GrantEvent.CodeEntered))
        assertNull(AdbGrantMachine.next(GrantPhase.WAITING_CONNECT_PORT, GrantEvent.CodeEntered))
    }

    @Test
    fun cancelTransitionsToIdleFromEveryPhase() {
        GrantPhase.entries.forEach { phase ->
            val transition = AdbGrantMachine.next(phase, GrantEvent.Cancel)
            assertEquals("Cancel from $phase", GrantPhase.IDLE, transition?.phase)
        }
    }

    @Test
    fun staleInProgressWithoutServiceResets() {
        assertTrue(AdbGrantMachine.shouldResetStale(GrantStatus(GrantPhase.WAITING_CODE), serviceRunning = false))
        assertTrue(AdbGrantMachine.shouldResetStale(GrantStatus(GrantPhase.WAITING_CONNECT_PORT), serviceRunning = false))
        assertFalse(AdbGrantMachine.shouldResetStale(GrantStatus(GrantPhase.WAITING_CODE), serviceRunning = true))
        listOf(GrantPhase.IDLE, GrantPhase.DONE, GrantPhase.FAILED).forEach { phase ->
            assertFalse(
                "phase=$phase should not be reset even when the service isn't running",
                AdbGrantMachine.shouldResetStale(GrantStatus(phase), serviceRunning = false),
            )
        }
    }

    /**
     * The flow must open by asking for the code alone. The pairing dialog is not open when the user
     * presses start, so the port cannot be known at that instant; asking for it up front makes the
     * notification contradict the guide. Escalating to [GrantMessages.WAITING] is the recovery path
     * taken only when a code arrives and discovery still finds nothing, never the opening move.
     */
    @Test
    fun startingAsksForTheCodeAlone() {
        listOf(GrantPhase.IDLE, GrantPhase.DONE, GrantPhase.FAILED).forEach { from ->
            val started = AdbGrantMachine.next(from, GrantEvent.Start)
            assertEquals("from=$from", GrantPhase.WAITING_CODE, started?.phase)
            assertEquals("from=$from", GrantMessages.WAITING_CODE_ONLY, started?.message)
        }
    }
}
