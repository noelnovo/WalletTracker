package win.downops.wallettracker.data.api.importe

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import win.downops.wallettracker.BuildConfig
import win.downops.wallettracker.data.api.communication.requests.CipheredRequest
import win.downops.wallettracker.data.api.communication.responses.BaseResponse
import win.downops.wallettracker.data.api.communication.responses.CipheredResponse

interface ImporteEndpoints {

    @POST("/api/v${BuildConfig.API_VERSION}/Importe/season")
    suspend fun getBySeasonId(
        @Header("Authorization") token: String,
        @Header("Signature") cipher: String,
        @Body body: CipheredRequest
    ): Response<BaseResponse<CipheredResponse>>

    @POST("/api/v${BuildConfig.API_VERSION}/Importe/id")
    suspend fun getById(
        @Header("Authorization") token: String,
        @Header("Signature") cipher: String,
        @Body body: CipheredRequest
    ): Response<BaseResponse<CipheredResponse>>

    @POST("/api/v${BuildConfig.API_VERSION}/Importe/")
    suspend fun create(
        @Header("Authorization") token: String,
        @Header("Signature") cipher: String,
        @Body body: CipheredRequest
    ): Response<BaseResponse<CipheredResponse>>

    @POST("/api/v${BuildConfig.API_VERSION}/Importe/delete")
    suspend fun deleteById(
        @Header("Authorization") token: String,
        @Header("Signature") cipher: String,
        @Body body: CipheredRequest
    ): Response<BaseResponse<Unit>>

    @POST("/api/v${BuildConfig.API_VERSION}/Importe/season/delete")
    suspend fun deleteBySeasonId(
        @Header("Authorization") token: String,
        @Header("Signature") cipher: String,
        @Body body: CipheredRequest
    ): Response<BaseResponse<Unit>>
}
