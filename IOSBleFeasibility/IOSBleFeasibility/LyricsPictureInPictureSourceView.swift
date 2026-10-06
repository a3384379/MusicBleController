import AVFoundation
import SwiftUI
import UIKit

final class LyricsPictureInPictureSourceUIView: UIView {
    override class var layerClass: AnyClass { AVSampleBufferDisplayLayer.self }
    var displayLayer: AVSampleBufferDisplayLayer { layer as! AVSampleBufferDisplayLayer }
    var becameVisible: (() -> Void)?

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window != nil { becameVisible?() }
    }
}

private struct LyricsPictureInPictureSourceView: UIViewRepresentable {
    let manager: BLETestManager
    let controller: LyricsPictureInPictureController

    func makeUIView(context: Context) -> LyricsPictureInPictureSourceUIView {
        let view = LyricsPictureInPictureSourceUIView()
        view.displayLayer.videoGravity = .resizeAspect
        view.backgroundColor = .black
        view.becameVisible = { [weak controller] in controller?.sourceBecameVisible() }
        controller.attach(view, snapshot: { [weak manager] in
            manager?.makeLyricsPresentationSnapshot() ?? .disconnected
        }, setPlaying: { [weak manager] target in
            manager?.requestFloatingLyricsPlayback(target) ?? false
        }, controlsStopped: { [weak manager] in
            manager?.cancelFloatingLyricsPlaybackIntent()
        })
        return view
    }

    func updateUIView(_ view: LyricsPictureInPictureSourceUIView, context: Context) {}
}

struct FloatingLyricsPanel: View {
    let manager: BLETestManager
    @ObservedObject private var controller = LyricsPictureInPictureController.shared

    var body: some View {
        HStack(spacing: 12) {
            LyricsPictureInPictureSourceView(manager: manager, controller: controller)
                .frame(width: 144, height: 81)
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .accessibilityLabel("悬浮歌词预览")
            VStack(alignment: .leading, spacing: 6) {
                Text(controller.state.title).font(.caption)
                if controller.state == .active {
                    Button("停止显示") { controller.stop() }
                } else {
                    Button("再次显示") { controller.requestStart() }
                        .disabled(controller.state == .starting || controller.state == .stopping)
                }
                if !controller.reason.isEmpty {
                    Text(controller.reason).font(.caption2).foregroundStyle(.secondary)
                }
                let controlState = manager.lyricsStore.floatingPlaybackControlState
                if controlState != .idle {
                    Text(controlState.message).font(.caption2).foregroundStyle(.secondary)
                }
                if controlState == .unknown {
                    Button("重新连接") { manager.forceReconnect() }
                }
            }
            Spacer(minLength: 0)
        }
        .padding(12)
        .background(.ultraThinMaterial)
        .onAppear { controller.setEnabled(true) }
    }
}

struct FloatingLyricsStatusView: View {
    @ObservedObject private var controller = LyricsPictureInPictureController.shared

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(controller.state.title).font(.caption)
            if !controller.reason.isEmpty {
                Text(controller.reason).font(.caption).foregroundStyle(.secondary)
            }
            if controller.state != .active {
                Button("再次显示") { controller.requestStart() }
                    .disabled(controller.state == .starting || controller.state == .stopping)
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
