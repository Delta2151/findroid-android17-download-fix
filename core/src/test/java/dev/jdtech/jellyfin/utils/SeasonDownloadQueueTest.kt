package dev.jdtech.jellyfin.utils

import android.app.DownloadManager
import dev.jdtech.jellyfin.core.presentation.dummy.dummyEpisode
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.models.UiText
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SeasonDownloadQueueTest {
    private fun episode() =
        dummyEpisode.copy(
            id = UUID.randomUUID(),
            sources = listOf(dummyEpisode.sources.first().copy(id = "remote-source")),
        )

    private fun local(episode: FindroidEpisode, complete: Boolean = false) =
        episode.sources
            .first()
            .copy(
                id = "local-source",
                type = FindroidSourceType.LOCAL,
                path = if (complete) "episode.mkv" else "episode.download",
                downloadId = 42L,
            )

    @Test
    fun skipsDownloadedAndAllActiveDownloadStates() = runBlocking {
        val downloader = FakeDownloader()
        val finished = episode().let { it.copy(sources = listOf(local(it, complete = true))) }
        val progress = queueSeasonDownloads(listOf(finished.id), 0, downloader, { finished })
        assertEquals(SeasonDownloadProgress(total = 1, skipped = 1), progress)
        for (status in
            listOf(
                DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_RUNNING,
                DownloadManager.STATUS_PAUSED,
                DownloadManager.STATUS_SUCCESSFUL,
            )) {
            val active = episode().let { it.copy(sources = listOf(local(it)) + it.sources) }
            downloader.status = status
            assertEquals(
                1,
                queueSeasonDownloads(listOf(active.id), 0, downloader, { active }).skipped,
            )
        }
        assertTrue(downloader.calls.isEmpty())
    }

    @Test
    fun retriesFailedPartialUsingRemoteSourceAndSelectedStorage() = runBlocking {
        val downloader = FakeDownloader().apply { status = DownloadManager.STATUS_FAILED }
        val item = episode().let { it.copy(sources = listOf(local(it)) + it.sources) }
        val progress = queueSeasonDownloads(listOf(item.id), 2, downloader, { item })
        assertEquals(1, progress.queued)
        assertEquals(listOf(Triple(item.id, "remote-source", 2)), downloader.calls)
    }

    @Test
    fun deduplicatesEpisodeIdsAndReportsEveryProcessedEpisode() = runBlocking {
        val downloader = FakeDownloader()
        val item = episode()
        val updates = mutableListOf<SeasonDownloadProgress>()
        val progress =
            queueSeasonDownloads(
                listOf(item.id, item.id),
                0,
                downloader,
                { item },
                onProgress = { updates.add(it) },
            )
        assertEquals(SeasonDownloadProgress(total = 1, queued = 1), progress)
        assertEquals(listOf(0, 1), updates.map { it.processed })
        assertEquals(1, downloader.calls.size)
    }

    @Test
    fun metadataAndEnqueueFailuresDoNotStopLaterEpisodes() = runBlocking {
        val items = List(4) { episode() }
        val downloader = FakeDownloader().apply { failId = items[1].id }
        val progress =
            queueSeasonDownloads(
                items.map { it.id },
                0,
                downloader,
                { id ->
                    if (id == items[0].id) throw IllegalStateException("Unavailable episode")
                    items.first { it.id == id }
                },
            )
        assertEquals(SeasonDownloadProgress(total = 4, queued = 2, failed = 2), progress)
        assertEquals(items.drop(1).map { it.id }, downloader.calls.map { it.first })
    }

    @Test
    fun unavailableOrForbiddenEpisodesAreSkippedAndMissingSourceIsReported() = runBlocking {
        val items =
            listOf(
                episode().copy(missing = true),
                episode().copy(canDownload = false),
                episode().copy(sources = emptyList()),
            )
        val downloader = FakeDownloader()
        val progress =
            queueSeasonDownloads(
                items.map { it.id },
                0,
                downloader,
                { id -> items.first { it.id == id } },
            )
        assertEquals(SeasonDownloadProgress(total = 3, skipped = 2, failed = 1), progress)
        assertTrue(downloader.calls.isEmpty())
    }

    @Test
    fun cancellationStopsBatchWithoutQueuingRemainingEpisodes() = runBlocking {
        val items = List(2) { episode() }
        val downloader = FakeDownloader()
        try {
            queueSeasonDownloads(
                items.map { it.id },
                0,
                downloader,
                { throw CancellationException("Stopped") },
            )
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertTrue(downloader.calls.isEmpty())
        }
    }

    @Test
    fun serverSwitchDuringMetadataFetchStopsBeforeEnqueue() = runBlocking {
        val item = episode()
        val downloader = FakeDownloader()
        var sessionValid = true
        try {
            queueSeasonDownloads(
                listOf(item.id),
                0,
                downloader,
                loadEpisode = {
                    sessionValid = false
                    item
                },
                checkSession = { check(sessionValid) },
            )
            fail("Changed session must stop the queue")
        } catch (_: IllegalStateException) {
            assertTrue(downloader.calls.isEmpty())
        }
    }

    private class FakeDownloader : Downloader {
        val calls = mutableListOf<Triple<UUID, String, Int>>()
        var status = DownloadManager.STATUS_PENDING
        var failId: UUID? = null

        override suspend fun downloadItem(
            item: FindroidItem,
            sourceId: String,
            storageIndex: Int,
        ): Pair<Long, UiText?> {
            calls.add(Triple(item.id, sourceId, storageIndex))
            return Pair(if (item.id == failId) -1L else 1L, null)
        }

        override suspend fun getProgress(downloadId: Long?) = Pair(status, 0)

        override suspend fun cancelDownload(item: FindroidItem, downloadId: Long) = Unit

        override suspend fun deleteItem(item: FindroidItem, source: FindroidSource) = Unit
    }
}
