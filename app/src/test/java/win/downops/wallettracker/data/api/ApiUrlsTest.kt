package win.downops.wallettracker.data.api

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiUrlsTest {
    @Test
    fun normalizesRetrofitBaseUrl() {
        assertEquals("https://api.downops.win/", ApiUrls.baseUrl("https://api.downops.win"))
        assertEquals("https://api.downops.win/", ApiUrls.baseUrl("https://api.downops.win/"))
    }

    @Test
    fun readinessHasOneSlashAndChecksDatabase() {
        for (base in listOf("https://api.downops.win", "https://api.downops.win/")) {
            assertEquals("https://api.downops.win/api/v1/ready", ApiUrls.readinessUrl(base, "1"))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidVersion() {
        ApiUrls.readinessUrl("https://api.downops.win", "../health")
    }
}
