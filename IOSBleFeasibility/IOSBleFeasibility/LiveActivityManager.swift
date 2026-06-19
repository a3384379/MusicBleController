import ActivityKit
import Foundation
import UIKit

@MainActor
final class LiveActivityManager {
    static let shared = LiveActivityManager()

    private var activity: Activity<SonyMusicActivityAttributes>?
    private var lastProgressUpdateAt = Date.distantPast
    private var lastState: SonyMusicActivityAttributes.ContentState?

    private init() {}

    func update(
        title: String,
        artist: String,
        album: String,
        lyric: String,
        isPlaying: Bool,
        positionMs: Int64,
        durationMs: Int64,
        trackId: String,
        albumArtImage: UIImage?,
        reason: String = "unknown",
        force: Bool = false,
        logger: ((String) -> Void)? = nil
    ) {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else {
            logger?("[LiveActivity] unsupported")
            return
        }

        let cleanTitle = cleaned(title, fallback: "Sony Music")
        let cleanArtist = cleaned(artist, fallback: "未知歌手")
        let cleanAlbum = cleaned(album, fallback: "")
        let cleanTrackId = trackId.isEmpty ? "\(cleanTitle)|\(cleanArtist)|\(cleanAlbum)" : trackId
        let cleanLyric = lyric.trimmingCharacters(in: .whitespacesAndNewlines)

        let state = SonyMusicActivityAttributes.ContentState(
            title: cleanTitle,
            artist: cleanArtist,
            album: cleanAlbum,
            lyric: cleanLyric.isEmpty ? "暂无歌词" : cleanLyric,
            isPlaying: isPlaying,
            positionMs: max(positionMs, 0),
            durationMs: max(durationMs, 0),
            trackId: cleanTrackId,
            albumArtCacheKey: nil,
            albumArtThumbnailBase64: makeThumbnailBase64(from: albumArtImage)
        )

        logger?(
            "[LiveActivity] update request reason=\(reason) " +
                "title=\(state.title) lyric=\(state.lyric) trackId=\(state.trackId)"
        )

        let now = Date()
        if let previous = lastState {
            if previous == state {
                logger?("[LiveActivity] update skipped reason=sameState")
                return
            }

            let criticalChanged = force || hasCriticalChange(previous: previous, current: state)
            if !criticalChanged && now.timeIntervalSince(lastProgressUpdateAt) < 5 {
                logger?(
                    "[LiveActivity] update skipped reason=throttled " +
                        "reasonType=\(reason)"
                )
                return
            }
        }

        if activity == nil {
            logger?("[LiveActivity] activity missing, start requested")
            start(state: state, logger: logger)
            return
        }

        Task {
            await activity?.update(ActivityContent(state: state, staleDate: nil))
            lastState = state
            lastProgressUpdateAt = now
            logger?(
                "[LiveActivity] update sent reason=\(reason) " +
                    "title=\(state.title) lyric=\(state.lyric) position=\(state.positionMs)"
            )
        }
    }

    func end(logger: ((String) -> Void)? = nil) {
        guard let activity else { return }
        let state = lastState
        self.activity = nil
        Task {
            if let state {
                await activity.end(
                    ActivityContent(state: state, staleDate: nil),
                    dismissalPolicy: .after(Date().addingTimeInterval(60))
                )
            } else {
                await activity.end(nil, dismissalPolicy: .immediate)
            }
            logger?("[LiveActivity] end")
        }
    }

    private func start(
        state: SonyMusicActivityAttributes.ContentState,
        logger: ((String) -> Void)?
    ) {
        do {
            let attributes = SonyMusicActivityAttributes(name: "Sony Music")
            activity = try Activity.request(
                attributes: attributes,
                content: ActivityContent(state: state, staleDate: nil),
                pushType: nil
            )
            lastState = state
            lastProgressUpdateAt = Date()
            logger?("[LiveActivity] start")
        } catch {
            logger?("[LiveActivity] error=\(error.localizedDescription)")
        }
    }

    private func cleaned(_ value: String, fallback: String) -> String {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty || trimmed == "-" {
            return fallback
        }
        return trimmed
    }

    private func makeThumbnailBase64(from image: UIImage?) -> String? {
        guard let image else { return nil }

        let targetSize = CGSize(width: 96, height: 96)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true

        let renderer = UIGraphicsImageRenderer(size: targetSize, format: format)
        let thumbnail = renderer.image { _ in
            UIColor.black.setFill()
            UIBezierPath(rect: CGRect(origin: .zero, size: targetSize)).fill()

            let sourceSize = image.size
            guard sourceSize.width > 0, sourceSize.height > 0 else { return }

            let scale = max(targetSize.width / sourceSize.width, targetSize.height / sourceSize.height)
            let drawSize = CGSize(width: sourceSize.width * scale, height: sourceSize.height * scale)
            let drawOrigin = CGPoint(
                x: (targetSize.width - drawSize.width) / 2,
                y: (targetSize.height - drawSize.height) / 2
            )
            image.draw(in: CGRect(origin: drawOrigin, size: drawSize))
        }

        return thumbnail.jpegData(compressionQuality: 0.58)?.base64EncodedString()
    }

    private func hasCriticalChange(
        previous: SonyMusicActivityAttributes.ContentState,
        current: SonyMusicActivityAttributes.ContentState
    ) -> Bool {
        previous.trackId != current.trackId ||
            previous.title != current.title ||
            previous.artist != current.artist ||
            previous.album != current.album ||
            previous.lyric != current.lyric ||
            previous.isPlaying != current.isPlaying ||
            previous.durationMs != current.durationMs ||
            previous.albumArtCacheKey != current.albumArtCacheKey ||
            previous.albumArtThumbnailBase64 != current.albumArtThumbnailBase64
    }
}
