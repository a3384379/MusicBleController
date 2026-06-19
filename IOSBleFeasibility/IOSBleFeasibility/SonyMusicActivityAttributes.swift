import ActivityKit
import Foundation

struct SonyMusicActivityAttributes: ActivityAttributes {
    public struct ContentState: Codable, Hashable {
        var title: String
        var artist: String
        var album: String
        var lyric: String
        var isPlaying: Bool
        var positionMs: Int64
        var durationMs: Int64
        var trackId: String
        var albumArtCacheKey: String?
        var albumArtThumbnailBase64: String?
    }

    var name: String
}
