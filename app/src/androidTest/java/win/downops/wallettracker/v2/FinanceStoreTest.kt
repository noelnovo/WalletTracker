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

    @Test fun ciphertextCannotBeMovedBetweenRecords() {
        val vault = DeviceVault(context, "instrumentation-${UUID.randomUUID()}")
        val encrypted = vault.seal("secret", "record:A")
        assertEquals("secret", vault.open(encrypted, "record:A"))
        try { vault.open(encrypted, "record:B"); fail("Wrong AAD must fail") } catch (_: javax.crypto.AEADBadTagException) { }
    }
}
