import ActivityKit
import Foundation

enum IslandState: String, Codable, Hashable {
    case playing
    case paused
    case buffering
    case connecting
    case trackChanged
    case seeking
    case disconnected

    static func resolved(from rawValue: String) -> IslandState {
        IslandState(rawValue: rawValue) ?? .paused
    }

    var isTransient: Bool {
        self == .trackChanged || self == .seeking
    }
}

enum DynamicIslandStyle: String, Codable, CaseIterable, Identifiable {
    case compactDefault
    case lyricFocused
    case waveformFocused

    var id: String { rawValue }

    var title: String {
        switch self {
        case .compactDefault:
            return "默认"
        case .lyricFocused:
            return "歌词优先"
        case .waveformFocused:
            return "节奏优先"
        }
    }

    static let userDefaultsKey = "dynamicIslandStyle"
    static let defaultStyle: DynamicIslandStyle = .lyricFocused
}

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
        var islandState: String
        var islandStateChangedAt: Date
        var dynamicIslandStyle: String
        var artworkKey: String?
        var artworkRevision: Int

        init(
            trackId: String,
            title: String,
            artist: String,
            lyric: String,
            lyricLineIndex: Int,
            isPlaying: Bool,
            positionAtAnchorMs: Int64,
            anchorDate: Date,
            durationMs: Int64,
            connectionState: String,
            islandState: String = IslandState.paused.rawValue,
            islandStateChangedAt: Date = Date(),
            dynamicIslandStyle: String = DynamicIslandStyle.defaultStyle.rawValue,
            artworkKey: String?,
            artworkRevision: Int
        ) {
            self.trackId = trackId
            self.title = title
            self.artist = artist
            self.lyric = lyric
            self.lyricLineIndex = lyricLineIndex
            self.isPlaying = isPlaying
            self.positionAtAnchorMs = positionAtAnchorMs
            self.anchorDate = anchorDate
            self.durationMs = durationMs
            self.connectionState = connectionState
            self.islandState = islandState
            self.islandStateChangedAt = islandStateChangedAt
            self.dynamicIslandStyle = dynamicIslandStyle
            self.artworkKey = artworkKey
            self.artworkRevision = artworkRevision
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            trackId = try container.decode(String.self, forKey: .trackId)
            title = try container.decode(String.self, forKey: .title)
            artist = try container.decode(String.self, forKey: .artist)
            lyric = try container.decode(String.self, forKey: .lyric)
            lyricLineIndex = try container.decode(Int.self, forKey: .lyricLineIndex)
            isPlaying = try container.decode(Bool.self, forKey: .isPlaying)
            positionAtAnchorMs = try container.decode(Int64.self, forKey: .positionAtAnchorMs)
            anchorDate = try container.decode(Date.self, forKey: .anchorDate)
            durationMs = try container.decode(Int64.self, forKey: .durationMs)
            connectionState = try container.decode(String.self, forKey: .connectionState)
            islandState = try container.decodeIfPresent(String.self, forKey: .islandState) ??
                (isPlaying ? IslandState.playing.rawValue : IslandState.paused.rawValue)
            islandStateChangedAt = try container.decodeIfPresent(Date.self, forKey: .islandStateChangedAt) ??
                anchorDate
            dynamicIslandStyle = try container.decodeIfPresent(String.self, forKey: .dynamicIslandStyle) ??
                DynamicIslandStyle.defaultStyle.rawValue
            artworkKey = try container.decodeIfPresent(String.self, forKey: .artworkKey)
            artworkRevision = try container.decode(Int.self, forKey: .artworkRevision)
        }
    }

    var name: String
}
