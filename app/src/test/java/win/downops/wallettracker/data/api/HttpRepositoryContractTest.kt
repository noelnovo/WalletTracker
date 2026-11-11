package win.downops.wallettracker.data.api

import com.google.gson.GsonBuilder
import java.security.Security
import java.sql.Date
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.Answers
import org.mockito.Mockito
import org.mockito.stubbing.Answer
import retrofit2.Response
import win.downops.wallettracker.data.SessionRepository
import win.downops.wallettracker.data.api.communication.requests.CipheredRequest
import win.downops.wallettracker.data.api.communication.responses.BaseResponse
import win.downops.wallettracker.data.api.communication.responses.CipheredResponse
import win.downops.wallettracker.data.api.communication.responses.LoginResponse
import win.downops.wallettracker.data.api.expense.ExpenseEndpoints
import win.downops.wallettracker.data.api.expense.ExpenseHttpService
import win.downops.wallettracker.data.api.season.SeasonEndpoints
import win.downops.wallettracker.data.api.season.SeasonHttpService
import win.downops.wallettracker.data.models.AppResult
import win.downops.wallettracker.data.models.Expense
import win.downops.wallettracker.data.models.Season
import win.downops.wallettracker.data.models.Session
import win.downops.wallettracker.util.Cryptography

/** Ephemeral synthetic RSA keys and mocked HTTP boundary; never contacts an API. */
class HttpRepositoryContractTest {
    companion object {
        private lateinit var clientKeys: List<String>
        private lateinit var serverKeys: List<String>
        @BeforeClass @JvmStatic fun prepareSyntheticCrypto() {
            Security.addProvider(BouncyCastleProvider())
            clientKeys = Cryptography.generateKeys()
            serverKeys = Cryptography.generateKeys()
        }
    }

    private class Sessions(privateKey: String, publicKey: String) {
        var edits = 0
        var missing = false
        val session = Session().apply {
            token = "initial-synthetic-token"; this.privateKey = privateKey; serverPublicKey = publicKey
        }
        val repository: SessionRepository = Mockito.mock(SessionRepository::class.java, Answer<Any?> { call ->
            when (call.method.name) {
                "getFirstSession" -> if (missing) null else session
                "edit" -> { edits++; 1 }
                else -> Answers.RETURNS_DEFAULTS.answer(call)
            }
        })
    }

    private fun sessions() = Sessions(clientKeys[0], serverKeys[1])
    private fun <T> rejected(code: Int): Response<T> = Response.error(code, "{}".toResponseBody("application/json".toMediaType()))
    private fun encrypted(json: String) = CipheredResponse(
        Cryptography.sign(serverKeys[0]), requireNotNull(Cryptography.hybridEncrypt(clientKeys[1], json)))
    private fun response(json: String): Response<BaseResponse<CipheredResponse>> =
        Response.success(BaseResponse(data = encrypted(json), message = "Synthetic envelope", success = true))

    private class Probe(repository: SessionRepository) : BaseHttpService(repository) {
        var refreshed: Response<BaseResponse<LoginResponse>> = Response.error(401, "{}".toResponseBody())
        override suspend fun refreshCredentials(token: String): Response<BaseResponse<LoginResponse>> = refreshed
        suspend fun plain(call: suspend (String) -> Response<BaseResponse<Int>>) = safeApiCall(call)
        suspend fun cipher(call: suspend (String) -> Response<BaseResponse<CipheredResponse>>) =
            safeCipheredApiCall(call) { AppResult.Success("Decoded", it) }
        fun validate(value: BaseResponse<CipheredResponse>?) = validateCipheredResponse(value)
        fun encrypt(value: Map<String, String>): CipheredRequest? = encryptData(value)
    }

    @Test fun plainSuccessFailureMissingBodyAndHttpErrorsKeepTheirContracts() = runBlocking {
        val probe = Probe(sessions().repository)
        val success = probe.plain { Response.success(BaseResponse(data = 7, message = "Synthetic", success = true)) }
        assertEquals(7, (success as AppResult.Success).data)
        assertTrue(probe.plain { Response.success(BaseResponse(data = 7, message = "Rejected", success = false)) } is AppResult.Error)
        assertTrue(probe.plain { Response.success<BaseResponse<Int>>(null) } is AppResult.Error)
        val denied = probe.plain { rejected(503) } as AppResult.Error
        assertEquals(503, denied.code)
    }

    @Test fun tokenRefreshRetriesOnlyWithTheRotatedSessionToken() = runBlocking {
        val sessions = sessions(); val probe = Probe(sessions.repository)
        probe.refreshed = Response.success(BaseResponse(data = LoginResponse("rotated-synthetic-token"), message = "Refreshed", success = true))
        val tokens = mutableListOf<String>()
        val result = probe.plain { token ->
            tokens.add(token)
            if (tokens.size == 1) rejected(401) else Response.success(BaseResponse(data = 9, message = "Synthetic", success = true))
        }
        assertEquals(9, (result as AppResult.Success).data)
        assertEquals(listOf("initial-synthetic-token", "rotated-synthetic-token"), tokens)
        assertEquals(1, sessions.edits)
        assertEquals("rotated-synthetic-token", sessions.session.token)
    }

    @Test fun rejectedAndIncompleteRefreshNeverInventAuthenticationSuccess() = runBlocking {
        val probe = Probe(sessions().repository)
        assertEquals(401, (probe.plain { rejected(401) } as AppResult.Error).code)
        probe.refreshed = Response.success(BaseResponse(data = null, message = "No token", success = true))
        assertTrue(probe.plain { rejected(401) } is AppResult.Error)
        probe.refreshed = Response.success(BaseResponse(data = LoginResponse("unused"), message = "Denied", success = false))
        assertTrue(probe.plain { rejected(401) } is AppResult.Error)
        val sessions = sessions(); sessions.missing = true
        try { Probe(sessions.repository).plain { rejected(503) }; fail("Missing session must fail") }
        catch (expected: Exception) { assertEquals("No session found", expected.message) }
    }

    @Test fun encryptedEnvelopeUsesActualSyntheticCryptoAndRejectsMissingFields() = runBlocking {
        val probe = Probe(sessions().repository)
        assertEquals("synthetic response", (probe.cipher { response("synthetic response") } as AppResult.Success).data)
        val request = requireNotNull(probe.encrypt(mapOf("value" to "synthetic")))
        assertEquals("{\"value\":\"synthetic\"}", Cryptography.hybridDecrypt(serverKeys[0],
            requireNotNull(request.encryptedAesKey), requireNotNull(request.iv), requireNotNull(request.ciphertext), requireNotNull(request.tag)))
        val bad = listOf<CipheredResponse?>(null, CipheredResponse(null, null), CipheredResponse("synthetic", null),
            CipheredResponse("synthetic", CipheredRequest(null, null, null, null)))
        for (envelope in bad) {
            val result = probe.cipher { Response.success(BaseResponse(data = envelope, message = "Synthetic", success = true)) }
            assertEquals(400, (result as AppResult.Error).code)
        }
        try { probe.validate(null); fail("Missing envelope must fail") }
        catch (expected: Exception) { assertEquals("Unexpected error", expected.message) }
        val wrongSignature = encrypted("synthetic response").copy(signature = Cryptography.sign(clientKeys[0]))
        assertTrue(probe.cipher { Response.success(BaseResponse(data = wrongSignature, message = "Synthetic", success = true)) } is AppResult.Error)
    }

    @Test fun expenseEndpointsParseSuccessRejectionListsAndDeletionResponses() = runBlocking {
        val sessions = sessions()
        val expense = Expense(7L).apply {
            setDescription("Synthetic expense"); setPrice(12.5); setDate(Date.valueOf("2026-01-02")); setCategoryId(3L)
        }
        var success = true
        var absent = false
        val methods = mutableSetOf<String>()
        val endpoint = Mockito.mock(ExpenseEndpoints::class.java, Answer<Any?> { call ->
            methods.add(call.method.name)
            assertEquals("Bearer initial-synthetic-token", call.arguments[0])
            if (call.method.name.startsWith("delete")) {
                if (absent) Response.success<BaseResponse<Unit>>(null)
                else Response.success(BaseResponse(data = Unit, message = "Synthetic deletion", success = success))
            } else {
                val data = when (call.method.name) { "getByCatId", "getBySeasonId" -> if (absent) null else listOf(expense); else -> if (absent) null else expense }
                response(GsonBuilder().setDateFormat("yyyy-MM-dd").create().toJson(BaseResponse(data = data, message = "Synthetic", success = success)))
            }
        })
        val service = ExpenseHttpService(sessions.repository, endpoint)
        assertEquals(7L, (service.getById(7L) as AppResult.Success).data!!.id)
        assertEquals(1, (service.getByCatId(3L) as AppResult.Success).data.size)
        assertEquals(1, (service.getBySeasonId(7L) as AppResult.Success).data.size)
        assertTrue(service.create(expense) is AppResult.Success); assertTrue(service.edit(expense) is AppResult.Success)
        assertTrue(service.deleteById(7L) is AppResult.Success); assertTrue(service.deleteAll() is AppResult.Success)
        success = false
        assertTrue(service.getById(7L) is AppResult.Error); assertTrue(service.getByCatId(3L) is AppResult.Error)
        assertTrue(service.getBySeasonId(7L) is AppResult.Error); assertTrue(service.create(expense) is AppResult.Error)
        assertTrue(service.edit(expense) is AppResult.Error); assertTrue(service.deleteById(7L) is AppResult.Error); assertTrue(service.deleteAll() is AppResult.Error)
        success = true; absent = true
        assertTrue((service.getByCatId(3L) as AppResult.Success).data.isEmpty())
        assertTrue((service.getBySeasonId(7L) as AppResult.Success).data.isEmpty())
        assertNull((service.getById(7L) as AppResult.Success).data)
        assertTrue(service.deleteById(7L) is AppResult.Error); assertTrue(service.deleteAll() is AppResult.Error)
        assertEquals(setOf("getById", "getByCatId", "getBySeasonId", "create", "edit", "deleteById", "deleteAll"), methods)
    }

    @Test fun seasonEndpointsRetainParsedAndControlledFailureResults() = runBlocking {
        var success = true; var absent = false
        val endpoint = Mockito.mock(SeasonEndpoints::class.java, Answer<Any?> { call ->
            if (call.method.name == "deleteById") Response.success(BaseResponse(data = Unit, message = "Synthetic", success = success))
            else {
                val data = if (absent) null else if (call.method.name == "getAll") listOf(Season(7L, 2026, 1)) else Season(7L, 2026, 1)
                response(GsonBuilder().create().toJson(BaseResponse(data = data, message = "Synthetic", success = success)))
            }
        })
        val service = SeasonHttpService(sessions().repository, endpoint)
        assertEquals(1, (service.getAll() as AppResult.Success).data.size)
        assertEquals(7L, (service.getById(7L) as AppResult.Success).data!!.id)
        assertTrue(service.getByYearMonth(2026, 1) is AppResult.Success)
        assertTrue(service.getOrCreate(2026, 1) is AppResult.Success); assertTrue(service.deleteById(7L) is AppResult.Success)
        success = false
        assertTrue(service.getAll() is AppResult.Error); assertTrue(service.getById(7L) is AppResult.Error)
        assertTrue(service.getByYearMonth(2026, 1) is AppResult.Error); assertTrue(service.deleteById(7L) is AppResult.Error)
        success = true; absent = true
        assertTrue((service.getAll() as AppResult.Success).data.isEmpty())
        assertNull((service.getById(7L) as AppResult.Success).data)
    }
}
