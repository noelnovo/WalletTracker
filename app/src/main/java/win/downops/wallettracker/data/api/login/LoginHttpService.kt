package win.downops.wallettracker.data.api.login

import win.downops.wallettracker.data.api.ApiClient
import win.downops.wallettracker.data.LoginRepository
import win.downops.wallettracker.data.api.communication.requests.LoginRequest
import win.downops.wallettracker.data.api.communication.requests.ServerPubKeyRequest
import win.downops.wallettracker.data.api.communication.responses.LoginResponse
import win.downops.wallettracker.data.api.communication.responses.ServerPubKeyResponse
import win.downops.wallettracker.data.models.AppResult
import javax.inject.Inject

private const val NO_DATA = "No data"

class LoginHttpService @Inject constructor(): LoginRepository {

    override suspend fun register(credentials: LoginRequest): AppResult<Unit> {
        val response = ApiClient.login.register(credentials)
        val body = response.body() ?: return AppResult.Error(NO_DATA, code = response.code())

        return if (body.success) AppResult.Success(body.message, Unit)
        else AppResult.Error(body.message, code = response.code())
    }

    override suspend fun login(credentials: LoginRequest): AppResult<LoginResponse?> {
        val response = ApiClient.login.login(credentials)
        val body = response.body() ?: return AppResult.Error(NO_DATA, code = response.code())

        return if (body.success) AppResult.Success(body.message, body.data)
        else AppResult.Error(body.message, code = response.code())
    }

    override suspend fun refresh(token: String): AppResult<LoginResponse?> {
        val response = ApiClient.login.refresh("Bearer $token")
        val body = response.body() ?: return AppResult.Error(NO_DATA, code = response.code())

        return if (body.success) AppResult.Success(body.message, body.data)
        else AppResult.Error(body.message, code = response.code())
    }

    override suspend fun logout(token: String): AppResult<Unit> {
        val response = ApiClient.login.logout("Bearer $token")
        val body = response.body() ?: return AppResult.Error(NO_DATA, code = response.code())

        return if (body.success) AppResult.Success(body.message, Unit)
        else AppResult.Error(body.message, code = response.code())
    }

    override suspend fun setUserClientPubKey(token: String, request: ServerPubKeyRequest): AppResult<Unit> {
        val response = ApiClient.login.setUserClientPubKey("Bearer $token", request)
        val body = response.body() ?: return AppResult.Error(NO_DATA, code = response.code())

        return if (body.success) AppResult.Success(body.message, Unit)
        else AppResult.Error(body.message, code = response.code())
    }

    override suspend fun getUserServerPubKey(token: String): AppResult<ServerPubKeyResponse?> {
        val response = ApiClient.login.getUserServerPubKey("Bearer $token")
        val body = response.body() ?: return AppResult.Error(NO_DATA, code = response.code())

        return if (body.success) AppResult.Success(body.message, body.data)
        else AppResult.Error(body.message, code = response.code())
    }
}
