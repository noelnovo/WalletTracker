package win.downops.wallettracker.data.models

import org.junit.Assert.*
import org.junit.Test
import java.sql.Date

class ExpenseTest {
    private val day = Date.valueOf("2026-11-10")

    @Test fun fullConstructorPopulatesEveryField() {
        val expense = Expense(7L, 12.5, day, 3L, "Lunch")
        assertEquals(7L, expense.id)
        assertEquals(12.5, expense.getPrice(), 0.0001)
        assertEquals("Lunch", expense.description)
        assertEquals(3L, expense.getCategoryId())
        assertEquals("2026-11-10", expense.getDateString())
        assertEquals(day, expense.getDate())
    }

    @Test fun settersUpdateTheirFields() {
        val expense = Expense(9.0, day, 1L, "Coffee")
        expense.setDescription("Espresso")
        expense.setPrice(2.5)
        expense.setDate(Date.valueOf("2026-11-09"))
        expense.setCategoryId(5L)
        expense.setSeasonId(2L)
        assertEquals("Espresso", expense.description)
        assertEquals(2.5, expense.getPrice(), 0.0001)
        assertEquals("2026-11-09", expense.getDateString())
        assertEquals(5L, expense.getCategoryId())
        assertEquals(2L, expense.seasonId)
    }

    @Test fun idOnlyConstructorSetsIdentity() {
        assertEquals(42L, Expense(42L).id)
    }

    @Test fun defaultConstructorHasEmptyDescription() {
        assertEquals("", Expense().description)
    }
}

class ImporteTest {
    private val day = Date.valueOf("2026-11-10")

    @Test fun fullConstructorPopulatesEveryField() {
        val importe = Importe(3L, "Salary", day, 100.0, 250.0, 4L)
        assertEquals(3L, importe.id)
        assertEquals("Salary", importe.concept)
        assertEquals(100.0, importe.amount, 0.0001)
        assertEquals(250.0, importe.balanceAfter, 0.0001)
        assertEquals(4L, importe.seasonId)
        assertEquals(day, importe.getDate())
        assertEquals("2026-11-10", importe.getDateString())
    }

    @Test fun settersUpdateTheirFields() {
        val importe = Importe("Rent", day, -500.0, 100.0, 1L)
        importe.setConcept("Rent November")
        importe.setAmount(-450.0)
        importe.setBalanceAfter(50.0)
        importe.setSeasonId(2L)
        importe.setDate(Date.valueOf("2026-11-01"))
        assertEquals("Rent November", importe.concept)
        assertEquals(-450.0, importe.amount, 0.0001)
        assertEquals(50.0, importe.balanceAfter, 0.0001)
        assertEquals(2L, importe.seasonId)
        assertEquals("2026-11-01", importe.getDateString())
    }

    @Test fun idOnlyConstructorSetsIdentity() {
        assertEquals(11L, Importe(11L).id)
    }
}

class SeasonTest {
    @Test fun fullConstructorPopulatesFields() {
        val season = Season(6L, 2026, 11)
        assertEquals(6L, season.id)
        assertEquals(2026, season.year)
        assertEquals(11, season.month)
    }

    @Test fun settersUpdateYearAndMonth() {
        val season = Season(2025, 1)
        season.setYear(2026)
        season.setMonth(12)
        assertEquals(2026, season.year)
        assertEquals(12, season.month)
    }
}

class ExpenseCategoryTest {
    @Test fun nameAndTotalRoundTrip() {
        val category = ExpenseCategory("Food")
        category.setTotal(33.25)
        assertEquals("Food", category.name)
        assertEquals(33.25, category.total, 0.0001)
    }

    @Test fun idConstructorStoresIdentity() {
        val category = ExpenseCategory(9L)
        category.setName("Transport")
        assertEquals(9L, category.id)
        assertEquals("Transport", category.name)
    }
}

class AppResultTest {
    @Test fun successCarriesMessageAndData() {
        val result = AppResult.Success<String>("Loaded", "payload")
        assertEquals("Loaded", result.message)
        assertEquals("payload", result.data)
    }

    @Test fun errorDefaultsToControlledWithoutTrace() {
        val result = AppResult.Error("Rejected")
        assertEquals("Rejected", result.message)
        assertTrue(result.isControlled)
        assertNull(result.stackTrace)
        assertNull(result.code)
    }

    @Test fun errorCanCarryDiagnostics() {
        val result = AppResult.Error("Broken", isControlled = false, stackTrace = "trace", code = 500)
        assertFalse(result.isControlled)
        assertEquals("trace", result.stackTrace)
        assertEquals(500, result.code)
    }
}

class CipheredCredentialsTest {
    @Test fun holdsBothParts() {
        val credentials = CipheredCredentials("cipher", "iv")
        assertEquals("cipher", credentials.credentials)
        assertEquals("iv", credentials.iv)
    }
}

class SessionTest {
    @Test fun sessionFieldsRoundTrip() {
        val session = Session()
        session.id = 1
        session.token = "token-value"
        session.username = "noel"
        session.privateKey = "pk"
        session.serverPublicKey = "spk"
        session.cipheredCredentials = "blob"
        session.iv = "iv"
        session.fingerPrint = true
        session.online = true
        assertEquals(1, session.id)
        assertEquals("token-value", session.token)
        assertEquals("noel", session.username)
        assertEquals("pk", session.privateKey)
        assertEquals("spk", session.serverPublicKey)
        assertEquals("blob", session.cipheredCredentials)
        assertEquals("iv", session.iv)
        assertTrue(session.fingerPrint)
        assertTrue(session.online)
    }
}
