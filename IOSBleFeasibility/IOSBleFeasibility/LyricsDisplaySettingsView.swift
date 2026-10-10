import SwiftUI

struct LyricsDisplaySettingsView: View {
    let manager: BLETestManager
    let onDismiss: () -> Void
    var startFloatingLyricsForTesting = false
    @ObservedObject private var preferences = PreferencesStore.shared
    @ObservedObject private var pipController = LyricsPictureInPictureController.shared
    @State private var dismissAfterStart = false

    var body: some View {
        NavigationStack {
            ZStack {
                LinearGradient(colors: [.black, Color(red: 0.07, green: 0.09, blue: 0.12)],
                               startPoint: .topLeading, endPoint: .bottomTrailing)
                    .ignoresSafeArea()
                ScrollViewReader { scroll in
                    ScrollView(showsIndicators: false) {
                        VStack(alignment: .leading, spacing: 16) {
                            PreferencesCard(title: "灵动岛歌词", systemImage: "capsule") {
                                Toggle("灵动岛显示歌词", isOn: $preferences.compactLyricsEnabled)
                                    .tint(PlayerDesignTokens.stableAccent)
                                Text("紧凑态保留封面，固定区域显示当前歌词；长按查看更多歌词，多活动时由系统选择最小态。")
                                    .font(.caption).foregroundStyle(.white.opacity(0.58))
                            }
                            PreferencesCard(title: "悬浮歌词", systemImage: "pip") {
                                Toggle("悬浮歌词", isOn: floatingLyricsBinding)
                                    .tint(PlayerDesignTokens.stableAccent)
                                Text("开启后自动显示，窗口准备好后返回播放器。关闭窗口不暂停 Sony 播放。")
                                    .font(.caption).foregroundStyle(.white.opacity(0.58))
                                FloatingLyricsSettingsPanel(manager: manager, onStart: showFloatingLyrics)
                            }
                            PreferencesCard(title: "显示样式", systemImage: "paintpalette") {
                                Picker("悬浮歌词行数", selection: $preferences.floatingLyricsLineMode) {
                                    ForEach(FloatingLyricsLineMode.allCases) { mode in
                                        Text(mode.title).tag(mode)
                                    }
                                }
                                .pickerStyle(.segmented)
                                Toggle("显示歌名", isOn: $preferences.floatingLyricsShowsTitle)
                                    .tint(PlayerDesignTokens.stableAccent)
                                floatingLyricsPalette
                            }
                        }
                        .padding(.horizontal, 18)
                        .padding(.vertical, 18)
                    }
                    .task(id: pipController.startRequested) {
                        guard pipController.startRequested else { return }
                        await Task.yield()
                        guard !Task.isCancelled else { return }
                        scroll.scrollTo("floatingLyricsPreview", anchor: .center)
                    }
                }
            }
            .navigationTitle("歌词设置")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("完成", action: onDismiss).foregroundStyle(.white)
                }
            }
            .toolbarBackground(.hidden, for: .navigationBar)
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        .preferredColorScheme(.dark)
        .onChange(of: pipController.state) { _, state in
            if state == .active, dismissAfterStart {
                dismissAfterStart = false
                onDismiss()
            }
        }
        .onDisappear {
            dismissAfterStart = false
            pipController.cancelPendingStart()
        }
        .task {
            #if DEBUG
            if startFloatingLyricsForTesting { showFloatingLyrics() }
            #endif
        }
    }

    private var floatingLyricsBinding: Binding<Bool> {
        Binding(get: { preferences.floatingLyricsEnabled }, set: { enabled in
            preferences.floatingLyricsEnabled = enabled
            pipController.setEnabled(enabled)
            if enabled {
                showFloatingLyrics()
            } else {
                dismissAfterStart = false
            }
        })
    }

    private func showFloatingLyrics() {
        preferences.floatingLyricsEnabled = true
        pipController.setEnabled(true)
        dismissAfterStart = true
        pipController.requestStart()
    }

    private var floatingLyricsPalette: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("背景配色").font(.subheadline.weight(.semibold))
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(FloatingLyricsTheme.allCases) { theme in
                        let palette = theme.palette
                        let selected = preferences.floatingLyricsTheme == theme
                        Button {
                            preferences.floatingLyricsTheme = theme
                        } label: {
                            VStack(spacing: 6) {
                                Text("Aa")
                                    .font(.system(size: 18, weight: .semibold))
                                    .foregroundStyle(paletteColor(palette.current))
                                    .frame(width: 48, height: 40)
                                    .background(LinearGradient(
                                        colors: [paletteColor(palette.start), paletteColor(palette.end)],
                                        startPoint: .leading, endPoint: .trailing
                                    ), in: RoundedRectangle(cornerRadius: 8))
                                    .overlay(RoundedRectangle(cornerRadius: 8)
                                        .stroke(selected ? PlayerDesignTokens.stableAccent : .clear, lineWidth: 2))
                                Text(theme.title)
                                    .font(.caption2)
                                    .foregroundStyle(selected ? .white : .white.opacity(0.65))
                            }
                            .padding(2)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(theme.title)
                        .accessibilityValue(AppLocalization.string(selected ? "已选择" : "未选择"))
                    }
                }
            }
        }
    }

    private func paletteColor(_ rgb: FloatingLyricsTheme.RGB) -> Color {
        Color(red: rgb.red, green: rgb.green, blue: rgb.blue)
    }
}
