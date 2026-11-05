package win.downops.wallettracker.v2

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.IOException

class WalletModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("wallet-v2-auth", 0)
    private val api = FinanceApi()
    private val mutex = Mutex()
    private var access: String? = null
    private var refresh: String? = null
    private var expiry = 0L
    var account: String? = null; private set
    var store: FinanceStore? = null; private set
    val changes = MutableStateFlow(0)
    val status = MutableStateFlow("Ready when you are")
    val savedAccount get() = prefs.getString("account", null)
    val savedName get() = prefs.getString("username", "") ?: ""
    val wrappedRefresh get() = prefs.getString("refresh", null)
    val unlocked get() = store != null

    init {
        viewModelScope.launch {
            while (true) { delay(30000); sync() }
        }
    }

    suspend fun login(username: String, password: String, remember: Boolean) {
        val result = api.call("login", JSONObject().put("username", username).put("password", password))
        val id = result.getString("user_id")
        prefs.edit().putString("username", username).apply()
        openAccount(id)
        acceptTokens(result, remember)
        sync()
    }

    suspend fun register(username: String, password: String) {
        api.call("register", JSONObject().put("username", username).put("password", password))
    }

    private fun openAccount(id: String) {
        store?.close()
        account = id
        // Bind cached data to both the server environment and authenticated user ID.
        store = FinanceStore(getApplication(), "${win.downops.wallettracker.BuildConfig.API_BASE_URL}:$id")
        changes.value++
    }

    suspend fun unlock(id: String, rawRefresh: String) {
        openAccount(id)
        refresh = rawRefresh
        access = null
        status.value = "Unlocked · local changes are safe offline"
        sync()
    }

    fun localOnly() {
        openAccount("local")
        refresh = null; access = null
        status.value = "Local notebook · not linked to an account"
    }

    private fun acceptTokens(result: JSONObject, remember: Boolean = wrappedRefresh != null) {
        access = result.getString("access_token")
        refresh = result.getString("refresh_token")
        expiry = System.currentTimeMillis() + result.getLong("expires_in") * 1000 - 30000
        if (remember) {
            val id = account!!
            val wrapped = DeviceVault(getApplication(), id).wrapRefresh(refresh!!)
            // Synchronous persistence before another refresh; an interrupted rotation must fail closed.
            check(prefs.edit().putString("account", id).putString("refresh", wrapped).commit())
        } else prefs.edit().remove("account").remove("refresh").apply()
    }

    fun changed() { changes.value++; viewModelScope.launch { sync() } }
    fun syncSoon(delayMillis: Long = 0) { viewModelScope.launch { delay(delayMillis); sync() } }

    suspend fun sync() = mutex.withLock {
        val local = store ?: return@withLock
        if (account == "local" || refresh == null) return@withLock
        try {
            status.value = "Synchronizing…"
            if (access == null || System.currentTimeMillis() >= expiry) {
                acceptTokens(api.call("refresh", JSONObject().put("refresh_token", refresh)))
            }
            withContext(Dispatchers.IO) {
                // Send first so our own acknowledged writes are not reported as conflicts.
                var operations = local.pending()
                while (operations.length() > 0) {
                    val result = api.call("sync", JSONObject().put("operations", operations), access)
                    local.acknowledge(operations, result.getJSONArray("results"))
                    operations = local.pending()
                }
                do {
                    val page = api.call("sync?cursor=${local.cursor()}&limit=100", token=access)
                    local.applyPage(page)
                } while (page.getBoolean("has_more"))
            }
            val conflicts = local.rows(true).count { it.optBoolean("has_conflict") }
            status.value = if (conflicts > 0) "$conflicts conflicts need your decision" else if (local.pendingCount() > 0) "Changes queued · undo is still available" else "Everything is up to date"
            changes.value++
        } catch (error: IOException) {
            status.value = if (error is ApiFailure) {
                if (error.status == 401) "Session expired · sign in again; local changes are retained" else error.message ?: "Sync failed"
            } else "Offline · changes saved on this device"
            changes.value++
        }
    }

    suspend fun logout() {
        mutex.withLock {
            try {
                if (refresh != null) {
                    if (access == null || System.currentTimeMillis() >= expiry) acceptTokens(api.call("refresh", JSONObject().put("refresh_token", refresh)))
                    api.call("logout", JSONObject(), access)
                }
            } finally { forget() }
        }
    }

    private fun forget() {
        savedAccount?.let { DeviceVault(getApplication(), it).forgetLogin() }
        prefs.edit().remove("account").remove("refresh").apply()
        clearMemory()
    }

    fun lock() { viewModelScope.launch { mutex.withLock { clearMemory() } } }

    private fun clearMemory() {
        access = null; refresh = null; expiry = 0
        store?.close(); store = null; account = null
        changes.value++
    }
}
