package win.downops.wallettracker.v2

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import win.downops.wallettracker.BuildConfig

/** Explicit opt-in: the device must be on the private development network. */
@RunWith(AndroidJUnit4::class)
class DevApiSmokeTest {
    @Test fun privateApiUsesVerifiedHttps() = runBlocking {
        assumeTrue(BuildConfig.DEBUG && InstrumentationRegistry.getArguments().getString("liveDevApi") == "true")
        val error = runCatching { FinanceApi().call("sync") }.exceptionOrNull()
        assertEquals("Private dev API should be reachable and require authentication", 401, (error as? ApiFailure)?.status)
    }
}
