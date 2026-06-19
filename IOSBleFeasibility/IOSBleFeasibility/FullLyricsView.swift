import SwiftUI
import UIKit

struct FullLyricsView: View {
    let title: String
    let artist: String
    let albumArtImage: UIImage?
    let lyrics: [LyricLine]
    let currentIndex: Int
    let isPlaying: Bool
    let onDismiss: () -> Void
    let onPrevious: () -> Void
    let onPlayPause: () -> Void
    let onNext: () -> Void

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
            ScrollView(showsIndicators: false) {
                LazyVStack(alignment: .leading, spacing: 24) {
                    Color.clear
                        .frame(height: 140)
                    if lyrics.isEmpty {
                        Text("暂无歌词")
                            .font(.system(size: 30, weight: .bold, design: .rounded))
                            .foregroundStyle(.white.opacity(0.62))
                            .frame(maxWidth: .infinity, alignment: .center)
                    } else {
                        ForEach(Array(lyrics.enumerated()), id: \.element.id) { index, line in
                            Text(line.text)
                                .font(
                                    .system(
                                        size: index == currentIndex ? 30 : 22,
                                        weight: index == currentIndex ? .bold : .medium,
                                        design: .rounded
                                    )
                                )
                                .foregroundStyle(
                                    index == currentIndex
                                        ? Color.green.opacity(0.98)
                                        : Color.white.opacity(0.42)
                                )
                                .multilineTextAlignment(.leading)
                                .lineSpacing(4)
                                .id(line.id)
                                .animation(.easeInOut(duration: 0.18), value: currentIndex)
                        }
                    }
                    Color.clear
                        .frame(height: 180)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .onAppear {
                scrollToCurrent(proxy)
            }
            .onChange(of: currentIndex) { _, _ in
                scrollToCurrent(proxy)
            }
        }
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
        withAnimation(.easeInOut(duration: 0.28)) {
            proxy.scrollTo(lyrics[currentIndex].id, anchor: .center)
        }
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
