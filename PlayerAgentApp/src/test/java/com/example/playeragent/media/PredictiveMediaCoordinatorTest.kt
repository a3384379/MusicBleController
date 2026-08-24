package com.example.playeragent.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

class PredictiveMediaCoordinatorTest {
    private var nowMs = 1_000L
    private val resolver = PredictionSourceResolver(nowElapsedMs = { nowMs }, ttlMs = 500L)

    @Test
    fun activeQueueIdentityCreatesConfirmedNextAndPreviousCandidates() {
        val snapshot = queueSnapshot()

        val next = resolver.resolve(
            snapshot,
            currentTrack(),
            PredictionDirection.NEXT,
            PredictionSource.MEDIA_SESSION_QUEUE
        )
        val previous = resolver.resolve(
            snapshot,
            currentTrack(),
            PredictionDirection.PREVIOUS,
            PredictionSource.MEDIA_SESSION_QUEUE
        )

        assertEquals(PredictionConfidence.CONFIRMED, next?.confidence)
        assertEquals("media-next", next?.track?.mediaId)
        assertEquals(PredictionConfidence.CONFIRMED, previous?.confidence)
        assertEquals("media-previous", previous?.track?.mediaId)
    }

    @Test
    fun uniqueStableCurrentMappingWithoutActiveIdIsStrong() {
        val snapshot = queueSnapshot().copy(activeQueueItemId = -1L)

        val candidate = resolver.resolve(
            snapshot,
            currentTrack(),
            PredictionDirection.NEXT,
            PredictionSource.MANUAL_NEXT_WITH_QUEUE
        )

        assertEquals(PredictionConfidence.STRONG, candidate?.confidence)
    }

    @Test
    fun ambiguousOrMismatchedCurrentQueueIdentityIsRejected() {
        val mismatch = queueSnapshot().copy(
            items = queueSnapshot().items.map { item ->
                if (item.queueItemId == 20L) {
                    item.copy(track = item.track.copy(title = "Different"))
                } else {
                    item
                }
            }
        )

        assertNull(
            resolver.resolve(
                mismatch,
                currentTrack(),
                PredictionDirection.NEXT,
                PredictionSource.MEDIA_SESSION_QUEUE
            )
        )
    }

    @Test
    fun targetWithoutStableIdIsWeakAndLocalOnly() {
        val items = queueSnapshot().items.toMutableList()
        items[2] = items[2].copy(
            queueItemId = -1L,
            mediaId = "",
            track = items[2].track.copy(trackId = "", mediaId = "")
        )

        val candidate = resolver.resolve(
            queueSnapshot().copy(items = items),
            currentTrack(),
            PredictionDirection.NEXT,
            PredictionSource.MEDIA_SESSION_QUEUE
        )

        assertEquals(PredictionConfidence.WEAK, candidate?.confidence)
    }

    @Test
    fun hotSetCapacityIsTwoAndUsesLruEviction() {
        val hotSet = PredictiveHotSet(capacity = 2, nowElapsedMs = { nowMs })
        val first = candidate("first", track("one", "media-one"))
        val second = candidate("second", track("two", "media-two"))
        val third = candidate("third", track("three", "media-three"))

        assertNull(hotSet.upsert(first))
        assertNull(hotSet.upsert(second))
        assertEquals(first, hotSet.get("first"))
        assertEquals(second, hotSet.upsert(third))
        assertEquals(listOf("first", "third"), hotSet.snapshot().map { it.candidateKey })
    }

    @Test
    fun hotSetExpiresOnMonotonicClock() {
        val hotSet = PredictiveHotSet(capacity = 2, nowElapsedMs = { nowMs })
        hotSet.upsert(candidate("expiring", track("one", "media-one")))

        nowMs = 1_501L

        assertTrue(hotSet.snapshot().isEmpty())
    }

    @Test
    fun duplicateCandidateKeepsReadyPrewarmMetadata() {
        val hotSet = PredictiveHotSet(capacity = 2, nowElapsedMs = { nowMs })
        val ready = candidate("same", track("one", "media-one")).copy(
            lyricsReady = true,
            lyricFingerprint = "fingerprint",
            state = PredictionState.LYRICS_READY
        )
        hotSet.upsert(ready)

        hotSet.upsert(candidate("same", track("one", "media-one")))

        assertTrue(hotSet.get("same")?.lyricsReady == true)
        assertEquals("fingerprint", hotSet.get("same")?.lyricFingerprint)
    }

    @Test
    fun exactReadyCandidatePromotesExistingCachePayload() {
        val coordinator = coordinator()
        val predicted = queueCandidate(track("next", "media-next"))
        coordinator.onCandidate(predicted)

        val result = coordinator.promote(track("next", "media-next"))

        assertEquals(1, result?.payload?.lines?.size)
        assertEquals(1L, coordinator.metricsSnapshot().applyHitCount)
        coordinator.close()
    }

    @Test
    fun identityMismatchRejectsCandidateAndFallsBackCold() {
        val coordinator = coordinator()
        coordinator.onCandidate(queueCandidate(track("next", "media-next")))

        val result = coordinator.promote(track("other", "media-other"))

        assertNull(result)
        assertEquals(1L, coordinator.metricsSnapshot().identityMismatchCount)
        assertTrue(coordinator.hotSetSnapshot().isEmpty())
        coordinator.close()
    }

    @Test
    fun weakCandidateCanPrewarmButNeverPromotes() {
        val coordinator = coordinator()
        coordinator.onCandidate(
            queueCandidate(track("next", "media-next")).copy(
                confidence = PredictionConfidence.WEAK
            )
        )

        assertNull(coordinator.promote(track("next", "media-next")))
        assertEquals(1L, coordinator.metricsSnapshot().preloadSuccessCount)
        assertEquals(0L, coordinator.metricsSnapshot().identityMismatchCount)
        coordinator.close()
    }

    @Test
    fun powerPolicyRejectsPrewarmWithoutRunningLoader() {
        var loaderCalled = false
        val coordinator = PredictiveMediaCoordinator(
            logger = {},
            executor = DirectExecutorService(),
            allowPrewarm = { false },
            prewarmer = { _, _ ->
                loaderCalled = true
                PredictionPrewarmResult(lyricsReady = true)
            },
            promoter = { null },
            nowElapsedMs = { nowMs }
        )

        coordinator.onCandidate(queueCandidate(track("next", "media-next")))

        assertFalse(loaderCalled)
        assertEquals(1L, coordinator.metricsSnapshot().rejectedCount)
        coordinator.close()
    }

    @Test
    fun candidateStoresMetadataOnlyAndPromotionReadsPayloadOnDemand() {
        var promotionReads = 0
        val coordinator = coordinator(onPromotion = { promotionReads += 1 })
        coordinator.onCandidate(queueCandidate(track("next", "media-next")))

        assertTrue(coordinator.hotSetSnapshot().single().lyricsReady)
        assertEquals(0, promotionReads)
        coordinator.promote(track("next", "media-next"))
        assertEquals(1, promotionReads)
        coordinator.close()
    }

    @Test
    fun sameTargetDeduplicatesAcrossObservationSources() {
        val first = resolver.resolve(
            queueSnapshot(),
            currentTrack(),
            PredictionDirection.NEXT,
            PredictionSource.MEDIA_SESSION_QUEUE
        )
        val second = resolver.resolve(
            queueSnapshot(),
            currentTrack(),
            PredictionDirection.NEXT,
            PredictionSource.MANUAL_NEXT_WITH_QUEUE
        )

        assertEquals(first?.candidateKey, second?.candidateKey)
    }

    @Test
    fun repeatedObservationDoesNotRepeatPrewarmWork() {
        var prewarmCalls = 0
        val coordinator = PredictiveMediaCoordinator(
            logger = {},
            executor = DirectExecutorService(),
            prewarmer = { _, _ ->
                prewarmCalls += 1
                PredictionPrewarmResult(
                    lyricsReady = true,
                    lyricFingerprint = "fingerprint",
                    source = "QRC"
                )
            },
            promoter = { null },
            nowElapsedMs = { nowMs }
        )
        val candidate = queueCandidate(track("next", "media-next"))

        coordinator.onCandidate(candidate)
        coordinator.onCandidate(candidate.copy(source = PredictionSource.MANUAL_NEXT_WITH_QUEUE))

        assertEquals(1, prewarmCalls)
        assertEquals(1L, coordinator.metricsSnapshot().candidateCount)
        coordinator.close()
    }

    @Test
    fun fingerprintChangeInvalidatesPromotionAndPreservesColdFallback() {
        val coordinator = PredictiveMediaCoordinator(
            logger = {},
            executor = DirectExecutorService(),
            prewarmer = { _, _ ->
                PredictionPrewarmResult(
                    lyricsReady = true,
                    lyricFingerprint = "old-fingerprint",
                    source = "QRC"
                )
            },
            promoter = {
                PredictionPromotionPayload(
                    lines = listOf(LyricManager.LyricLine(0L, "line")),
                    source = "QRC",
                    lyricFingerprint = "new-fingerprint"
                )
            },
            nowElapsedMs = { nowMs }
        )
        coordinator.onCandidate(queueCandidate(track("next", "media-next")))

        assertNull(coordinator.promote(track("next", "media-next")))
        assertEquals(1L, coordinator.metricsSnapshot().applyMissCount)
        assertTrue(coordinator.hotSetSnapshot().isEmpty())
        coordinator.close()
    }

    @Test
    fun candidateExpiringDuringPrewarmIsCancelledAndCounted() {
        val coordinator = PredictiveMediaCoordinator(
            logger = {},
            executor = DirectExecutorService(),
            prewarmer = { _, _ ->
                nowMs = 2_000L
                PredictionPrewarmResult(
                    lyricsReady = true,
                    lyricFingerprint = "fingerprint",
                    source = "QRC"
                )
            },
            promoter = { null },
            nowElapsedMs = { nowMs }
        )
        coordinator.onCandidate(queueCandidate(track("next", "media-next")))

        assertTrue(coordinator.hotSetSnapshot().isEmpty())
        assertEquals(1L, coordinator.metricsSnapshot().expiredCount)
        coordinator.close()
    }

    @Test
    fun cacheMissRemovesCandidateWithoutBlockingColdPath() {
        val coordinator = PredictiveMediaCoordinator(
            logger = {},
            executor = DirectExecutorService(),
            prewarmer = { _, _ -> PredictionPrewarmResult(lyricsReady = false, reason = "miss") },
            promoter = { null },
            nowElapsedMs = { nowMs }
        )
        coordinator.onCandidate(queueCandidate(track("next", "media-next")))

        assertTrue(coordinator.hotSetSnapshot().isEmpty())
        assertNull(coordinator.promote(track("next", "media-next")))
        assertEquals(1L, coordinator.metricsSnapshot().preloadFailedCount)
        coordinator.close()
    }

    private fun coordinator(onPromotion: () -> Unit = {}): PredictiveMediaCoordinator {
        return PredictiveMediaCoordinator(
            logger = {},
            executor = DirectExecutorService(),
            prewarmer = { _, cancelled ->
                assertFalse(cancelled())
                PredictionPrewarmResult(
                    lyricsReady = true,
                    lyricFingerprint = "fingerprint",
                    lineCount = 1,
                    hasWordTiming = true,
                    source = "QRC"
                )
            },
            promoter = {
                onPromotion()
                PredictionPromotionPayload(
                    lines = listOf(LyricManager.LyricLine(0L, "line")),
                    source = "QRC",
                    lyricFingerprint = "fingerprint"
                )
            },
            nowElapsedMs = { nowMs }
        )
    }

    private fun queueSnapshot(): PredictionQueueSnapshot {
        return PredictionQueueSnapshot(
            activeQueueItemId = 20L,
            items = listOf(
                PredictionQueueItem(10L, "media-previous", track("previous", "media-previous")),
                PredictionQueueItem(20L, "media-current", currentTrack()),
                PredictionQueueItem(30L, "media-next", track("next", "media-next"))
            )
        )
    }

    private fun currentTrack(): PredictiveMediaTrack = track("current", "media-current")

    private fun track(name: String, mediaId: String): PredictiveMediaTrack {
        return PredictiveMediaTrack(
            trackId = "track-$name",
            songKey = "$name|artist|album",
            title = name,
            artist = "artist",
            album = "album",
            durationMs = 180_000L,
            mediaId = mediaId
        )
    }

    private fun queueCandidate(track: PredictiveMediaTrack): PredictionCandidate {
        return candidate("candidate-${track.mediaId}", track)
    }

    private fun candidate(key: String, track: PredictiveMediaTrack): PredictionCandidate {
        return PredictionCandidate(
            candidateKey = key,
            source = PredictionSource.MEDIA_SESSION_QUEUE,
            confidence = PredictionConfidence.CONFIRMED,
            direction = PredictionDirection.NEXT,
            track = track,
            queueItemId = 30L,
            createdElapsedMs = nowMs,
            expiresElapsedMs = nowMs + 500L
        )
    }

    private class DirectExecutorService : AbstractExecutorService() {
        private var shutdown = false

        override fun execute(command: Runnable) = command.run()
        override fun shutdown() {
            shutdown = true
        }
        override fun shutdownNow(): MutableList<Runnable> {
            shutdown = true
            return mutableListOf()
        }
        override fun isShutdown(): Boolean = shutdown
        override fun isTerminated(): Boolean = shutdown
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = shutdown
    }
}
