package com.shilapi.xcertplay.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InternetNetworkSelectorTest {
    @Test fun requiresBothInternetAndValidation() {
        assertFalse(InternetNetworkSelector.isValidatedInternet(true, false))
        assertTrue(InternetNetworkSelector.isValidatedInternet(true, true))
    }
}
