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
    private val legacy = LegacyUpgrade(application)
    private val mutex = Mutex()
    private var access: String? = null
    private var refresh: String? = null
    private var expiry = 0L
    private var locked = true
    var account: String? = null; private set
    var store: FinanceStore? = null; private set
    val changes = MutableStateFlow(0)
    val status = MutableStateFlow("")
    val savedAccount get() = prefs.getString("account", null)
    val savedName get() = prefs.getString("username", "") ?: ""
    val wrappedRefresh get() = prefs.getString("refresh", null)
    val unlocked get() = !locked && store != null

    init {
        viewModelScope.launch(Dispatchers.IO) { runCatching { legacy.scrubPlainSession() } }
        viewModelScope.launch {
            while (true) { delay(30000); sync() }
        }
    }

    suspend fun login(username: String, password: String, remember: Boolean) {
        val result = api.call("login", JSONObject().put("username", username).put("password", password))
        val id = result.getString("user_id")
        prefs.edit().putString("username", username).apply()
        mutex.withLock {
            // Password reauthentication replaces enrollment-invalidated wrapping keys.
            savedAccount?.let { DeviceVault(getApplication(), it).forgetLogin() }
            DeviceVault(getApplication(), id).forgetLogin()
            openAccount(id); acceptTokens(result, remember)
        }
        withContext(Dispatchers.IO) { legacy.retireCredentials() }
        sync()
    }

    suspend fun register(username: String, password: String) {
        api.call("register", JSONObject().put("username", username).put("password", password))
    }

    fun legacyCredentials(): LegacyUpgrade.Credentials? = legacy.credentials()

    suspend fun legacySnapshot(): LegacyUpgrade.Snapshot? = withContext(Dispatchers.IO) {
        if (unlocked) legacy.snapshot() else null
    }

    suspend fun migrateLegacy(snapshot: LegacyUpgrade.Snapshot) {
        mutex.withLock {
            check(unlocked)
            val destination = "${win.downops.wallettracker.BuildConfig.API_BASE_URL}:${account!!}"
            withContext(Dispatchers.IO) { legacy.migrate(snapshot, destination, store!!) }
        }
        changed()
    }

    private fun openAccount(id: String) {
        store?.close()
        account = id
        // Bind cached data to both the server environment and authenticated user ID.
        store = FinanceStore(getApplication(), "${win.downops.wallettracker.BuildConfig.API_BASE_URL}:$id")
        locked = false
        changes.value++
    }

    suspend fun unlock(id: String, rawRefresh: String) {
        mutex.withLock { openAccount(id); refresh = rawRefresh; access = null }
        status.value = "Offline"
        sync()
    }

    suspend fun localOnly() {
        mutex.withLock { openAccount("local"); refresh = null; access = null }
        status.value = "On this device"
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
    fun retrySync() { viewModelScope.launch {
        mutex.withLock { if (unlocked) store?.retryItemizedBills() }
        sync()
    } }

    suspend fun sync() = mutex.withLock {
        val local = store ?: return@withLock
        if (!unlocked) return@withLock
        if (account == "local" || refresh == null) return@withLock
        try {
            status.value = "Syncing…"
            if (access == null || System.currentTimeMillis() >= expiry) {
                acceptTokens(api.call("refresh", JSONObject().put("refresh_token", refresh)))
            }
            if (!unlocked) return@withLock
            withContext(Dispatchers.IO) {
                // Send first so our own acknowledged writes are not reported as conflicts.
                var operations = local.pending()
                while (operations.length() > 0 && unlocked) {
                    val result = api.call("sync", JSONObject().put("operations", operations), access)
                    local.acknowledge(operations, result.getJSONArray("results"))
                    operations = local.pending()
                }
                if (!unlocked) return@withContext
                do {
                    val page = api.call("sync?cursor=${local.cursor()}&limit=100", token=access)
                    local.applyPage(page)
                } while (page.getBoolean("has_more") && unlocked)
            }
            if (!unlocked) return@withLock
            val summary = withContext(Dispatchers.IO) {
                val conflicts = local.rows(true).count { it.optBoolean("has_conflict") }
                if (local.blockedItemizedBills() > 0) "Itemized bills need a server update; saved on this phone"
                else if (conflicts > 0) "Review ($conflicts)" else if (local.pendingCount() > 0) "Pending" else "Synced"
            }
            if (!unlocked) return@withLock
            status.value = summary
            changes.value++
        } catch (error: IOException) {
            if (!unlocked) return@withLock
            if (error is ItemizedBillSyncUnavailable) withContext(Dispatchers.IO) { local.retainIncompatibleBill(error) }
            status.value = if (error is ItemizedBillSyncUnavailable) error.message.orEmpty() else if (error is ApiFailure) {
                if (error.status == 401) "Sign in again" else error.message ?: "Sync failed"
            } else "Offline"
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

    fun lock() {
        locked = true
        changes.value++
        viewModelScope.launch { mutex.withLock { clearMemory() } }
    }

    private fun clearMemory() {
        locked = true
        access = null; refresh = null; expiry = 0
        store?.close(); store = null; account = null
        changes.value++
    }
}
