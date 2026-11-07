package win.downops.wallettracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistrationConfigurationTest {
    @Test
    fun registrationUiIsAvailableWithoutAClientAuthorizationFlag() {
        assertTrue(BuildConfig.REGISTRATION_UI_AVAILABLE)
        assertEquals("2", BuildConfig.API_VERSION)
        assertEquals("", BuildConfig.SIGN_SECRET)
    }
}
