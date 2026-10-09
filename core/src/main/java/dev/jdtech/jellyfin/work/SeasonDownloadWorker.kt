package dev.jdtech.jellyfin.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.utils.Downloader
import dev.jdtech.jellyfin.utils.SeasonDownloadProgress
import dev.jdtech.jellyfin.utils.queueSeasonDownloadBatch
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.ItemFields
import timber.log.Timber

/** Short persistent steps prepare one batch, then yield while its media workers finish. */
@HiltWorker
class SeasonDownloadWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: JellyfinRepository,
    private val downloader: Downloader,
    private val appPreferences: AppPreferences,
    private val workManager: WorkManager,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            var progress = inputData.toSeasonDownloadProgress()
            try {
                val seasonId = UUID.fromString(inputData.getString(KEY_SEASON_ID))
                val storageIndex = inputData.getInt(KEY_STORAGE_INDEX, 0)
                require(storageIndex >= 0)
                val serverId = inputData.getString(KEY_SERVER_ID)!!
                val userId = UUID.fromString(inputData.getString(KEY_USER_ID))
                val checkSession = {
                    check(appPreferences.getValue(appPreferences.currentServer) == serverId)
                    check(repository.getUserId() == userId)
                }
                checkSession()
                val name = uniqueWorkName(serverId, userId, seasonId)
                val step = inputData.getInt(KEY_STEP, 0) + 1
                val tag = stepTag(step)
                // A process may stop after saving the continuation but before completing this step.
                // The continuation already owns the checkpoint; do not enqueue again or overwrite
                // it.
                if (
                    workManager.getWorkInfosForUniqueWorkFlow(name).first().any { tag in it.tags }
                ) {
                    return@withContext Result.success()
                }
                val savedIds = inputData.getByteArray(KEY_REMAINING_IDS)
                val ids =
                    if (savedIds != null) decodeEpisodeIds(savedIds)
                    else {
                        val season = repository.getSeason(seasonId)
                        checkSession()
                        repository
                            .getEpisodes(
                                seriesId = season.seriesId,
                                seasonId = seasonId,
                                fields = listOf(ItemFields.MEDIA_SOURCES),
                            )
                            .filter { it.indexNumber > 0 && !it.missing }
                            .sortedWith(compareBy({ it.indexNumber }, { it.id }))
                            .map { it.id }
                            .distinct()
                            .also {
                                progress = SeasonDownloadProgress(total = it.size)
                                // Validate the saved plan before starting downloads (WorkManager's
                                // 10 KB limit).
                                Data.Builder()
                                    .putAll(inputData)
                                    .putAll(progress.toData())
                                    .putLongArray(KEY_WAITING_IDS, LongArray(5))
                                    .putInt(KEY_STEP, 0)
                                    .putByteArray(KEY_REMAINING_IDS, encodeEpisodeIds(it))
                                    .build()
                            }
                    }
                val batch =
                    queueSeasonDownloadBatch(
                        episodeIds = ids,
                        storageIndex = storageIndex,
                        downloader = downloader,
                        loadEpisode = { repository.getEpisode(it) },
                        batchSize =
                            (appPreferences
                                    .getValue(appPreferences.seasonDownloadBatchSize)
                                    .toIntOrNull() ?: 2)
                                .coerceIn(1, 5),
                        waitingDownloadIds =
                            inputData.getLongArray(KEY_WAITING_IDS)?.toList().orEmpty(),
                        initialProgress = progress,
                        checkSession = checkSession,
                        onProgress = {
                            progress = it
                            setProgress(it.toData())
                        },
                    )
                checkSession()
                if (!batch.complete) {
                    // A restarted step must not append its continuation twice.
                    if (
                        workManager.getWorkInfosForUniqueWorkFlow(name).first().none {
                            tag in it.tags
                        }
                    ) {
                        val nextData =
                            Data.Builder()
                                .putAll(inputData)
                                .putAll(progress.toData())
                                .putByteArray(
                                    KEY_REMAINING_IDS,
                                    encodeEpisodeIds(batch.remainingIds),
                                )
                                .putLongArray(
                                    KEY_WAITING_IDS,
                                    batch.waitingDownloadIds.toLongArray(),
                                )
                                .putInt(KEY_STEP, step)
                                .build()
                        val networkType =
                            when {
                                !appPreferences.getValue(appPreferences.downloadOverMobileData) ->
                                    NetworkType.UNMETERED
                                !appPreferences.getValue(appPreferences.downloadWhenRoaming) ->
                                    NetworkType.NOT_ROAMING
                                else -> NetworkType.CONNECTED
                            }
                        val next =
                            OneTimeWorkRequestBuilder<SeasonDownloadWorker>()
                                .setInputData(nextData)
                                .addTag(tag)
                                .setConstraints(
                                    Constraints.Builder()
                                        .setRequiredNetworkType(networkType)
                                        .build()
                                )
                                .setInitialDelay(30, TimeUnit.SECONDS)
                                .build()
                        workManager
                            .enqueueUniqueWork(name, ExistingWorkPolicy.APPEND, next)
                            .result
                            .get()
                    }
                }
                Result.success(progress.toData(batch.complete))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Could not advance season download batch")
                Result.failure(progress.toData())
            }
        }

    companion object {
        const val KEY_SEASON_ID = "season_id"
        const val KEY_SERVER_ID = "server_id"
        const val KEY_USER_ID = "user_id"
        const val KEY_STORAGE_INDEX = "storage_index"
        const val KEY_COMPLETE = "complete"
        private const val KEY_REMAINING_IDS = "remaining_ids"
        private const val KEY_WAITING_IDS = "waiting_ids"
        private const val KEY_STEP = "step"
        private const val STEP_TAG_PREFIX = "season-step-"
        private const val KEY_TOTAL = "total"
        private const val KEY_QUEUED = "queued"
        private const val KEY_SKIPPED = "skipped"
        private const val KEY_FAILED = "failed"

        fun uniqueWorkName(serverId: String, userId: UUID, seasonId: UUID) =
            "season-download-$serverId-$userId-$seasonId"

        fun stepTag(step: Int) = "$STEP_TAG_PREFIX$step"

        fun stepNumber(tags: Set<String>): Int =
            tags
                .firstOrNull { it.startsWith(STEP_TAG_PREFIX) }
                ?.removePrefix(STEP_TAG_PREFIX)
                ?.toIntOrNull() ?: 0

        internal fun encodeEpisodeIds(ids: List<UUID>): ByteArray {
            val buffer = ByteBuffer.allocate(ids.size * 16)
            ids.forEach { buffer.putLong(it.mostSignificantBits).putLong(it.leastSignificantBits) }
            return buffer.array()
        }

        internal fun decodeEpisodeIds(bytes: ByteArray): List<UUID> {
            require(bytes.size % 16 == 0)
            val buffer = ByteBuffer.wrap(bytes)
            return List(bytes.size / 16) { UUID(buffer.long, buffer.long) }
        }

        fun SeasonDownloadProgress.toData(complete: Boolean = false) =
            workDataOf(
                KEY_TOTAL to total,
                KEY_QUEUED to queued,
                KEY_SKIPPED to skipped,
                KEY_FAILED to failed,
                KEY_COMPLETE to complete,
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
