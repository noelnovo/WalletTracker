package win.downops.wallettracker.data.sqlite.expense

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import jakarta.inject.Inject
import win.downops.wallettracker.data.sqlite.DatabaseHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import win.downops.wallettracker.data.models.AppResult
import win.downops.wallettracker.data.models.Expense
import win.downops.wallettracker.data.ExpenseRepository
import java.io.Closeable
import java.sql.Date
import java.sql.SQLException
import java.text.SimpleDateFormat
import kotlin.Long
import kotlin.Throws

private const val DATABASE_NOT_AVAILABLE = "Database not available"
private const val ID_EQUALS = "id = ?"

@RequiresApi(Build.VERSION_CODES.O)
class ExpenseSqlService private constructor(
    private var database: SQLiteDatabase?,
    private var dbHelper: DatabaseHelper?
) : Closeable, ExpenseRepository {
    @Inject constructor(@ApplicationContext context: Context?) : this(null, DatabaseHelper(context)) { open() }
    internal constructor(database: SQLiteDatabase?) : this(database, null)

    @Throws(SQLException::class)
    fun open() {
        database = dbHelper?.writableDatabase
    }

    override fun close() {
        dbHelper?.close()
    }

    @SuppressLint("SimpleDateFormat")
    override suspend fun create(expense: Expense): AppResult<Expense?> = withContext(Dispatchers.IO) {
        try {
            val seasonId = getOrCreateSeasonId(expense.getDate())
            val values = ContentValues().apply {
                put("price", expense.getPrice())
                put("expenseDate", SimpleDateFormat("yyyy-MM-dd").format(expense.getDate()))
                put("category", expense.getCategoryId())
                put("description", expense.description)
                if (seasonId != null) put("seasonId", seasonId) else putNull("seasonId")
            }

            val rowId = database?.insert("Expense", null, values)
                ?: return@withContext AppResult.Error(DATABASE_NOT_AVAILABLE, isControlled = true)

            if (rowId == -1L)
                return@withContext AppResult.Error("Failed to create expense", isControlled = true)

            AppResult.Success("Expense created successfully", expense)
        } catch (e: Exception) {
            AppResult.Error(
                e.message ?: "Unexpected error creating expense",
                isControlled = false,
                e.stackTrace.joinToString("\n"))
        }
    }

    @SuppressLint("SimpleDateFormat")
    override suspend fun edit(expense: Expense): AppResult<Expense?> = withContext(Dispatchers.IO) {
        try {
            val seasonId = getOrCreateSeasonId(expense.getDate())
            val values = ContentValues().apply {
                put("price", expense.getPrice())
                put("category", expense.getCategoryId())
                put("description", expense.description)
                put("expenseDate", SimpleDateFormat("yyyy-MM-dd").format(expense.getDate()))
                if (seasonId != null) put("seasonId", seasonId) else putNull("seasonId")
            }

            val rowsAffected = database?.update("Expense", values, ID_EQUALS, arrayOf(expense.id.toString()))
                ?: return@withContext AppResult.Error(DATABASE_NOT_AVAILABLE, isControlled = true)

            if (rowsAffected == 0)
                return@withContext AppResult.Error("Failed to update expense", isControlled = true)

            AppResult.Success("Expense modified successfully", expense)
        } catch (e: Exception) {
            AppResult.Error(
                e.message ?: "Unexpected error updating expense",
                isControlled = false,
                e.stackTrace.joinToString("\n"))
        }
    }

    override suspend fun deleteById(expenseId: Long): AppResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val rowsDeleted = database?.delete("Expense", ID_EQUALS, arrayOf(expenseId.toString()))
                ?: return@withContext AppResult.Error(DATABASE_NOT_AVAILABLE, isControlled = true)

            if (rowsDeleted == 0)
                return@withContext AppResult.Error("Failed to delete expense", isControlled = true)

            AppResult.Success("Expense deleted successfully", Unit)
        } catch (e: Exception) {
            AppResult.Error(
                e.message ?: "Unexpected error deleting expense",
                isControlled = false,
                e.stackTrace.joinToString("\n"))
        }
    }

    override suspend fun deleteAll(): AppResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val rowsDeleted = database?.delete("Expense", null, null)
                ?: return@withContext AppResult.Error(DATABASE_NOT_AVAILABLE, isControlled = true)

            if (rowsDeleted == 0)
                return@withContext AppResult.Error("No expenses found to delete", isControlled = true)

            AppResult.Success("Expenses deleted successfully", Unit)
        } catch (e: Exception) {
            AppResult.Error(
                e.message ?: "Unexpected error deleting all expenses",
                isControlled = false,
                e.stackTrace.joinToString("\n"))
        }
    }

    override suspend fun getBySeasonId(seasonId: Long): AppResult<List<Expense>> = withContext(Dispatchers.IO) {
        try {
            val expenseList = mutableListOf<Expense>()
            val cursor = database?.query(
                "Expense", null, "seasonId = ?", arrayOf(seasonId.toString()),
                null, null, "expenseDate DESC, id DESC"
            ) ?: return@withContext AppResult.Error(DATABASE_NOT_AVAILABLE, isControlled = true)
            cursor.use { while (it.moveToNext()) expenseList.add(mapCursor(it)) }
            AppResult.Success("Expenses fetched successfully", expenseList)
        } catch (e: Exception) {
            AppResult.Error(e.message ?: "Unexpected error reading expenses", isControlled = false, e.stackTrace.joinToString("\n"))
        }
    }

    override suspend fun getByCatId(catId: Long): AppResult<List<Expense>> = withContext(Dispatchers.IO) {
        try {
            val expenseList = mutableListOf<Expense>()
            val cursor = database?.query(
                "Expense",
                null,
                "category = ?",
                arrayOf(catId.toString()),
                null,
                null,
                "expenseDate DESC, id DESC"
            ) ?: return@withContext AppResult.Error(DATABASE_NOT_AVAILABLE, isControlled = true)

            cursor.use {
                while (it.moveToNext()) {
                    expenseList.add(mapCursor(it))
                }
            }

            if (expenseList.isEmpty())
                return@withContext AppResult.Error("No expenses found for category ID: $catId", isControlled = true)

            AppResult.Success("Expenses fetched successfully", expenseList)
        } catch (e: Exception) {
            AppResult.Error(
                e.message ?: "Unexpected error reading expenses",
                isControlled = false,
                e.stackTrace.joinToString("\n"))
        }
    }

    override suspend fun getById(expenseId: Long): AppResult<Expense?> = withContext(Dispatchers.IO) {
        try {
            val cursor = database?.query(
                "Expense",
                null,
                ID_EQUALS,
                arrayOf(expenseId.toString()),
                null,
                null,
                null
            ) ?: return@withContext AppResult.Error(DATABASE_NOT_AVAILABLE, isControlled = true)

            val expense = cursor.use { if (it.moveToFirst()) mapCursor(it) else null }

            if (expense == null)
                return@withContext AppResult.Error("Expense not found with ID: $expenseId", isControlled = true)

            AppResult.Success("Expense fetched successfully", expense)
        } catch (e: Exception) {
            AppResult.Error(
                e.message ?: "Unexpected error fetching expense",
                isControlled = false,
                e.stackTrace.joinToString("\n"))
        }
    }

    @SuppressLint("Range")
    private fun mapCursor(cursor: Cursor): Expense {
        try {
            return Expense(cursor.getLong(cursor.getColumnIndex("id"))).apply {
                setDescription(cursor.getString(cursor.getColumnIndex("description")))
                setPrice(cursor.getDouble(cursor.getColumnIndex("price")))
                setDate(Date.valueOf(cursor.getString(cursor.getColumnIndex("expenseDate"))))
                setCategoryId(cursor.getLong(cursor.getColumnIndex("category")))
                val seasonIdx = cursor.getColumnIndex("seasonId")
                if (seasonIdx != -1 && !cursor.isNull(seasonIdx))
                    setSeasonId(cursor.getLong(seasonIdx))
            }
        } catch (e: Exception) {
            throw e
        }
    }

    private fun getOrCreateSeasonId(date: Date): Long? {
        return try {
            val cal = java.util.Calendar.getInstance().apply { time = date }
            val year = cal.get(java.util.Calendar.YEAR)
            val month = cal.get(java.util.Calendar.MONTH) + 1
            database?.execSQL(
                "INSERT OR IGNORE INTO Season (year, month) VALUES (?, ?)",
                arrayOf(year.toString(), month.toString())
            )
            val cursor = database?.query(
                "Season", arrayOf("id"), "year = ? AND month = ?",
                arrayOf(year.toString(), month.toString()), null, null, null
            )
            var id: Long? = null
            cursor?.use { if (it.moveToFirst()) id = it.getLong(0) }
            id
        } catch (e: Exception) {
            null
        }
    }
}
