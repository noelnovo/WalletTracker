package win.downops.wallettracker.data.api.expense

import android.os.Build
import androidx.annotation.RequiresApi
import win.downops.wallettracker.data.api.ApiClient
import win.downops.wallettracker.data.api.BaseHttpService
import win.downops.wallettracker.data.api.communication.requests.ExpenseIdRequest
import win.downops.wallettracker.data.api.communication.requests.CreateExpenseRequest
import win.downops.wallettracker.data.api.communication.requests.EditExpenseRequest
import win.downops.wallettracker.data.api.communication.requests.ExpenseByCategoryIdRequest
import win.downops.wallettracker.data.api.communication.requests.ExpenseBySeasonIdRequest
import win.downops.wallettracker.util.Messages.authenticationErrorMessage
import com.google.gson.GsonBuilder
import jakarta.inject.Inject
import win.downops.wallettracker.data.ExpenseRepository
import win.downops.wallettracker.data.SessionRepository
import win.downops.wallettracker.data.models.AppResult
import win.downops.wallettracker.data.models.Expense
import win.downops.wallettracker.data.api.communication.responses.BaseResponse

private const val DATE_FORMAT = "yyyy-MM-dd"
private const val NO_DATA = "No data"

@RequiresApi(Build.VERSION_CODES.O)
class ExpenseHttpService @Inject constructor(
    sessionRepository: SessionRepository
) : BaseHttpService(sessionRepository),
    ExpenseRepository{

    override suspend fun getById(expenseId: Long): AppResult<Expense?> {
        return safeCipheredApiCall(
            call = { ApiClient.expense.getById("Bearer ${getToken()}", getCipheredText(), encryptData(ExpenseIdRequest(expenseId)) ?: throw Exception(authenticationErrorMessage)) },
            parser = { json ->
                val parsed = GsonBuilder().setDateFormat(DATE_FORMAT).create()
                    .fromJson(json, object : com.google.gson.reflect.TypeToken<BaseResponse<Expense?>>() {}.type) as BaseResponse<Expense?>
                if (parsed.success) AppResult.Success(parsed.message, parsed.data)
                else AppResult.Error(parsed.message)
            }
        )
    }

    override suspend fun getBySeasonId(seasonId: Long): AppResult<List<Expense>> {
        return safeCipheredApiCall(
            call = { ApiClient.expense.getBySeasonId("Bearer ${getToken()}", getCipheredText(), encryptData(ExpenseBySeasonIdRequest(seasonId)) ?: throw Exception(authenticationErrorMessage)) },
            parser = { json ->
                val parsed = GsonBuilder().setDateFormat(DATE_FORMAT).create()
                    .fromJson(json, object : com.google.gson.reflect.TypeToken<BaseResponse<List<Expense>>>() {}.type) as BaseResponse<List<Expense>>
                if (parsed.success) AppResult.Success(parsed.message, parsed.data ?: emptyList())
                else AppResult.Error(parsed.message)
            }
        )
    }

    override suspend fun getByCatId(catId: Long): AppResult<List<Expense>> {
        return safeCipheredApiCall(
            call = { ApiClient.expense.getByCatId("Bearer ${getToken()}", getCipheredText(), encryptData(ExpenseByCategoryIdRequest(catId)) ?: throw Exception(authenticationErrorMessage)) },
            parser = { json ->
                val parsed = GsonBuilder().setDateFormat(DATE_FORMAT).create()
                    .fromJson(json, object : com.google.gson.reflect.TypeToken<BaseResponse<List<Expense>>>() {}.type) as BaseResponse<List<Expense>>
                if (parsed.success) AppResult.Success(parsed.message, parsed.data ?: emptyList())
                else AppResult.Error(parsed.message)
            }
        )
    }

    override suspend fun create(expense: Expense): AppResult<Expense?> {
        return safeCipheredApiCall(
            call = { ApiClient.expense.create("Bearer ${getToken()}", getCipheredText(), encryptData(CreateExpenseRequest(expense.getPrice(), expense.getDateString(), expense.getCategoryId(), expense.description)) ?: throw Exception(authenticationErrorMessage)) },
            parser = { json ->
                val parsed = GsonBuilder().setDateFormat(DATE_FORMAT).create()
                    .fromJson(json, object : com.google.gson.reflect.TypeToken<BaseResponse<Expense?>>() {}.type) as BaseResponse<Expense?>
                if (parsed.success) AppResult.Success(parsed.message, parsed.data)
                else AppResult.Error(parsed.message)
            }
        )
    }

    override suspend fun edit(expense: Expense): AppResult<Expense?> {
        return safeCipheredApiCall(
            call = { ApiClient.expense.edit("Bearer ${getToken()}", getCipheredText(), encryptData(EditExpenseRequest(expense.id, expense.getPrice(), expense.getDateString(), expense.getCategoryId(), expense.description)) ?: throw Exception(authenticationErrorMessage)) },
            parser = { json ->
                val parsed = GsonBuilder().setDateFormat(DATE_FORMAT).create()
                    .fromJson(json, object : com.google.gson.reflect.TypeToken<BaseResponse<Expense?>>() {}.type) as BaseResponse<Expense?>
                if (parsed.success) AppResult.Success(parsed.message, parsed.data)
                else AppResult.Error(parsed.message)
            }
        )
    }

    override suspend fun deleteById(expenseId: Long): AppResult<Unit> {
        val response = ApiClient.expense.deleteById("Bearer ${getToken()}", getCipheredText(), encryptData(ExpenseIdRequest(expenseId)) ?: throw Exception(authenticationErrorMessage))
        if (response.code() == 401) {
            val refresh = ApiClient.login.refresh("Bearer ${getToken()}")
            if (refresh.isSuccessful && refresh.body()?.success == true) {
                val newToken = refresh.body()?.data?.token
                if (newToken != null) {
                    val currentSession = session
                    currentSession.token = newToken
                    sessionRepository.edit(currentSession)
                    val retryResponse = ApiClient.expense.deleteById("Bearer ${newToken}", getCipheredText(), encryptData(ExpenseIdRequest(expenseId)) ?: throw Exception(authenticationErrorMessage))
                    val body = retryResponse.body() ?: return AppResult.Error(NO_DATA, code = retryResponse.code())
                    return if (body.success) AppResult.Success(body.message, Unit) else AppResult.Error(body.message, code = retryResponse.code())
                }
            }
        }
        val body = response.body() ?: return AppResult.Error(NO_DATA, code = response.code())
        return if (body.success) AppResult.Success(body.message, Unit) else AppResult.Error(body.message, code = response.code())
    }

    override suspend fun deleteAll(): AppResult<Unit> {
        val response = ApiClient.expense.deleteAll("Bearer ${getToken()}", getCipheredText())
        if (response.code() == 401) {
             val refresh = ApiClient.login.refresh("Bearer ${getToken()}")
             if (refresh.isSuccessful && refresh.body()?.success == true) {
                 val newToken = refresh.body()?.data?.token
                 if (newToken != null) {
                     val currentSession = session
                     currentSession.token = newToken
                     sessionRepository.edit(currentSession)
                     val retryResponse = ApiClient.expense.deleteAll("Bearer ${newToken}", getCipheredText())
                     val body = retryResponse.body() ?: return AppResult.Error(NO_DATA, code = retryResponse.code())
                     return if (body.success) AppResult.Success(body.message, Unit) else AppResult.Error(body.message, code = retryResponse.code())
                 }
             }
        }
        val body = response.body() ?: return AppResult.Error(NO_DATA, code = response.code())
        return if (body.success) AppResult.Success(body.message, Unit) else AppResult.Error(body.message, code = response.code())
    }
}
