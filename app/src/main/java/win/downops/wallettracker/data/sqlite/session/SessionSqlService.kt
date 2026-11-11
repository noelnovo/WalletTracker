package win.downops.wallettracker.data.sqlite.session

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
import win.downops.wallettracker.data.models.Session
import win.downops.wallettracker.data.SessionRepository
import win.downops.wallettracker.util.Logger
import java.io.Closeable
import kotlin.Int
import kotlin.Long
import kotlin.arrayOf


class SessionSqlService private constructor(
    private var database: SQLiteDatabase?,
    private var dbHelper: DatabaseHelper?
) : Closeable, SessionRepository {
    @Inject constructor(@ApplicationContext context: Context?) : this(null, DatabaseHelper(context)) {
        database = dbHelper?.writableDatabase
    }
    internal constructor(database: SQLiteDatabase?) : this(database, null)
    override fun close() {
        dbHelper?.close()
    }

    override fun edit(session: Session): Int {
        val values = ContentValues()
        values.put("token", session.token)
        values.put("username", session.username)
        values.put("privateKey", session.privateKey)
        values.put("serverPublicKey", session.serverPublicKey)
        values.put("cipheredCredentials", session.cipheredCredentials)
        values.put("iv", session.iv)
        values.put("fingerPrint", if (session.fingerPrint) 1 else 0)
        values.put("online", if (session.online) 1 else 0)

        return database!!.update("Session", values, "id = ?", arrayOf(session.id.toString()))
    }


    override fun insert(session: Session): Long {
        val values = ContentValues()
        values.put("token", session.token)
        values.put("username", session.username)
        values.put("privateKey", session.privateKey)
        values.put("serverPublicKey", session.serverPublicKey)
        values.put("cipheredCredentials", session.cipheredCredentials)
        values.put("iv", session.iv)
        values.put("fingerPrint", if (session.fingerPrint) 1 else 0)
        values.put("online", if (session.online) 1 else 0)
        return database!!.insert("Session", null, values)
    }


    override fun deleteAll() {
        database!!.delete("Session", null, null)
    }
    @RequiresApi(Build.VERSION_CODES.O)
    override fun getFirstSession(): Session? {
        var cat: Session? = null
        val cursor = database!!.rawQuery("SELECT * FROM Session LIMIT 1", null)
        cursor.moveToFirst()
        if(cursor.isFirst){
            cat = cursor(cursor)
        }
        cursor.close()
        return cat
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @SuppressLint("Range")
    private fun cursor(cursor: Cursor): Session {
        return Session().apply {
            id = cursor.getInt(cursor.getColumnIndex("id"))
            token = cursor.getString(cursor.getColumnIndex("token"))
            username = cursor.getString(cursor.getColumnIndex("username")) ?: ""
            privateKey = cursor.getString(cursor.getColumnIndex("privateKey"))
            serverPublicKey = cursor.getString(cursor.getColumnIndex("serverPublicKey"))
            cipheredCredentials = cursor.getString(cursor.getColumnIndex("cipheredCredentials"))
            iv = cursor.getString(cursor.getColumnIndex("iv"))
            fingerPrint = cursor.getInt(cursor.getColumnIndex("fingerPrint")) == 1
            online = cursor.getInt(cursor.getColumnIndex("online")) == 1
        }
    }


}
