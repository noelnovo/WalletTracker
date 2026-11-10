package win.downops.wallettracker.ui.categoriesExpenses

import android.os.Build
import androidx.annotation.RequiresApi
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
import win.downops.wallettracker.data.models.Expense
import win.downops.wallettracker.data.models.ExpenseCategory
import java.util.Calendar

@HiltViewModel
class CategoriesExpensesViewModel @Inject constructor(
    private val expenseRepo: ExpenseRepository,
    private val categoryRepo: ExpenseCategoryRepository,
    private val seasonRepo: SeasonRepository
) : ViewModel() {

    val showAll = MutableLiveData(false)

    private val _deleteResult = MutableLiveData<AppResult<Unit>>()
    val deleteResult: LiveData<AppResult<Unit>> = _deleteResult

    fun deleteExpense(expenseId: Long) = viewModelScope.launch {
        _deleteResult.postValue(expenseRepo.deleteById(expenseId))
    }

    private val _getCategoryResult = MutableLiveData<AppResult<ExpenseCategory?>>()
    val getCategoryResult: LiveData<AppResult<ExpenseCategory?>> = _getCategoryResult

    fun getCategory(categoryId: Long) = viewModelScope.launch {
        _getCategoryResult.postValue(categoryRepo.getById(categoryId))
    }

    private val _getExpensesByCategoryIdResult = MutableLiveData<AppResult<List<Expense>>>()
    val getExpensesByCategoryIdResult: LiveData<AppResult<List<Expense>>> = _getExpensesByCategoryIdResult

    fun getExpensesByCategoryId(categoryId: Long) = viewModelScope.launch {
        if (showAll.value == true) {
            _getExpensesByCategoryIdResult.postValue(expenseRepo.getByCatId(categoryId))
        } else {
            val cal = Calendar.getInstance()
            val year = cal.get(Calendar.YEAR)
            val month = cal.get(Calendar.MONTH) + 1
            val seasonResult = seasonRepo.getByYearMonth(year, month)
            if (seasonResult is AppResult.Success && seasonResult.data != null) {
                val seasonExpenses = expenseRepo.getBySeasonId(seasonResult.data.id)
                if (seasonExpenses is AppResult.Success) {
                    val filtered = seasonExpenses.data.filter { it.getCategoryId() == categoryId }
                    _getExpensesByCategoryIdResult.postValue(AppResult.Success("Expenses fetched", filtered))
                } else {
                    _getExpensesByCategoryIdResult.postValue(seasonExpenses)
                }
            } else {
                _getExpensesByCategoryIdResult.postValue(AppResult.Success("No expenses for this season", emptyList()))
            }
        }
    }

    fun toggleShowAll(categoryId: Long) {
        showAll.value = !(showAll.value ?: false)
        getExpensesByCategoryId(categoryId)
    }

    private val _editExpenseCategoryResult = MutableLiveData<AppResult<ExpenseCategory?>>()
    val editExpenseCategoryResult: LiveData<AppResult<ExpenseCategory?>> = _editExpenseCategoryResult

    fun editExpenseCategory(expenseCategory: ExpenseCategory) = viewModelScope.launch {
        _editExpenseCategoryResult.postValue(categoryRepo.edit(expenseCategory))
    }

    private val _deleteExpenseCategoryResult = MutableLiveData<AppResult<Unit>>()
    val deleteExpenseCategoryResult: LiveData<AppResult<Unit>> = _deleteExpenseCategoryResult

    fun deleteExpenseCategory(categoryId: Long) = viewModelScope.launch {
        _deleteExpenseCategoryResult.postValue(categoryRepo.deleteById(categoryId))
    }
}
