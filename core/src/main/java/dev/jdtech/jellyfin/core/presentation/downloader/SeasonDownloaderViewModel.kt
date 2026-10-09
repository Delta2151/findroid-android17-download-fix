package dev.jdtech.jellyfin.core.presentation.downloader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.utils.SeasonDownloadProgress
import dev.jdtech.jellyfin.work.SeasonDownloadWorker
import dev.jdtech.jellyfin.work.SeasonDownloadWorker.Companion.toSeasonDownloadProgress
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SeasonDownloadState(
    val isQueuing: Boolean = false,
    val finished: Boolean = false,
    val error: Boolean = false,
    val progress: SeasonDownloadProgress = SeasonDownloadProgress(),
)

@HiltViewModel
class SeasonDownloaderViewModel
@Inject
constructor(
    private val workManager: WorkManager,
    private val repository: JellyfinRepository,
    private val appPreferences: AppPreferences,
) : ViewModel() {
    private val _state = MutableStateFlow(SeasonDownloadState())
    val state = _state.asStateFlow()
    private var observation: Job? = null
    private var workName: String? = null

    fun observeSeason(seasonId: UUID) {
        val serverId = appPreferences.getValue(appPreferences.currentServer) ?: return
        val name = SeasonDownloadWorker.uniqueWorkName(serverId, repository.getUserId(), seasonId)
        if (workName == name) return
        workName = name
        observation?.cancel()
        observation = viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(name).collect { infos ->
                val ordered = infos.sortedBy { SeasonDownloadWorker.stepNumber(it.tags) }
                val active = ordered.firstOrNull { !it.state.isFinished }
                val latest = ordered.lastOrNull()
                val latestOutput = ordered.lastOrNull { it.state.isFinished }?.outputData
                val data =
                    if (active != null && active.progress.keyValueMap.isNotEmpty()) active.progress
                    else latestOutput ?: androidx.work.Data.EMPTY
                val error =
                    latest?.state == WorkInfo.State.FAILED ||
                        latest?.state == WorkInfo.State.CANCELLED
                _state.value =
                    SeasonDownloadState(
                        isQueuing = active != null,
                        finished =
                            active == null &&
                                (error ||
                                    data.getBoolean(SeasonDownloadWorker.KEY_COMPLETE, false)),
                        error = error,
                        progress = data.toSeasonDownloadProgress(),
                    )
            }
        }
    }

    fun downloadSeason(seasonId: UUID, storageIndex: Int) {
        if (_state.value.isQueuing) return
        val serverId = appPreferences.getValue(appPreferences.currentServer) ?: return
        val userId = repository.getUserId()
        observeSeason(seasonId)
        _state.value = SeasonDownloadState(isQueuing = true)
        viewModelScope.launch {
            try {
                val networkType =
                    when {
                        !appPreferences.getValue(appPreferences.downloadOverMobileData) ->
                            NetworkType.UNMETERED
                        !appPreferences.getValue(appPreferences.downloadWhenRoaming) ->
                            NetworkType.NOT_ROAMING
                        else -> NetworkType.CONNECTED
                    }
                val request =
                    OneTimeWorkRequestBuilder<SeasonDownloadWorker>()
                        .setConstraints(
                            Constraints.Builder().setRequiredNetworkType(networkType).build()
                        )
                        .addTag(SeasonDownloadWorker.stepTag(0))
                        .setInputData(
                            workDataOf(
                                SeasonDownloadWorker.KEY_SEASON_ID to seasonId.toString(),
                                SeasonDownloadWorker.KEY_SERVER_ID to serverId,
                                SeasonDownloadWorker.KEY_USER_ID to userId.toString(),
                                SeasonDownloadWorker.KEY_STORAGE_INDEX to storageIndex,
                            )
                        )
                        .build()
                // KEEP also protects against duplicate taps from a second screen instance.
                withContext(Dispatchers.IO) {
                    workManager
                        .enqueueUniqueWork(
                            SeasonDownloadWorker.uniqueWorkName(serverId, userId, seasonId),
                            ExistingWorkPolicy.KEEP,
                            request,
                        )
                        .result
                        .get()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = SeasonDownloadState(error = true, finished = true)
            }
        }
    }
}
