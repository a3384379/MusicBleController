import Foundation

struct ArtworkCacheDiagnostic: Equatable, Identifiable {
    var id: String { quality }
    let quality: String
    let exists: Bool
    let bytes: Int
    let pixelWidth: Int
    let pixelHeight: Int
    let isPlaceholder: Bool
    let modifiedAt: Date?
    let path: String

    static func missing(quality: String, path: String = "-") -> ArtworkCacheDiagnostic {
        ArtworkCacheDiagnostic(
            quality: quality,
            exists: false,
            bytes: 0,
            pixelWidth: 0,
            pixelHeight: 0,
            isPlaceholder: false,
            modifiedAt: nil,
            path: path
        )
    }
}

struct ConnectionDiagnosticSnapshot: Equatable {
    let connectionStatus: String
    let displayState: String
    let healthState: String
    let autoReconnectState: String
    let autoReconnectAttempt: Int
    let mtuBytes: Int
    let lastNotifyAgeMs: Int64
    let peripheralState: String
    let characteristicReady: Bool
    let probeInFlight: Bool
    let lastHardReconnectReason: String
}

struct NowPlayingDiagnosticSnapshot {
    let generatedAt: Date
    let title: String
    let artist: String
    let album: String
    let trackId: String
    let albumArtId: String
    let albumArtDisplayQuality: String
    let displayArtworkPixelWidth: Int
    let displayArtworkPixelHeight: Int
    let artworkEnhancementStatus: ArtworkEnhancementDebugStatus
    let artworkCaches: [ArtworkCacheDiagnostic]
    let isPlaying: Bool
    let positionMs: Int64
    let durationMs: Int64
    let currentLyric: String
    let lyricDiagnostic: LyricDiagnostic?
    let fullLyricsLineCount: Int
    let isFullLyricsCurrent: Bool
    let isFullLyricsReceiving: Bool
    let connection: ConnectionDiagnosticSnapshot

    var diagnosticText: String {
        var lines: [String] = []
        lines.append("Now Playing Diagnostics")
        lines.append("generatedAt: \(Self.dateTimeFormatter.string(from: generatedAt))")
        lines.append("")
        lines.append("[Track]")
        lines.append("title: \(title)")
        lines.append("artist: \(artist)")
        lines.append("album: \(album)")
        lines.append("trackId: \(trackId)")
        lines.append("albumArtId: \(albumArtId)")
        lines.append("playing: \(isPlaying)")
        lines.append("position: \(Self.formatDuration(positionMs)) / \(Self.formatDuration(durationMs))")
        lines.append("currentLyric: \(currentLyric)")
        lines.append("")
        lines.append("[Lyrics]")
        if let lyricDiagnostic {
            lines.append("status: \(lyricDiagnostic.status)")
            lines.append("statusTitle: \(lyricDiagnostic.statusTitle)")
            lines.append("reason: \(lyricDiagnostic.reason)")
            lines.append("humanReason: \(lyricDiagnostic.humanReadableReason)")
            lines.append("suggestion: \(lyricDiagnostic.suggestion)")
            lines.append("suggestionText: \(lyricDiagnostic.suggestionText)")
            lines.append("source: \(lyricDiagnostic.source)")
            lines.append("lines: \(lyricDiagnostic.lines)")
            lines.append("retryCount: \(lyricDiagnostic.retryCount)")
            lines.append("waitingQqMusicCache: \(lyricDiagnostic.waitingQqMusicCache)")
        } else {
            lines.append("diagnostic: nil")
        }
        lines.append("fullLyricsLineCount: \(fullLyricsLineCount)")
        lines.append("isFullLyricsCurrent: \(isFullLyricsCurrent)")
        lines.append("isFullLyricsReceiving: \(isFullLyricsReceiving)")
        lines.append("")
        lines.append("[Artwork]")
        lines.append("displayQuality: \(albumArtDisplayQuality)")
        lines.append("displayPixels: \(displayArtworkPixelWidth)x\(displayArtworkPixelHeight)")
        lines.append("enhancementEnabled: \(artworkEnhancementStatus.enabled)")
        lines.append("enhancementTarget: \(artworkEnhancementStatus.target)")
        lines.append("enhancementSharpness: \(String(format: "%.2f", artworkEnhancementStatus.sharpness))")
        lines.append("enhancementLastCostMs: \(artworkEnhancementStatus.lastProcessingCostMs)")
        lines.append("enhancementEdgeGain: \(String(format: "%.2f", artworkEnhancementStatus.lastEdgeGainPercent))")
        lines.append("enhancementMessage: \(artworkEnhancementStatus.lastMessage)")
        for cache in artworkCaches {
            lines.append(
                "\(cache.quality): exists=\(cache.exists) bytes=\(cache.bytes) " +
                    "pixels=\(cache.pixelWidth)x\(cache.pixelHeight) " +
                    "placeholder=\(cache.isPlaceholder) modified=\(Self.optionalDate(cache.modifiedAt))"
            )
            lines.append("  path: \(cache.path)")
        }
        lines.append("")
        lines.append("[Connection]")
        lines.append("status: \(connection.connectionStatus)")
        lines.append("displayState: \(connection.displayState)")
        lines.append("healthState: \(connection.healthState)")
        lines.append("autoReconnectState: \(connection.autoReconnectState)")
        lines.append("autoReconnectAttempt: \(connection.autoReconnectAttempt)")
        lines.append("mtuBytes: \(connection.mtuBytes)")
        lines.append("lastNotifyAgeMs: \(connection.lastNotifyAgeMs)")
        lines.append("peripheralState: \(connection.peripheralState)")
        lines.append("characteristicReady: \(connection.characteristicReady)")
        lines.append("probeInFlight: \(connection.probeInFlight)")
        lines.append("lastHardReconnectReason: \(connection.lastHardReconnectReason)")
        return lines.joined(separator: "\n")
    }

    static func formatDuration(_ value: Int64) -> String {
        guard value > 0 else { return "00:00" }
        let totalSeconds = max(0, value / 1_000)
        let minutes = totalSeconds / 60
        let seconds = totalSeconds % 60
        return String(format: "%02lld:%02lld", minutes, seconds)
    }

    static func optionalDate(_ date: Date?) -> String {
        guard let date else { return "-" }
        return dateTimeFormatter.string(from: date)
    }

    private static let dateTimeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return formatter
    }()
}
