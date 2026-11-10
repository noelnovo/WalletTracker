package win.downops.wallettracker.data.models

class ExpenseCategory {
    private var _id: Long = 0
    private var _name: String = ""
    private var _total: Double = 0.0
    private var sortOrder: Int? = null
    constructor()
    constructor(name: String) {
        this._name = name
    }
    constructor(id:Long, name: String) {
        this._name = name
    }
    constructor(id: Long) {
        this._id = id
    }
    val id: Long get() = _id
    val name: String get() = _name
    fun setName(value: String){
        this._name = value
    }
    fun getOrder(): Int?{
        return this.sortOrder
    }
    fun setOrder(value: Int?){
        this.sortOrder = value
    }
    val total: Double get() = _total
    fun setTotal(value: Double){
        this._total = value
    }
    override fun toString(): String {
        return "ExpenseCategory(id=$id, name=$name, total=$total)"
    }

}
