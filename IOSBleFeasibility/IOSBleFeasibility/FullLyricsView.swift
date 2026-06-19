import SwiftUI
import UIKit

struct FullLyricsView: View {
    let title: String
    let artist: String
    let albumArtImage: UIImage?
    let lyrics: [LyricLine]
    let currentIndex: Int
    let positionMs: Int64
    let isPlaying: Bool
    let isConnected: Bool
    let onDismiss: () -> Void
    let onPrevious: () -> Void
    let onPlayPause: () -> Void
    let onNext: () -> Void
    let onSeekToLine: (Int64) -> Void

    @State private var isBrowsingLyrics = false
    @State private var selectedLyricIndex: Int?
    @State private var lastAutoScrolledIndex: Int?
    @State private var isProgrammaticScroll = false
    @State private var browseResetWorkItem: DispatchWorkItem?

    var body: some View {
        ZStack {
            PlayerLyricsBackgroundView(image: albumArtImage)
                .ignoresSafeArea()

            VStack(spacing: 18) {
                header
                lyricsList
                controls
            }
            .padding(.horizontal, 26)
            .padding(.top, 18)
            .padding(.bottom, 30)
        }
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
                                ForEach(Array(lyrics.enumerated()), id: \.element.id) { index, line in
                                    lyricRow(index: index, line: line)
                                        .id(line.id)
                                        .background(
                                            GeometryReader { rowProxy in
                                                Color.clear.preference(
                                                    key: LyricLineCenterPreferenceKey.self,
                                                    value: [
                                                        index: rowProxy.frame(in: .named("lyricsScroll")).midY
                                                    ]
                                                )
                                            }
                                        )
                                }
                            }
                            Color.clear
                                .frame(height: max(viewport.size.height * 0.42, 140))
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .coordinateSpace(name: "lyricsScroll")
                    .simultaneousGesture(
                        DragGesture(minimumDistance: 8)
                            .onChanged { _ in
                                enterBrowseMode()
                            }
                            .onEnded { _ in
                                scheduleFollowModeRestore(proxy)
                            }
                    )
                    .onPreferenceChange(LyricLineCenterPreferenceKey.self) { centers in
                        updateSelectedLine(
                            centers: centers,
                            viewportCenterY: viewport.size.height / 2
                        )
                    }

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
                .onChange(of: lyrics) { _, _ in
                    resetBrowseState()
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
                        scrollToCurrent(proxy)
                    }
                }
            }
        }
    }

    private var emptyLyricsView: some View {
        Text("暂无歌词")
            .font(.system(size: 30, weight: .bold, design: .rounded))
            .foregroundStyle(.white.opacity(0.62))
            .frame(maxWidth: .infinity, alignment: .center)
    }

    private func lyricRow(index: Int, line: LyricLine) -> some View {
        let isCurrent = index == currentIndex
        let isSelected = isBrowsingLyrics && index == selectedLyricIndex
        return Group {
            HStack(alignment: .center, spacing: 14) {
                lyricText(
                    index: index,
                    line: line,
                    isCurrent: isCurrent,
                    isSelected: isSelected
                )
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .animation(.easeInOut(duration: 0.18), value: currentIndex)
                    .animation(.easeInOut(duration: 0.18), value: selectedLyricIndex)

                if isSelected {
                    seekTimeCapsule(line: line)
                    .transition(.opacity.combined(with: .scale(scale: 0.96)))
                }
            }
            .contentShape(Rectangle())
        }
        .onTapGesture {
            guard isBrowsingLyrics, isConnected else {
                selectedLyricIndex = index
                enterBrowseMode()
                return
            }
            seekToLine(line)
        }
        .opacity((!isConnected && isSelected) ? 0.58 : 1)
        .accessibilityLabel("歌词 \(line.text)")
    }

    private func lyricColor(isCurrent: Bool, isSelected: Bool) -> Color {
        if isCurrent {
            return Color.green.opacity(0.98)
        }
        if isSelected {
            return .white.opacity(0.92)
        }
        return .white.opacity(0.42)
    }

    @ViewBuilder
    private func lyricText(
        index: Int,
        line: LyricLine,
        isCurrent: Bool,
        isSelected: Bool
    ) -> some View {
        if isCurrent {
            KaraokeLyricText(
                text: line.text,
                progress: lineProgress(index: index),
                words: line.words,
                positionMs: positionMs,
                highlightColor: Color.green.opacity(0.98),
                normalColor: Color.white.opacity(isSelected ? 0.58 : 0.36),
                font: .system(size: 28, weight: .bold, design: .rounded),
                lineLimit: 3,
                alignment: .leading
            )
        } else {
            Text(line.text)
                .font(
                    .system(
                        size: isSelected ? 23 : 21,
                        weight: isSelected ? .semibold : .medium,
                        design: .rounded
                    )
                )
                .foregroundStyle(lyricColor(isCurrent: false, isSelected: isSelected))
                .multilineTextAlignment(.leading)
                .lineLimit(3)
        }
    }

    private func lineProgress(index: Int) -> Double {
        LyricTimelineHelper.lineProgress(
            lines: lyrics,
            index: index,
            positionMs: positionMs
        )
    }

    private func seekTimeCapsule(line: LyricLine) -> some View {
        Button {
            guard isConnected else { return }
            seekToLine(line)
        } label: {
            HStack(spacing: 4) {
                Image(systemName: "play.fill")
                    .font(.system(size: 9, weight: .bold))
                Text(formatTime(line.timeMs))
                    .font(.caption.monospacedDigit().weight(.semibold))
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 10)
            .padding(.vertical, 7)
            .background(.white.opacity(0.14), in: Capsule())
            .overlay {
                Capsule().stroke(.white.opacity(0.12), lineWidth: 1)
            }
        }
        .buttonStyle(FullLyricsPressStyle(pressedScale: 0.94))
        .disabled(!isConnected)
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
            .accessibilityLabel("播放 / 暂停")
            controlButton(systemImage: "forward.fill", size: 52, action: onNext)
        }
        .padding(.top, 4)
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
        withAnimation(.easeInOut(duration: 0.28)) {
            proxy.scrollTo(lyrics[currentIndex].id, anchor: .center)
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) {
            isProgrammaticScroll = false
        }
    }

    private func enterBrowseMode() {
        guard !isProgrammaticScroll else { return }
        isBrowsingLyrics = true
        browseResetWorkItem?.cancel()
    }

    private func scheduleFollowModeRestore(_ proxy: ScrollViewProxy) {
        browseResetWorkItem?.cancel()
        let workItem = DispatchWorkItem {
            restoreFollowMode(proxy)
        }
        browseResetWorkItem = workItem
        DispatchQueue.main.asyncAfter(deadline: .now() + 4, execute: workItem)
    }

    private func restoreFollowMode(_ proxy: ScrollViewProxy) {
        browseResetWorkItem?.cancel()
        isBrowsingLyrics = false
        selectedLyricIndex = nil
        scrollToCurrent(proxy)
    }

    private func resetBrowseState() {
        browseResetWorkItem?.cancel()
        isBrowsingLyrics = false
        selectedLyricIndex = nil
        lastAutoScrolledIndex = nil
        isProgrammaticScroll = false
    }

    private func updateSelectedLine(
        centers: [Int: CGFloat],
        viewportCenterY: CGFloat
    ) {
        guard isBrowsingLyrics, !centers.isEmpty else { return }
        selectedLyricIndex = centers.min {
            abs($0.value - viewportCenterY) < abs($1.value - viewportCenterY)
        }?.key
    }

    private func seekToLine(_ line: LyricLine) {
        onSeekToLine(line.timeMs)
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        isBrowsingLyrics = false
        selectedLyricIndex = nil
    }

    private func formatTime(_ milliseconds: Int64) -> String {
        let totalSeconds = max(milliseconds, 0) / 1_000
        return String(format: "%02lld:%02lld", totalSeconds / 60, totalSeconds % 60)
    }
}

private struct LyricLineCenterPreferenceKey: PreferenceKey {
    static var defaultValue: [Int: CGFloat] = [:]

    static func reduce(
        value: inout [Int: CGFloat],
        nextValue: () -> [Int: CGFloat]
    ) {
        value.merge(nextValue(), uniquingKeysWith: { _, new in new })
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
