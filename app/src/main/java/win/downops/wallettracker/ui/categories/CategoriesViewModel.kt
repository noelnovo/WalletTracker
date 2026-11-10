package win.downops.wallettracker.ui.categories

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jakarta.inject.Inject
import kotlinx.coroutines.launch
import win.downops.wallettracker.data.ExpenseCategoryRepository
import win.downops.wallettracker.data.ExpenseRepository
import win.downops.wallettracker.data.SeasonRepository
import win.downops.wallettracker.data.models.AppResult
import win.downops.wallettracker.data.models.ExpenseCategory
import java.util.Calendar

@HiltViewModel
class CategoriesViewModel @Inject constructor(
    private val repo: ExpenseCategoryRepository,
    private val expenseRepo: ExpenseRepository,
    private val seasonRepo: SeasonRepository
) : ViewModel() {

    private val _getCategoriesResult = MutableLiveData<AppResult<List<ExpenseCategory>>>()
    val getCategoriesResult: LiveData<AppResult<List<ExpenseCategory>>> = _getCategoriesResult

    private val _seasonLabel = MutableLiveData<String>()
    val seasonLabel: LiveData<String> = _seasonLabel

    private val monthNames = listOf(
        "Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio",
        "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre"
    )

    fun getCategories() = viewModelScope.launch {
        val categoriesResult = repo.getAll()
        if (categoriesResult !is AppResult.Success) {
            _getCategoriesResult.postValue(categoriesResult)
            return@launch
        }

        val categories = categoriesResult.data.map { cat ->
            ExpenseCategory(cat.id).apply {
                setName(cat.name)
                setOrder(cat.getOrder())
                setTotal(0.0)
            }
        }

        val cal = Calendar.getInstance()
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val monthName = monthNames.getOrElse(month - 1) { month.toString() }
        _seasonLabel.postValue("$monthName $year")

        val seasonResult = seasonRepo.getByYearMonth(year, month)
        if (seasonResult is AppResult.Success && seasonResult.data != null) {
            val expensesResult = expenseRepo.getBySeasonId(seasonResult.data.id)
            if (expensesResult is AppResult.Success) {
                val totals = expensesResult.data
                    .groupBy { it.getCategoryId() }
                    .mapValues { (_, list) -> list.sumOf { it.getPrice() } }
                categories.forEach { cat -> cat.setTotal(totals[cat.id] ?: 0.0) }
            }
        }

        _getCategoriesResult.postValue(AppResult.Success(categoriesResult.message, categories))
    }

    private val _editCategoryResult = MutableLiveData<AppResult<ExpenseCategory?>>()
    val editCategoryResult: LiveData<AppResult<ExpenseCategory?>> = _editCategoryResult
    fun editCategory(category: ExpenseCategory) = viewModelScope.launch {
        _editCategoryResult.postValue(repo.edit(category))
    }

    private val _deleteCategoryResult = MutableLiveData<AppResult<Unit>>()
    val deleteCategoryResult: LiveData<AppResult<Unit>> = _deleteCategoryResult
    fun deleteCategory(id: Long) = viewModelScope.launch {
        _deleteCategoryResult.postValue(repo.deleteById(id))
    }
}
