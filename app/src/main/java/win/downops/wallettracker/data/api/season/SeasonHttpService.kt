package win.downops.wallettracker.data.api.season

import android.os.Build
import androidx.annotation.RequiresApi
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import jakarta.inject.Inject
import win.downops.wallettracker.data.SeasonRepository
import win.downops.wallettracker.data.SessionRepository
import win.downops.wallettracker.data.api.ApiClient
import win.downops.wallettracker.data.api.BaseHttpService
import win.downops.wallettracker.data.api.communication.requests.GetOrCreateSeasonRequest
import win.downops.wallettracker.data.api.communication.requests.SeasonIdRequest
import win.downops.wallettracker.data.api.communication.responses.BaseResponse
import win.downops.wallettracker.data.models.AppResult
import win.downops.wallettracker.data.models.Season
import win.downops.wallettracker.util.Messages.authenticationErrorMessage

@RequiresApi(Build.VERSION_CODES.O)
class SeasonHttpService @Inject constructor(
    sessionRepository: SessionRepository
) : BaseHttpService(sessionRepository), SeasonRepository {
    private var service: SeasonEndpoints? = null
    private val endpoint: SeasonEndpoints get() = service ?: ApiClient.season
    internal constructor(sessionRepository: SessionRepository, endpoint: SeasonEndpoints) : this(sessionRepository) {
        service = endpoint
    }

    override suspend fun getAll(): AppResult<List<Season>> {
        return safeCipheredApiCall(
            call = { token -> endpoint.getAll("Bearer $token", getCipheredText()) },
            parser = { json ->
                val parsed = GsonBuilder().create().fromJson<BaseResponse<List<Season>>>(json, object : TypeToken<BaseResponse<List<Season>>>() {}.type)
                if (parsed.success) AppResult.Success(parsed.message, parsed.data ?: emptyList())
                else AppResult.Error(parsed.message)
            }
        )
    }

    override suspend fun getById(seasonId: Long): AppResult<Season?> {
        return safeCipheredApiCall(
            call = { token -> endpoint.getById("Bearer $token", getCipheredText(), encryptData(SeasonIdRequest(seasonId)) ?: throw Exception(authenticationErrorMessage)) },
            parser = { json ->
                val parsed = GsonBuilder().create().fromJson<BaseResponse<Season?>>(json, object : TypeToken<BaseResponse<Season?>>() {}.type)
                if (parsed.success) AppResult.Success(parsed.message, parsed.data)
                else AppResult.Error(parsed.message)
            }
        )
    }

    override suspend fun getByYearMonth(year: Int, month: Int): AppResult<Season?> {
        return safeCipheredApiCall(
            call = { token -> endpoint.getOrCreate("Bearer $token", getCipheredText(), encryptData(GetOrCreateSeasonRequest(year, month)) ?: throw Exception(authenticationErrorMessage)) },
            parser = { json ->
                val parsed = GsonBuilder().create().fromJson<BaseResponse<Season?>>(json, object : TypeToken<BaseResponse<Season?>>() {}.type)
                if (parsed.success) AppResult.Success(parsed.message, parsed.data)
                else AppResult.Error(parsed.message)
            }
        )
    }

    override suspend fun getOrCreate(year: Int, month: Int): AppResult<Season?> {
        return getByYearMonth(year, month)
    }

    override suspend fun deleteById(seasonId: Long): AppResult<Unit> {
        return safeApiCall { token ->
            endpoint.deleteById("Bearer $token", getCipheredText(), encryptData(SeasonIdRequest(seasonId)) ?: throw Exception(authenticationErrorMessage))
        }.let { result ->
            when (result) {
                is AppResult.Success -> AppResult.Success(result.message, Unit)
                is AppResult.Error -> result
            }
        }
    }
}
