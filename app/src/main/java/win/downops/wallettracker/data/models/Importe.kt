package win.downops.wallettracker.data.models

import java.sql.Date
import java.text.SimpleDateFormat
import java.util.Locale

class Importe {
    private var _id: Long = 0
    private var _concept: String = ""
    private var importeDate: Date? = null
    private var _amount: Double = 0.0
    private var _balanceAfter: Double = 0.0
    private var _seasonId: Long = 0

    constructor()
    constructor(id: Long, concept: String, importeDate: Date?, amount: Double, balanceAfter: Double, seasonId: Long) {
        this._id = id
        this._concept = concept
        this.importeDate = importeDate
        this._amount = amount
        this._balanceAfter = balanceAfter
        this._seasonId = seasonId
    }
    constructor(concept: String, importeDate: Date?, amount: Double, balanceAfter: Double, seasonId: Long) {
        this._concept = concept
        this.importeDate = importeDate
        this._amount = amount
        this._balanceAfter = balanceAfter
        this._seasonId = seasonId
    }
    constructor(id: Long) {
        this._id = id
    }

    val id: Long get() = _id
    val concept: String get() = _concept
    fun setConcept(value: String) { _concept = value }
    fun getDate(): Date = importeDate!!
    fun getDateString(): String = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(importeDate)
    fun setDate(value: Date) { importeDate = value }
    val amount: Double get() = _amount
    fun setAmount(value: Double) { _amount = value }
    val balanceAfter: Double get() = _balanceAfter
    fun setBalanceAfter(value: Double) { _balanceAfter = value }
    val seasonId: Long get() = _seasonId
    fun setSeasonId(value: Long) { _seasonId = value }
}
