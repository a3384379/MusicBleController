import SwiftUI

@main
struct IOSBleFeasibilityApp: App {
    @ObservedObject private var preferences = PreferencesStore.shared

    init() {
        MetricDiagnosticsSubscriber.shared.start()
    }

    var body: some Scene {
        WindowGroup {
            appContent
                .environment(\.locale, preferences.appLanguage.locale)
        }
    }

    @ViewBuilder
    private var appContent: some View {
        #if DEBUG && targetEnvironment(simulator)
        if ProcessInfo.processInfo.arguments.contains("--lyrics-reading-ui-test") {
            LyricsReadingUITestHost()
        } else {
            ContentView()
        }
        #else
        ContentView()
        #endif
    }
}

#if DEBUG && targetEnvironment(simulator)
// Deterministic data for the UI runner. No BLE manager or persisted test preferences.
private struct LyricsReadingUITestHost: View {
    @State private var expanded = false
    @State private var track = 0
    @State private var currentLine = 22
    @State private var seeks = 0
    @State private var revision: UInt64 = 0

    private var arguments: [String] { ProcessInfo.processInfo.arguments }
    private var lines: [LyricLine] {
        let indices = expanded ? Array(0..<80) : Array(20..<25)
        return indices.map { index in
            let text = arguments.contains("--long-lines")
                ? String(repeating: "长句 mixed lyrics with complete words 阅读 ", count: 7)
                : "Lyric line \(index) · 歌词第 \(index) 行"
            return LyricLine(
                index: index, timeMs: Int64(index) * 1_000, durationMs: 1_000,
                text: text, translation: expanded ? "Translation \(index) · 完整翻译" : nil,
                romanization: expanded ? "romanization \(index)" : nil, words: []
            )
        }
    }

    var body: some View {
        Group {
            if arguments.contains("--statistics") {
                PlaybackStatsView(stats: [:])
                    .background(PlayerDesignTokens.canvas)
                    .preferredColorScheme(.dark)
            } else {
                VStack(spacing: 0) {
                    HStack {
                        Button("Advance") { currentLine += 10 }
                            .accessibilityIdentifier("fixture.advance")
                        Button("Fill") { expanded = true; revision &+= 1 }
                            .accessibilityIdentifier("fixture.fill")
                        Button("Track") { track += 1; currentLine = 3; expanded = true; revision &+= 1 }
                            .accessibilityIdentifier("fixture.track")
                        Text("\(seeks)").accessibilityIdentifier("fixture.seeks")
                    }
                    .font(.caption).buttonStyle(.bordered).frame(minHeight: 44)
                    FullLyricsView(
                        title: "Reading fixture \(track)", artist: "UI test",
                        albumArtImage: nil, lyrics: lines, lyricsIdentity: "fixture-\(track)", lyricsRevision: revision,
                        currentIndex: lines.firstIndex(where: { $0.index == currentLine }) ?? -1,
                        positionMs: Int64(currentLine) * 1_000,
                        translationState: .ready, romanizationState: .ready,
                        isPlaying: false, isConnected: true,
                        onDismiss: {}, onPrevious: {}, onPlayPause: {}, onNext: {},
                        onSeekToLine: { time in seeks += 1; currentLine = Int(time / 1_000) },
                        showDiagnosticButton: false, onShowDiagnostic: {}
                    )
                }
            }
        }
        .environment(\.dynamicTypeSize, arguments.contains("--large-type") ? .accessibility3 : .large)
    }
}
#endif
