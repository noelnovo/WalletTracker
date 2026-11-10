package win.downops.wallettracker.data.sqlite.expenseCategory

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.database.getIntOrNull
import dagger.hilt.android.qualifiers.ApplicationContext
import jakarta.inject.Inject
import win.downops.wallettracker.data.sqlite.DatabaseHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import win.downops.wallettracker.data.models.AppResult
import win.downops.wallettracker.data.models.ExpenseCategory
import win.downops.wallettracker.data.ExpenseCategoryRepository
import java.io.Closeable
import java.sql.SQLException

private const val DATABASE_NOT_AVAILABLE = "Database not available"
private const val ID_EQUALS = "id = ?"

class ExpenseCategorySqlService @Inject constructor(
    @ApplicationContext context: Context?)
    : Closeable,
    ExpenseCategoryRepository {

    private var database: SQLiteDatabase? = null
    private var dbHelper: DatabaseHelper? = null

    init {
        dbHelper = DatabaseHelper(context)
        database = dbHelper?.writableDatabase
    }

    @Throws(SQLException::class)
    fun open() {
        database = dbHelper?.writableDatabase
    }

    override fun close() {
        dbHelper?.close()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override suspend fun getAll(): AppResult<List<ExpenseCategory>> = withContext(Dispatchers.IO) {
        return@withContext try {
            val expenseCategories = mutableListOf<ExpenseCategory>()

            val query = """
                SELECT ec.sortOrder, ec.id, ec.name, SUM(e.price) AS total
                FROM ExpenseCategory ec
                LEFT JOIN Expense e ON ec.id = e.category
                GROUP BY ec.id
                ORDER BY 
                    CASE WHEN ec.sortOrder IS NULL THEN 1 ELSE 0 END,
                    ec.sortOrder ASC, 
                    ec.id ASC
            """

            val cursor = database?.rawQuery(query, null)
                ?: return@withContext AppResult.Error(DATABASE_NOT_AVAILABLE, isControlled = true)

            cursor.use {
                while (it.moveToNext()) {
                    expenseCategories.add(mapCursorToExpenseCategory(it))
                }
            }

            AppResult.Success("ExpenseCategory's fetched successfully", expenseCategories)
        } catch (e: Exception) {
            AppResult.Error(
                e.message ?: "Unexpected error reading categories",
                isControlled = false,
                e.stackTrace.joinToString("\n"))
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override suspend fun getById(catId: Long): AppResult<ExpenseCategory?> = withContext(Dispatchers.IO) {
        return@withContext try {
            val cursor = database?.query(
                "ExpenseCategory",
                null,
                ID_EQUALS,
                arrayOf(catId.toString()),
                null,
                null,
                null
            ) ?: return@withContext AppResult.Error(DATABASE_NOT_AVAILABLE, isControlled = true)

            cursor.use {
                if (it.moveToFirst()) {
                    val category = mapCursorToExpenseCategory(it)
                    return@withContext AppResult.Success("ExpenseCategory fetched successfully", category)
                }
            }

            AppResult.Error("Category not found", isControlled = true)
        } catch (e: Exception) {
            AppResult.Error(
                e.message ?: "Unexpected error reading category",
                isControlled = false,
                e.stackTrace.joinToString("\n"))
        }
    }

    override suspend fun create(category: ExpenseCategory): AppResult<ExpenseCategory?> = withContext(Dispatchers.IO) {
        return@withContext try {
            val values = ContentValues().apply {
                put("name", category.name)
            }

            val rowId = database?.insert("ExpenseCategory", null, values)
                ?: return@withContext AppResult.Error(DATABASE_NOT_AVAILABLE, isControlled = true)

            if (rowId == -1L)
                return@withContext AppResult.Error("Failed to create expense category", isControlled = true)

            AppResult.Success("ExpenseCategory created successfully", category)
        } catch (e: Exception) {
            AppResult.Error(
                e.message ?: "Unexpected error creating category",
                isControlled = false,
                e.stackTrace.joinToString("\n"))
        }
    }

    override suspend fun edit(category: ExpenseCategory): AppResult<ExpenseCategory?> = withContext(Dispatchers.IO) {
        return@withContext try {
            val values = ContentValues().apply {
                put("name", category.name)
                put("sortOrder", category.getOrder())
            }

            val rowsAffected = database?.update(
                "ExpenseCategory",
                values,
                ID_EQUALS,
                arrayOf(category.id.toString())
            ) ?: return@withContext AppResult.Error(DATABASE_NOT_AVAILABLE, isControlled = true)

            if (rowsAffected <= 0)
                return@withContext AppResult.Error("Failed to update category", isControlled = true)

            AppResult.Success("ExpenseCategory modified successfully", category)
        } catch (e: Exception) {
            AppResult.Error(
                e.message ?: "Unexpected error updating category",
                isControlled = false,
                e.stackTrace.joinToString("\n"))
        }
    }

    override suspend fun deleteById(catId: Long): AppResult<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            val rowsDeleted = database?.delete(
                "ExpenseCategory",
                ID_EQUALS,
                arrayOf(catId.toString())
            ) ?: return@withContext AppResult.Error(DATABASE_NOT_AVAILABLE, isControlled = true)

            if (rowsDeleted <= 0)
                return@withContext AppResult.Error("Failed to delete category", isControlled = true)

            AppResult.Success("ExpenseCategory deleted successfully", Unit)
        } catch (e: Exception) {
            AppResult.Error(
                e.message ?: "Unexpected error deleting category",
                isControlled = false,
                e.stackTrace.joinToString("\n"))
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @SuppressLint("Range")
    private fun mapCursorToExpenseCategory(cursor: Cursor): ExpenseCategory {
        try{
            return ExpenseCategory(cursor.getLong(cursor.getColumnIndex("id"))).apply {
                setName(cursor.getString(cursor.getColumnIndex("name")))
                setOrder(cursor.getIntOrNull(cursor.getColumnIndex("sortOrder")))
                if (cursor.getColumnIndex("total") >= 0) {
                    setTotal(cursor.getDouble(cursor.getColumnIndex("total")))
                }
            }
        }catch (e: Exception) {
            throw e
        }

    }
}