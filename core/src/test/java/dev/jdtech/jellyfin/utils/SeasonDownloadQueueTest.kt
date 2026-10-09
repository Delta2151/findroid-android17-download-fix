package dev.jdtech.jellyfin.utils

import android.app.DownloadManager
import dev.jdtech.jellyfin.core.presentation.dummy.dummyEpisode
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.work.SeasonDownloadWorker.Companion.decodeEpisodeIds
import dev.jdtech.jellyfin.work.SeasonDownloadWorker.Companion.encodeEpisodeIds
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

    private fun local(item: FindroidEpisode, complete: Boolean = false) =
        item.sources
            .first()
            .copy(
                id = "local-source",
                type = FindroidSourceType.LOCAL,
                path = if (complete) "episode.mkv" else "episode.download",
                downloadId = 42L,
            )

    private suspend fun batch(
        items: List<FindroidEpisode>,
        downloader: FakeDownloader,
        size: Int = 2,
        previous: SeasonDownloadBatch? = null,
    ) =
        queueSeasonDownloadBatch(
            episodeIds = previous?.remainingIds ?: items.map { it.id },
            storageIndex = 2,
            downloader = downloader,
            loadEpisode = { id -> items.first { it.id == id } },
            batchSize = size,
            waitingDownloadIds = previous?.waitingDownloadIds.orEmpty(),
            initialProgress = previous?.progress ?: SeasonDownloadProgress(total = items.size),
        )

    @Test
    fun startsOnlyConfiguredBatchAndWaitsForEveryMember() = runBlocking {
        val items = List(5) { episode() }
        val downloader = FakeDownloader()
        val first = batch(items, downloader)
        assertEquals(items.take(2).map { it.id }, downloader.calls.map { it.first })
        assertEquals(3, first.remainingIds.size)
        downloader.statuses[first.waitingDownloadIds.first()] = DownloadManager.STATUS_SUCCESSFUL
        val waiting = batch(items, downloader, previous = first)
        assertEquals(first, waiting)
        assertEquals(2, downloader.calls.size)
        downloader.statuses[first.waitingDownloadIds.last()] = DownloadManager.STATUS_SUCCESSFUL
        val second = batch(items, downloader, previous = waiting)
        assertEquals(items.take(4).map { it.id }, downloader.calls.map { it.first })
        assertEquals(4, second.progress.queued)
        second.waitingDownloadIds.forEach {
            downloader.statuses[it] = DownloadManager.STATUS_SUCCESSFUL
        }
        val third = batch(items, downloader, previous = second)
        assertEquals(5, third.progress.queued)
        assertTrue(third.remainingIds.isEmpty())
        assertTrue(!third.complete)
        third.waitingDownloadIds.forEach {
            downloader.statuses[it] = DownloadManager.STATUS_SUCCESSFUL
        }
        assertTrue(batch(items, downloader, previous = third).complete)
    }

    @Test
    fun sequentialSettingNeverStartsSecondEpisodeEarly() = runBlocking {
        val items = List(3) { episode() }
        val downloader = FakeDownloader()
        val first = batch(items, downloader, size = 1)
        assertEquals(1, downloader.calls.size)
        assertEquals(first, batch(items, downloader, size = 1, previous = first))
        downloader.statuses[first.waitingDownloadIds.single()] = DownloadManager.STATUS_SUCCESSFUL
        assertEquals(2, batch(items, downloader, size = 1, previous = first).progress.queued)
    }

    @Test
    fun pendingRunningAndRetryingDownloadsAllBlockNextBatch() = runBlocking {
        val items = List(3) { episode() }
        val downloader = FakeDownloader()
        val first = batch(items, downloader)
        for (status in
            listOf(
                DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_RUNNING,
                DownloadManager.STATUS_PAUSED,
            )) {
            first.waitingDownloadIds.forEach { downloader.statuses[it] = status }
            assertEquals(first, batch(items, downloader, previous = first))
            assertEquals(2, downloader.calls.size)
        }
    }

    @Test
    fun terminalFailureOrCancellationDoesNotBlockRemainingSeason() = runBlocking {
        val items = List(3) { episode() }
        val downloader = FakeDownloader()
        val first = batch(items, downloader)
        first.waitingDownloadIds.forEach { downloader.statuses[it] = DownloadManager.STATUS_FAILED }
        val next = batch(items, downloader, previous = first)
        assertEquals(3, next.progress.queued)
        assertEquals(items.last().id, downloader.calls.last().first)
    }

    @Test
    fun existingActiveDownloadOccupiesBatchSlotAndCompletedEpisodesAreSkipped() = runBlocking {
        val downloaded = episode().let { it.copy(sources = listOf(local(it, complete = true))) }
        val active = episode().let { it.copy(sources = listOf(local(it)) + it.sources) }
        val items = listOf(downloaded, active, episode(), episode())
        val downloader = FakeDownloader()
        val result = batch(items, downloader)
        assertEquals(SeasonDownloadProgress(total = 4, queued = 1, skipped = 2), result.progress)
        assertEquals(1, result.remainingIds.size)
        assertEquals(2, result.waitingDownloadIds.size)
        assertEquals(items[2].id, downloader.calls.single().first)
    }

    @Test
    fun failedPartialUsesRemoteSourceAndSelectedStorage() = runBlocking {
        val item = episode().let { it.copy(sources = listOf(local(it)) + it.sources) }
        val downloader = FakeDownloader().apply { statuses[42] = DownloadManager.STATUS_FAILED }
        assertEquals(1, batch(listOf(item), downloader).progress.queued)
        assertEquals(listOf(Triple(item.id, "remote-source", 2)), downloader.calls)
    }

    @Test
    fun episodeZeroVirtualAndForbiddenEpisodesAreNeverDownloaded() = runBlocking {
        val items =
            listOf(
                episode().copy(indexNumber = 0),
                episode().copy(missing = true),
                episode().copy(canDownload = false),
                episode(),
            )
        val downloader = FakeDownloader()
        assertEquals(
            SeasonDownloadProgress(total = 4, queued = 1, skipped = 3),
            batch(items, downloader).progress,
        )
        assertEquals(items.last().id, downloader.calls.single().first)
    }

    @Test
    fun deduplicatesIdsAndReportsEachProcessedEpisode() = runBlocking {
        val item = episode()
        val downloader = FakeDownloader()
        val updates = mutableListOf<SeasonDownloadProgress>()
        val result =
            queueSeasonDownloadBatch(
                listOf(item.id, item.id),
                0,
                downloader,
                { item },
                2,
                onProgress = { updates.add(it) },
            )
        assertEquals(SeasonDownloadProgress(total = 1, queued = 1), result.progress)
        assertEquals(listOf(0, 1), updates.map { it.processed })
        assertEquals(1, downloader.calls.size)
    }

    @Test
    fun metadataAndEnqueueFailuresDoNotStopLaterEpisodes() = runBlocking {
        val items = List(4) { episode() }
        val downloader = FakeDownloader().apply { failId = items[1].id }
        val result =
            queueSeasonDownloadBatch(
                items.map { it.id },
                0,
                downloader,
                { id ->
                    if (id == items[0].id) throw IllegalStateException("Unavailable")
                    items.first { it.id == id }
                },
                2,
            )
        assertEquals(SeasonDownloadProgress(total = 4, queued = 2, failed = 2), result.progress)
    }

    @Test
    fun missingRemoteSourceIsReported() = runBlocking {
        val result = batch(listOf(episode().copy(sources = emptyList())), FakeDownloader())
        assertEquals(1, result.progress.failed)
        assertTrue(result.complete)
    }

    @Test
    fun cancellationStopsBatchWithoutQueuingMoreEpisodes() = runBlocking {
        val item = episode()
        val downloader = FakeDownloader()
        try {
            queueSeasonDownloadBatch(
                listOf(item.id),
                0,
                downloader,
                { throw CancellationException("Stopped") },
                2,
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
        var valid = true
        try {
            queueSeasonDownloadBatch(
                listOf(item.id),
                0,
                downloader,
                {
                    valid = false
                    item
                },
                2,
                checkSession = { check(valid) },
            )
            fail("Changed session must stop batch")
        } catch (_: IllegalStateException) {
            assertTrue(downloader.calls.isEmpty())
        }
    }

    @Test
    fun savedEpisodePlanRoundTripsEmptyAndLargeSeasonsInOrder() {
        for (size in listOf(0, 18, 500)) {
            val ids = List(size) { UUID.randomUUID() }
            val encoded = encodeEpisodeIds(ids)
            assertEquals(size * 16, encoded.size)
            assertEquals(ids, decodeEpisodeIds(encoded))
        }
    }

    private class FakeDownloader : Downloader {
        val calls = mutableListOf<Triple<UUID, String, Int>>()
        val statuses = mutableMapOf<Long, Int>()
        var failId: UUID? = null

        override suspend fun downloadItem(
            item: FindroidItem,
            sourceId: String,
            storageIndex: Int,
        ): Pair<Long, UiText?> {
            calls.add(Triple(item.id, sourceId, storageIndex))
            return Pair(if (item.id == failId) -1L else 100L + calls.size, null)
        }

        override suspend fun getProgress(downloadId: Long?) =
            Pair(statuses[downloadId] ?: DownloadManager.STATUS_PENDING, 0)

        override suspend fun cancelDownload(item: FindroidItem, downloadId: Long) = Unit

        override suspend fun deleteItem(item: FindroidItem, source: FindroidSource) = Unit
    }
}
