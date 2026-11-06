package win.downops.wallettracker.v2

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LegacyUpgradeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val databases = mutableListOf<String>()
    private val accounts = mutableListOf<String>()
    private val preferences = mutableListOf<String>()
    private val archives = mutableListOf<File>()

    private fun fixture(): Pair<File, LegacyUpgrade> {
        val name = "legacy-test-${UUID.randomUUID()}.db"
        databases.add(name)
        val file = context.getDatabasePath(name)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE Session (id INTEGER PRIMARY KEY, token TEXT, privateKey TEXT, username TEXT, cipheredCredentials TEXT, iv TEXT, fingerPrint INTEGER)")
            db.execSQL("INSERT INTO Session VALUES (1,'synthetic-token','synthetic-private','fixture','synthetic-cipher','synthetic-iv',1)")
            db.execSQL("CREATE TABLE ExpenseCategory (id INTEGER PRIMARY KEY, name TEXT, sortOrder INTEGER)")
            db.execSQL("INSERT INTO ExpenseCategory VALUES (1,'Food',7)")
            db.execSQL("CREATE TABLE Expense (id INTEGER PRIMARY KEY, price DOUBLE, description TEXT, expenseDate TEXT, category INTEGER)")
            db.execSQL("INSERT INTO Expense VALUES (1,10.005,'Lunch','2026-11-05',1)")
            db.execSQL("CREATE TABLE Season (id INTEGER PRIMARY KEY, year INTEGER, month INTEGER)")
            db.execSQL("INSERT INTO Season VALUES (1,2026,11)")
            db.execSQL("CREATE TABLE Importe (id INTEGER PRIMARY KEY, concept TEXT, iban TEXT, amount DOUBLE)")
            db.execSQL("INSERT INTO Importe VALUES (1,'Retained statement','SYNTHETIC-IBAN',100.25)")
            db.version = 8
        }
        val prefs = "legacy-test-${UUID.randomUUID()}"
        preferences.add(prefs)
        val upgrade = LegacyUpgrade(context, file, prefs, "legacy-key-test-${UUID.randomUUID()}")
        val source = upgrade.sourceId
        accounts.add("legacy-recovery:$source")
        archives.add(File(context.filesDir, "legacy-recovery/$source.enc"))
        return file to upgrade
    }

    private fun store(): FinanceStore {
        val account = "migration-test-${UUID.randomUUID()}"
        accounts.add(account)
        return FinanceStore(context, account).also { databases.add(it.databaseName!!) }
    }

    @After fun cleanup() {
        databases.forEach { context.deleteDatabase(it) }
        preferences.forEach { context.deleteSharedPreferences(it) }
        archives.forEach { it.delete() }
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        accounts.forEach { account -> val alias = "wallet.v2.data.${DeviceVault.hash(account)}"; if (keys.containsAlias(alias)) keys.deleteEntry(alias) }
    }

    @Test fun movesExpensesExactlyOnceAndVerifiesEncryptedRecovery() {
        val (file, upgrade) = fixture()
        val snapshot = requireNotNull(upgrade.snapshot())
        assertEquals(1, snapshot.categories)
        assertEquals(1, snapshot.expenses)
        assertEquals(2, snapshot.retainedOtherRows)
        assertEquals(1001, snapshot.records.first { it.getString("kind") == "expense" }.getJSONObject("payload").getLong("amount_minor"))
        store().use { target ->
            upgrade.migrate(snapshot, "fixture-account", target)
            assertEquals(2, target.rows().size)
            assertEquals(2, target.pending().length())
            upgrade.migrate(snapshot, "fixture-account", target)
            assertEquals(2, target.rows().size)
            assertNull(upgrade.snapshot())
        }
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM Expense", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            db.rawQuery("SELECT COUNT(*) FROM Importe", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            db.rawQuery("SELECT COUNT(*) FROM Session", null).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        }
        val encrypted = archives.single().readText()
        assertFalse(encrypted.contains("SYNTHETIC-IBAN"))
        val recovered = JSONObject(DeviceVault(context, "legacy-recovery:${snapshot.source}").open(encrypted, "legacy-database:${snapshot.source}"))
        assertEquals(8, recovered.getInt("user_version"))
        assertEquals("SYNTHETIC-IBAN", recovered.getJSONObject("tables").getJSONObject("Importe").getJSONArray("rows").getJSONObject(0).getString("iban"))
        assertFalse(recovered.getJSONObject("tables").has("Session"))
    }

    @Test fun invalidRowsAreRetainedForReviewInsteadOfBlockingOtherSync() {
        val (file, upgrade) = fixture()
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("UPDATE Expense SET price=NULL,expenseDate=NULL,category=NULL") }
        val snapshot = requireNotNull(upgrade.snapshot())
        assertEquals(1, snapshot.reviewCount)
        store().use { target ->
            upgrade.migrate(snapshot, "fixture-account", target)
            assertEquals(2, target.rows().size)
            assertEquals(1, target.pending().length())
            val draft = target.rows().first { it.getString("kind") == "expense" }
            assertTrue(draft.getBoolean("needs_review"))
            val category = target.rows().first { it.getString("kind") == "category" }
            target.save("expense", JSONObject().put("amount_minor", 1234).put("date", "2026-11-05").put("currency", "EUR").put("description", "Fixed").put("category_id", category.getString("id")), draft)
            assertEquals(2, target.pending().length())
            assertFalse(target.rows().first { it.getString("kind") == "expense" }.getBoolean("needs_review"))
        }
    }

    @Test fun sourceChangesAbortBeforeImportOrDeletion() {
        val (file, upgrade) = fixture()
        val snapshot = requireNotNull(upgrade.snapshot())
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("UPDATE Expense SET description='Changed locally'") }
        store().use { target ->
            try { upgrade.migrate(snapshot, "fixture-account", target); fail("Changed source must not be erased") } catch (_: IllegalStateException) { }
            assertTrue(target.rows().isEmpty())
        }
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT description FROM Expense", null).use { it.moveToFirst(); assertEquals("Changed locally", it.getString(0)) }
        }
    }

    @Test fun aMigrationCannotBeRepeatedWithDifferentSourceData() {
        val (_, upgrade) = fixture()
        val snapshot = requireNotNull(upgrade.snapshot())
        store().use { target ->
            target.importLegacy(snapshot.source, snapshot.records, "synthetic-first-proof")
            try { target.importLegacy(snapshot.source, snapshot.records, "synthetic-changed-proof"); fail("Different snapshot must require review") } catch (_: IllegalStateException) { }
            assertEquals(2, target.rows().size)
        }
    }

    @Test fun retirementDoesNotDeleteFinancialData() {
        val (file, upgrade) = fixture()
        upgrade.scrubPlainSession()
        assertNotNull(upgrade.credentials())
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT token,privateKey FROM Session", null).use { it.moveToFirst(); assertEquals("", it.getString(0)); assertEquals("", it.getString(1)) }
        }
        upgrade.retireCredentials()
        assertNull(upgrade.credentials())
        assertEquals(1, requireNotNull(upgrade.snapshot()).expenses)
    }

    @Test fun upgradeOfV2CacheIsNonDestructive() {
        val account = "schema-test-${UUID.randomUUID()}"
        accounts.add(account)
        val name = "finance-v2-${DeviceVault.hash(account).take(32)}.db"
        databases.add(name)
        val row = JSONObject().put("id", UUID.randomUUID().toString()).put("kind", "category").put("version", 1).put("deleted", false).put("payload", JSONObject().put("name", "Existing"))
        val encrypted = DeviceVault(context, account).seal(row.toString(), "record:${row.getString("id")}")
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
            db.execSQL("CREATE TABLE records (id TEXT PRIMARY KEY, body TEXT NOT NULL, operation TEXT, conflict TEXT, not_before INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE metadata (id INTEGER PRIMARY KEY, cursor INTEGER NOT NULL)")
            db.execSQL("INSERT INTO metadata VALUES (1,7)")
            db.execSQL("INSERT INTO records(id,body) VALUES (?,?)", arrayOf(row.getString("id"), encrypted))
            db.version = 1
        }
        FinanceStore(context, account).use { target ->
            assertEquals("Existing", target.rows().single().getJSONObject("payload").getString("name"))
            assertEquals(7, target.cursor())
        }
    }
}
