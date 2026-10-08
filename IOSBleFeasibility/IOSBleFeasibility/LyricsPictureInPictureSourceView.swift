import AVFoundation
import SwiftUI
import UIKit

final class LyricsPictureInPictureSourceUIView: UIView {
    override class var layerClass: AnyClass { AVSampleBufferDisplayLayer.self }
    var displayLayer: AVSampleBufferDisplayLayer { layer as! AVSampleBufferDisplayLayer }
    var becameVisible: (() -> Void)?

    var isVisibleForPictureInPicture: Bool {
        guard let window, !bounds.isEmpty, var frontmost = window.rootViewController else { return false }
        while let presented = frontmost.presentedViewController {
            frontmost = presented
        }
        // A source inside the settings sheet is visible; a source behind that
        // sheet is not. Do not reject all presented view controllers alike.
        guard let visibleRoot = frontmost.viewIfLoaded, isDescendant(of: visibleRoot) else { return false }
        var ancestor: UIView? = self
        while let view = ancestor {
            if view.isHidden || view.alpha <= 0.01 { return false }
            if view.clipsToBounds, !convert(bounds, to: view).intersects(view.bounds) { return false }
            ancestor = view.superview
        }
        return convert(bounds, to: window).intersects(window.bounds)
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window != nil { becameVisible?() }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        if isVisibleForPictureInPicture { becameVisible?() }
    }
}

private struct LyricsPictureInPictureSourceView: UIViewRepresentable {
    let manager: BLETestManager
    let controller: LyricsPictureInPictureController
    let appearance: FloatingLyricsAppearance

    func makeUIView(context: Context) -> LyricsPictureInPictureSourceUIView {
        let view = LyricsPictureInPictureSourceUIView()
        view.displayLayer.videoGravity = .resizeAspect
        view.backgroundColor = appearance.theme.palette.start.uiColor
        view.becameVisible = { [weak controller] in controller?.sourceBecameVisible() }
        controller.attach(view, snapshot: { [weak manager] in
            manager?.makeLyricsPresentationSnapshot() ?? .disconnected
        }, controlsStopped: { [weak manager] in
            manager?.cancelFloatingLyricsPlaybackIntent()
        })
        return view
    }

    func updateUIView(_ view: LyricsPictureInPictureSourceUIView, context: Context) {
        view.backgroundColor = appearance.theme.palette.start.uiColor
    }
}

struct FloatingLyricsSettingsPanel: View {
    let manager: BLETestManager
    let onStart: () -> Void
    @ObservedObject private var controller = LyricsPictureInPictureController.shared
    @ObservedObject private var preferences = PreferencesStore.shared

    private var needsSourcePreview: Bool {
        controller.startRequested || controller.state == .preparing ||
            controller.state == .ready || controller.state == .starting
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if needsSourcePreview {
                LyricsPictureInPictureSourceView(manager: manager, controller: controller,
                                                 appearance: preferences.floatingLyricsAppearance)
                    .aspectRatio(CGFloat(LyricsSampleBufferRenderer.width) /
                                 CGFloat(preferences.floatingLyricsAppearance.pixelHeight), contentMode: .fit)
                    .frame(maxWidth: 320)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                    .accessibilityLabel("悬浮歌词预览")
                    .id("floatingLyricsPreview")
            }
            FloatingLyricsStatusView(onStart: onStart)
            Text("悬浮窗只显示歌词，播放控制请使用主界面或 Sony。")
                .font(.caption2).foregroundStyle(.secondary)
            Text("轻点窗口可收起系统控件，双指缩放可调整大小。")
                .font(.caption2).foregroundStyle(.secondary)
        }
        .onDisappear {
            controller.cancelPendingStart()
        }
    }
}

struct FloatingLyricsStatusView: View {
    let onStart: () -> Void
    @ObservedObject private var controller = LyricsPictureInPictureController.shared

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(controller.state.title).font(.caption)
            if !controller.reason.isEmpty {
                Text(controller.reason).font(.caption).foregroundStyle(.secondary)
            }
            if controller.state != .active {
                Button(AppLocalization.string(controller.state == .stopped || controller.state == .stopping
                                              ? "再次显示" : "显示悬浮歌词"), action: onStart)
                    .disabled(controller.startRequested || controller.state == .starting)
            } else {
                Button("停止显示") { controller.stop() }
            }
        }
    }
}

extension LyricsPresentationSnapshot {
    static var disconnected: LyricsPresentationSnapshot {
        LyricsPresentationSnapshot(trackID: "", trackGeneration: 0, revision: 0, timelineRevision: 0,
                                   lineIndex: -1, text: "", title: "-", artist: "-", isPlaying: false,
                                   status: .disconnected, validUntilUptime: 0)
    }
}
