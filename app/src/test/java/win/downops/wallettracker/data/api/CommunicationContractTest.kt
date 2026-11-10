package win.downops.wallettracker.data.api

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import win.downops.wallettracker.data.api.communication.requests.AutoLoginRequest
import win.downops.wallettracker.data.api.communication.requests.CipheredRequest
import win.downops.wallettracker.data.api.communication.requests.CreateExpenseCategoryRequest
import win.downops.wallettracker.data.api.communication.requests.CreateExpenseRequest
import win.downops.wallettracker.data.api.communication.requests.CreateImporteRequest
import win.downops.wallettracker.data.api.communication.requests.EditExpenseCategoryRequest
import win.downops.wallettracker.data.api.communication.requests.EditExpenseRequest
import win.downops.wallettracker.data.api.communication.requests.ExpenseByCategoryIdRequest
import win.downops.wallettracker.data.api.communication.requests.ExpenseBySeasonIdRequest
import win.downops.wallettracker.data.api.communication.requests.ExpenseCategoryIdRequest
import win.downops.wallettracker.data.api.communication.requests.ExpenseIdRequest
import win.downops.wallettracker.data.api.communication.requests.GetOrCreateSeasonRequest
import win.downops.wallettracker.data.api.communication.requests.ImporteBySeasonIdRequest
import win.downops.wallettracker.data.api.communication.requests.ImporteIdRequest
import win.downops.wallettracker.data.api.communication.requests.LoginRequest
import win.downops.wallettracker.data.api.communication.requests.SeasonIdRequest
import win.downops.wallettracker.data.api.communication.requests.ServerPubKeyRequest
import win.downops.wallettracker.data.api.communication.responses.BaseResponse
import win.downops.wallettracker.data.api.communication.responses.CipheredResponse
import win.downops.wallettracker.data.api.communication.responses.LoginResponse
import win.downops.wallettracker.data.api.communication.responses.ServerPubKeyResponse
import win.downops.wallettracker.util.LogTag
import win.downops.wallettracker.util.Messages

/** Serialization contract: JSON field names cross the wire, so renames must be annotated. */
class CommunicationContractTest {
    private val gson = Gson()

    @Test fun cipheredRequestKeepsSnakeCaseWireNames() {
        val json = gson.toJson(CipheredRequest("key", "iv", "body", "tag"))
        assertTrue(json.contains("\"encrypted_aes_key\""))
        assertTrue(json.contains("\"iv\"")); assertTrue(json.contains("\"ciphertext\"")); assertTrue(json.contains("\"tag\""))
    }

    @Test fun cipheredResponseReadsSnakeCaseWireNames() {
        val response = gson.fromJson(
            """{"signature":"sig","encrypted_data":{"encrypted_aes_key":"k","iv":"i","ciphertext":"c","tag":"t"}}""",
            CipheredResponse::class.java)
        assertEquals("sig", response.signature)
        assertEquals("k", response.encryptedData?.encryptedAesKey)
        assertEquals("c", response.encryptedData?.ciphertext)
    }

    @Test fun loginResponsesCarryTheirValues() {
        assertEquals("token-123", gson.fromJson("""{"token":"token-123"}""", LoginResponse::class.java).token)
        val pubKey = gson.fromJson("""{"userId":7,"publicKey":"pk"}""", ServerPubKeyResponse::class.java)
        assertEquals(7, pubKey.userId)
        assertEquals("pk", pubKey.publicKey)
    }

    @Test fun baseResponseCarriesEnvelope() {
        val response = gson.fromJson("""{"data":{"a":1},"message":"ok","success":true}""",
            BaseResponse::class.java)
        assertEquals("ok", response.message)
        assertTrue(response.success)
        assertNotNull(response.data)
    }

    @Test fun loginRequestUsesPlainFieldNames() {
        val json = gson.toJson(LoginRequest("noel", "secret-password"))
        assertEquals("""{"username":"noel","password":"secret-password"}""", json)
    }

    @Test fun requestConstructorsCarryTheirValues() {
        assertEquals(5L, ImporteIdRequest(5L).id)
        assertEquals(6L, ImporteBySeasonIdRequest(6L).seasonId)
        val createImporte = CreateImporteRequest("wage", "2026-11-10", 10.0, 20.0, 3L)
        assertEquals("wage", createImporte.concept); assertEquals(10.0, createImporte.amount, 0.0001)
        assertEquals(20.0, createImporte.balanceAfter!!, 0.0001); assertEquals(3L, createImporte.seasonId)
        assertEquals(7L, ExpenseByCategoryIdRequest(7L).catId)
        assertEquals(8L, ExpenseBySeasonIdRequest(8L).seasonId)
        assertEquals(9L, ExpenseIdRequest(9L).id)
        val createExpense = CreateExpenseRequest(5.0, "2026-11-10", 2L, "note")
        assertEquals(5.0, createExpense.price, 0.0001); assertEquals(2L, createExpense.category)
        val editExpense = EditExpenseRequest(4L, 6.0, "2026-11-11", 3L, "edited")
        assertEquals(4L, editExpense.id); assertEquals("edited", editExpense.description)
        assertEquals(1L, ExpenseCategoryIdRequest(1L).id)
        assertEquals("Food", CreateExpenseCategoryRequest("Food").name)
        val editCategory = EditExpenseCategoryRequest(2L, "Brunch")
        assertEquals(2L, editCategory.id); assertEquals("Brunch", editCategory.name)
        assertEquals(12L, SeasonIdRequest(12L).id)
        assertEquals(2026, GetOrCreateSeasonRequest(2026, 11).year)
        assertEquals(3, AutoLoginRequest(3, "blob").userId)
        assertEquals("pk", ServerPubKeyRequest("pk").publicKey)
    }

    @Test fun messageConstantsStayStable() {
        assertEquals("Login failed", Messages.loginFailedMessage)
        assertEquals("No data to show", Messages.noDataMessage)
        assertEquals("Debug", LogTag.DEBUG)
    }
}
