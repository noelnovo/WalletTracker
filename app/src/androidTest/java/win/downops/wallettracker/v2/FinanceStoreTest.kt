package win.downops.wallettracker.v2

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FinanceStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun store() = FinanceStore(context, "instrumentation-${UUID.randomUUID()}")
    private fun page(record: JSONObject, cursor: Long = record.getLong("revision")) = JSONObject()
        .put("records", JSONArray().put(record)).put("cursor", cursor).put("has_more", false)

    @Test fun encryptedPersistenceAndAccountIsolation() {
        val account = "instrumentation-${UUID.randomUUID()}"
        val first = FinanceStore(context, account)
        first.save("category", JSONObject().put("name", "Private groceries"))
        val database = first.databaseName!!
        first.close()
        val bytes = context.getDatabasePath(database).readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(bytes.contains("Private groceries"))
        val reopened = FinanceStore(context, account)
        assertEquals("Private groceries", reopened.rows().single().getJSONObject("payload").getString("name"))
        assertEquals(1, reopened.pendingCount())
        val other = store()
        assertTrue(other.rows().isEmpty())
        reopened.close(); other.close()
    }

    @Test fun concurrentServerEditBecomesResolvableConflict() {
        val store = store()
        val id = UUID.randomUUID().toString()
        val remote = JSONObject().put("id", id).put("kind", "category").put("version", 1).put("revision", 1)
            .put("deleted", false).put("payload", JSONObject().put("name", "Food"))
        store.applyPage(page(remote))
        store.save("category", JSONObject().put("name", "Local name"), store.rows().single())
        val changedRemote = JSONObject(remote.toString()).put("version", 2).put("revision", 2).put("payload", JSONObject().put("name", "Remote name"))
        store.applyPage(page(changedRemote))
        val local = store.rows().single()
        assertTrue(local.getBoolean("has_conflict"))
        assertEquals("Local name", local.getJSONObject("payload").getString("name"))
        assertEquals(0, store.pending().length())
        store.resolve(local, true)
        assertEquals(2, store.pending().getJSONObject(0).getInt("base_version"))
        assertFalse(store.rows().single().getBoolean("has_conflict"))
        store.close()
    }

    @Test fun deletionWaitsForUndoAndRestorePreservesIdentity() {
        val store = store()
        val original = store.save("category", JSONObject().put("name", "Food"))
        val deleted = store.save("category", original.getJSONObject("payload"), original, deleted = true, delay = 6000)
        assertTrue(store.rows().isEmpty())
        assertEquals(0, store.pending().length())
        store.save("category", original.getJSONObject("payload"), deleted)
        assertEquals(original.getString("id"), store.rows().single().getString("id"))
        assertFalse(store.pending().getJSONObject(0).getBoolean("deleted"))
        store.close()
    }

    @Test fun editRacingAcknowledgementIsRetainedAndRebased() {
        val store = store()
        val first = store.save("category", JSONObject().put("name", "First"))
        val sent = store.pending()
        store.save("category", JSONObject().put("name", "Second"), first)
        val remote = JSONObject(first.toString()).put("version", 1).put("revision", 1)
        val result = JSONObject().put("operation_id", sent.getJSONObject(0).getString("operation_id"))
            .put("status", "applied").put("record", remote)
        store.acknowledge(sent, JSONArray().put(result))
        assertEquals("Second", store.rows().single().getJSONObject("payload").getString("name"))
        assertEquals(1, store.pending().getJSONObject(0).getInt("base_version"))
        store.close()
    }

    @Test fun itemizedBillsRoundTripWithoutLosingAssignedExtras() {
        val store = store()
        val payload = JSONObject().put("amount_minor", 1500).put("description", "Dinner")
            .put("participants", JSONArray().put("Alex").put("Sam")).put("paid_by", "Sam")
            .put("extras", JSONArray().put(JSONObject().put("description", "Wine").put("amount_minor", 500).put("participant", "Alex")))
            .put("shares", JSONArray().put(JSONObject().put("name", "Alex").put("amount_minor", 1000))
                .put(JSONObject().put("name", "Sam").put("amount_minor", 500)))
        val row = store.save("bill", payload)
        val sent = store.pending()
        val remote = JSONObject(row.toString()).put("version", 1).put("revision", 1)
        val result = JSONObject().put("operation_id", sent.getJSONObject(0).getString("operation_id"))
            .put("status", "applied").put("record", remote)
        store.acknowledge(sent, JSONArray().put(result))
        assertEquals(0, store.pendingCount())
        val saved = store.rows().single().getJSONObject("payload")
        assertEquals(500, saved.getJSONArray("extras").getJSONObject(0).getInt("amount_minor"))
        assertEquals(1000, saved.getJSONArray("shares").getJSONObject(0).getInt("amount_minor"))
        store.close()
    }

    @Test fun olderServerCannotSilentlyReplaceItemizedBillWithEqualShares() {
        val store = store()
        val payload = JSONObject().put("amount_minor", 1500).put("description", "Dinner")
            .put("extras", JSONArray().put(JSONObject().put("description", "Wine").put("amount_minor", 500).put("participant", "Alex")))
            .put("shares", JSONArray().put(JSONObject().put("name", "Alex").put("amount_minor", 1000))
                .put(JSONObject().put("name", "Sam").put("amount_minor", 500)))
        val row = store.save("bill", payload)
        val sent = store.pending()
        val equalPayload = JSONObject(payload.toString()).apply {
            remove("extras")
            put("shares", JSONArray().put(JSONObject().put("name", "Alex").put("amount_minor", 750))
                .put(JSONObject().put("name", "Sam").put("amount_minor", 750)))
        }
        val remote = JSONObject(row.toString()).put("version", 1).put("revision", 1).put("payload", equalPayload)
        val result = JSONObject().put("operation_id", sent.getJSONObject(0).getString("operation_id"))
            .put("status", "applied").put("record", remote)
        try { store.acknowledge(sent, JSONArray().put(result)); fail("An incompatible server must not discard extras") }
        catch (error: ItemizedBillSyncUnavailable) {
            assertEquals(row.getString("id"), error.recordId)
            assertEquals(1, error.remoteVersion)
        }
        assertEquals(1, store.pendingCount())
        assertEquals(1000, store.rows().single().getJSONObject("payload").getJSONArray("shares").getJSONObject(0).getInt("amount_minor"))
        assertEquals(sent.getJSONObject(0).getString("operation_id"), store.pending().getJSONObject(0).getString("operation_id"))
        store.retainIncompatibleBill(ItemizedBillSyncUnavailable(row.getString("id"), 1))
        assertEquals(1, store.blockedItemizedBills())
        assertEquals(0, store.pending().length())
        assertFalse(store.rows().single().getBoolean("needs_review"))
        store.retryItemizedBills()
        assertEquals(0, store.blockedItemizedBills())
        val retry = store.pending().getJSONObject(0)
        assertEquals(1, retry.getInt("base_version"))
        assertNotEquals(sent.getJSONObject(0).getString("operation_id"), retry.getString("operation_id"))
        assertEquals(1000, retry.getJSONObject("payload").getJSONArray("shares").getJSONObject(0).getInt("amount_minor"))
        store.close()
    }

    @Test fun ciphertextCannotBeMovedBetweenRecords() {
        val vault = DeviceVault(context, "instrumentation-${UUID.randomUUID()}")
        val encrypted = vault.seal("secret", "record:A")
        assertEquals("secret", vault.open(encrypted, "record:A"))
        try { vault.open(encrypted, "record:B"); fail("Wrong AAD must fail") } catch (_: javax.crypto.AEADBadTagException) { }
    }

    @Test fun acknowledgementDoesNotShortenAnActiveUndoWindow() {
        val store = store()
        val original = store.save("category", JSONObject().put("name", "Food"))
        val sent = store.pending()
        store.save("category", original.getJSONObject("payload"), original, deleted = true, delay = 6000)
        val remote = JSONObject(original.toString()).put("version", 1).put("revision", 1)
        val result = JSONObject().put("operation_id", sent.getJSONObject(0).getString("operation_id"))
            .put("status", "applied").put("record", remote)
        store.acknowledge(sent, JSONArray().put(result))
        assertEquals(0, store.pending().length())
        val deleted = store.rows(true).single()
        assertTrue(deleted.getBoolean("deleted"))
        store.save("category", original.getJSONObject("payload"), deleted)
        assertEquals(1, store.pending().getJSONObject(0).getInt("base_version"))
        store.close()
    }

    @Test fun refreshCanBeWrappedButNotUnwrappedWithoutFingerprint() {
        org.junit.Assume.assumeTrue(androidx.biometric.BiometricManager.from(context).canAuthenticate(
            androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS)
        val vault = DeviceVault(context, "instrumentation-${UUID.randomUUID()}")
        val wrapped = vault.wrapRefresh("synthetic-refresh-${UUID.randomUUID()}")
        assertTrue(wrapped.isNotBlank())
        val attempted = runCatching { vault.unlockCipher().doFinal(android.util.Base64.decode(wrapped, android.util.Base64.NO_WRAP)) }
        assertTrue("Private-key decryption must require a biometric match", attempted.isFailure)
        vault.forgetLogin()
    }
}
