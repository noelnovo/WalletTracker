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

@RequiresApi(Build.VERSION_CODES.O)
class ImporteHttpService @Inject constructor(
    sessionRepository: SessionRepository
) : BaseHttpService(sessionRepository), ImporteRepository {

    override suspend fun getBySeasonId(seasonId: Long): AppResult<List<Importe>> {
        return safeCipheredApiCall(
            call = { token -> ApiClient.importe.getBySeasonId("Bearer $token", getCipheredText(), encryptData(ImporteBySeasonIdRequest(seasonId)) ?: throw Exception(authenticationErrorMessage)) },
            parser = { json ->
                val parsed = GsonBuilder().setDateFormat("yyyy-MM-dd").create()
                    .fromJson<BaseResponse<List<Importe>>>(json, object : TypeToken<BaseResponse<List<Importe>>>() {}.type)
                if (parsed.success) AppResult.Success(parsed.message, parsed.data ?: emptyList())
                else AppResult.Error(parsed.message)
            }
        )
    }

    override suspend fun getById(importeId: Long): AppResult<Importe?> {
        return safeCipheredApiCall(
            call = { token -> ApiClient.importe.getById("Bearer $token", getCipheredText(), encryptData(ImporteIdRequest(importeId)) ?: throw Exception(authenticationErrorMessage)) },
            parser = { json ->
                val parsed = GsonBuilder().setDateFormat("yyyy-MM-dd").create()
                    .fromJson<BaseResponse<Importe?>>(json, object : TypeToken<BaseResponse<Importe?>>() {}.type)
                if (parsed.success) AppResult.Success(parsed.message, parsed.data)
                else AppResult.Error(parsed.message)
            }
        )
    }

    override suspend fun create(importe: Importe): AppResult<Importe?> {
        return safeCipheredApiCall(
            call = { token -> ApiClient.importe.create("Bearer $token", getCipheredText(), encryptData(CreateImporteRequest(importe.getConcept(), importe.getDateString(), importe.getAmount(), importe.getBalanceAfter(), importe.getSeasonId())) ?: throw Exception(authenticationErrorMessage)) },
            parser = { json ->
                val parsed = GsonBuilder().setDateFormat("yyyy-MM-dd").create()
                    .fromJson<BaseResponse<Importe?>>(json, object : TypeToken<BaseResponse<Importe?>>() {}.type)
                if (parsed.success) AppResult.Success(parsed.message, parsed.data)
                else AppResult.Error(parsed.message)
            }
        )
    }

    override suspend fun deleteById(importeId: Long): AppResult<Unit> {
        return safeApiCall { token ->
            ApiClient.importe.deleteById("Bearer $token", getCipheredText(), encryptData(ImporteIdRequest(importeId)) ?: throw Exception(authenticationErrorMessage))
        }.let { result ->
            when (result) {
                is AppResult.Success -> AppResult.Success(result.message, Unit)
                is AppResult.Error -> result
            }
        }
    }

    override suspend fun deleteBySeasonId(seasonId: Long): AppResult<Unit> {
        return safeApiCall { token ->
            ApiClient.importe.deleteBySeasonId("Bearer $token", getCipheredText(), encryptData(ImporteBySeasonIdRequest(seasonId)) ?: throw Exception(authenticationErrorMessage))
        }.let { result ->
            when (result) {
                is AppResult.Success -> AppResult.Success(result.message, Unit)
                is AppResult.Error -> result
            }
        }
    }
}
