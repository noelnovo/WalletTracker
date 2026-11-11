package win.downops.wallettracker.data.sqlite.season

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import jakarta.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import win.downops.wallettracker.data.SeasonRepository
import win.downops.wallettracker.data.models.AppResult
import win.downops.wallettracker.data.models.Season
import win.downops.wallettracker.data.sqlite.DatabaseHelper
import java.io.Closeable
import java.sql.SQLException

private const val DATABASE_NOT_AVAILABLE = "Database not available"

class SeasonSqlService private constructor(
    private var database: SQLiteDatabase?,
    private var dbHelper: DatabaseHelper?
) : Closeable, SeasonRepository {
    @Inject constructor(@ApplicationContext context: Context?) : this(null, DatabaseHelper(context)) { open() }
    internal constructor(database: SQLiteDatabase?) : this(database, null)

    @Throws(SQLException::class)
    fun open() {
        database = dbHelper?.writableDatabase
    }

    override fun close() {
        dbHelper?.close()
    }

    override suspend fun getAll(): AppResult<List<Season>> = withContext(Dispatchers.IO) {
        try {
            val list = mutableListOf<Season>()
            val cursor = database?.query("Season", null, null, null, null, null, "year DESC, month DESC")
                ?: return@withContext AppResult.Error(DATABASE_NOT_AVAILABLE, isControlled = true)
            cursor.use {
                while (it.moveToNext()) {
                    list.add(mapCursor(it))
                }
            }
            AppResult.Success("Seasons fetched successfully", list)
        } catch (e: Exception) {
            AppResult.Error(e.message ?: "Unexpected error reading seasons", isControlled = false, e.stackTrace.joinToString("\n"))
        }
    }

    override suspend fun getById(seasonId: Long): AppResult<Season?> = withContext(Dispatchers.IO) {
        try {
            val cursor = database?.query("Season", null, "id = ?", arrayOf(seasonId.toString()), null, null, null)
                ?: return@withContext AppResult.Error(DATABASE_NOT_AVAILABLE, isControlled = true)
            val season = cursor.use { if (it.moveToFirst()) mapCursor(it) else null }
            if (season == null) return@withContext AppResult.Error("Season not found", isControlled = true)
            AppResult.Success("Season fetched successfully", season)
        } catch (e: Exception) {
            AppResult.Error(e.message ?: "Unexpected error fetching season", isControlled = false, e.stackTrace.joinToString("\n"))
        }
    }

    override suspend fun getByYearMonth(year: Int, month: Int): AppResult<Season?> = withContext(Dispatchers.IO) {
        try {
            val cursor = database?.query("Season", null, "year = ? AND month = ?", arrayOf(year.toString(), month.toString()), null, null, null)
                ?: return@withContext AppResult.Error(DATABASE_NOT_AVAILABLE, isControlled = true)
            var season: Season? = null
            cursor.use { if (it.moveToFirst()) season = mapCursor(it) }
            AppResult.Success("Season fetched successfully", season)
        } catch (e: Exception) {
            AppResult.Error(e.message ?: "Unexpected error fetching season", isControlled = false, e.stackTrace.joinToString("\n"))
        }
    }

    override suspend fun getOrCreate(year: Int, month: Int): AppResult<Season?> = withContext(Dispatchers.IO) {
        try {
            val existing = getByYearMonth(year, month)
            if (existing is AppResult.Success && existing.data != null) {
                return@withContext existing
            }
            val values = ContentValues().apply {
                put("year", year)
                put("month", month)
            }
            val rowId = database?.insert("Season", null, values)
                ?: return@withContext AppResult.Error(DATABASE_NOT_AVAILABLE, isControlled = true)
            if (rowId == -1L) return@withContext AppResult.Error("Failed to create season", isControlled = true)
            AppResult.Success("Season created successfully", Season(rowId, year, month))
        } catch (e: Exception) {
            AppResult.Error(e.message ?: "Unexpected error creating season", isControlled = false, e.stackTrace.joinToString("\n"))
        }
    }

    override suspend fun deleteById(seasonId: Long): AppResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val rowsDeleted = database?.delete("Season", "id = ?", arrayOf(seasonId.toString()))
                ?: return@withContext AppResult.Error(DATABASE_NOT_AVAILABLE, isControlled = true)
            if (rowsDeleted == 0) return@withContext AppResult.Error("Season not found", isControlled = true)
            AppResult.Success("Season deleted successfully", Unit)
        } catch (e: Exception) {
            AppResult.Error(e.message ?: "Unexpected error deleting season", isControlled = false, e.stackTrace.joinToString("\n"))
        }
    }

    @SuppressLint("Range")
    private fun mapCursor(cursor: Cursor): Season {
        return Season(
            cursor.getLong(cursor.getColumnIndex("id")),
            cursor.getInt(cursor.getColumnIndex("year")),
            cursor.getInt(cursor.getColumnIndex("month"))
        )
    }
}
