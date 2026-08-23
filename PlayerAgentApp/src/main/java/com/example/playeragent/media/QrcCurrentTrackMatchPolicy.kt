package com.example.playeragent.media

import kotlin.math.abs

/**
 * Conservative policy for binding a newly written QQ Music QRC group to the
 * current MediaSession track when producer/metadata sidecars are incomplete.
 *
 * This policy intentionally never accepts an artist-only match. A candidate
 * with no artist is accepted only when its lyric title is an exact normalized
 * match and it is the only recent candidate that can represent the request.
 */
internal object QrcCurrentTrackMatchPolicy {

    data class Track(
        val title: String,
        val artist: String,
        val album: String = "",
        val durationMs: Long = 0L,
        val requestStillValid: Boolean = true
    )

    data class Candidate(
        val groupId: String,
        val metadataTitle: String,
        val metadataArtist: String,
        val lyricTitleCandidate: String,
        val lastLineEndMs: Long,
        val effectiveModifiedAtMs: Long
    )

    data class Decision(
        val candidate: Candidate? = null,
        val reason: String,
        val inferredTitle: String = ""
    ) {
        val matched: Boolean get() = candidate != null
    }

    fun evaluateRecent(
        track: Track,
        candidates: List<Candidate>,
        recentCandidateCount: Int = candidates.size
    ): Decision {
        if (!track.requestStillValid) {
            return Decision(reason = "stale request")
        }
        if (recentCandidateCount <= 0) {
            return Decision(reason = "no recent candidates")
        }
        val trackTitle = QrcLyricUtils.normalizeForMatch(track.title)
        if (trackTitle.isBlank()) {
            return Decision(reason = "empty current title")
        }
        val trackArtists = QrcLyricUtils.splitArtists(track.artist)
        val safeExact = candidates.filter { candidate ->
            val candidateTitle = candidate.metadataTitle
                .ifBlank { candidate.lyricTitleCandidate }
            val titleExact = QrcLyricUtils.normalizeForMatch(candidateTitle) == trackTitle
            val candidateArtists = QrcLyricUtils.splitArtists(candidate.metadataArtist)
            val artistSafe = candidateArtists.isEmpty() ||
                trackArtists.isEmpty() ||
                trackArtists.any(candidateArtists::contains)
            titleExact && artistSafe && durationIsReasonable(track.durationMs, candidate.lastLineEndMs)
        }
        if (safeExact.isEmpty()) {
            return Decision(reason = "no exact title candidate")
        }
        if (safeExact.size != 1) {
            return Decision(reason = "ambiguous recent candidates")
        }
        val candidate = safeExact.single()
        return Decision(
            candidate = candidate,
            reason = if (candidate.metadataArtist.isBlank()) {
                "unique exact lyric title without artist"
            } else {
                "exact title and artist"
            },
            inferredTitle = candidate.metadataTitle.ifBlank { candidate.lyricTitleCandidate }
        )
    }

    fun extractTitleCandidate(lines: List<String>): String {
        return lines.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .take(MAX_TITLE_SCAN_LINES)
            .firstOrNull { line ->
                line.length <= MAX_TITLE_LENGTH &&
                    !CREDIT_LINE_REGEX.containsMatchIn(line) &&
                    !line.startsWith("[") &&
                    !line.startsWith("{")
            }
            .orEmpty()
    }

    fun lastLineEndMs(lines: List<QrcLyricLine>): Long {
        val last = lines.maxByOrNull(QrcLyricLine::timeMs) ?: return 0L
        val wordEnd = last.words.maxOfOrNull { it.startMs + it.durationMs } ?: 0L
        return maxOf(last.timeMs + last.durationMs, wordEnd, last.timeMs)
    }

    private fun durationIsReasonable(trackDurationMs: Long, lyricEndMs: Long): Boolean {
        if (trackDurationMs <= 0L || lyricEndMs <= 0L) {
            return true
        }
        val delta = abs(trackDurationMs - lyricEndMs)
        val ratio = lyricEndMs.toDouble() / trackDurationMs.toDouble()
        return ratio in MIN_DURATION_RATIO..MAX_DURATION_RATIO || delta <= DURATION_TOLERANCE_MS
    }

    private const val MAX_TITLE_SCAN_LINES = 8
    private const val MAX_TITLE_LENGTH = 80
    private const val MIN_DURATION_RATIO = 0.45
    private const val MAX_DURATION_RATIO = 1.35
    private const val DURATION_TOLERANCE_MS = 90_000L
    private val CREDIT_LINE_REGEX = Regex(
        """^(词|作词|曲|作曲|编曲|制作|制作人|演唱|歌手|混音|和声|录音|母带|出品|发行|监制|吉他|贝斯|鼓)\s*[：:]""",
        RegexOption.IGNORE_CASE
    )
}
