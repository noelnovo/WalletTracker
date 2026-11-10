package win.downops.wallettracker.data.models

class Season {
    private var _id: Long = 0
    private var _year: Int = 0
    private var _month: Int = 0

    constructor()
    constructor(id: Long, year: Int, month: Int) {
        this._id = id
        this._year = year
        this._month = month
    }
    constructor(year: Int, month: Int) {
        this._year = year
        this._month = month
    }

    val id: Long get() = _id
    val year: Int get() = _year
    val month: Int get() = _month
    fun setYear(value: Int) { _year = value }
    fun setMonth(value: Int) { _month = value }
}
