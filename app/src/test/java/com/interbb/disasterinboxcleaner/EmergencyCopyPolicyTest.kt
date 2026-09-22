package com.interbb.disasterinboxcleaner

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyCopyPolicyTest {
    @Test
    fun deleteFilterTargetsOnlyCmasAddressesAfterEnableTime() {
        val start = 1_700_000_000_000L

        assertTrue(EmergencyCopyPolicy.deleteSelection.contains("address LIKE '#CMAS#%'"))
        assertTrue(EmergencyCopyPolicy.deleteSelection.contains("date >= ?"))
        assertFalse(EmergencyCopyPolicy.deleteSelection.contains("body", ignoreCase = true))
        assertArrayEquals(
            arrayOf(start.toString()),
            EmergencyCopyPolicy.deleteSelectionArgs(start),
        )
    }

    @Test
    fun manualSelectionsTargetInboxCopiesByAddressAndType() {
        // Deletes now go through content://sms (all boxes), so every selection must pin the inbox type.
        assertTrue(EmergencyCopyPolicy.deleteSelection.contains("type = 1"))
        assertTrue(EmergencyCopyPolicy.allCopiesSelection.contains("address LIKE '#CMAS#%'"))
        assertTrue(EmergencyCopyPolicy.allCopiesSelection.contains("type = 1"))
        assertFalse(EmergencyCopyPolicy.allCopiesSelection.contains("date"))
        assertTrue(EmergencyCopyPolicy.byIdSelection.startsWith("_id = ?"))
        assertTrue(EmergencyCopyPolicy.byIdSelection.contains("address LIKE '#CMAS#%'"))
        assertTrue(EmergencyCopyPolicy.byIdSelection.contains("type = 1"))
        assertArrayEquals(arrayOf("18"), EmergencyCopyPolicy.byIdSelectionArgs(18L))
    }
}
