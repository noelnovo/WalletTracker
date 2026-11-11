package win.downops.wallettracker.data.api.importe

import android.os.Build
import androidx.annotation.RequiresApi
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import jakarta.inject.Inject
import win.downops.wallettracker.data.ImporteRepository
import win.downops.wallettracker.data.SessionRepository
import win.downops.wallettracker.data.api.ApiClient
import win.downops.wallettracker.data.api.BaseHttpService
import win.downops.wallettracker.data.api.communication.requests.CreateImporteRequest
import win.downops.wallettracker.data.api.communication.requests.ImporteBySeasonIdRequest
import win.downops.wallettracker.data.api.communication.requests.ImporteIdRequest
import win.downops.wallettracker.data.api.communication.responses.BaseResponse
import win.downops.wallettracker.data.models.AppResult
import win.downops.wallettracker.data.models.Importe
import win.downops.wallettracker.util.Messages.authenticationErrorMessage

private const val DATE_FORMAT = "yyyy-MM-dd"

@RequiresApi(Build.VERSION_CODES.O)
class ImporteHttpService @Inject constructor(
    sessionRepository: SessionRepository
) : BaseHttpService(sessionRepository), ImporteRepository {
    private var service: ImporteEndpoints? = null
    private val endpoint: ImporteEndpoints get() = service ?: ApiClient.importe
    internal constructor(sessionRepository: SessionRepository, endpoint: ImporteEndpoints) : this(sessionRepository) {
        service = endpoint
    }

    override suspend fun getBySeasonId(seasonId: Long): AppResult<List<Importe>> {
        return safeCipheredApiCall(
            call = { token -> endpoint.getBySeasonId("Bearer $token", getCipheredText(), encryptData(ImporteBySeasonIdRequest(seasonId)) ?: throw Exception(authenticationErrorMessage)) },
            parser = { json ->
                val parsed = GsonBuilder().setDateFormat(DATE_FORMAT).create()
                    .fromJson<BaseResponse<List<Importe>>>(json, object : TypeToken<BaseResponse<List<Importe>>>() {}.type)
                if (parsed.success) AppResult.Success(parsed.message, parsed.data ?: emptyList())
                else AppResult.Error(parsed.message)
            }
        )
    }

    override suspend fun getById(importeId: Long): AppResult<Importe?> {
        return safeCipheredApiCall(
            call = { token -> endpoint.getById("Bearer $token", getCipheredText(), encryptData(ImporteIdRequest(importeId)) ?: throw Exception(authenticationErrorMessage)) },
            parser = { json ->
                val parsed = GsonBuilder().setDateFormat(DATE_FORMAT).create()
                    .fromJson<BaseResponse<Importe?>>(json, object : TypeToken<BaseResponse<Importe?>>() {}.type)
                if (parsed.success) AppResult.Success(parsed.message, parsed.data)
                else AppResult.Error(parsed.message)
            }
        )
    }

    override suspend fun create(importe: Importe): AppResult<Importe?> {
        return safeCipheredApiCall(
            call = { token -> endpoint.create("Bearer $token", getCipheredText(), encryptData(CreateImporteRequest(importe.concept, importe.getDateString(), importe.amount, importe.balanceAfter, importe.seasonId)) ?: throw Exception(authenticationErrorMessage)) },
            parser = { json ->
                val parsed = GsonBuilder().setDateFormat(DATE_FORMAT).create()
                    .fromJson<BaseResponse<Importe?>>(json, object : TypeToken<BaseResponse<Importe?>>() {}.type)
                if (parsed.success) AppResult.Success(parsed.message, parsed.data)
                else AppResult.Error(parsed.message)
            }
        )
    }

    override suspend fun deleteById(importeId: Long): AppResult<Unit> {
        return safeApiCall { token ->
            endpoint.deleteById("Bearer $token", getCipheredText(), encryptData(ImporteIdRequest(importeId)) ?: throw Exception(authenticationErrorMessage))
        }.let { result ->
            when (result) {
                is AppResult.Success -> AppResult.Success(result.message, Unit)
                is AppResult.Error -> result
            }
        }
    }

    override suspend fun deleteBySeasonId(seasonId: Long): AppResult<Unit> {
        return safeApiCall { token ->
            endpoint.deleteBySeasonId("Bearer $token", getCipheredText(), encryptData(ImporteBySeasonIdRequest(seasonId)) ?: throw Exception(authenticationErrorMessage))
        }.let { result ->
            when (result) {
                is AppResult.Success -> AppResult.Success(result.message, Unit)
                is AppResult.Error -> result
            }
        }
    }
}
