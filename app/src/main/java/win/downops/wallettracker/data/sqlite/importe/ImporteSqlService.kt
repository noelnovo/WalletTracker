package win.downops.wallettracker.data.sqlite.importe

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import jakarta.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import win.downops.wallettracker.data.ImporteRepository
import win.downops.wallettracker.data.models.AppResult
import win.downops.wallettracker.data.models.Importe
import win.downops.wallettracker.data.sqlite.DatabaseHelper
import java.io.Closeable
import java.sql.Date
import java.sql.SQLException
import java.text.SimpleDateFormat

class ImporteSqlService @Inject constructor(
    @ApplicationContext context: Context?
) : Closeable, ImporteRepository {

    private var database: SQLiteDatabase? = null
    private var dbHelper: DatabaseHelper? = DatabaseHelper(context)

    init {
        database = dbHelper?.writableDatabase
    }

    @Throws(SQLException::class)
    fun open() {
        database = dbHelper?.writableDatabase
    }

    override fun close() {
        dbHelper?.close()
    }

    override suspend fun getBySeasonId(seasonId: Long): AppResult<List<Importe>> = withContext(Dispatchers.IO) {
        try {
            val list = mutableListOf<Importe>()
            val cursor = database?.query(
                "Importe", null, "seasonId = ?", arrayOf(seasonId.toString()),
                null, null, "importeDate DESC, id DESC"
            ) ?: return@withContext AppResult.Error("Database not available", isControlled = true)
            cursor.use { while (it.moveToNext()) list.add(mapCursor(it)) }
            AppResult.Success("Importes fetched successfully", list)
        } catch (e: Exception) {
            AppResult.Error(e.message ?: "Unexpected error reading importes", isControlled = false, e.stackTrace.joinToString("\n"))
        }
    }

    override suspend fun getById(importeId: Long): AppResult<Importe?> = withContext(Dispatchers.IO) {
        try {
            val cursor = database?.query("Importe", null, "id = ?", arrayOf(importeId.toString()), null, null, null)
                ?: return@withContext AppResult.Error("Database not available", isControlled = true)
            var importe: Importe? = null
            cursor.use { if (it.moveToFirst()) importe = mapCursor(it) }
            if (importe == null) return@withContext AppResult.Error("Importe not found", isControlled = true)
            AppResult.Success("Importe fetched successfully", importe!!)
        } catch (e: Exception) {
            AppResult.Error(e.message ?: "Unexpected error fetching importe", isControlled = false, e.stackTrace.joinToString("\n"))
        }
    }

    @SuppressLint("SimpleDateFormat")
    override suspend fun create(importe: Importe): AppResult<Importe?> = withContext(Dispatchers.IO) {
        try {
            val values = ContentValues().apply {
                put("concept", importe.getConcept())
                put("importeDate", SimpleDateFormat("yyyy-MM-dd").format(importe.getDate()))
                put("amount", importe.getAmount())
                put("balanceAfter", importe.getBalanceAfter())
                put("seasonId", importe.getSeasonId())
            }
            val rowId = database?.insert("Importe", null, values)
                ?: return@withContext AppResult.Error("Database not available", isControlled = true)
            if (rowId == -1L) return@withContext AppResult.Error("Failed to create importe", isControlled = true)
            AppResult.Success("Importe created successfully", importe)
        } catch (e: Exception) {
            AppResult.Error(e.message ?: "Unexpected error creating importe", isControlled = false, e.stackTrace.joinToString("\n"))
        }
    }

    override suspend fun deleteById(importeId: Long): AppResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val rowsDeleted = database?.delete("Importe", "id = ?", arrayOf(importeId.toString()))
                ?: return@withContext AppResult.Error("Database not available", isControlled = true)
            if (rowsDeleted == 0) return@withContext AppResult.Error("Importe not found", isControlled = true)
            AppResult.Success("Importe deleted successfully", Unit)
        } catch (e: Exception) {
            AppResult.Error(e.message ?: "Unexpected error deleting importe", isControlled = false, e.stackTrace.joinToString("\n"))
        }
    }

    override suspend fun deleteBySeasonId(seasonId: Long): AppResult<Unit> = withContext(Dispatchers.IO) {
        try {
            database?.delete("Importe", "seasonId = ?", arrayOf(seasonId.toString()))
                ?: return@withContext AppResult.Error("Database not available", isControlled = true)
            AppResult.Success("Importes deleted successfully", Unit)
        } catch (e: Exception) {
            AppResult.Error(e.message ?: "Unexpected error deleting importes", isControlled = false, e.stackTrace.joinToString("\n"))
        }
    }

    @SuppressLint("Range")
    private fun mapCursor(cursor: Cursor): Importe {
        return Importe(
            cursor.getLong(cursor.getColumnIndex("id")),
            cursor.getString(cursor.getColumnIndex("concept")),
            Date.valueOf(cursor.getString(cursor.getColumnIndex("importeDate"))),
            cursor.getDouble(cursor.getColumnIndex("amount")),
            cursor.getDouble(cursor.getColumnIndex("balanceAfter")),
            cursor.getLong(cursor.getColumnIndex("seasonId"))
        )
    }
}
