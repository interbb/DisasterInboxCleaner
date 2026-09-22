package com.interbb.disasterinboxcleaner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryUiStateTest {
    @Test
    fun deleteAllRemainsAvailableWhenIndividualListingIsRestricted() {
        assertTrue(HistoryUiState(individualListingRestricted = true).canRequestDeleteAll)
        assertFalse(HistoryUiState().canRequestDeleteAll)
    }

    @Test
    fun restrictedListingAppliesOnlyToDisasterSourceOfNonDefaultApp() {
        assertTrue(individualListingRestrictedFor(HistorySource.DISASTER, isDefaultSmsApp = false))
        assertFalse(individualListingRestrictedFor(HistorySource.DISASTER, isDefaultSmsApp = true))
        assertFalse(individualListingRestrictedFor(HistorySource.AD, isDefaultSmsApp = false))
        assertFalse(individualListingRestrictedFor(HistorySource.AD, isDefaultSmsApp = true))
        assertEquals(HistorySource.DISASTER, HistoryUiState().source)
    }
}
