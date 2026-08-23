import SwiftUI
import UIKit

struct FullLyricsView: View {
    let title: String
    let artist: String
    let albumArtImage: UIImage?
    let lyrics: [LyricLine]
    let lyricsIdentity: String
    let currentIndex: Int
    let positionMs: Int64
    let translationState: LyricSecondaryLoadState
    let romanizationState: LyricSecondaryLoadState
    let isPlaying: Bool
    let isConnected: Bool
    let onDismiss: () -> Void
    let onPrevious: () -> Void
    let onPlayPause: () -> Void
    let onNext: () -> Void
    let onSeekToLine: (Int64) -> Void
    var showDiagnosticButton = true
    let onShowDiagnostic: () -> Void

    @State private var followState = FullLyricsFollowState()
    @State private var lastAutoScrolledIndex: Int?
    @State private var isProgrammaticScroll = false
    @ObservedObject private var preferences = PreferencesStore.shared
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var isBrowsingLyrics: Bool { followState.showsReturnToCurrent }

    var body: some View {
        ZStack {
            PlayerLyricsBackgroundView(image: albumArtImage)
                .ignoresSafeArea()

            VStack(spacing: 18) {
                header
                displayModePicker
                secondaryMissingHint
                lyricsList
                controls
            }
            .padding(.horizontal, 26)
            .padding(.top, 18)
            .padding(.bottom, 30)
        }
    }

    private var displayModeBinding: Binding<LyricDisplayMode> {
        Binding(
            get: { displayMode },
            set: { preferences.lyricDisplayMode = $0 }
        )
    }

    private var displayMode: LyricDisplayMode {
        preferences.lyricDisplayMode
    }

    @ViewBuilder
    private var secondaryMissingHint: some View {
        if let message = secondaryMissingMessage {
            Text(message)
                .font(.caption.weight(.medium))
                .foregroundStyle(.white.opacity(0.58))
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var secondaryMissingMessage: String? {
        guard !lyrics.isEmpty else { return nil }
        switch displayMode {
        case .originalTranslation:
            return secondaryMessage(
                state: translationState,
                hasContent: hasAnyTranslation,
                title: "翻译"
            )
        case .originalRomanization:
            return secondaryMessage(
                state: romanizationState,
                hasContent: hasAnyRomanization,
                title: "罗马音"
            )
        case .originalTranslationRomanization:
            return [
                secondaryMessage(
                    state: translationState,
                    hasContent: hasAnyTranslation,
                    title: "翻译"
                ),
                secondaryMessage(
                    state: romanizationState,
                    hasContent: hasAnyRomanization,
                    title: "罗马音"
                )
            ]
            .compactMap { $0 }
            .joined(separator: " · ")
            .nilIfEmpty
        case .original:
            return nil
        }
    }

    private func secondaryMessage(
        state: LyricSecondaryLoadState,
        hasContent: Bool,
        title: String
    ) -> String? {
        guard !hasContent else { return nil }
        switch state {
        case .idle, .loading:
            return "正在获取\(title)…"
        case .ready, .unavailable:
            return "该歌曲暂无\(title)"
        case .failed:
            return "\(title)获取失败，可稍后切换模式重试"
        }
    }

    private var hasAnyTranslation: Bool {
        lyrics.contains { sanitizedSecondaryText($0.translation) != nil }
    }

    private var hasAnyRomanization: Bool {
        lyrics.contains { sanitizedSecondaryText($0.romanization) != nil }
    }

    private var header: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.headline.weight(.bold))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                Text(artist)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.white.opacity(0.62))
                    .lineLimit(1)
            }
            Spacer()
            Button(action: onDismiss) {
                Image(systemName: "xmark")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(width: 34, height: 34)
                    .background(.white.opacity(0.10), in: Circle())
                    .overlay {
                        Circle().stroke(.white.opacity(0.10), lineWidth: 1)
                    }
            }
            .buttonStyle(FullLyricsPressStyle())
            .accessibilityLabel("关闭歌词")
        }
    }

    private var displayModePicker: some View {
        Picker("歌词显示", selection: displayModeBinding) {
            ForEach(LyricDisplayMode.allCases) { mode in
                Text(mode.title).tag(mode)
            }
        }
        .pickerStyle(.segmented)
        .tint(.white)
    }

    private var lyricsList: some View {
        ScrollViewReader { proxy in
            GeometryReader { viewport in
                ZStack(alignment: .topTrailing) {
                    ScrollView(showsIndicators: false) {
                        LazyVStack(alignment: .leading, spacing: 24) {
                            Color.clear
                                .frame(height: max(viewport.size.height * 0.38, 120))
                            if lyrics.isEmpty {
                                emptyLyricsView
                            } else {
                                ForEach(lyrics) { line in
                                    lyricRow(index: line.index, line: line)
                                        .id(line.id)
                                }
                            }
                            Color.clear
                                .frame(height: max(viewport.size.height * 0.42, 140))
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .simultaneousGesture(
                        DragGesture(minimumDistance: 8)
                            .onChanged { _ in
                                enterBrowseMode()
                            }
                    )

                    if isBrowsingLyrics, !lyrics.isEmpty {
                        Button {
                            restoreFollowMode(proxy)
                        } label: {
                            Label("回到当前歌词", systemImage: "location.fill")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.white)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 8)
                                .background(.white.opacity(0.12), in: Capsule())
                                .overlay {
                                    Capsule().stroke(.white.opacity(0.10), lineWidth: 1)
                                }
                        }
                        .buttonStyle(FullLyricsPressStyle())
                        .padding(.top, 4)
                    }
                }
                .onAppear {
                    scrollToCurrent(proxy)
                }
                .onChange(of: currentIndex) { _, _ in
                    guard !isBrowsingLyrics else { return }
                    scrollToCurrent(proxy)
                }
                .onChange(of: lyricsIdentity) { _, _ in
                    resetBrowseState()
                    DispatchQueue.main.async {
                        scrollToCurrent(proxy)
                    }
                }
            }
        }
    }

    private var emptyLyricsView: some View {
        VStack(spacing: 12) {
            Text("暂无歌词")
                .font(.system(size: 30, weight: .bold, design: .rounded))
                .foregroundStyle(.white.opacity(0.62))
            if showDiagnosticButton {
                Button(action: onShowDiagnostic) {
                    Text("查看原因")
                        .font(.callout.weight(.bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 16)
                        .frame(height: 36)
                        .background(.white.opacity(0.12), in: Capsule())
                }
                .buttonStyle(.plain)
            } else {
                Text("可在 Sony QQ音乐打开歌词/桌面歌词后稍等")
                    .font(.callout.weight(.medium))
                    .foregroundStyle(.white.opacity(0.58))
                    .multilineTextAlignment(.center)
            }
        }
            .frame(maxWidth: .infinity, alignment: .center)
    }

    private func lyricRow(index: Int, line: LyricLine) -> some View {
        let isCurrent = index == currentIndex
        return Button {
            guard isConnected else { return }
            seekToLine(line)
        } label: {
            HStack(alignment: .center, spacing: 14) {
                lyricText(
                    index: index,
                    line: line,
                    isCurrent: isCurrent
                )
                .frame(maxWidth: .infinity, alignment: .leading)
                .animation(
                    reduceMotion ? nil : .easeInOut(duration: 0.18),
                    value: currentIndex
                )

                if isBrowsingLyrics {
                    Image(systemName: "play.circle.fill")
                        .font(.system(size: 25, weight: .semibold))
                        .foregroundStyle(.white.opacity(isConnected ? 0.82 : 0.28))
                        .transition(.opacity)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!isConnected)
        .accessibilityLabel("歌词 \(line.text)")
        .accessibilityHint(isConnected ? "跳转到这句" : "Sony 未连接")
    }

    private func lyricColor(isCurrent: Bool) -> Color {
        if isCurrent {
            return PlayerDesignTokens.stableAccent
        }
        return .white.opacity(0.42)
    }

    @ViewBuilder
    private func lyricText(
        index: Int,
        line: LyricLine,
        isCurrent: Bool
    ) -> some View {
        if isCurrent {
            lyricStack(
                line: line,
                isCurrent: true
            ) {
                KaraokeLyricText(
                    text: line.text,
                    progress: lineProgress(index: index),
                    words: line.words,
                    positionMs: positionMs,
                    isPlaying: isPlaying,
                    highlightColor: PlayerDesignTokens.stableAccent,
                    normalColor: Color.white.opacity(0.36),
                    font: .system(size: 28, weight: .bold, design: .rounded),
                    lineLimit: 3,
                    alignment: .leading
                )
            }
        } else {
            lyricStack(
                line: line,
                isCurrent: false
            ) {
                Text(line.text)
                    .font(
                        .system(
                            size: 21,
                            weight: .medium,
                            design: .rounded
                        )
                    )
                    .foregroundStyle(lyricColor(isCurrent: false))
                    .multilineTextAlignment(.leading)
                    .lineLimit(3)
            }
        }
    }

    private func lyricStack<Original: View>(
        line: LyricLine,
        isCurrent: Bool,
        @ViewBuilder original: () -> Original
    ) -> some View {
        VStack(alignment: .leading, spacing: isCurrent ? 5 : 4) {
            original()
            if displayMode.showsTranslation,
               let translation = sanitizedSecondaryText(line.translation) {
                auxiliaryLyricText(
                    translation,
                    isCurrent: isCurrent
                )
            }
            if displayMode.showsRomanization,
               let romanization = sanitizedSecondaryText(line.romanization) {
                auxiliaryLyricText(
                    romanization,
                    isCurrent: isCurrent
                )
            }
        }
    }

    private func auxiliaryLyricText(
        _ text: String,
        isCurrent: Bool
    ) -> some View {
        Text(text)
            .font(
                .system(
                    size: isCurrent ? 17 : 15,
                    weight: .medium,
                    design: .rounded
                )
            )
            .foregroundStyle(
                isCurrent
                    ? Color.white.opacity(0.70)
                    : Color.white.opacity(0.34)
            )
            .multilineTextAlignment(.leading)
            .fixedSize(horizontal: false, vertical: true)
    }

    private func lineProgress(index: Int) -> Double {
        LyricTimelineHelper.lineProgress(
            lines: lyrics,
            index: index,
            positionMs: positionMs
        )
    }

    private var controls: some View {
        HStack(spacing: 32) {
            controlButton(systemImage: "backward.fill", size: 52, action: onPrevious)
            Button(action: onPlayPause) {
                Image(systemName: isPlaying ? "pause.fill" : "play.fill")
                    .font(.system(size: 30, weight: .bold))
                    .foregroundStyle(.black)
                    .frame(width: 72, height: 72)
                    .background(.white, in: Circle())
                    .shadow(color: .black.opacity(0.18), radius: 12, y: 7)
            }
            .buttonStyle(FullLyricsPressStyle(pressedScale: 0.92))
            .accessibilityLabel(isPlaying ? "暂停" : "播放")
            controlButton(systemImage: "forward.fill", size: 52, action: onNext)
        }
        .padding(.top, 4)
        .disabled(!isConnected)
        .opacity(isConnected ? 1 : 0.42)
    }

    private func controlButton(
        systemImage: String,
        size: CGFloat,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 22, weight: .bold))
                .foregroundStyle(.white)
                .frame(width: size, height: size)
                .background(.white.opacity(0.10), in: Circle())
                .overlay {
                    Circle().stroke(.white.opacity(0.10), lineWidth: 1)
                }
        }
        .buttonStyle(FullLyricsPressStyle(pressedScale: 0.92))
    }

    private func scrollToCurrent(_ proxy: ScrollViewProxy) {
        guard lyrics.indices.contains(currentIndex) else { return }
        guard currentIndex != lastAutoScrolledIndex || !isProgrammaticScroll else { return }
        lastAutoScrolledIndex = currentIndex
        isProgrammaticScroll = true
        if reduceMotion {
            proxy.scrollTo(lyrics[currentIndex].id, anchor: .center)
        } else {
            withAnimation(.easeInOut(duration: 0.28)) {
                proxy.scrollTo(lyrics[currentIndex].id, anchor: .center)
            }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) {
            isProgrammaticScroll = false
        }
    }

    private func enterBrowseMode() {
        guard !isProgrammaticScroll else { return }
        followState.userDidBrowse()
    }

    private func restoreFollowMode(_ proxy: ScrollViewProxy) {
        followState.returnToCurrent()
        scrollToCurrent(proxy)
    }

    private func resetBrowseState() {
        followState.trackDidChange()
        lastAutoScrolledIndex = nil
        isProgrammaticScroll = false
    }

    private func seekToLine(_ line: LyricLine) {
        onSeekToLine(line.timeMs)
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        followState.returnToCurrent()
    }
}

private extension String {
    var nilIfEmpty: String? {
        isEmpty ? nil : self
    }
}

private struct PlayerLyricsBackgroundView: View {
    let image: UIImage?

    var body: some View {
        GeometryReader { proxy in
            ZStack {
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                        .saturation(1.15)
                        .brightness(-0.08)
                        .frame(width: proxy.size.width, height: proxy.size.height)
                        .clipped()
                        .blur(radius: 34)
                        .overlay(Color.black.opacity(0.38))
                } else {
                    LinearGradient(
                        colors: [
                            Color(red: 0.04, green: 0.09, blue: 0.16),
                            Color(red: 0.12, green: 0.18, blue: 0.28),
                            Color(red: 0.02, green: 0.03, blue: 0.06)
                        ],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                }

                LinearGradient(
                    colors: [
                        Color.black.opacity(0.22),
                        Color.black.opacity(0.08),
                        Color.black.opacity(0.82)
                    ],
                    startPoint: .top,
                    endPoint: .bottom
                )
            }
            .frame(width: proxy.size.width, height: proxy.size.height)
        }
    }
}

private struct FullLyricsPressStyle: ButtonStyle {
    var pressedScale: CGFloat = 0.96

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? pressedScale : 1)
            .animation(.spring(response: 0.18, dampingFraction: 0.72), value: configuration.isPressed)
    }
}
