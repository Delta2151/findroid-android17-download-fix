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

/** Queue episodes individually so one unavailable episode does not stop the season. */
internal suspend fun queueSeasonDownloads(
    episodeIds: List<UUID>,
    storageIndex: Int,
    downloader: Downloader,
    loadEpisode: suspend (UUID) -> FindroidEpisode,
    checkSession: () -> Unit = {},
    onProgress: suspend (SeasonDownloadProgress) -> Unit = {},
): SeasonDownloadProgress {
    val ids = episodeIds.distinct()
    var progress = SeasonDownloadProgress(total = ids.size)
    onProgress(progress)
    for (id in ids) {
        currentCoroutineContext().ensureActive()
        checkSession()
        try {
            val episode = loadEpisode(id)
            checkSession()
            val alreadyAvailable =
                episode.isDownloaded() ||
                    episode.sources
                        .filter { it.type == FindroidSourceType.LOCAL }
                        .any { source ->
                            when (downloader.getProgress(source.downloadId).first) {
                                DownloadManager.STATUS_PENDING,
                                DownloadManager.STATUS_RUNNING,
                                DownloadManager.STATUS_PAUSED,
                                DownloadManager.STATUS_SUCCESSFUL -> true
                                else -> false
                            }
                        }
            if (alreadyAvailable || episode.missing || !episode.canDownload) {
                progress = progress.copy(skipped = progress.skipped + 1)
            } else {
                val source = episode.sources.firstOrNull { it.type == FindroidSourceType.REMOTE }
                if (source == null) {
                    progress = progress.copy(failed = progress.failed + 1)
                } else {
                    checkSession()
                    val (downloadId, _) = downloader.downloadItem(episode, source.id, storageIndex)
                    progress =
                        if (downloadId == -1L) {
                            progress.copy(failed = progress.failed + 1)
                        } else {
                            progress.copy(queued = progress.queued + 1)
                        }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A changed session stops the batch, rather than counting every remaining item as
            // failed.
            checkSession()
            progress = progress.copy(failed = progress.failed + 1)
        }
        onProgress(progress)
    }
    return progress
}
