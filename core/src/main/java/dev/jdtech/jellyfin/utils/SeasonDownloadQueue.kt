package dev.jdtech.jellyfin.utils

import android.app.DownloadManager
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.models.isDownloaded
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class SeasonDownloadProgress(
    val total: Int = 0,
    val queued: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
) {
    val processed: Int
        get() = queued + skipped + failed
}

internal data class SeasonDownloadBatch(
    val remainingIds: List<UUID>,
    val waitingDownloadIds: List<Long>,
    val progress: SeasonDownloadProgress,
) {
    val complete: Boolean
        get() = remainingIds.isEmpty() && waitingDownloadIds.isEmpty()
}

internal fun Int.isActiveDownload() =
    this == DownloadManager.STATUS_PENDING ||
        this == DownloadManager.STATUS_RUNNING ||
        this == DownloadManager.STATUS_PAUSED

/** A batch barrier: do not prepare any more episodes until every previous download is terminal. */
internal suspend fun queueSeasonDownloadBatch(
    episodeIds: List<UUID>,
    storageIndex: Int,
    downloader: Downloader,
    loadEpisode: suspend (UUID) -> FindroidEpisode,
    batchSize: Int,
    waitingDownloadIds: List<Long> = emptyList(),
    initialProgress: SeasonDownloadProgress =
        SeasonDownloadProgress(total = episodeIds.distinct().size),
    checkSession: () -> Unit = {},
    onProgress: suspend (SeasonDownloadProgress) -> Unit = {},
): SeasonDownloadBatch {
    require(batchSize > 0)
    checkSession()
    var progress = initialProgress
    onProgress(progress)
    // Check the entire batch, not just the first episode or the average percentage.
    if (waitingDownloadIds.any { downloader.getProgress(it).first.isActiveDownload() }) {
        return SeasonDownloadBatch(episodeIds, waitingDownloadIds, progress)
    }
    val ids = episodeIds.distinct()
    val waiting = mutableListOf<Long>()
    var processed = 0
    for (id in ids) {
        if (waiting.size == batchSize) break
        currentCoroutineContext().ensureActive()
        checkSession()
        try {
            val episode = loadEpisode(id)
            checkSession()
            // Episode zero is often a virtual/un-numbered placeholder, not a regular season
            // episode.
            if (
                episode.indexNumber <= 0 ||
                    episode.missing ||
                    !episode.canDownload ||
                    episode.isDownloaded()
            ) {
                progress = progress.copy(skipped = progress.skipped + 1)
            } else {
                val localStates =
                    episode.sources
                        .filter { it.type == FindroidSourceType.LOCAL }
                        .map { it to downloader.getProgress(it.downloadId).first }
                val active = localStates.firstOrNull { it.second.isActiveDownload() }?.first
                if (localStates.any { it.second == DownloadManager.STATUS_SUCCESSFUL }) {
                    progress = progress.copy(skipped = progress.skipped + 1)
                } else if (active != null) {
                    active.downloadId?.let { waiting.add(it) }
                    progress = progress.copy(skipped = progress.skipped + 1)
                } else {
                    val source =
                        episode.sources.firstOrNull { it.type == FindroidSourceType.REMOTE }
                    if (source == null) {
                        progress = progress.copy(failed = progress.failed + 1)
                    } else {
                        checkSession()
                        val (downloadId, _) =
                            downloader.downloadItem(episode, source.id, storageIndex)
                        if (downloadId == -1L) {
                            progress = progress.copy(failed = progress.failed + 1)
                        } else {
                            waiting.add(downloadId)
                            progress = progress.copy(queued = progress.queued + 1)
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            checkSession()
            progress = progress.copy(failed = progress.failed + 1)
        }
        processed++
        onProgress(progress)
    }
    return SeasonDownloadBatch(ids.drop(processed), waiting, progress)
}
