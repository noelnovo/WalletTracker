package win.downops.wallettracker.data.models

import java.sql.Date
import java.text.SimpleDateFormat
import java.util.Locale

class Expense {
    private var _id: Long = 0
    private var price: Double? = null
    private var _description: String = ""
    private var expenseDate: Date? = null
    private var category: Long? = null
    private var _seasonId: Long? = null
    constructor()
    constructor(id:Long, price: Double?, expenseDate: Date?, category: Long?, description: String) {
        this._id = id
        this.price = price
        this.expenseDate = expenseDate
        this.category = category
        this._description = description
    }
    constructor(price: Double?, expenseDate: Date?, category: Long?, description: String) {
        this.price = price
        this.expenseDate = expenseDate
        this.category = category
        this._description = description
    }
    constructor(id: Long) {
        this._id = id
    }
    val id: Long get() = _id
    val description: String get() = _description
    fun setDescription(value: String){
        this._description = value
    }

    fun getPrice(): Double{
        return this.price!!
    }
    fun setPrice(value: Double){
        this.price = value
    }
    fun getDate(): Date {
        return this.expenseDate!!
    }
    fun getDateString(): String{
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return  dateFormat.format(this.getDate())
    }

    fun setDate(value: Date){
        this.expenseDate = value
    }
    fun getCategoryId(): Long{
        return this.category!!
    }
    fun setCategoryId(value: Long){
        this.category = value
    }
    val seasonId: Long? get() = _seasonId
    fun setSeasonId(value: Long) { _seasonId = value }
}