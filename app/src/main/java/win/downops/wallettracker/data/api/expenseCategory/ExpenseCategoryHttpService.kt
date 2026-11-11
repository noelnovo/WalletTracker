package win.downops.wallettracker.data.api.expenseCategory
import win.downops.wallettracker.data.api.BaseHttpService
import android.os.Build
import androidx.annotation.RequiresApi
import win.downops.wallettracker.data.api.ApiClient
import win.downops.wallettracker.data.api.communication.responses.BaseResponse
import win.downops.wallettracker.data.api.communication.requests.ExpenseCategoryIdRequest
import win.downops.wallettracker.data.api.communication.requests.CreateExpenseCategoryRequest
import win.downops.wallettracker.data.api.communication.requests.EditExpenseCategoryRequest
import com.google.gson.GsonBuilder
import jakarta.inject.Inject
import win.downops.wallettracker.data.ExpenseCategoryRepository
import win.downops.wallettracker.data.SessionRepository
import win.downops.wallettracker.data.models.AppResult
import win.downops.wallettracker.data.models.ExpenseCategory

@RequiresApi(Build.VERSION_CODES.O)
class ExpenseCategoryHttpService @Inject constructor(
    sessionRepository: SessionRepository
) : BaseHttpService(sessionRepository),
    ExpenseCategoryRepository {
    private var service: ExpenseCategoryEndpoints? = null
    private val endpoint: ExpenseCategoryEndpoints get() = service ?: ApiClient.expenseCategory
    internal constructor(sessionRepository: SessionRepository, endpoint: ExpenseCategoryEndpoints) : this(sessionRepository) {
        service = endpoint
    }

    override suspend fun getAll(): AppResult<List<ExpenseCategory>> {
        return safeCipheredApiCall(
            call = { endpoint.getExpenseCategories("Bearer ${getToken()}", getCipheredText()) },
            parser = { json ->
                val parsed = GsonBuilder().create()
                    .fromJson(json, object : com.google.gson.reflect.TypeToken<BaseResponse<List<ExpenseCategory>>>() {}.type) as BaseResponse<List<ExpenseCategory>>
                if (parsed.success) AppResult.Success(parsed.message, parsed.data ?: emptyList())
                else AppResult.Error(parsed.message)
            }
        )
    }

    override suspend fun getById(catId: Long): AppResult<ExpenseCategory?> {
        return safeCipheredApiCall(
            call = { endpoint.getExpenseCategoryById("Bearer ${getToken()}", getCipheredText(), encryptData(ExpenseCategoryIdRequest(catId)) ?: throw Exception("Auth error")) },
            parser = { json ->
                val parsed = GsonBuilder().create()
                    .fromJson(json, object : com.google.gson.reflect.TypeToken<BaseResponse<ExpenseCategory?>>() {}.type) as BaseResponse<ExpenseCategory?>
                if (parsed.success) AppResult.Success(parsed.message, parsed.data)
                else AppResult.Error(parsed.message)
            }
        )
    }

    override suspend fun create(category: ExpenseCategory): AppResult<ExpenseCategory?> {
        return safeCipheredApiCall(
            call = { endpoint.createExpenseCategories("Bearer ${getToken()}", getCipheredText(), encryptData(CreateExpenseCategoryRequest(category.name)) ?: throw Exception("Auth error")) },
            parser = { json ->
                val parsed = GsonBuilder().create()
                    .fromJson(json, object : com.google.gson.reflect.TypeToken<BaseResponse<ExpenseCategory?>>() {}.type) as BaseResponse<ExpenseCategory?>
                if (parsed.success) AppResult.Success(parsed.message, parsed.data)
                else AppResult.Error(parsed.message)
            }
        )
    }

    override suspend fun edit(category: ExpenseCategory): AppResult<ExpenseCategory?> {
        return safeCipheredApiCall(
            call = { endpoint.editName("Bearer ${getToken()}", getCipheredText(), encryptData(EditExpenseCategoryRequest(category.id, category.name)) ?: throw Exception("Auth error")) },
            parser = { json ->
                val parsed = GsonBuilder().create()
                    .fromJson(json, object : com.google.gson.reflect.TypeToken<BaseResponse<ExpenseCategory?>>() {}.type) as BaseResponse<ExpenseCategory?>
                if (parsed.success) AppResult.Success(parsed.message, parsed.data)
                else AppResult.Error(parsed.message)
            }
        )
    }

    override suspend fun deleteById(catId: Long): AppResult<Unit> {
        return safeApiCall { token ->
            endpoint.deleteById("Bearer $token", getCipheredText(), encryptData(ExpenseCategoryIdRequest(catId)) ?: throw Exception("Auth error"))
        }.let { result ->
            when (result) {
                is AppResult.Success -> AppResult.Success(result.message, Unit)
                is AppResult.Error -> result
            }
        }
    }
}
