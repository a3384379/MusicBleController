import ActivityKit
import Foundation

struct SonyMusicActivityAttributes: ActivityAttributes {
    public struct ContentState: Codable, Hashable {
        var trackId: String
        var title: String
        var artist: String
        var lyric: String
        var lyricLineIndex: Int
        var isPlaying: Bool
        var positionAtAnchorMs: Int64
        var anchorDate: Date
        var durationMs: Int64
        var connectionState: String
        var artworkKey: String?
        var artworkRevision: Int
    }

    var name: String
}
