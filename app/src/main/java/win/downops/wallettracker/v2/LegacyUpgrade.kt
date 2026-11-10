package win.downops.wallettracker.v2

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

/** Internal upgrade of the app's own database; not a file-import feature. */
class LegacyUpgrade(
    private val context: Context,
    private val sourceFile: File = context.getDatabasePath("walletTracker.db"),
    preferencesName: String = "wallet-v2-legacy-upgrade",
    private val legacyKeyAlias: String = "USER_KEY"
) {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    val sourceId: String get() {
        preferences.getString("source", null)?.let { return it }
        val value = UUID.randomUUID().toString()
        check(preferences.edit().putString("source", value).commit())
        return value
    }
    val completed get() = preferences.contains("destination")

    class Snapshot(val source: String, val records: List<JSONObject>, val recovery: JSONObject,
                   val categories: Int, val expenses: Int, val retainedOtherRows: Int) {
        val reviewCount get() = records.count { it.has("blocked_reason") }
    }

    class Credentials(val username: String, val ciphertext: String, val iv: String)

    private fun open() = SQLiteDatabase.openDatabase(sourceFile.path, null, SQLiteDatabase.OPEN_READWRITE)
    private fun quote(name: String) = "\"${name.replace("\"", "\"\"")}\""
    private fun secureDelete(db: SQLiteDatabase) {
        db.rawQuery("PRAGMA secure_delete=ON", null).use { check(it.moveToFirst() && it.getInt(0) == 1) }
    }
    private fun checkpoint(db: SQLiteDatabase) {
        db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { check(it.moveToFirst() && it.getInt(0) == 0) }
    }
    private fun tables(db: SQLiteDatabase): JSONObject {
        val result = JSONObject()
        db.rawQuery("SELECT name,sql FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('Session','android_metadata') ORDER BY name", null).use { names ->
            while (names.moveToNext()) {
                val name = names.getString(0)
                val rows = JSONArray()
                db.rawQuery("SELECT * FROM ${quote(name)}", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val row = JSONObject()
                        cursor.columnNames.forEachIndexed { index, column ->
                            val value: Any = when (cursor.getType(index)) {
                                Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                                Cursor.FIELD_TYPE_BLOB -> JSONObject().put("blob_base64", Base64.encodeToString(cursor.getBlob(index), Base64.NO_WRAP))
                                else -> cursor.getString(index)
                            }
                            row.put(column, value)
                        }
                        rows.put(row)
                    }
                }
                result.put(name, JSONObject().put("schema", names.getString(1)).put("rows", rows))
            }
        }
        return result
    }

    private fun rows(tables: JSONObject, name: String): List<JSONObject> {
        val array = tables.optJSONObject(name)?.optJSONArray("rows") ?: return emptyList()
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    private fun identifier(kind: String, id: Long): String = UUID.nameUUIDFromBytes(
        "wallettracker:device:$sourceId:$kind:$id".toByteArray(Charsets.UTF_8)).toString()

    private fun record(id: String, kind: String, payload: JSONObject, issues: List<String>): JSONObject {
        val row = JSONObject().put("id", id).put("kind", kind).put("version", 0)
            .put("deleted", false).put("payload", payload)
        if (issues.isNotEmpty()) row.put("blocked_reason", issues.distinct().joinToString(", "))
        return row
    }

    fun snapshot(): Snapshot? {
        if (completed || !sourceFile.isFile) return null
        val recovery = open().use { db ->
            db.beginTransactionNonExclusive()
            try { tables(db) } finally { db.endTransaction() }
        }
        val categories = rows(recovery, "ExpenseCategory")
        val expenses = rows(recovery, "Expense")
        val records = mutableListOf<JSONObject>()
        val validCategories = mutableMapOf<Long, Boolean>()
        categories.forEach { old -> snapshotCategory(old, records, validCategories) }
        expenses.forEach { old -> snapshotExpense(old, records, validCategories) }
        var otherRows = 0
        recovery.keys().forEach { name -> if (name !in listOf("Expense", "ExpenseCategory")) otherRows += rows(recovery, name).size }
        if (records.isEmpty() && otherRows == 0) return null
        return Snapshot(sourceId, records, recovery, categories.size, expenses.size, otherRows)
    }

    private fun snapshotCategory(old: JSONObject, records: MutableList<JSONObject>, validCategories: MutableMap<Long, Boolean>) {
        val name = old.optString("name", "").takeUnless { it == "null" }.orEmpty()
        val valid = name.isNotBlank() && name.length <= 80
        val id = old.getLong("id")
        validCategories[id] = valid
        records.add(record(identifier("category", id), "category", JSONObject().put("name", name.ifBlank { "Unnamed category" }), if (valid) emptyList() else listOf("Category")))
    }

    private fun snapshotExpense(old: JSONObject, records: MutableList<JSONObject>, validCategories: Map<Long, Boolean>) {
        val issues = mutableListOf<String>()
        val amount = runCatching { BigDecimal(old.get("price").toString()).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact() }.getOrNull()
        if (amount == null || amount !in 1..99999999999L) issues.add("Amount")
        val day = old.optString("expenseDate", "").takeUnless { it == "null" }.orEmpty()
        val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
        if (!day.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) || runCatching { parser.format(requireNotNull(parser.parse(day))) == day }.getOrDefault(false).not()) issues.add("Date")
        val category = old.optLong("category", -1)
        if (validCategories[category] != true) issues.add("Category")
        val description = old.optString("description", "").takeUnless { it == "null" }.orEmpty().ifBlank { "Expense" }
        if (description.length > 255) issues.add("Description")
        val payload = JSONObject().put("amount_minor", amount ?: 0).put("currency", "EUR")
            .put("date", day).put("description", description)
            .put("category_id", if (category in validCategories) identifier("category", category) else "")
        records.add(record(identifier("expense", old.getLong("id")), "expense", payload, issues))
    }

    /** Archive first, verify it, then import atomically; never infer account ownership. */
    fun migrate(snapshot: Snapshot, destination: String, store: FinanceStore) {
        require(snapshot.source == sourceId)
        if (completed) {
            check(preferences.getString("destination", null) == destination) { "Local data already belongs to another wallet" }
            return
        }
        open().use { db ->
            secureDelete(db)
            db.beginTransactionNonExclusive()
            try {
                check(tables(db).toString() == snapshot.recovery.toString()) { "Local data changed; recovery retained" }
                val payload = JSONObject().put("format", 1).put("source", snapshot.source).put("user_version", db.version).put("tables", snapshot.recovery).toString()
                val directory = File(context.filesDir, "legacy-recovery").apply { check(isDirectory || mkdirs()) }
                val archive = AtomicFile(File(directory, "${snapshot.source}.enc"))
                val vault = DeviceVault(context, "legacy-recovery:${snapshot.source}")
                val purpose = "legacy-database:${snapshot.source}"
                val output = archive.startWrite()
                try { output.write(vault.seal(payload, purpose).toByteArray(Charsets.UTF_8)); archive.finishWrite(output) }
                catch (error: Exception) { archive.failWrite(output); throw error }
                check(vault.open(archive.readFully().toString(Charsets.UTF_8), purpose) == payload) { "Local recovery verification failed" }
                store.importLegacy(snapshot.source, snapshot.records, DeviceVault.hash(snapshot.recovery.toString()))
                snapshot.recovery.keys().forEach { db.delete(quote(it), null, null) }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            checkpoint(db)
            db.execSQL("VACUUM")
        }
        check(preferences.edit().putString("destination", destination).commit())
    }

    fun credentials(): Credentials? {
        if (!sourceFile.isFile) return null
        return runCatching {
            open().use { db ->
                db.rawQuery("SELECT username,cipheredCredentials,iv,fingerPrint FROM Session LIMIT 1", null).use { cursor ->
                    if (!cursor.moveToFirst() || cursor.getInt(3) != 1) return@use null
                    val encrypted = cursor.getString(1).orEmpty()
                    val iv = cursor.getString(2).orEmpty()
                    if (encrypted.isEmpty() || iv.isEmpty()) null else Credentials(cursor.getString(0).orEmpty(), encrypted, iv)
                }
            }
        }.getOrNull()
    }

    /** Old v1 token/client key are no longer used; keep biometric password only until reauth. */
    fun scrubPlainSession() {
        if (!sourceFile.isFile) return
        open().use { db ->
            val columns = mutableSetOf<String>()
            db.rawQuery("PRAGMA table_info(Session)", null).use { while (it.moveToNext()) columns.add(it.getString(1)) }
            val values = ContentValues()
            listOf("token", "privateKey").filter { it in columns }.forEach { values.put(it, "") }
            if (values.size() == 0) return@use
            secureDelete(db)
            db.update("Session", values, null, null)
            checkpoint(db)
            db.execSQL("VACUUM")
        }
    }

    fun retireCredentials() {
        if (sourceFile.isFile) open().use { db ->
            val exists = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='Session'", null).use { it.moveToFirst() }
            if (exists) {
                secureDelete(db)
                db.delete("Session", null, null)
                checkpoint(db)
                db.execSQL("VACUUM")
            }
        }
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.let { if (it.containsAlias(legacyKeyAlias)) it.deleteEntry(legacyKeyAlias) }
    }
}
