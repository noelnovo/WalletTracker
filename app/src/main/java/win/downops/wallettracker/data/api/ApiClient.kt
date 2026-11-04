package win.downops.wallettracker.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import win.downops.wallettracker.BuildConfig
import win.downops.wallettracker.data.api.expense.ExpenseEndpoints
import win.downops.wallettracker.data.api.expenseCategory.ExpenseCategoryEndpoints
import win.downops.wallettracker.data.api.importe.ImporteEndpoints
import win.downops.wallettracker.data.api.login.LoginEndpoints
import win.downops.wallettracker.data.api.season.SeasonEndpoints
import okhttp3.ConnectionPool
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import win.downops.wallettracker.util.Logger
import java.util.concurrent.TimeUnit

object ApiClient {
    private val BASE_URL = ApiUrls.baseUrl(BuildConfig.API_BASE_URL)

    private val okHttpClient by lazy {

        OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .build()
    }

    private val retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    suspend fun isServerReachable(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(ApiUrls.readinessUrl(BASE_URL, BuildConfig.API_VERSION))
                    .head()
                    .build()
                okHttpClient.newCall(request).execute().use { it.isSuccessful }
            } catch (e: Exception) {
                Logger.log("Server check failed: ${e::class.simpleName} - ${e.message}")
                false
            }
        }
    }

    val login: LoginEndpoints by lazy { retrofit.create(LoginEndpoints::class.java) }
    val expenseCategory: ExpenseCategoryEndpoints by lazy { retrofit.create(ExpenseCategoryEndpoints::class.java) }
    val expense: ExpenseEndpoints by lazy { retrofit.create(ExpenseEndpoints::class.java) }
    val season: SeasonEndpoints by lazy { retrofit.create(SeasonEndpoints::class.java) }
    val importe: ImporteEndpoints by lazy { retrofit.create(ImporteEndpoints::class.java) }
}
