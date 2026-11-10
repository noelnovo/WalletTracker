package win.downops.wallettracker.ui.metrics

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jakarta.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import win.downops.wallettracker.data.ExpenseCategoryRepository
import win.downops.wallettracker.data.ExpenseRepository
import win.downops.wallettracker.data.ImporteRepository
import win.downops.wallettracker.data.SeasonRepository
import win.downops.wallettracker.data.models.AppResult
import win.downops.wallettracker.data.models.Expense
import win.downops.wallettracker.data.models.Importe
import win.downops.wallettracker.data.models.Season
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class CategoryOption(val id: Long, val name: String) {
    override fun toString(): String = name
}
data class MonthOption(val key: String, val label: String) {
    override fun toString(): String = label
}

data class CategoryMetric(
    val name: String,
    val total: Double,
    val percentage: Double
)

data class MonthMetric(
    val label: String,
    val total: Double,
    val percentage: Double
)

data class ComparisonMetric(
    val expenseTotal: Double,
    val importTotalIncome: Double,
    val importTotalExpense: Double,
    val difference: Double,
    val matchPercentage: Double
)

data class MetricsData(
    val total: Double,
    val count: Int,
    val average: Double,
    val categories: List<CategoryMetric>,
    val months: List<MonthMetric>,
    val comparison: ComparisonMetric? = null
)

@HiltViewModel
class MetricsViewModel @Inject constructor(
    private val categoryRepo: ExpenseCategoryRepository,
    private val expenseRepo: ExpenseRepository,
    private val seasonRepo: SeasonRepository,
    private val importeRepo: ImporteRepository
) : ViewModel() {

    private var allExpenses: List<Expense> = emptyList()
    private var allSeasons: List<Season> = emptyList()
    private var categoryNameMap: Map<Long, String> = emptyMap()

    var selectedCategoryId: Long? = null
        private set
    var selectedMonthKey: String? = null
        private set

    private val _metricsResult = MutableLiveData<AppResult<MetricsData>>()
    val metricsResult: LiveData<AppResult<MetricsData>> = _metricsResult

    private val _categoryOptions = MutableLiveData<List<CategoryOption>>()
    val categoryOptions: LiveData<List<CategoryOption>> = _categoryOptions

    private val _monthOptions = MutableLiveData<List<MonthOption>>()
    val monthOptions: LiveData<List<MonthOption>> = _monthOptions

    private val monthFormat = SimpleDateFormat("yyyy-MM", Locale.getDefault())
    private val labelFormat = SimpleDateFormat("MMM yy", Locale.getDefault())

    fun loadMetrics() = viewModelScope.launch {
        selectedCategoryId = null
        selectedMonthKey = null

        val categoriesResult = categoryRepo.getAll()
        if (categoriesResult is AppResult.Error) {
            _metricsResult.postValue(AppResult.Error(categoriesResult.message, categoriesResult.isControlled))
            return@launch
        }

        val categories = (categoriesResult as AppResult.Success).data
        categoryNameMap = categories.associate { it.id to it.name }

        val seasonsResult = seasonRepo.getAll()
        if (seasonsResult is AppResult.Success) {
            allSeasons = seasonsResult.data
        }

        val expensePairs = categories.map { cat ->
            async {
                val catId = cat.id
                when (val result = expenseRepo.getByCatId(catId)) {
                    is AppResult.Success -> catId to result.data
                    is AppResult.Error -> catId to emptyList<Expense>()
                }
            }
        }.awaitAll()

        allExpenses = expensePairs.flatMap { it.second }

        val catOptions = categories
            .filter { cat -> expensePairs.any { it.first == cat.id && it.second.isNotEmpty() } }
            .map { CategoryOption(it.id, it.name) }
        _categoryOptions.postValue(catOptions)

        val expenseMonths = allExpenses
            .mapNotNull { try { monthFormat.format(it.getDate()) } catch (e: Exception) { null } }
        
        val seasonMonths = allSeasons.map { String.format(Locale.getDefault(), "%04d-%02d", it.year, it.month) }

        val monthOpts = (expenseMonths + seasonMonths)
            .distinct()
            .sortedDescending()
            .map { key ->
                val cal = Calendar.getInstance()
                try { cal.time = monthFormat.parse(key)!! } catch (e: Exception) { /* keep the calendar's current time when parsing fails */ }
                MonthOption(key, labelFormat.format(cal.time))
            }
        _monthOptions.postValue(monthOpts)

        applyFilters()
    }

    fun setFilter(categoryId: Long?, monthKey: String?) {
        if (selectedCategoryId == categoryId && selectedMonthKey == monthKey) return
        selectedCategoryId = categoryId
        selectedMonthKey = monthKey
        applyFilters()
    }

    private fun applyFilters() {
        viewModelScope.launch {
            val filtered = applySelectionFilters()

            val total = filtered.sumOf { try { it.getPrice() } catch (e: Exception) { 0.0 } }
            val count = filtered.size
            val average = if (count > 0) total / count else 0.0

            val categoryMetrics = buildCategoryMetrics(filtered, total)
            val monthMetrics = buildMonthMetrics(filtered)
            val comparison = buildComparison(total)

            if (filtered.isEmpty() && comparison == null) {
                _metricsResult.postValue(AppResult.Error("No data for the selected filters", isControlled = true))
                return@launch
            }

            _metricsResult.postValue(
                AppResult.Success("Metrics loaded", MetricsData(total, count, average, categoryMetrics, monthMetrics, comparison))
            )
        }
    }

    private fun applySelectionFilters(): List<Expense> {
        var filtered = allExpenses
        selectedCategoryId?.let { catId ->
            filtered = filtered.filter { it.getCategoryId() == catId }
        }
        selectedMonthKey?.let { monthKey ->
            filtered = filtered.filter { expense ->
                try { monthFormat.format(expense.getDate()) == monthKey } catch (e: Exception) { false }
            }
        }
        return filtered
    }

    private fun buildCategoryMetrics(expenses: List<Expense>, total: Double): List<CategoryMetric> {
        return expenses
            .groupBy { it.getCategoryId() }
            .map { (catId, catExpenses) ->
                val catTotal = catExpenses.sumOf { try { it.getPrice() } catch (e: Exception) { 0.0 } }
                CategoryMetric(
                    name = categoryNameMap[catId] ?: "Unknown",
                    total = catTotal,
                    percentage = if (total > 0) (catTotal / total) * 100 else 0.0
                )
            }
            .sortedByDescending { it.total }
    }

    private suspend fun buildComparison(expenseTotal: Double): ComparisonMetric? {
        var comparison: ComparisonMetric? = null
        if (selectedMonthKey != null && selectedCategoryId == null) {
            comparison = calculateComparison(selectedMonthKey!!, expenseTotal)
        }
        return comparison
    }

    private suspend fun calculateComparison(monthKey: String, expenseTotal: Double): ComparisonMetric? {
        val parts = monthKey.split("-")
        if (parts.size != 2) return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null

        val season = allSeasons.find { it.year == year && it.month == month } ?: return null
        val result = importeRepo.getBySeasonId(season.id)
        if (result !is AppResult.Success || result.data.isEmpty()) return null

        val imports = result.data
        val importTotalIncome = imports.filter { it.amount > 0 }.sumOf { it.amount }
        val importTotalExpense = imports.filter { it.amount < 0 }.sumOf { Math.abs(it.amount) }

        val diff = Math.abs(importTotalExpense - expenseTotal)
        val matchPercentage = if (importTotalExpense > 0) {
            Math.max(0.0, 100.0 - (diff / importTotalExpense * 100.0))
        } else if (expenseTotal == 0.0) 100.0 else 0.0

        return ComparisonMetric(expenseTotal, importTotalIncome, importTotalExpense, diff, matchPercentage)
    }

    private fun buildMonthMetrics(expenses: List<Expense>): List<MonthMetric> {
        val totalsPerMonth = expenses
            .groupBy { try { monthFormat.format(it.getDate()) } catch (e: Exception) { "" } }
            .filterKeys { it.isNotEmpty() }
            .mapValues { (_, list) -> list.sumOf { try { it.getPrice() } catch (e: Exception) { 0.0 } } }

        val maxTotal = totalsPerMonth.values.maxOrNull() ?: 1.0

        return totalsPerMonth.entries
            .sortedBy { it.key }
            .map { (key, monthTotal) ->
                val cal = Calendar.getInstance()
                try { cal.time = monthFormat.parse(key)!! } catch (e: Exception) { /* keep the calendar's current time when parsing fails */ }
                MonthMetric(
                    label = labelFormat.format(cal.time),
                    total = monthTotal,
                    percentage = if (maxTotal > 0) (monthTotal / maxTotal) * 100 else 0.0
                )
            }
    }
}
