package com.example.playeragent.ui

data class SonyPlayerUiState(
    val service: ServiceState = ServiceState(),
    val playback: PlaybackState = PlaybackState(),
    val lyrics: LyricsState = LyricsState(),
    val maintenance: MaintenanceState = MaintenanceState()
) {
    data class ServiceState(
        val running: Boolean = false,
        val bleHealthy: Boolean = false,
        val connectedDevices: Int = 0
    )

    data class PlaybackState(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val playing: Boolean = false
    )

    data class LyricsState(
        val text: String = "",
        val rawStatus: String = "",
        val displayStatus: String = lyricStatusText(rawStatus),
        val needsRelink: Boolean = lyricNeedsRelink(rawStatus)
    )

    data class MaintenanceState(
        val busy: Boolean = false,
        val label: String = ""
    )

    companion object {
        fun lyricStatusText(status: String): String {
            val value = status.lowercase()
            return when {
                value.isBlank() || value == "unknown" -> "等待播放状态"
                "ready" in value || "loaded" in value || "sent" in value -> "歌词已就绪"
                "loading" in value || "parsing" in value -> "正在解析歌词"
                "waiting" in value || "cache" in value || "cooldown" in value ->
                    "等待 QQ 音乐生成歌词缓存"
                "ambiguous" in value || "no safe" in value ->
                    "发现歌词文件，但无法安全关联"
                "not found" in value || "no lyric" in value -> "当前歌曲暂无歌词"
                "failed" in value || "error" in value || "decrypt" in value -> "歌词解析失败"
                else -> "歌词状态更新中"
            }
        }

        fun lyricNeedsRelink(status: String): Boolean {
            val value = status.lowercase()
            return "ambiguous" in value || "no safe" in value || "failed" in value
        }
    }
}
