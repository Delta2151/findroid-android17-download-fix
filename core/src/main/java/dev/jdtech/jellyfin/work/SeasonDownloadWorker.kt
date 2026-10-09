package dev.jdtech.jellyfin.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.utils.Downloader
import dev.jdtech.jellyfin.utils.SeasonDownloadProgress
import dev.jdtech.jellyfin.utils.queueSeasonDownloads
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.ItemFields
import timber.log.Timber

/** Persistently prepares the season; each media file has its own download worker. */
@HiltWorker
class SeasonDownloadWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: JellyfinRepository,
    private val downloader: Downloader,
    private val appPreferences: AppPreferences,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            var progress = SeasonDownloadProgress()
            try {
                val seasonId = UUID.fromString(inputData.getString(KEY_SEASON_ID))
                val storageIndex = inputData.getInt(KEY_STORAGE_INDEX, 0)
                require(storageIndex >= 0)
                val checkSession = {
                    check(
                        appPreferences.getValue(appPreferences.currentServer) ==
                            inputData.getString(KEY_SERVER_ID)
                    )
                    check(repository.getUserId().toString() == inputData.getString(KEY_USER_ID))
                }
                checkSession()
                val season = repository.getSeason(seasonId)
                checkSession()
                val episodes =
                    repository.getEpisodes(
                        seriesId = season.seriesId,
                        seasonId = seasonId,
                        fields = listOf(ItemFields.MEDIA_SOURCES),
                    )
                progress =
                    queueSeasonDownloads(
                        episodeIds = episodes.map { it.id },
                        storageIndex = storageIndex,
                        downloader = downloader,
                        loadEpisode = { repository.getEpisode(it) },
                        checkSession = checkSession,
                        onProgress = {
                            progress = it
                            setProgress(it.toData())
                        },
                    )
                Result.success(progress.toData())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Could not queue season downloads")
                Result.failure(progress.toData())
            }
        }

    companion object {
        const val KEY_SEASON_ID = "season_id"
        const val KEY_SERVER_ID = "server_id"
        const val KEY_USER_ID = "user_id"
        const val KEY_STORAGE_INDEX = "storage_index"
        private const val KEY_TOTAL = "total"
        private const val KEY_QUEUED = "queued"
        private const val KEY_SKIPPED = "skipped"
        private const val KEY_FAILED = "failed"

        fun uniqueWorkName(serverId: String, userId: UUID, seasonId: UUID) =
            "season-download-$serverId-$userId-$seasonId"

        fun SeasonDownloadProgress.toData() =
            workDataOf(
                KEY_TOTAL to total,
                KEY_QUEUED to queued,
                KEY_SKIPPED to skipped,
                KEY_FAILED to failed,
            )

        fun Data.toSeasonDownloadProgress() =
            SeasonDownloadProgress(
                total = getInt(KEY_TOTAL, 0),
                queued = getInt(KEY_QUEUED, 0),
                skipped = getInt(KEY_SKIPPED, 0),
                failed = getInt(KEY_FAILED, 0),
            )
    }
}
