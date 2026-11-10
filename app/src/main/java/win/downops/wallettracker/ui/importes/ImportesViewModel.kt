package win.downops.wallettracker.ui.importes

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jakarta.inject.Inject
import kotlinx.coroutines.launch
import win.downops.wallettracker.data.ImporteRepository
import win.downops.wallettracker.data.SeasonRepository
import win.downops.wallettracker.data.models.AppResult
import win.downops.wallettracker.data.models.Importe
import win.downops.wallettracker.data.models.Season

data class YearOption(val year: Int, val label: String) {
    override fun toString() = label
}
data class MonthOption(val season: Season, val label: String) {
    override fun toString() = label
}

@HiltViewModel
class ImportesViewModel @Inject constructor(
    private val seasonRepository: SeasonRepository,
    private val importeRepository: ImporteRepository
) : ViewModel() {

    private val _years = MutableLiveData<List<YearOption>>()
    val years: LiveData<List<YearOption>> = _years

    private val _months = MutableLiveData<List<MonthOption>>()
    val months: LiveData<List<MonthOption>> = _months

    private val _selectedMonthId = MutableLiveData<Long>()
    val selectedMonthId: LiveData<Long> = _selectedMonthId

    private val _importes = MutableLiveData<List<Importe>>()
    val importes: LiveData<List<Importe>> = _importes

    private val _total = MutableLiveData<Double>()
    val total: LiveData<Double> = _total

    private val _totalIncome = MutableLiveData<Double>()
    val totalIncome: LiveData<Double> = _totalIncome

    private val _totalExpense = MutableLiveData<Double>()
    val totalExpense: LiveData<Double> = _totalExpense

    private var allSeasons: List<Season> = emptyList()

    private val monthNames = listOf(
        "Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio",
        "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre"
    )

    fun loadSeasons() {
        viewModelScope.launch {
            val result = seasonRepository.getAll()
            if (result is AppResult.Success) {
                allSeasons = result.data
                val yearList = allSeasons
                    .map { it.year }
                    .distinct()
                    .sortedDescending()
                    .map { YearOption(it, it.toString()) }
                _years.value = yearList

                if (yearList.isNotEmpty()) selectYear(yearList.first().year)
            }
        }
    }

    fun selectYear(year: Int) {
        val monthList = allSeasons
            .filter { it.year == year }
            .sortedByDescending { it.month }
            .map { season -> MonthOption(season, monthNames.getOrElse(season.month - 1) { season.month.toString() }) }
        _months.value = monthList

        viewModelScope.launch {
            var validSeasonId: Long = 0
            for (month in monthList) {
                val seasonId = month.season.id
                val result = importeRepository.getBySeasonId(seasonId)
                if(result is AppResult.Success && result.data.isNotEmpty()){
                    validSeasonId = seasonId
                    break
                }
            }
            if(validSeasonId <= 0 && monthList.isNotEmpty()){
                validSeasonId = monthList.first().season.id
            }

            if (validSeasonId > 0) {
                _selectedMonthId.value = validSeasonId
                loadImportes(validSeasonId)
            }
        }
    }

    fun loadImportes(seasonId: Long) {
        _selectedMonthId.value = seasonId
        viewModelScope.launch {
            val result = importeRepository.getBySeasonId(seasonId)
            if (result is AppResult.Success) {
                val list = result.data
                _importes.value = list
                _total.value = list.sumOf { it.amount }
                _totalIncome.value = list.filter { it.amount > 0 }.sumOf { it.amount }
                _totalExpense.value = list.filter { it.amount < 0 }.sumOf { it.amount }
            } else {
                _importes.value = emptyList()
                _total.value = 0.0
                _totalIncome.value = 0.0
                _totalExpense.value = 0.0
            }
        }
    }

    fun emptySeason(seasonId: Long) {
        viewModelScope.launch {
            val result = importeRepository.deleteBySeasonId(seasonId)
            if (result is AppResult.Success) {
                loadImportes(seasonId)
            }
        }
    }
}
