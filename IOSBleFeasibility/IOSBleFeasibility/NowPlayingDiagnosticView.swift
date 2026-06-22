import SwiftUI
import UIKit

struct NowPlayingDiagnosticView: View {
    @ObservedObject var bleManager: BLETestManager
    let onDismiss: () -> Void
    @State private var snapshot: NowPlayingDiagnosticSnapshot?
    @State private var copyStatus = ""

    var body: some View {
        NavigationStack {
            ZStack {
                LinearGradient(
                    colors: [
                        Color.black,
                        Color(red: 0.08, green: 0.10, blue: 0.13),
                        Color.black.opacity(0.94)
                    ],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
                .ignoresSafeArea()

                ScrollView(showsIndicators: false) {
                    VStack(alignment: .leading, spacing: 14) {
                        if let snapshot {
                            trackCard(snapshot)
                            lyricCard(snapshot)
                            artworkCard(snapshot)
                            connectionCard(snapshot)
                            actionCard(snapshot)
                        } else {
                            ProgressView("正在生成诊断")
                                .tint(.white)
                                .foregroundStyle(.white)
                                .frame(maxWidth: .infinity, minHeight: 220)
                        }
                    }
                    .padding(.horizontal, 18)
                    .padding(.top, 18)
                    .padding(.bottom, 32)
                }
            }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Text("当前歌曲诊断")
                        .font(.headline.weight(.bold))
                        .foregroundStyle(.white)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("完成", action: onDismiss)
                        .foregroundStyle(.white)
                }
            }
            .toolbarBackground(.hidden, for: .navigationBar)
            .onAppear {
                bleManager.requestLyricDiagnostic(manual: false)
                refreshSnapshot()
            }
            .onChange(of: bleManager.lyricDiagnostic) { _, _ in
                refreshSnapshot()
            }
            .onChange(of: bleManager.connectionHealthLastNotifyAgeMs) { _, _ in
                refreshSnapshot()
            }
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
    }

    private func trackCard(_ snapshot: NowPlayingDiagnosticSnapshot) -> some View {
        DiagnosticCard {
            VStack(alignment: .leading, spacing: 12) {
                Label("当前歌曲", systemImage: "music.note")
                    .font(.headline.weight(.bold))

                VStack(alignment: .leading, spacing: 4) {
                    Text(snapshot.title.nonEmpty ?? "未知歌曲")
                        .font(.title3.weight(.bold))
                        .lineLimit(1)
                    Text(snapshot.artist.nonEmpty ?? "未知歌手")
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.white.opacity(0.68))
                        .lineLimit(1)
                    Text(snapshot.album.nonEmpty ?? "未知专辑")
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.50))
                        .lineLimit(1)
                }

                Divider().overlay(.white.opacity(0.12))

                diagnosticRow("播放状态", snapshot.isPlaying ? "播放中" : "已暂停")
                diagnosticRow(
                    "进度",
                    "\(NowPlayingDiagnosticSnapshot.formatDuration(snapshot.positionMs)) / \(NowPlayingDiagnosticSnapshot.formatDuration(snapshot.durationMs))"
                )
                diagnosticRow("trackId", snapshot.trackId.nonEmpty ?? "-")
                diagnosticRow("albumArtId", snapshot.albumArtId.nonEmpty ?? "-")
                diagnosticRow("生成时间", NowPlayingDiagnosticSnapshot.optionalDate(snapshot.generatedAt))
            }
        }
    }

    private func lyricCard(_ snapshot: NowPlayingDiagnosticSnapshot) -> some View {
        let diagnostic = snapshot.lyricDiagnostic
        return DiagnosticCard {
            VStack(alignment: .leading, spacing: 12) {
                Label("歌词状态", systemImage: "text.magnifyingglass")
                    .font(.headline.weight(.bold))

                Text(diagnostic?.statusTitle ?? "暂无完整诊断")
                    .font(.headline.weight(.semibold))
                Text(diagnostic?.humanReadableReason ?? "尚未从 Sony 获取歌词诊断，可点击下方刷新。")
                    .font(.subheadline)
                    .foregroundStyle(.white.opacity(0.72))
                    .fixedSize(horizontal: false, vertical: true)
                Text(diagnostic?.suggestionText ?? "如果当前显示暂无歌词，请先刷新诊断。")
                    .font(.callout.weight(.medium))
                    .foregroundStyle(.white.opacity(0.84))
                    .fixedSize(horizontal: false, vertical: true)

                Divider().overlay(.white.opacity(0.12))

                diagnosticRow("当前歌词", snapshot.currentLyric.nonEmpty ?? "暂无歌词")
                diagnosticRow("状态", diagnostic?.status ?? "-")
                diagnosticRow("原因", diagnostic?.reason ?? "-")
                diagnosticRow("来源", diagnostic?.source.nonEmpty ?? "-")
                diagnosticRow("诊断行数", "\(diagnostic?.lines ?? 0)")
                diagnosticRow("完整歌词", "\(snapshot.fullLyricsLineCount) 行")
                diagnosticRow("接收中", snapshot.isFullLyricsReceiving ? "是" : "否")
                diagnosticRow("当前曲目", snapshot.isFullLyricsCurrent ? "是" : "否")
            }
        }
    }

    private func artworkCard(_ snapshot: NowPlayingDiagnosticSnapshot) -> some View {
        DiagnosticCard {
            VStack(alignment: .leading, spacing: 12) {
                Label("封面状态", systemImage: "photo")
                    .font(.headline.weight(.bold))

                diagnosticRow("显示质量", snapshot.albumArtDisplayQuality)
                diagnosticRow(
                    "显示像素",
                    "\(snapshot.displayArtworkPixelWidth)x\(snapshot.displayArtworkPixelHeight)"
                )
                diagnosticRow("增强开关", snapshot.artworkEnhancementStatus.enabled ? "开启" : "关闭")
                diagnosticRow("增强目标", snapshot.artworkEnhancementStatus.target)
                diagnosticRow(
                    "锐化",
                    String(format: "%.2f", snapshot.artworkEnhancementStatus.sharpness)
                )
                diagnosticRow("处理耗时", "\(snapshot.artworkEnhancementStatus.lastProcessingCostMs)ms")
                diagnosticRow(
                    "锐度提升",
                    String(format: "%.2f%%", snapshot.artworkEnhancementStatus.lastEdgeGainPercent)
                )
                diagnosticRow("增强状态", snapshot.artworkEnhancementStatus.lastMessage)

                VStack(spacing: 8) {
                    ForEach(snapshot.artworkCaches) { cache in
                        cacheRow(cache)
                    }
                }
                .padding(.top, 2)
            }
        }
    }

    private func connectionCard(_ snapshot: NowPlayingDiagnosticSnapshot) -> some View {
        let connection = snapshot.connection
        return DiagnosticCard {
            VStack(alignment: .leading, spacing: 12) {
                Label("连接健康", systemImage: "antenna.radiowaves.left.and.right")
                    .font(.headline.weight(.bold))

                diagnosticRow("连接", connection.connectionStatus)
                diagnosticRow("显示状态", connection.displayState)
                diagnosticRow("健康状态", connection.healthState)
                diagnosticRow("自动重连", connection.autoReconnectState)
                diagnosticRow("重连尝试", "\(connection.autoReconnectAttempt)")
                diagnosticRow("MTU", connection.mtuBytes > 0 ? "\(connection.mtuBytes)" : "-")
                diagnosticRow(
                    "上次通知",
                    connection.lastNotifyAgeMs >= 0 ? "\(connection.lastNotifyAgeMs)ms ago" : "-"
                )
                diagnosticRow("Peripheral", connection.peripheralState)
                diagnosticRow("Characteristic", connection.characteristicReady ? "就绪" : "未就绪")
                diagnosticRow("Probe", connection.probeInFlight ? "进行中" : "无")
                diagnosticRow("最近硬重连", connection.lastHardReconnectReason)
            }
        }
    }

    private func actionCard(_ snapshot: NowPlayingDiagnosticSnapshot) -> some View {
        DiagnosticCard {
            VStack(alignment: .leading, spacing: 12) {
                Label("操作", systemImage: "wrench.and.screwdriver")
                    .font(.headline.weight(.bold))

                LazyVGrid(
                    columns: [
                        GridItem(.flexible(), spacing: 10),
                        GridItem(.flexible(), spacing: 10)
                    ],
                    spacing: 10
                ) {
                    actionButton("刷新全部", "arrow.clockwise") {
                        bleManager.refreshNowPlayingDiagnostics()
                        refreshSnapshot(after: 0.35)
                    }
                    actionButton("刷新歌词诊断", "text.magnifyingglass") {
                        bleManager.requestLyricDiagnostic(manual: true)
                        refreshSnapshot(after: 0.35)
                    }
                    actionButton("请求完整歌词", "text.quote") {
                        bleManager.sendGetFullLyrics(force: true)
                        refreshSnapshot(after: 0.35)
                    }
                    actionButton("重新请求 HQ", "photo.badge.arrow.down") {
                        bleManager.requestCurrentHqAlbumArt()
                        refreshSnapshot(after: 0.35)
                    }
                    actionButton("强制重连", "arrow.triangle.2.circlepath") {
                        bleManager.forceReconnect()
                        refreshSnapshot(after: 0.35)
                    }
                    actionButton("复制诊断", "doc.on.doc") {
                        UIPasteboard.general.string = snapshot.diagnosticText
                        copyStatus = "已复制诊断信息"
                        bleManager.noteNowPlayingDiagnosticsCopied(trackId: snapshot.trackId)
                    }
                }

                if !copyStatus.isEmpty {
                    Text(copyStatus)
                        .font(.caption.weight(.medium))
                        .foregroundStyle(.green.opacity(0.9))
                }
            }
        }
    }

    private func cacheRow(_ cache: ArtworkCacheDiagnostic) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack {
                Text(cache.quality.uppercased())
                    .font(.caption.weight(.bold))
                Spacer()
                Text(cache.exists ? "存在" : "缺失")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(cache.exists ? .green : .white.opacity(0.45))
            }
            HStack(spacing: 8) {
                Text("\(cache.pixelWidth)x\(cache.pixelHeight)")
                Text("\(cache.bytes) bytes")
                if cache.isPlaceholder {
                    Text("占位图")
                        .foregroundStyle(.orange)
                }
            }
            .font(.caption.monospacedDigit())
            .foregroundStyle(.white.opacity(0.68))
            Text(cache.path)
                .font(.caption2.monospaced())
                .foregroundStyle(.white.opacity(0.42))
                .lineLimit(2)
        }
        .padding(10)
        .background(.white.opacity(0.055), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private func diagnosticRow(_ title: String, _ value: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Text(title)
                .font(.caption.weight(.medium))
                .foregroundStyle(.white.opacity(0.52))
                .frame(width: 78, alignment: .leading)
            Text(value.isEmpty ? "-" : value)
                .font(.caption.monospacedDigit())
                .foregroundStyle(.white.opacity(0.82))
                .frame(maxWidth: .infinity, alignment: .leading)
                .lineLimit(3)
        }
    }

    private func actionButton(
        _ title: String,
        _ systemImage: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 7) {
                Image(systemName: systemImage)
                Text(title)
                    .lineLimit(1)
                    .minimumScaleFactor(0.78)
            }
            .font(.caption.weight(.semibold))
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity)
            .frame(height: 42)
            .background(.white.opacity(0.10), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
        .buttonStyle(.plain)
    }

    private func refreshSnapshot(after delay: TimeInterval = 0) {
        let update = {
            snapshot = bleManager.makeNowPlayingDiagnosticSnapshot()
        }
        if delay > 0 {
            DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: update)
        } else {
            update()
        }
    }
}

private struct DiagnosticCard<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        content
            .foregroundStyle(.white)
            .padding(16)
            .background(.black.opacity(0.28), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .stroke(.white.opacity(0.09), lineWidth: 1)
            }
    }
}

private extension String {
    var nonEmpty: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty || trimmed == "-" ? nil : trimmed
    }
}
