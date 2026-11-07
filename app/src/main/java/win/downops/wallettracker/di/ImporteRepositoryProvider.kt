package win.downops.wallettracker.di

import jakarta.inject.Inject
import jakarta.inject.Singleton
import win.downops.wallettracker.data.ImporteRepository
import win.downops.wallettracker.data.api.importe.ImporteHttpService
import win.downops.wallettracker.data.models.AppResult
import win.downops.wallettracker.data.models.Importe
import win.downops.wallettracker.data.sqlite.importe.ImporteSqlService

@Singleton
class ImporteRepositoryProvider @Inject constructor(
    private val apiRepository: dagger.Lazy<ImporteHttpService>,
    private val localRepository: dagger.Lazy<ImporteSqlService>,
    private val appMode: AppMode
) : ImporteRepository {

    private fun repo(): ImporteRepository =
        if (appMode.isOnline) apiRepository.get() else localRepository.get()

    override suspend fun getBySeasonId(seasonId: Long): AppResult<List<Importe>> = repo().getBySeasonId(seasonId)
    override suspend fun getById(importeId: Long): AppResult<Importe?> = repo().getById(importeId)
    override suspend fun create(importe: Importe): AppResult<Importe?> = repo().create(importe)
    override suspend fun deleteById(importeId: Long): AppResult<Unit> = repo().deleteById(importeId)
    override suspend fun deleteBySeasonId(seasonId: Long): AppResult<Unit> = repo().deleteBySeasonId(seasonId)
}
