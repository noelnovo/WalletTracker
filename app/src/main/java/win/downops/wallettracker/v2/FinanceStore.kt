package win.downops.wallettracker.v2

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One local database per server account. Only IDs/versions are stored unencrypted. */
class FinanceStore(context: Context, account: String) : SQLiteOpenHelper(context,
    "finance-v2-${DeviceVault.hash(account).take(32)}.db", null, 2) {
    private val vault = DeviceVault(context, account)
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE records (id TEXT PRIMARY KEY, body TEXT NOT NULL, operation TEXT, conflict TEXT, not_before INTEGER NOT NULL DEFAULT 0, blocked_reason TEXT)")
        db.execSQL("CREATE TABLE metadata (id INTEGER PRIMARY KEY CHECK(id=1), cursor INTEGER NOT NULL)")
        db.execSQL("INSERT INTO metadata VALUES (1,0)")
        db.execSQL("CREATE TABLE legacy_sources (id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE records ADD COLUMN blocked_reason TEXT")
            db.execSQL("CREATE TABLE legacy_sources (id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL)")
        }
    }

    private fun transaction(block: () -> Unit) {
        writableDatabase.beginTransaction()
        try { block(); writableDatabase.setTransactionSuccessful() } finally { writableDatabase.endTransaction() }
    }

    @Synchronized fun rows(includeDeleted: Boolean = false): List<JSONObject> = readableDatabase.rawQuery("SELECT id,body,conflict,blocked_reason FROM records", null).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val row = JSONObject(vault.open(cursor.getString(1), "record:$id"))
                row.put("has_conflict", !cursor.isNull(2))
                row.put("needs_review", !cursor.isNull(3))
                if (includeDeleted || !row.optBoolean("deleted")) add(row)
            }
        }.sortedByDescending { it.optJSONObject("payload")?.optString("date", "") }
    }

    @Synchronized fun save(kind: String, payload: JSONObject, existing: JSONObject? = null, deleted: Boolean = false, delay: Long = 0): JSONObject {
        val id = existing?.getString("id") ?: UUID.randomUUID().toString()
        val version = existing?.optInt("version") ?: 0
        val row = JSONObject().put("id", id).put("kind", kind).put("version", version).put("payload", payload).put("deleted", deleted)
        putPending(row, delay)
        return row
    }

    private fun putPending(row: JSONObject, delay: Long = 0) {
        val id = row.getString("id")
        val operation = JSONObject(row.toString()).put("operation_id", UUID.randomUUID().toString()).put("base_version", row.optInt("version"))
        operation.remove("version"); operation.remove("revision"); operation.remove("has_conflict")
        operation.remove("needs_review"); operation.remove("blocked_reason")
        val values = ContentValues().apply {
            put("id", id); put("body", vault.seal(row.toString(), "record:$id"))
            put("operation", vault.seal(operation.toString(), "operation:$id"))
            putNull("conflict"); put("not_before", System.currentTimeMillis() + delay)
            put("blocked_reason", row.optString("blocked_reason").takeIf(String::isNotBlank))
        }
        writableDatabase.insertWithOnConflict("records", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun pending(): JSONArray = readableDatabase.rawQuery("SELECT id,operation FROM records WHERE operation IS NOT NULL AND conflict IS NULL AND blocked_reason IS NULL AND not_before<=? ORDER BY rowid LIMIT 100", arrayOf(System.currentTimeMillis().toString())).use { cursor ->
        val operations = mutableListOf<JSONObject>()
        while (cursor.moveToNext()) operations.add(JSONObject(vault.open(cursor.getString(1), "operation:${cursor.getString(0)}")))
        // Dependencies first: create categories before expenses; delete expenses before categories.
        JSONArray(operations.sortedBy { when { it.optBoolean("deleted") && it.getString("kind") != "category" -> 0; it.getString("kind") == "category" && !it.optBoolean("deleted") -> 1; else -> 2 } })
    }

    @Synchronized fun acknowledge(sent: JSONArray, results: JSONArray) = transaction {
        for (i in 0 until results.length()) {
            val result = results.getJSONObject(i)
            val operation = (0 until sent.length()).map { sent.getJSONObject(it) }.first { it.getString("operation_id") == result.getString("operation_id") }
            val id = operation.getString("id")
            if (result.getString("status") == "conflict") {
                writableDatabase.update("records", ContentValues().apply { put("conflict", vault.seal(result.optJSONObject("remote")?.toString() ?: "null", "conflict:$id")) }, "id=?", arrayOf(id))
            } else {
                val remote = result.getJSONObject("record")
                val current = pendingFor(id)
                if (current != null && current.getString("operation_id") != operation.getString("operation_id")) {
                    // A UI edit raced the request: keep it and rebase on our acknowledged write.
                    val local = rows(true).first { it.getString("id") == id }.put("version", remote.getInt("version"))
                    val remainingUndo = readableDatabase.rawQuery("SELECT not_before FROM records WHERE id=?", arrayOf(id)).use {
                        if (it.moveToFirst()) (it.getLong(0) - System.currentTimeMillis()).coerceAtLeast(0) else 0
                    }
                    putPending(local, remainingUndo)
                } else putRemote(remote)
            }
        }
    }

    private fun pendingFor(id: String): JSONObject? = readableDatabase.rawQuery("SELECT operation FROM records WHERE id=?", arrayOf(id)).use {
        if (it.moveToFirst() && !it.isNull(0)) JSONObject(vault.open(it.getString(0), "operation:$id")) else null
    }

    private fun putRemote(row: JSONObject) {
        val id = row.getString("id")
        writableDatabase.insertWithOnConflict("records", null, ContentValues().apply {
            put("id", id); put("body", vault.seal(row.toString(), "record:$id")); putNull("operation"); putNull("conflict"); put("not_before", 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun applyPage(page: JSONObject) = transaction {
        val records = page.getJSONArray("records")
        for (i in 0 until records.length()) {
            val remote = records.getJSONObject(i)
            val id = remote.getString("id")
            val pending = pendingFor(id)
            if (pending == null) putRemote(remote)
            else if (pending.getInt("base_version") != remote.getInt("version")) {
                writableDatabase.update("records", ContentValues().apply { put("conflict", vault.seal(remote.toString(), "conflict:$id")) }, "id=?", arrayOf(id))
            }
        }
        writableDatabase.execSQL("UPDATE metadata SET cursor=? WHERE id=1", arrayOf(page.getLong("cursor")))
    }

    @Synchronized fun cursor(): Long = readableDatabase.rawQuery("SELECT cursor FROM metadata WHERE id=1", null).use { it.moveToFirst(); it.getLong(0) }
    @Synchronized fun pendingCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM records WHERE operation IS NOT NULL", null).use { it.moveToFirst(); it.getInt(0) }
    @Synchronized fun remoteConflict(id: String): JSONObject? = readableDatabase.rawQuery("SELECT conflict FROM records WHERE id=?", arrayOf(id)).use {
        if (it.moveToFirst() && !it.isNull(0)) vault.open(it.getString(0), "conflict:$id").takeUnless { value -> value == "null" }?.let(::JSONObject) else null
    }
    @Synchronized fun resolve(local: JSONObject, keepLocal: Boolean) = transaction {
        val remote = remoteConflict(local.getString("id"))
        if (keepLocal) putPending(JSONObject(local.toString()).put("version", remote?.optInt("version") ?: 0))
        else if (remote != null) putRemote(remote)
        else writableDatabase.delete("records", "id=?", arrayOf(local.getString("id")))
    }

    @Synchronized fun importLegacy(source: String, records: List<JSONObject>, sourceFingerprint: String = DeviceVault.hash(JSONArray(records).toString())) = transaction {
        val previous = readableDatabase.rawQuery("SELECT fingerprint FROM legacy_sources WHERE id=?", arrayOf(source)).use {
            if (it.moveToFirst()) vault.open(it.getString(0), "legacy-proof:$source") else null
        }
        if (previous != null) {
            check(previous == sourceFingerprint) { "Local migration source changed; recovery retained" }
            return@transaction
        }
        records.forEach { row ->
            val id = row.getString("id")
            val collision = readableDatabase.rawQuery("SELECT 1 FROM records WHERE id=?", arrayOf(id)).use { it.moveToFirst() }
            check(!collision) { "Local migration identity conflict" }
            putPending(row)
        }
        writableDatabase.execSQL("INSERT INTO legacy_sources(id,fingerprint) VALUES (?,?)", arrayOf(source, vault.seal(sourceFingerprint, "legacy-proof:$source")))
    }
}
