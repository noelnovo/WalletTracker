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

@RequiresApi(Build.VERSION_CODES.O)
class ExpenseHttpService @Inject constructor(
    sessionRepository: SessionRepository
) : BaseHttpService(sessionRepository),
    ExpenseRepository{
    private var service: ExpenseEndpoints? = null
    private val endpoint: ExpenseEndpoints get() = service ?: ApiClient.expense
    internal constructor(sessionRepository: SessionRepository, endpoint: ExpenseEndpoints) : this(sessionRepository) {
        service = endpoint
    }

    override suspend fun getById(expenseId: Long): AppResult<Expense?> {
        return safeCipheredApiCall(
            call = { endpoint.getById("Bearer ${getToken()}", getCipheredText(), encryptData(ExpenseIdRequest(expenseId)) ?: throw Exception(authenticationErrorMessage)) },
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
            call = { endpoint.getBySeasonId("Bearer ${getToken()}", getCipheredText(), encryptData(ExpenseBySeasonIdRequest(seasonId)) ?: throw Exception(authenticationErrorMessage)) },
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
            call = { endpoint.getByCatId("Bearer ${getToken()}", getCipheredText(), encryptData(ExpenseByCategoryIdRequest(catId)) ?: throw Exception(authenticationErrorMessage)) },
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
            call = { endpoint.create("Bearer ${getToken()}", getCipheredText(), encryptData(CreateExpenseRequest(expense.getPrice(), expense.getDateString(), expense.getCategoryId(), expense.description)) ?: throw Exception(authenticationErrorMessage)) },
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
            call = { endpoint.edit("Bearer ${getToken()}", getCipheredText(), encryptData(EditExpenseRequest(expense.id, expense.getPrice(), expense.getDateString(), expense.getCategoryId(), expense.description)) ?: throw Exception(authenticationErrorMessage)) },
            parser = { json ->
                val parsed = GsonBuilder().setDateFormat(DATE_FORMAT).create()
                    .fromJson(json, object : com.google.gson.reflect.TypeToken<BaseResponse<Expense?>>() {}.type) as BaseResponse<Expense?>
                if (parsed.success) AppResult.Success(parsed.message, parsed.data)
                else AppResult.Error(parsed.message)
            }
        )
    }

    override suspend fun deleteById(expenseId: Long): AppResult<Unit> {
        return safeApiCall { token ->
            endpoint.deleteById("Bearer $token", getCipheredText(), encryptData(ExpenseIdRequest(expenseId)) ?: throw Exception(authenticationErrorMessage))
        }.let { result ->
            when (result) {
                is AppResult.Success -> AppResult.Success(result.message, Unit)
                is AppResult.Error -> result
            }
        }
    }

    override suspend fun deleteAll(): AppResult<Unit> {
        return safeApiCall { token -> endpoint.deleteAll("Bearer $token", getCipheredText()) }.let { result ->
            when (result) {
                is AppResult.Success -> AppResult.Success(result.message, Unit)
                is AppResult.Error -> result
            }
        }
    }
}
