package win.downops.wallettracker.v2

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import win.downops.wallettracker.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiFailure(val status: Int, message: String) : IOException(message)

class FinanceApi {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private val base = BuildConfig.API_BASE_URL.trimEnd('/') + "/api/v2/"

    suspend fun call(path: String, data: JSONObject? = null, token: String? = null): JSONObject = withContext(Dispatchers.IO) {
        require(base.startsWith("https://")) { "HTTPS is required" }
        val request = Request.Builder().url(base + path).header("Accept", "application/json")
        token?.let { request.header("Authorization", "Bearer $it") }
        data?.let { request.post(it.toString().toRequestBody("application/json; charset=utf-8".toMediaType())) }
        client.newCall(request.build()).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(raw) }.getOrNull()
            if (!response.isSuccessful) throw ApiFailure(response.code, json?.optString("error")?.takeIf { it.isNotBlank() } ?: "Server returned ${response.code}")
            json ?: throw IOException("Invalid server response")
        }
    }
}
