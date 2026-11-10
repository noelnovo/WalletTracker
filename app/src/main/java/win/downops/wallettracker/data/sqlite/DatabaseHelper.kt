package win.downops.wallettracker.data.sqlite

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DatabaseHelper(context: Context?) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_TABLE_SESSION)
        db.execSQL(CREATE_TABLE_EXPENSE_CATEGORY)
        db.execSQL(CREATE_TABLE_SEASON)
        db.execSQL(CREATE_TABLE_EXPENSE)
        db.execSQL(CREATE_TABLE_IMPORTE)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Never destroy a user's unsynchronized finances as an upgrade fallback.
        error("Legacy database migration requires an explicit migration path ($oldVersion -> $newVersion)")
    }
    companion object {
        private const val DATABASE_NAME = "walletTracker.db"
        private const val DATABASE_VERSION = 8
        private const val CREATE_TABLE = "CREATE TABLE "
        private const val TABLE_SESSION = "Session"
        private const val CREATE_TABLE_SESSION = CREATE_TABLE + TABLE_SESSION + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "online Integer, " +
                "token TEXT, " +
                "username TEXT, " +
                "privateKey TEXT, " +
                "cipheredCredentials TEXT, " +
                "iv TEXT, " +
                "fingerPrint INTEGER, " +
                "serverPublicKey TEXT);"
        private const val TABLE_EXPENSE = "Expense"
        private const val TABLE_EXPENSE_CATEGORY = "ExpenseCategory"
        private const val TABLE_SEASON = "Season"
        private const val TABLE_IMPORTE = "Importe"

        private const val CREATE_TABLE_EXPENSE_CATEGORY =
            CREATE_TABLE + TABLE_EXPENSE_CATEGORY + " (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "sortOrder Integer NULL, " +
                    "name TEXT NOT NULL);"
        private const val CREATE_TABLE_SEASON = CREATE_TABLE + TABLE_SEASON + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "year INTEGER NOT NULL, " +
                "month INTEGER NOT NULL, " +
                "UNIQUE(year, month));"
        private const val CREATE_TABLE_EXPENSE = CREATE_TABLE + TABLE_EXPENSE + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "price DOUBLE, " +
                "description TEXT, " +
                "expenseDate DATE, " +
                "category INTEGER, " +
                "seasonId INTEGER NULL, " +
                "FOREIGN KEY(category) REFERENCES " + TABLE_EXPENSE_CATEGORY + "(id) ON DELETE CASCADE, " +
                "FOREIGN KEY(seasonId) REFERENCES " + TABLE_SEASON + "(id) ON DELETE SET NULL);"
        private const val CREATE_TABLE_IMPORTE = CREATE_TABLE + TABLE_IMPORTE + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "concept TEXT, " +
                "importeDate DATE, " +
                "amount DOUBLE, " +
                "balanceAfter DOUBLE, " +
                "iban TEXT, " +
                "seasonId INTEGER NOT NULL, " +
                "FOREIGN KEY(seasonId) REFERENCES " + TABLE_SEASON + "(id) ON DELETE CASCADE);"
    }
}
