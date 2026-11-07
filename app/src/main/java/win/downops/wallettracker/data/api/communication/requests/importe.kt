package win.downops.wallettracker.data.api.communication.requests

data class ImporteIdRequest(val id: Long)
data class ImporteBySeasonIdRequest(val seasonId: Long)
data class CreateImporteRequest(
    val concept: String,
    val importeDate: String,
    val amount: Double,
    val balanceAfter: Double?,
    val seasonId: Long
)
