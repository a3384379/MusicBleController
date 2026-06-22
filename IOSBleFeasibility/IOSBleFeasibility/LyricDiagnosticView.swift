import SwiftUI
import UIKit

struct LyricDiagnosticView: View {
    @ObservedObject var bleManager: BLETestManager
    let onDismiss: () -> Void

    var body: some View {
        NavigationStack {
            ZStack {
                LinearGradient(
                    colors: [
                        Color.black,
                        Color(red: 0.08, green: 0.10, blue: 0.13),
                        Color.black.opacity(0.92)
                    ],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
                    .ignoresSafeArea()

                ScrollView(showsIndicators: false) {
                    VStack(alignment: .leading, spacing: 16) {
                        summaryCard
                        detailsCard
                        actionCard
                    }
                    .padding(.horizontal, 22)
                    .padding(.top, 18)
                    .padding(.bottom, 32)
                }
            }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Text("歌词诊断")
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
                bleManager.requestLyricDiagnostic(manual: true)
            }
        }
    }

    private var diagnostic: LyricDiagnostic? {
        bleManager.lyricDiagnostic
    }

    private var summaryCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(diagnostic?.title.nonEmpty ?? bleManager.title.nonEmpty ?? "当前歌曲")
                .font(.title3.weight(.bold))
                .foregroundStyle(.white)
                .lineLimit(1)
            Text(diagnostic?.artist.nonEmpty ?? bleManager.artist.nonEmpty ?? "未知歌手")
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.white.opacity(0.65))
                .lineLimit(1)

            Divider().overlay(.white.opacity(0.12))

            Text(diagnostic?.statusTitle ?? "正在获取诊断")
                .font(.headline.weight(.semibold))
                .foregroundStyle(.white)
            Text(diagnostic?.humanReadableReason ?? "正在向 Sony 读取当前歌词状态。")
                .font(.subheadline)
                .foregroundStyle(.white.opacity(0.72))
                .fixedSize(horizontal: false, vertical: true)
            Text(diagnostic?.suggestionText ?? "请稍等片刻。")
                .font(.callout.weight(.medium))
                .foregroundStyle(.white.opacity(0.84))
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(16)
        .background(.black.opacity(0.30), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(.white.opacity(0.10), lineWidth: 1)
        }
    }

    private var detailsCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("状态细节")
                .font(.headline.weight(.bold))
                .foregroundStyle(.white)
            detailRow("状态", diagnostic?.status ?? "-")
            detailRow("原因", diagnostic?.reason ?? "-")
            detailRow("数据源", diagnostic?.source.nonEmpty ?? "-")
            detailRow("行数", "\(diagnostic?.lines ?? 0)")
            detailRow("上次尝试", formattedTime(diagnostic?.lastAttemptAt ?? 0))
            detailRow("下次重试", formattedTime(diagnostic?.nextRetryAt ?? 0))
            detailRow("重试次数", "\(diagnostic?.retryCount ?? 0)")
            detailRow("冷却结束", formattedTime(diagnostic?.cooldownUntil ?? 0))
            detailRow("Fuzzy 索引", boolText(diagnostic?.fuzzyIndexReady))
            detailRow("QRC 索引", boolText(diagnostic?.qrcIndexLoaded))
            detailRow("维护任务", boolText(diagnostic?.maintenanceBusy))
            detailRow("等待 QQ音乐", boolText(diagnostic?.waitingQqMusicCache))
        }
        .padding(16)
        .background(.black.opacity(0.24), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(.white.opacity(0.08), lineWidth: 1)
        }
    }

    private var actionCard: some View {
        VStack(spacing: 10) {
            Button {
                bleManager.requestLyricDiagnostic(manual: true)
            } label: {
                actionLabel("arrow.clockwise", "刷新诊断")
            }
            Button {
                bleManager.sendGetFullLyrics(force: true)
            } label: {
                actionLabel("text.quote", "请求完整歌词")
            }
            Button {
                copyDiagnostic()
            } label: {
                actionLabel("doc.on.doc", "复制诊断信息")
            }
        }
    }

    private func actionLabel(_ icon: String, _ title: String) -> some View {
        HStack {
            Image(systemName: icon)
            Text(title)
            Spacer()
        }
        .font(.body.weight(.semibold))
        .foregroundStyle(.white)
        .padding(.horizontal, 16)
        .frame(height: 46)
        .background(.white.opacity(0.10), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private func detailRow(_ title: String, _ value: String) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(title)
                .font(.caption.weight(.medium))
                .foregroundStyle(.white.opacity(0.52))
                .frame(width: 78, alignment: .leading)
            Text(value)
                .font(.caption.monospacedDigit())
                .foregroundStyle(.white.opacity(0.82))
                .frame(maxWidth: .infinity, alignment: .leading)
                .lineLimit(2)
        }
    }

    private func formattedTime(_ value: Int64) -> String {
        guard value > 0 else { return "-" }
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        return formatter.string(from: Date(timeIntervalSince1970: TimeInterval(value) / 1_000.0))
    }

    private func boolText(_ value: Bool?) -> String {
        guard let value else { return "-" }
        return value ? "是" : "否"
    }

    private func copyDiagnostic() {
        guard let diagnostic else { return }
        UIPasteboard.general.string = """
        title: \(diagnostic.title)
        artist: \(diagnostic.artist)
        trackId: \(diagnostic.trackId)
        songKey: \(diagnostic.songKey)
        status: \(diagnostic.status)
        reason: \(diagnostic.reason)
        source: \(diagnostic.source)
        lines: \(diagnostic.lines)
        suggestion: \(diagnostic.suggestion)
        """
    }
}

private extension String {
    var nonEmpty: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
