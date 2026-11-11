package win.downops.wallettracker.data.sqlite

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.sql.Date
import java.sql.SQLException
import java.util.IdentityHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Answers
import org.mockito.Mockito
import org.mockito.stubbing.Answer
import win.downops.wallettracker.data.models.*
import win.downops.wallettracker.data.sqlite.expense.ExpenseSqlService
import win.downops.wallettracker.data.sqlite.expenseCategory.ExpenseCategorySqlService
import win.downops.wallettracker.data.sqlite.importe.ImporteSqlService
import win.downops.wallettracker.data.sqlite.season.SeasonSqlService
import win.downops.wallettracker.data.sqlite.session.SessionSqlService

/** Real repository code, mocked Android boundary; no phone/database/network access. */
class SqlRepositoryContractTest {
    private class Boundary {
        val values = IdentityHashMap<ContentValues, MutableMap<String, Any?>>()
        val calls = mutableListOf<Pair<String, List<Any?>>>()
        var empty = false
        var fail = false
        var invalidDate = false
        var affected = 1
        var inserted = 99L
        var closedCursors = 0
        var lastValues: Map<String, Any?> = emptyMap()
        val row = linkedMapOf<String, Any?>(
            "id" to 7L, "price" to 12.5, "expenseDate" to "2026-01-02", "description" to "Synthetic expense",
            "category" to 3L, "seasonId" to 7L, "name" to "Synthetic category", "sortOrder" to 2, "total" to 12.5,
            "concept" to "Synthetic income", "importeDate" to "2026-01-02", "amount" to 25.0, "balanceAfter" to 37.5,
            "year" to 2026, "month" to 1, "token" to "synthetic-session", "username" to "synthetic-user",
            "privateKey" to "synthetic-not-a-key", "serverPublicKey" to "synthetic-not-a-key",
            "cipheredCredentials" to "synthetic-data", "iv" to "synthetic-data", "fingerPrint" to 1, "online" to 1
        )
        val database: SQLiteDatabase = Mockito.mock(SQLiteDatabase::class.java, Answer<Any?> { invocation ->
            calls.add(invocation.method.name to invocation.arguments.toList())
            if (fail) throw SQLException("Synthetic storage failure")
            when (invocation.method.name) {
                "query", "rawQuery" -> cursor()
                "insert" -> { lastValues = values[invocation.arguments[2] as ContentValues]?.toMap().orEmpty(); inserted }
                "update" -> { lastValues = values[invocation.arguments[1] as ContentValues]?.toMap().orEmpty(); affected }
                "delete" -> affected
                else -> Answers.RETURNS_DEFAULTS.answer(invocation)
            }
        })

        fun contentValues() = Mockito.mockConstruction(ContentValues::class.java,
            Mockito.withSettings().defaultAnswer(Answer<Any?> { invocation ->
                val data = values.getOrPut(invocation.mock as ContentValues) { mutableMapOf() }
                when (invocation.method.name) {
                    "put" -> { data[invocation.arguments[0] as String] = invocation.arguments[1]; null }
                    "putNull" -> { data[invocation.arguments[0] as String] = null; null }
                    else -> Answers.RETURNS_DEFAULTS.answer(invocation)
                }
            }))

        private fun cursor(): Cursor {
            val columns = row.keys.toList()
            var position = -1
            fun value(index: Int): Any? {
                val name = columns.getOrNull(index) ?: return null
                return if (invalidDate && name in listOf("expenseDate", "importeDate")) "invalid-date" else row[name]
            }
            return Mockito.mock(Cursor::class.java, Answer<Any?> { invocation ->
                when (invocation.method.name) {
                    "moveToFirst" -> { position = 0; !empty }
                    "moveToNext" -> { position++; !empty && position == 0 }
                    "isFirst" -> !empty && position == 0
                    "getColumnIndex", "getColumnIndexOrThrow" -> columns.indexOf(invocation.arguments[0])
                    "isNull" -> value(invocation.arguments[0] as Int) == null
                    "getLong" -> (value(invocation.arguments[0] as Int) as Number).toLong()
                    "getInt" -> (value(invocation.arguments[0] as Int) as Number).toInt()
                    "getDouble" -> (value(invocation.arguments[0] as Int) as Number).toDouble()
                    "getString" -> value(invocation.arguments[0] as Int)?.toString()
                    "close" -> { closedCursors++; null }
                    else -> Answers.RETURNS_DEFAULTS.answer(invocation)
                }
            })
        }
    }

    private fun exercise(block: suspend (Boundary) -> Unit) = runBlocking {
        withContext(Dispatchers.IO) {
            val boundary = Boundary()
            boundary.contentValues().use { block(boundary) }
        }
    }

    private fun expense() = Expense(7L).apply {
        setDate(Date.valueOf("2026-01-02")); setPrice(12.5); setCategoryId(3L); setDescription("Synthetic expense")
    }
    private fun category() = ExpenseCategory(7L).apply { setName("Synthetic category"); setOrder(2) }
    private fun income() = Importe(7L, "Synthetic income", Date.valueOf("2026-01-02"), 25.0, 37.5, 7L)
    private fun successful(result: AppResult<*>) = assertTrue(result.toString(), result is AppResult.Success)
    private fun failed(result: AppResult<*>) = assertTrue(result.toString(), result is AppResult.Error)

    @Test fun expenseCrudPreservesColumnsBindingsAndCursorMapping() = exercise { boundary ->
        val service = ExpenseSqlService(boundary.database)
        successful(service.create(expense()))
        assertEquals(12.5, boundary.lastValues["price"])
        assertEquals("2026-01-02", boundary.lastValues["expenseDate"])
        assertEquals(7L, boundary.lastValues["seasonId"])
        successful(service.edit(expense()))
        val row = (service.getById(7L) as AppResult.Success).data!!
        assertEquals(7L, row.id); assertEquals(3L, row.getCategoryId()); assertEquals(12.5, row.getPrice(), 0.0)
        assertEquals(1, (service.getByCatId(3L) as AppResult.Success).data.size)
        assertEquals(1, (service.getBySeasonId(7L) as AppResult.Success).data.size)
        successful(service.deleteById(7L)); successful(service.deleteAll())
        assertTrue(boundary.closedCursors >= 5)
        assertTrue(boundary.calls.any { it.first == "query" && it.second[2] == "category = ?" })
        service.close()
    }

    @Test fun expenseAbsentRowsFailedWritesAndUnavailableDatabaseAreControlled() = exercise { boundary ->
        val service = ExpenseSqlService(boundary.database)
        boundary.empty = true
        failed(service.getById(7L)); failed(service.getByCatId(3L)); successful(service.getBySeasonId(7L))
        successful(service.create(expense())); assertTrue(boundary.lastValues.containsKey("seasonId"))
        assertNull(boundary.lastValues["seasonId"])
        boundary.inserted = -1L; boundary.affected = 0
        failed(service.create(expense())); failed(service.edit(expense())); failed(service.deleteById(7L)); failed(service.deleteAll())
        val unavailable = ExpenseSqlService(null as SQLiteDatabase?)
        failed(unavailable.create(expense())); failed(unavailable.edit(expense())); failed(unavailable.getById(7L))
        failed(unavailable.getByCatId(3L)); failed(unavailable.getBySeasonId(7L)); failed(unavailable.deleteById(7L)); failed(unavailable.deleteAll())
        unavailable.open(); unavailable.close()
    }

    @Test fun expenseExceptionsAndMalformedRowsRetainFailureResults() = exercise { boundary ->
        val service = ExpenseSqlService(boundary.database)
        boundary.fail = true
        failed(service.create(expense())); failed(service.edit(expense())); failed(service.deleteById(7L)); failed(service.deleteAll())
        failed(service.getById(7L)); failed(service.getByCatId(3L)); failed(service.getBySeasonId(7L))
        boundary.fail = false; boundary.invalidDate = true
        failed(service.getById(7L))
        boundary.invalidDate = false; boundary.row.remove("seasonId")
        successful(service.getById(7L))
    }

    @Test fun categoryCrudMapsTotalsAndOrdering() = exercise { boundary ->
        val service = ExpenseCategorySqlService(boundary.database)
        successful(service.create(category())); assertEquals("Synthetic category", boundary.lastValues["name"])
        successful(service.edit(category())); assertEquals(2, boundary.lastValues["sortOrder"])
        val found = (service.getById(7L) as AppResult.Success).data!!
        assertEquals("Synthetic category", found.name); assertEquals(2, found.getOrder())
        assertEquals(1, (service.getAll() as AppResult.Success).data.size)
        successful(service.deleteById(7L))
        boundary.row.remove("total"); boundary.row["sortOrder"] = null
        successful(service.getById(7L)); service.close()
    }

    @Test fun categoryEmptyUnavailableAndDatabaseErrorsRemainFailures() = exercise { boundary ->
        val service = ExpenseCategorySqlService(boundary.database)
        boundary.empty = true; failed(service.getById(7L)); successful(service.getAll())
        boundary.inserted = -1L; boundary.affected = 0
        failed(service.create(category())); failed(service.edit(category())); failed(service.deleteById(7L))
        boundary.fail = true
        failed(service.getById(7L)); failed(service.getAll()); failed(service.create(category())); failed(service.edit(category())); failed(service.deleteById(7L))
        val unavailable = ExpenseCategorySqlService(null as SQLiteDatabase?)
        failed(unavailable.getAll()); failed(unavailable.getById(7L)); failed(unavailable.create(category()))
        failed(unavailable.edit(category())); failed(unavailable.deleteById(7L)); unavailable.open(); unavailable.close()
    }

    @Test fun incomeCrudPreservesAmountsAndSeasonBindings() = exercise { boundary ->
        val service = ImporteSqlService(boundary.database)
        successful(service.create(income())); assertEquals(25.0, boundary.lastValues["amount"])
        assertEquals(37.5, boundary.lastValues["balanceAfter"]); assertEquals(7L, boundary.lastValues["seasonId"])
        val found = (service.getById(7L) as AppResult.Success).data!!
        assertEquals(25.0, found.amount, 0.0); assertEquals(37.5, found.balanceAfter, 0.0)
        assertEquals(1, (service.getBySeasonId(7L) as AppResult.Success).data.size)
        successful(service.deleteById(7L)); successful(service.deleteBySeasonId(7L)); service.close()
    }

    @Test fun incomeMissingUnavailableAndMalformedRowsAreHandled() = exercise { boundary ->
        val service = ImporteSqlService(boundary.database)
        boundary.empty = true; failed(service.getById(7L)); successful(service.getBySeasonId(7L))
        boundary.inserted = -1L; boundary.affected = 0
        failed(service.create(income())); failed(service.deleteById(7L)); successful(service.deleteBySeasonId(7L))
        boundary.fail = true
        failed(service.getById(7L)); failed(service.getBySeasonId(7L)); failed(service.create(income()))
        failed(service.deleteById(7L)); failed(service.deleteBySeasonId(7L))
        boundary.fail = false; boundary.empty = false; boundary.invalidDate = true
        failed(service.getById(7L))
        val unavailable = ImporteSqlService(null as SQLiteDatabase?)
        failed(unavailable.getById(7L)); failed(unavailable.getBySeasonId(7L)); failed(unavailable.create(income()))
        failed(unavailable.deleteById(7L)); failed(unavailable.deleteBySeasonId(7L)); unavailable.open(); unavailable.close()
    }

    @Test fun seasonLookupCreateAndDeleteContracts() = exercise { boundary ->
        val service = SeasonSqlService(boundary.database)
        assertEquals(1, (service.getAll() as AppResult.Success).data.size)
        assertEquals(7L, (service.getById(7L) as AppResult.Success).data!!.id)
        successful(service.getByYearMonth(2026, 1)); successful(service.getOrCreate(2026, 1))
        assertFalse(boundary.calls.any { it.first == "insert" })
        boundary.empty = true
        successful(service.getOrCreate(2026, 1)); assertEquals(2026, boundary.lastValues["year"])
        assertEquals(1, boundary.lastValues["month"])
        boundary.inserted = -1L; failed(service.getOrCreate(2026, 1))
        boundary.empty = false; successful(service.deleteById(7L))
        boundary.affected = 0; failed(service.deleteById(7L)); service.close()
    }

    @Test fun seasonDatabaseErrorsMissingAndUnavailableStatesAreExplicit() = exercise { boundary ->
        val service = SeasonSqlService(boundary.database)
        boundary.empty = true; failed(service.getById(7L))
        boundary.fail = true
        failed(service.getAll()); failed(service.getById(7L)); failed(service.getByYearMonth(2026, 1))
        failed(service.getOrCreate(2026, 1)); failed(service.deleteById(7L))
        val unavailable = SeasonSqlService(null as SQLiteDatabase?)
        failed(unavailable.getAll()); failed(unavailable.getById(7L)); failed(unavailable.getByYearMonth(2026, 1))
        failed(unavailable.getOrCreate(2026, 1)); failed(unavailable.deleteById(7L)); unavailable.open(); unavailable.close()
    }

    @Test fun legacySessionRowsPreserveIdentityAndBooleanFlags() = exercise { boundary ->
        val service = SessionSqlService(boundary.database)
        val session = service.getFirstSession()!!
        assertEquals(7, session.id); assertEquals("synthetic-user", session.username)
        assertTrue(session.fingerPrint); assertTrue(session.online)
        assertEquals(99L, service.insert(session)); assertEquals(1, boundary.lastValues["fingerPrint"])
        session.fingerPrint = false; session.online = false
        assertEquals(1, service.edit(session)); assertEquals(0, boundary.lastValues["fingerPrint"])
        assertEquals(0, boundary.lastValues["online"])
        boundary.row["fingerPrint"] = 0; boundary.row["online"] = 0; boundary.row["username"] = null
        val offline = service.getFirstSession()!!
        assertFalse(offline.online); assertFalse(offline.fingerPrint); assertEquals("", offline.username)
        boundary.empty = true; assertNull(service.getFirstSession())
        service.deleteAll(); service.close()
    }
}
