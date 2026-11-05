package win.downops.wallettracker.data

import win.downops.wallettracker.data.models.AppResult
import win.downops.wallettracker.data.models.Importe

interface ImporteRepository {
    suspend fun getBySeasonId(seasonId: Long): AppResult<List<Importe>>
    suspend fun getById(importeId: Long): AppResult<Importe?>
    suspend fun create(importe: Importe): AppResult<Importe?>
    suspend fun deleteById(importeId: Long): AppResult<Unit>
    suspend fun deleteBySeasonId(seasonId: Long): AppResult<Unit>
}
