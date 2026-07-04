import SwiftUI

struct ContentView: View {
    @StateObject private var bleManager = BLETestManager()
    @ObservedObject private var preferences = PreferencesStore.shared
    @State private var showFullLyrics = false
    @State private var showDebugPage = false
    @State private var showPlaybackHistory = false
    @State private var showLyricDiagnostic = false
    @State private var showNowPlayingDiagnostic = false
    @State private var showSystemHealthOverview = false
    @State private var showPreferences = false

    var body: some View {
        NavigationStack {
            ZStack {
                PlayerBackgroundView(image: bleManager.albumArtImage)
                    .ignoresSafeArea()

                GeometryReader { proxy in
                    darkControlLayout(size: proxy.size)
                }
            }
            .toolbar(.hidden, for: .navigationBar)
            .sheet(isPresented: $showDebugPage) {
                DebugToolsView(bleManager: bleManager)
            }
            .sheet(isPresented: $showPlaybackHistory) {
                PlaybackHistoryView(bleManager: bleManager)
            }
            .sheet(isPresented: $showPreferences) {
                PreferencesView(
                    bleManager: bleManager,
                    onDismiss: { showPreferences = false }
                )
            }
            .sheet(isPresented: $showLyricDiagnostic) {
                LyricDiagnosticView(
                    bleManager: bleManager,
                    onDismiss: { showLyricDiagnostic = false }
                )
            }
            .sheet(isPresented: $showNowPlayingDiagnostic) {
                NowPlayingDiagnosticView(
                    bleManager: bleManager,
                    onDismiss: { showNowPlayingDiagnostic = false }
                )
            }
            .sheet(isPresented: $showSystemHealthOverview) {
                SystemHealthOverviewView(
                    bleManager: bleManager,
                    onDismiss: { showSystemHealthOverview = false }
                )
            }
            .fullScreenCover(isPresented: $showFullLyrics) {
                FullLyricsView(
                    title: nowPlayingInfo.title,
                    artist: nowPlayingInfo.artist,
                    albumArtImage: bleManager.albumArtImage,
                    lyrics: currentTrackFullLyrics,
                    currentIndex: currentFullLyricIndex,
                    positionMs: karaokePositionMs,
                    isPlaying: bleManager.isPlaying,
                    isConnected: isConnected,
                    onDismiss: { showFullLyrics = false },
                    onPrevious: bleManager.sendPrevious,
                    onPlayPause: bleManager.sendPlayPause,
                    onNext: bleManager.sendNext,
                    onSeekToLine: bleManager.seekToLyricLine,
                    showDiagnosticButton: isDebugMode,
                    onShowDiagnostic: {
                        showFullLyrics = false
                        showLyricDiagnostic = true
                    }
                )
            }
            .onChange(of: displayedPositionMs) { _, newValue in
                bleManager.logKaraokeOffset(rawPositionMs: newValue)
            }
            .onChange(of: preferences.lyricDisplayMode) { _, _ in
                requestOptionalLyricsIfNeeded()
            }
            .onChange(of: showFullLyrics) { _, isPresented in
                if isPresented {
                    requestOptionalLyricsIfNeeded()
                }
            }
            .onChange(of: bleManager.fullLyrics) { _, _ in
                requestOptionalLyricsIfNeeded()
            }
            .onChange(of: preferences.appExperienceMode) { _, mode in
                if mode == .daily {
                    showDebugPage = false
                    showLyricDiagnostic = false
                    showNowPlayingDiagnostic = false
                    showSystemHealthOverview = false
                }
            }
        }
    }

    private func darkControlLayout(size: CGSize) -> some View {
        let horizontalPadding = size.width >= 430 ? CGFloat(32) : CGFloat(24)
        return darkMainPlayerPanel
            .padding(.horizontal, horizontalPadding)
            .padding(.top, 22)
            .padding(.bottom, 24)
            .frame(width: size.width, height: size.height)
    }

    private var darkMainPlayerPanel: some View {
        VStack(spacing: 0) {
            darkSystemHeader

            Spacer(minLength: 24)

            darkTrackInfoSection

            Spacer(minLength: 28)

            darkLyricsSection

            Spacer(minLength: 22)

            darkProgressSection
                .padding(.bottom, 26)

            darkControlSection
                .padding(.bottom, 30)

            darkVolumeControl
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var darkSystemHeader: some View {
        HStack(spacing: 12) {
            Button {
                bleManager.scanSonyFromMenu()
            } label: {
                HStack(spacing: 10) {
                    Circle()
                        .fill(systemState.connection.color)
                        .frame(width: 9, height: 9)
                        .shadow(color: systemState.connection.color.opacity(0.55), radius: 7)

                    Text("Sony PlayerAgent")
                        .font(.system(size: 15, weight: .semibold, design: .rounded))
                        .foregroundStyle(.white.opacity(0.94))
                        .lineLimit(1)

                    Text(systemState.connection.title)
                        .font(.system(size: 14, weight: .semibold, design: .rounded))
                        .foregroundStyle(systemState.connection.color.opacity(0.98))
                        .lineLimit(1)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(PressScaleButtonStyle(pressedScale: 0.98))
            .accessibilityLabel("连接状态，点按扫描或重连")

            Spacer()

            darkMenuButton
        }
    }

    private var darkMenuButton: some View {
        Menu {
            Button {
                bleManager.scanSonyFromMenu()
            } label: {
                Label("扫描 / 重连", systemImage: "antenna.radiowaves.left.and.right")
            }

            Button {
                showPlaybackHistory = true
            } label: {
                Label("播放历史", systemImage: "clock.arrow.circlepath")
            }

            Button {
                showPreferences = true
            } label: {
                Label("更多设置", systemImage: "gearshape")
            }

            Button {
                bleManager.toggleAppExperienceMode()
            } label: {
                Label(
                    preferences.appExperienceMode.toggleTitle,
                    systemImage: isDebugMode ? "person.fill" : "ladybug.fill"
                )
            }

            if isDebugMode {
                Divider()

                Button {
                    showSystemHealthOverview = true
                } label: {
                    Label("系统健康总览", systemImage: "heart.text.square")
                }

                Button {
                    showNowPlayingDiagnostic = true
                } label: {
                    Label("当前歌曲诊断", systemImage: "waveform.path.ecg.rectangle")
                }

                Button {
                    bleManager.requestLyricDiagnostic(manual: true)
                    showLyricDiagnostic = true
                } label: {
                    Label("歌词诊断中心", systemImage: "text.magnifyingglass")
                }

                Button {
                    showDebugPage = true
                } label: {
                    Label("调试工具", systemImage: "slider.horizontal.3")
                }
            }

            Divider()

            Picker("歌词显示", selection: lyricDisplayModeBinding) {
                ForEach(LyricDisplayMode.allCases) { mode in
                    Text(mode.menuTitle).tag(mode)
                }
            }
        } label: {
            Image(systemName: "gearshape")
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(.white.opacity(0.86))
                .frame(width: 42, height: 42)
                .background(.white.opacity(0.05), in: Circle())
                .overlay {
                    Circle().stroke(.white.opacity(0.08), lineWidth: 1)
                }
        }
        .buttonStyle(PressScaleButtonStyle(pressedScale: 0.96))
        .accessibilityLabel("设置")
    }

    private var darkTrackInfoSection: some View {
        HStack(spacing: 34) {
            albumArtwork(size: 214)

            VStack(alignment: .leading, spacing: 14) {
                Text(nowPlayingInfo.title)
                    .font(.system(size: 34, weight: .bold, design: .rounded))
                    .foregroundStyle(.white)
                    .lineLimit(2)
                    .minimumScaleFactor(0.58)

                Text(nowPlayingInfo.artist)
                    .font(.system(size: 22, weight: .medium, design: .rounded))
                    .foregroundStyle(.white.opacity(0.76))
                    .lineLimit(1)

                Text("专辑 · \(nowPlayingInfo.album)")
                    .font(.system(size: 16, weight: .medium, design: .rounded))
                    .foregroundStyle(.white.opacity(0.48))
                    .lineLimit(1)

                DarkPlaybackStatusBadge(state: uiState.playback)
                    .padding(.top, 10)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var darkLyricsSection: some View {
        HStack(spacing: 22) {
            DarkLyricSideDots(color: uiState.playback.accentColor)

            VStack(spacing: 18) {
                Text(lyricPreviewLine(offset: -1))
                    .font(.system(size: 20, weight: .medium, design: .rounded))
                    .foregroundStyle(.white.opacity(0.42))
                    .multilineTextAlignment(.center)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)

                KaraokeLyricText(
                    text: lyricPreviewLine(offset: 0),
                    progress: currentLyricProgress,
                    words: lyricPreviewLineModel(offset: 0)?.words ?? [],
                    positionMs: karaokePositionMs,
                    highlightColor: uiState.playback.accentColor,
                    normalColor: Color.white.opacity(0.94),
                    font: .system(size: 34, weight: .bold, design: .rounded),
                    lineLimit: 2,
                    alignment: .center
                )
                .minimumScaleFactor(0.62)
                .frame(maxWidth: .infinity)
                .transition(.opacity.combined(with: .move(edge: .bottom)))

                Text(lyricPreviewLine(offset: 1))
                    .font(.system(size: 21, weight: .medium, design: .rounded))
                    .foregroundStyle(.white.opacity(0.48))
                    .multilineTextAlignment(.center)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)

                GeometryReader { proxy in
                    DarkLyricRhythmLine(
                        state: uiState.playback,
                        trackSeed: "\(nowPlayingInfo.title)|\(nowPlayingInfo.artist)|\(nowPlayingInfo.album)",
                        lyricProgress: currentLyricProgress,
                        wordSignature: "\(bleManager.currentWordLineIndex):\(bleManager.currentWordIndex)",
                        positionMs: karaokePositionMs
                    )
                        .frame(width: proxy.size.width * 0.5, height: 48)
                        .frame(maxWidth: .infinity)
                }
                .frame(height: 48)
                .padding(.top, 10)
            }
            .id(lyricPreviewIdentity)
            .animation(.easeInOut(duration: 0.24), value: lyricPreviewIdentity)
            .contentShape(Rectangle())
            .onTapGesture {
                if bleManager.fullLyrics.isEmpty {
                    bleManager.sendGetFullLyrics(force: true)
                }
                showFullLyrics = true
            }

            DarkLyricSideDots(color: uiState.playback.accentColor)
        }
        .accessibilityLabel("当前歌词，点按打开完整歌词")
    }

    private var darkProgressSection: some View {
        HStack(alignment: .center, spacing: 14) {
            Text(format(milliseconds: displayedPositionMs))
                .font(.caption.monospacedDigit().weight(.semibold))
                .foregroundStyle(.white.opacity(0.72))
                .frame(width: 46, alignment: .leading)

            Slider(
                value: Binding(
                    get: {
                        Double(
                            bleManager.isSeeking
                                ? bleManager.seekPositionMs
                                : bleManager.displayPositionMs
                        )
                    },
                    set: { value in
                        bleManager.updateSeekPosition(value)
                    }
                ),
                in: 0...Double(max(bleManager.durationMs, 1)),
                onEditingChanged: { editing in
                    if editing {
                        bleManager.beginSeeking()
                    } else {
                        bleManager.finishSeeking()
                    }
                }
            )
            .tint(uiState.playback.accentColor)
            .disabled(!isConnected || bleManager.durationMs <= 0)

            Text(format(milliseconds: bleManager.durationMs))
                .font(.caption.monospacedDigit().weight(.semibold))
                .foregroundStyle(.white.opacity(0.72))
                .frame(width: 46, alignment: .trailing)
        }
        .animation(
            bleManager.isSeeking ? nil : .linear(duration: 0.18),
            value: bleManager.positionMs
        )
    }

    private var darkControlSection: some View {
        HStack(spacing: 58) {
            darkTransportButton(
                title: "上一首",
                systemImage: "backward.end.fill",
                action: bleManager.sendPrevious
            )

            DarkDynamicPlayButton(
                state: uiState.playback,
                isEnabled: isConnected,
                action: bleManager.sendPlayPause
            )

            darkTransportButton(
                title: "下一首",
                systemImage: "forward.end.fill",
                action: bleManager.sendNext
            )
        }
        .frame(maxWidth: .infinity)
        .disabled(!isConnected)
        .opacity(isConnected ? 1 : 0.48)
    }

    private var darkVolumeControl: some View {
        HStack(spacing: 16) {
            Image(systemName: volumeIcon)
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 26)

            Slider(
                value: Binding(
                    get: {
                        Double(
                            bleManager.isVolumeSeeking
                                ? bleManager.volumeSeekValue
                                : bleManager.volumeCurrent
                        )
                    },
                    set: { value in
                        bleManager.updateVolumeSeekValue(value)
                    }
                ),
                in: 0...Double(max(bleManager.volumeMax, 1)),
                step: 1,
                onEditingChanged: { editing in
                    if editing {
                        bleManager.beginVolumeSeeking()
                    } else {
                        bleManager.finishVolumeSeeking()
                    }
                }
            )
            .tint(uiState.playback.accentColor)
            .disabled(!isConnected || bleManager.volumeMax <= 0)

            Text("\(displayedVolume) / \(bleManager.volumeMax)")
                .font(.system(size: 15, weight: .semibold, design: .rounded).monospacedDigit())
                .foregroundStyle(.white.opacity(0.84))
                .frame(width: 64, alignment: .trailing)
                .contentTransition(.numericText())
        }
        .disabled(!isConnected)
        .opacity(isConnected ? 1 : 0.48)
        .animation(.easeInOut(duration: 0.16), value: displayedVolume)
    }

    private var systemState: DarkControlSystemState {
        DarkControlSystemState(connection: DarkControlConnectionState(rawValue: bleManager.connectionDisplayState) ?? .disconnected)
    }

    private var playbackState: DarkControlPlaybackState {
        DarkControlPlaybackState(
            isPlaying: bleManager.isPlaying,
            isLoading: !isConnected && bleManager.connectionDisplayState != "disconnected"
        )
    }

    private var uiState: DarkControlUIState {
        DarkControlUIState(system: systemState, playback: playbackState.visualState(connection: systemState.connection))
    }

    private func albumArtwork(size: CGFloat) -> some View {
        ZStack(alignment: .bottomLeading) {
            if let image = bleManager.albumArtImage {
                Image(uiImage: image)
                    .resizable()
                    .interpolation(.high)
                    .antialiased(true)
                    .scaledToFill()
            } else {
                DefaultAlbumArtView()
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: 25, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 25, style: .continuous)
                .stroke(.white.opacity(0.12), lineWidth: 1)
        }
        .shadow(color: .black.opacity(0.36), radius: 22, x: 0, y: 14)
        .accessibilityLabel("当前歌曲封面")
    }

    private func darkTransportButton(
        title: String,
        systemImage: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 30, weight: .bold))
                .foregroundStyle(.white)
                .frame(width: 62, height: 62)
                .contentShape(Circle())
        }
        .buttonStyle(PressScaleButtonStyle(pressedScale: 0.90))
        .accessibilityLabel(title)
    }

    private var connectionSection: some View {
        HStack(spacing: 12) {
            Button {
                bleManager.scanSonyFromMenu()
            } label: {
                HStack(spacing: 8) {
                    Circle()
                        .fill(connectionColor)
                        .frame(width: 8, height: 8)
                        .shadow(color: connectionColor.opacity(0.42), radius: 5)

                    Text(connectionStatusTitle)
                        .font(.footnote.weight(.medium))
                        .foregroundStyle(.white.opacity(0.88))
                        .lineLimit(1)
                }
                .padding(.horizontal, 14)
                .frame(height: 42)
                .contentShape(Capsule())
            }
            .buttonStyle(PressScaleButtonStyle(pressedScale: 0.97))
            .background(.white.opacity(0.070), in: Capsule())
            .overlay {
                Capsule().stroke(.white.opacity(0.070), lineWidth: 1)
            }
            .accessibilityLabel("连接状态，点按扫描或重连")

            Spacer()

            Menu {
                Button {
                    bleManager.scanSonyFromMenu()
                } label: {
                    Label("扫描 / 重连", systemImage: "antenna.radiowaves.left.and.right")
                }

                Button {
                    showPlaybackHistory = true
                } label: {
                    Label("播放历史", systemImage: "clock.arrow.circlepath")
                }

                Button {
                    showPreferences = true
                } label: {
                    Label("设置", systemImage: "gearshape")
                }

                Button {
                    bleManager.toggleAppExperienceMode()
                } label: {
                    Label(
                        preferences.appExperienceMode.toggleTitle,
                        systemImage: isDebugMode ? "person.fill" : "ladybug.fill"
                    )
                }

                if isDebugMode {
                    Divider()

                    Button {
                        showSystemHealthOverview = true
                    } label: {
                        Label("系统健康总览", systemImage: "heart.text.square")
                    }

                    Button {
                        showNowPlayingDiagnostic = true
                    } label: {
                        Label("当前歌曲诊断", systemImage: "waveform.path.ecg.rectangle")
                    }

                    Button {
                        bleManager.requestLyricDiagnostic(manual: true)
                        showLyricDiagnostic = true
                    } label: {
                        Label("歌词诊断中心", systemImage: "text.magnifyingglass")
                    }

                    Button {
                        showDebugPage = true
                    } label: {
                        Label("调试工具", systemImage: "slider.horizontal.3")
                    }
                }

                Divider()

                Picker("歌词显示", selection: lyricDisplayModeBinding) {
                    ForEach(LyricDisplayMode.allCases) { mode in
                        Text(mode.menuTitle).tag(mode)
                    }
                }
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 19, weight: .bold))
                    .frame(width: 42, height: 42)
            }
            .buttonStyle(PressScaleButtonStyle(pressedScale: 0.96))
            .background(.white.opacity(0.075), in: Circle())
            .overlay {
                Circle().stroke(.white.opacity(0.075), lineWidth: 1)
            }
            .accessibilityLabel("更多操作")
        }
        .foregroundStyle(.white)
        .animation(.easeInOut(duration: 0.2), value: bleManager.connectionDisplayState)
    }

    private var nowPlayingSection: some View {
        VStack(spacing: 9) {
            albumArtView
                .id(albumArtIdentity)
                .transition(.opacity.combined(with: .scale(scale: 0.98)))
                .animation(.easeInOut(duration: 0.28), value: albumArtIdentity)

            VStack(spacing: 4) {
                Text(nowPlayingInfo.title)
                    .font(.system(size: 32, weight: .bold, design: .rounded))
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                    .minimumScaleFactor(0.58)

                Text(nowPlayingInfo.artist)
                    .font(.system(size: 18, weight: .medium, design: .rounded))
                    .foregroundStyle(.white.opacity(0.72))
                    .lineLimit(1)
                    .padding(.top, 4)

                Text(nowPlayingInfo.album)
                    .font(.system(size: 15, weight: .regular, design: .rounded))
                    .foregroundStyle(.white.opacity(0.46))
                    .lineLimit(1)
            }

            HStack(spacing: 8) {
                Image(systemName: bleManager.isPlaying ? "music.note" : "pause.fill")
                Text(bleManager.isPlaying ? "播放中" : "已暂停")
            }
            .font(.caption.weight(.semibold))
            .foregroundStyle(
                bleManager.isPlaying
                    ? Color.green.opacity(0.95)
                    : Color.white.opacity(0.62)
            )
            .padding(.horizontal, 11)
            .padding(.vertical, 6)
            .background(.white.opacity(0.095), in: Capsule())
            .overlay {
                Capsule().stroke(.white.opacity(0.08), lineWidth: 1)
            }
            .animation(.spring(response: 0.28, dampingFraction: 0.78), value: bleManager.isPlaying)
        }
        .foregroundStyle(.white)
        .frame(maxWidth: .infinity)
    }

    private var albumArtView: some View {
        ZStack {
            if let image = bleManager.albumArtImage {
                Image(uiImage: image)
                    .resizable()
                    .interpolation(.high)
                    .antialiased(true)
                    .scaledToFill()
            } else {
                DefaultAlbumArtView()
            }
        }
        .frame(width: albumArtSize, height: albumArtSize)
        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .stroke(.white.opacity(0.14), lineWidth: 1)
        }
        .shadow(color: .black.opacity(0.28), radius: 22, y: 12)
        .accessibilityLabel("当前歌曲封面")
    }

    private var lyricCard: some View {
        VStack(spacing: 8) {
            Text("当前歌词")
                .font(.system(size: 12, weight: .semibold, design: .rounded))
                .foregroundStyle(.white.opacity(0.46))
                .textCase(.uppercase)
                .tracking(1.2)

            if currentTrackFullLyrics.isEmpty {
                VStack(spacing: 8) {
                    Text(currentLyricText)
                        .font(.system(size: 28, weight: .semibold, design: .rounded))
                        .foregroundStyle(
                            currentLyricText == "暂无歌词"
                                ? Color.white.opacity(0.58)
                                : Color.white.opacity(0.93)
                        )
                        .multilineTextAlignment(.center)
                        .lineLimit(2)
                        .minimumScaleFactor(0.68)
                        .frame(maxWidth: .infinity, minHeight: 48)

                    if currentLyricText == "暂无歌词" {
                        if isDebugMode {
                            Text("原因：\(bleManager.lyricDiagnostic?.statusTitle ?? "正在确认")")
                                .font(.caption.weight(.medium))
                                .foregroundStyle(.white.opacity(0.54))
                                .lineLimit(1)

                            Button {
                                bleManager.requestLyricDiagnostic(manual: true)
                                showLyricDiagnostic = true
                            } label: {
                                Text("查看原因")
                                    .font(.caption.weight(.bold))
                                    .foregroundStyle(.white)
                                    .padding(.horizontal, 14)
                                    .frame(height: 30)
                                    .background(.white.opacity(0.10), in: Capsule())
                            }
                            .buttonStyle(.plain)
                        } else {
                            Text("提示：可在 Sony QQ音乐打开歌词/桌面歌词后稍等")
                                .font(.caption.weight(.medium))
                                .foregroundStyle(.white.opacity(0.54))
                                .multilineTextAlignment(.center)
                                .lineLimit(2)

                            Button {
                                bleManager.refreshCurrentLyricFromNowPlayingDiagnostics()
                            } label: {
                                Text("刷新歌词")
                                    .font(.caption.weight(.bold))
                                    .foregroundStyle(.white)
                                    .padding(.horizontal, 14)
                                    .frame(height: 30)
                                    .background(.white.opacity(0.10), in: Capsule())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                .frame(maxWidth: .infinity, minHeight: 82)
            } else {
                VStack(spacing: 5) {
                    Text(lyricPreviewLine(offset: -1))
                        .font(.system(size: 17, weight: .medium, design: .rounded))
                        .foregroundStyle(.white.opacity(0.44))
                        .lineLimit(1)
                    KaraokeLyricText(
                        text: lyricPreviewLine(offset: 0),
                        progress: currentLyricProgress,
                        words: lyricPreviewLineModel(offset: 0)?.words ?? [],
                        positionMs: karaokePositionMs,
                        highlightColor: Color.green.opacity(0.98),
                        normalColor: Color.white.opacity(0.48),
                        font: .system(size: 24, weight: .bold, design: .rounded),
                        lineLimit: 2,
                        alignment: .center
                    )
                    .minimumScaleFactor(0.72)
                    if let auxiliary = lyricPreviewAuxiliaryText(offset: 0) {
                        Text(auxiliary)
                            .font(.system(size: 14, weight: .medium, design: .rounded))
                            .foregroundStyle(.white.opacity(0.52))
                            .lineLimit(1)
                            .minimumScaleFactor(0.72)
                    }
                    Text(lyricPreviewLine(offset: 1))
                        .font(.system(size: 17, weight: .medium, design: .rounded))
                        .foregroundStyle(.white.opacity(0.44))
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity, minHeight: 82)
            }
        }
        .padding(.horizontal, 4)
        .padding(.vertical, 4)
        .id(lyricPreviewIdentity)
        .transition(.opacity)
        .animation(.easeInOut(duration: 0.22), value: lyricPreviewIdentity)
        .contentShape(Rectangle())
        .onTapGesture {
            if bleManager.fullLyrics.isEmpty {
                bleManager.sendGetFullLyrics(force: true)
            }
            showFullLyrics = true
        }
        .accessibilityLabel("打开完整歌词")
    }

    private var progressSection: some View {
        VStack(spacing: 6) {
            Slider(
                value: Binding(
                    get: {
                        Double(
                            bleManager.isSeeking
                                ? bleManager.seekPositionMs
                                : bleManager.displayPositionMs
                        )
                    },
                    set: { value in
                        bleManager.updateSeekPosition(value)
                    }
                ),
                in: 0...Double(max(bleManager.durationMs, 1)),
                onEditingChanged: { editing in
                    if editing {
                        bleManager.beginSeeking()
                    } else {
                        bleManager.finishSeeking()
                    }
                }
            )
            .tint(.white.opacity(0.94))
            .disabled(!isConnected || bleManager.durationMs <= 0)

            HStack {
                Text(format(milliseconds: displayedPositionMs))
                Spacer()
                Text(format(milliseconds: bleManager.durationMs))
            }
            .font(.caption2.monospacedDigit().weight(.medium))
            .foregroundStyle(.white.opacity(0.58))
        }
        .padding(.horizontal, 4)
        .animation(
            bleManager.isSeeking ? nil : .linear(duration: 0.18),
            value: bleManager.positionMs
        )
    }

    private var playbackControls: some View {
        HStack(spacing: 26) {
            playerControlButton(
                title: "上一首",
                systemImage: "backward.fill",
                size: 52,
                fontSize: 22,
                action: bleManager.sendPrevious
            )

            Button(action: bleManager.sendPlayPause) {
                Image(systemName: bleManager.isPlaying ? "pause.fill" : "play.fill")
                    .font(.system(size: 30, weight: .bold))
                    .foregroundStyle(.black)
                    .frame(width: 70, height: 70)
                    .background(.white, in: Circle())
                    .shadow(color: .black.opacity(0.18), radius: 12, y: 7)
                    .scaleEffect(bleManager.isPlaying ? 1.0 : 0.96)
            }
            .buttonStyle(PressScaleButtonStyle(pressedScale: 0.92))
            .accessibilityLabel("播放 / 暂停")

            playerControlButton(
                title: "下一首",
                systemImage: "forward.fill",
                size: 52,
                fontSize: 22,
                action: bleManager.sendNext
            )
        }
        .frame(maxWidth: .infinity)
        .disabled(!isConnected)
        .opacity(isConnected ? 1 : 0.46)
        .animation(.spring(response: 0.28, dampingFraction: 0.72), value: bleManager.isPlaying)
    }

    private var volumeSection: some View {
        HStack(spacing: 12) {
            Image(systemName: volumeIcon)
                .font(.headline)
                .frame(width: 24)

            Slider(
                value: Binding(
                    get: {
                        Double(
                            bleManager.isVolumeSeeking
                                ? bleManager.volumeSeekValue
                                : bleManager.volumeCurrent
                        )
                    },
                    set: { value in
                        bleManager.updateVolumeSeekValue(value)
                    }
                ),
                in: 0...Double(max(bleManager.volumeMax, 1)),
                step: 1,
                onEditingChanged: { editing in
                    if editing {
                        bleManager.beginVolumeSeeking()
                    } else {
                        bleManager.finishVolumeSeeking()
                    }
                }
            )
            .tint(.white.opacity(0.92))
            .disabled(!isConnected || bleManager.volumeMax <= 0)

            Text("\(displayedVolume) / \(bleManager.volumeMax)")
                .font(.caption.monospacedDigit().weight(.semibold))
                .foregroundStyle(.white.opacity(0.76))
                .frame(width: 58, alignment: .trailing)
                .contentTransition(.numericText())
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .foregroundStyle(.white)
        .background(.white.opacity(0.070), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(.white.opacity(0.08), lineWidth: 1)
        }
        .disabled(!isConnected)
        .opacity(isConnected ? 1 : 0.52)
        .animation(.easeInOut(duration: 0.16), value: displayedVolume)
    }

    private var connectionColor: Color {
        switch bleManager.connectionDisplayState {
        case "connected":
            return .green
        case "reconnecting":
            return .orange
        case "disconnected":
            return .secondary
        default:
            return .secondary
        }
    }

    private var connectionStatusTitle: String {
        switch bleManager.connectionDisplayState {
        case "connected":
            return "Sony 已连接"
        case "reconnecting":
            return "正在重连"
        case "disconnected":
            return "未连接"
        default:
            return "未连接"
        }
    }

    private var isConnected: Bool {
        bleManager.connectionDisplayState == "connected"
    }

    private var isDebugMode: Bool {
        preferences.appExperienceMode == .debug
    }

    private var displayedPositionMs: Int64 {
        bleManager.isSeeking ? bleManager.seekPositionMs : bleManager.displayPositionMs
    }

    private var karaokePositionMs: Int64 {
        bleManager.karaokePositionMs(rawPositionMs: displayedPositionMs)
    }

    private var displayedVolume: Int {
        bleManager.isVolumeSeeking ? bleManager.volumeSeekValue : bleManager.volumeCurrent
    }

    private var nowPlayingInfo: NowPlayingInfoProvider {
        NowPlayingInfoProvider(
            title: displayText(bleManager.title, fallback: "Sony Music"),
            artist: displayText(bleManager.artist, fallback: "未知歌手"),
            album: displayText(bleManager.album, fallback: "未知专辑"),
            albumArt: bleManager.albumArtImage,
            positionMs: displayedPositionMs,
            durationMs: bleManager.durationMs,
            isPlaying: bleManager.isPlaying
        )
    }

    private var lyricDisplayMode: LyricDisplayMode {
        preferences.lyricDisplayMode
    }

    private func requestOptionalLyricsIfNeeded() {
        bleManager.requestFullLyricsOptionalFieldsIfNeeded(displayMode: lyricDisplayMode)
    }

    private var lyricDisplayModeBinding: Binding<LyricDisplayMode> {
        Binding(
            get: { lyricDisplayMode },
            set: { preferences.lyricDisplayMode = $0 }
        )
    }

    private var currentLyricText: String {
        let trimmed = bleManager.lyric.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "暂无歌词" : trimmed
    }

    private var currentFullLyricIndex: Int {
        LyricTimelineHelper.currentIndex(
            lines: currentTrackFullLyrics,
            positionMs: karaokePositionMs
        ) ?? -1
    }

    private var currentLyricProgress: Double {
        LyricTimelineHelper.lineProgress(
            lines: currentTrackFullLyrics,
            index: currentFullLyricIndex,
            positionMs: karaokePositionMs
        )
    }

    private var lyricPreviewIdentity: String {
        if currentTrackFullLyrics.isEmpty {
            return currentLyricText
        }
        return "\(bleManager.fullLyricsTrackId)-\(currentFullLyricIndex)"
    }

    private func lyricPreviewLine(offset: Int) -> String {
        guard let line = lyricPreviewLineModel(offset: offset) else {
            return offset == 0 ? currentLyricText : " "
        }
        let text = line.text.trimmingCharacters(in: .whitespacesAndNewlines)
        return text.isEmpty ? " " : text
    }

    private func lyricPreviewLineModel(offset: Int) -> LyricLine? {
        let index = currentFullLyricIndex + offset
        guard currentTrackFullLyrics.indices.contains(index) else {
            return nil
        }
        return currentTrackFullLyrics[index]
    }

    private func lyricPreviewAuxiliaryText(offset: Int) -> String? {
        guard let line = lyricPreviewLineModel(offset: offset) else { return nil }
        if lyricDisplayMode.showsTranslation,
           let translation = sanitizedSecondaryText(line.translation) {
            return translation
        }
        if lyricDisplayMode.showsRomanization,
           let romanization = sanitizedSecondaryText(line.romanization) {
            return romanization
        }
        return nil
    }

    private var currentTrackFullLyrics: [LyricLine] {
        bleManager.isFullLyricsCurrent ? bleManager.fullLyrics : []
    }

    private var albumArtIdentity: String {
        if bleManager.albumArtImage == nil {
            return "default-\(bleManager.title)-\(bleManager.artist)"
        }
        return "art-\(bleManager.title)-\(bleManager.artist)-\(bleManager.album)-\(bleManager.artworkDisplayQuality.label)"
    }

    private var albumArtSize: CGFloat {
        preferences.artworkDisplaySize.pointSize
    }

    private var volumeIcon: String {
        if displayedVolume <= 0 {
            return "speaker.slash.fill"
        }
        if displayedVolume < max(bleManager.volumeMax / 2, 1) {
            return "speaker.wave.1.fill"
        }
        return "speaker.wave.2.fill"
    }

    private func playerControlButton(
        title: String,
        systemImage: String,
        size: CGFloat,
        fontSize: CGFloat,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: fontSize, weight: .bold))
                .foregroundStyle(.white)
                .frame(width: size, height: size)
                .background(.white.opacity(0.10), in: Circle())
                .overlay {
                    Circle().stroke(.white.opacity(0.10), lineWidth: 1)
                }
        }
        .buttonStyle(PressScaleButtonStyle(pressedScale: 0.92))
        .accessibilityLabel(title)
    }

    private func compactButton(
        title: String,
        systemImage: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Label(title, systemImage: systemImage)
                .font(.subheadline.weight(.semibold))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
        }
        .buttonStyle(PressScaleButtonStyle(pressedScale: 0.96))
        .background(.white.opacity(0.10), in: Capsule())
        .overlay {
            Capsule().stroke(.white.opacity(0.09), lineWidth: 1)
        }
    }

    private func displayText(_ value: String, fallback: String) -> String {
        value == "-" || value.isEmpty ? fallback : value
    }

    private func format(milliseconds: Int64) -> String {
        guard bleManager.durationMs > 0 else { return "00:00" }
        let totalSeconds = max(milliseconds, 0) / 1_000
        return String(format: "%02lld:%02lld", totalSeconds / 60, totalSeconds % 60)
    }
}

private struct PlayerBackgroundView: View {
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
                        .overlay(Color.black.opacity(0.28))
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
                    .frame(width: proxy.size.width, height: proxy.size.height)
                }

                LinearGradient(
                    colors: [
                        Color.black.opacity(0.28),
                        Color.black.opacity(0.12),
                        Color.black.opacity(0.80)
                    ],
                    startPoint: .top,
                    endPoint: .bottom
                )
                .frame(width: proxy.size.width, height: proxy.size.height)
            }
        }
    }
}

private struct NowPlayingInfoProvider {
    let title: String
    let artist: String
    let album: String
    let albumArt: UIImage?
    let positionMs: Int64
    let durationMs: Int64
    let isPlaying: Bool
}

private struct DarkControlSystemState {
    let connection: DarkControlConnectionState
}

private struct DarkControlPlaybackState {
    let isPlaying: Bool
    let isLoading: Bool

    func visualState(connection: DarkControlConnectionState) -> DarkPlaybackVisualState {
        switch connection {
        case .disconnected:
            return .stopped
        case .reconnecting:
            return .reconnecting
        case .connecting:
            return .loading
        case .connected:
            if isLoading {
                return .loading
            }
            return isPlaying ? .playing : .paused
        }
    }
}

private struct DarkControlUIState {
    let system: DarkControlSystemState
    let playback: DarkPlaybackVisualState
}

private enum DarkControlConnectionState: String {
    case connected
    case reconnecting
    case connecting
    case disconnected

    var title: String {
        switch self {
        case .connected:
            return "已连接"
        case .connecting:
            return "连接中"
        case .reconnecting:
            return "重连中"
        case .disconnected:
            return "未连接"
        }
    }

    var detail: String {
        switch self {
        case .connected:
            return "设备连接正常"
        case .connecting:
            return "正在连接 Sony"
        case .reconnecting:
            return "正在恢复连接"
        case .disconnected:
            return "请检查设备连接"
        }
    }

    var color: Color {
        switch self {
        case .connected:
            return .green
        case .connecting:
            return .orange
        case .reconnecting:
            return .purple
        case .disconnected:
            return .gray
        }
    }

    var icon: String {
        switch self {
        case .connected:
            return "checkmark.circle"
        case .connecting:
            return "dot.radiowaves.left.and.right"
        case .reconnecting:
            return "arrow.clockwise.circle"
        case .disconnected:
            return "link.slash"
        }
    }
}

private enum DarkPlaybackVisualState: Equatable {
    case playing
    case paused
    case loading
    case reconnecting
    case stopped

    var title: String {
        switch self {
        case .playing:
            return "正在播放"
        case .paused:
            return "已暂停"
        case .loading:
            return "加载中"
        case .reconnecting:
            return "重连中"
        case .stopped:
            return "已停止"
        }
    }

    var detail: String {
        switch self {
        case .playing:
            return "正在播放音乐"
        case .paused:
            return "音乐暂停状态"
        case .loading:
            return "内容加载中"
        case .reconnecting:
            return "设备重连中"
        case .stopped:
            return "音乐已停止"
        }
    }

    var accentColor: Color {
        switch self {
        case .playing:
            return .green
        case .paused:
            return .blue
        case .loading:
            return .orange
        case .reconnecting:
            return .purple
        case .stopped:
            return .gray
        }
    }

    var icon: String {
        switch self {
        case .playing:
            return "pause.fill"
        case .paused:
            return "play.fill"
        case .loading:
            return "circle.dotted"
        case .reconnecting:
            return "arrow.clockwise"
        case .stopped:
            return "stop.fill"
        }
    }
}

private struct DarkPlaybackStatusBadge: View {
    let state: DarkPlaybackVisualState

    var body: some View {
        HStack(spacing: 8) {
            if state == .playing {
                MicroWaveformBars(color: state.accentColor, height: 18, animated: true)
                    .frame(width: 25, height: 18)
            } else if state == .loading || state == .reconnecting {
                ProgressView()
                    .progressViewStyle(.circular)
                    .tint(state.accentColor)
                    .scaleEffect(0.62)
                    .frame(width: 20, height: 18)
            } else {
                Image(systemName: state.icon)
                    .font(.system(size: 12, weight: .bold))
            }

            Text(state.title)
                .font(.system(size: 15, weight: .bold, design: .rounded))
        }
        .foregroundStyle(state.accentColor)
        .padding(.horizontal, 13)
        .padding(.vertical, 8)
        .background(state.accentColor.opacity(0.12), in: Capsule())
        .overlay {
            Capsule().stroke(state.accentColor.opacity(0.14), lineWidth: 1)
        }
    }
}

private struct DarkDynamicPlayButton: View {
    let state: DarkPlaybackVisualState
    let isEnabled: Bool
    let action: () -> Void

    @State private var isPulsing = false

    var body: some View {
        Button(action: action) {
            ZStack {
                Circle()
                    .fill(
                        RadialGradient(
                            colors: [
                                state.accentColor.opacity(0.95),
                                state.accentColor.opacity(0.72),
                                Color.black.opacity(0.28)
                            ],
                            center: .topLeading,
                            startRadius: 2,
                            endRadius: 58
                        )
                    )
                    .frame(width: 88, height: 88)
                    .overlay {
                        Circle()
                            .strokeBorder(.white.opacity(0.22), lineWidth: 1)
                    }
                    .shadow(
                        color: state == .playing ? state.accentColor.opacity(isPulsing ? 0.58 : 0.22) : .clear,
                        radius: state == .playing ? (isPulsing ? 24 : 10) : 0
                    )
                    .scaleEffect(state == .playing && isPulsing ? 1.035 : 1.0)

                if state == .loading || state == .reconnecting {
                    ProgressView()
                        .progressViewStyle(.circular)
                        .tint(.white)
                        .scaleEffect(1.18)
                } else {
                    Image(systemName: state.icon)
                        .font(.system(size: 34, weight: .bold))
                        .foregroundStyle(.white)
                }
            }
            .contentShape(Circle())
        }
        .buttonStyle(PressScaleButtonStyle(pressedScale: 0.90))
        .disabled(!isEnabled || state == .loading || state == .reconnecting)
        .opacity(isEnabled ? 1 : 0.55)
        .onAppear {
            isPulsing = true
        }
        .animation(
            state == .playing
                ? .easeInOut(duration: 1.15).repeatForever(autoreverses: true)
                : .default,
            value: isPulsing
        )
        .animation(.spring(response: 0.26, dampingFraction: 0.72), value: state)
        .accessibilityLabel("播放 / 暂停")
    }
}

private struct MicroWaveformBars: View {
    let color: Color
    let height: CGFloat
    var animated: Bool

    @State private var phase = false

    private let multipliers: [CGFloat] = [0.38, 0.76, 0.52, 0.92, 0.44]

    var body: some View {
        HStack(alignment: .center, spacing: 3) {
            ForEach(multipliers.indices, id: \.self) { index in
                Capsule()
                    .fill(color)
                    .frame(
                        width: 3,
                        height: height * (animated && phase ? multipliers[index] : max(0.35, multipliers.reversed()[index]))
                    )
                    .animation(
                        animated
                            ? .easeInOut(duration: 0.55 + Double(index) * 0.10).repeatForever(autoreverses: true)
                            : .default,
                        value: phase
                    )
            }
        }
        .onAppear {
            if animated {
                phase = true
            }
        }
    }
}

private struct DarkLyricRhythmLine: View {
    let state: DarkPlaybackVisualState
    let trackSeed: String
    let lyricProgress: Double
    let wordSignature: String
    let positionMs: Int64

    @StateObject private var spectrumEngine = NaturalPseudoSpectrumEngine()

    private var opacity: Double {
        switch state {
        case .playing:
            return 0.78
        case .paused:
            return 0.36
        case .loading, .reconnecting:
            return 0.28
        case .stopped:
            return 0.18
        }
    }

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30.0)) { timeline in
            let time = timeline.date.timeIntervalSinceReferenceDate
            GeometryReader { proxy in
                let levels = spectrumEngine.levels(
                    time: time,
                    width: proxy.size.width,
                    state: state,
                    trackSeed: trackSeed,
                    lyricProgress: lyricProgress,
                    wordSignature: wordSignature,
                    positionMs: positionMs
                )
                spectrumCanvas(levels: levels)
            }
        }
        .frame(maxWidth: .infinity)
    }

    private func spectrumCanvas(levels: [Double]) -> some View {
        Canvas { context, size in
            guard !levels.isEmpty, size.width > 0, size.height > 0 else { return }

            let accent = state.accentColor
            let centerY = size.height / 2
            let usableWidth = size.width
            let count = levels.count
            let step = usableWidth / CGFloat(count)
            let gap = max(2.0, min(4.0, step * 0.28))
            let barWidth = max(2.4, step - gap)
            let minHeight = max(2.4, size.height * 0.12)
            let maxHeight = size.height * 0.92

            let lineRect = CGRect(x: 0, y: centerY - 0.6, width: usableWidth, height: 1.2)
            context.fill(
                Path(roundedRect: lineRect, cornerRadius: 0.6),
                with: .linearGradient(
                    Gradient(colors: [
                        .clear,
                        accent.opacity(opacity * 0.20),
                        accent.opacity(opacity * 0.30),
                        accent.opacity(opacity * 0.20),
                        .clear
                    ]),
                    startPoint: CGPoint(x: 0, y: centerY),
                    endPoint: CGPoint(x: usableWidth, y: centerY)
                )
            )

            for index in levels.indices {
                let x = CGFloat(index) * step + step / 2
                let heightRatio = max(0, min(1, levels[index]))
                let height = minHeight + CGFloat(heightRatio) * (maxHeight - minHeight)
                let bandOpacity = opacity * (0.52 + heightRatio * 0.48)
                let rect = CGRect(
                    x: x - barWidth / 2,
                    y: centerY - height / 2,
                    width: barWidth,
                    height: height
                )
                let path = Path(roundedRect: rect, cornerRadius: barWidth / 2)

                context.fill(
                    path,
                    with: .linearGradient(
                        Gradient(colors: [
                            accent.opacity(bandOpacity * 0.30),
                            accent.opacity(bandOpacity),
                            accent.opacity(bandOpacity * 0.44)
                        ]),
                        startPoint: CGPoint(x: rect.midX, y: rect.maxY),
                        endPoint: CGPoint(x: rect.midX, y: rect.minY)
                    )
                )
            }
        }
    }
}

private final class NaturalPseudoSpectrumEngine: ObservableObject {
    private struct Constants {
        static let minBars = 28
        static let maxBars = 44
        static let attack = 0.38
        static let release = 0.13
        static let pauseRelease = 0.08
        static let stoppedRelease = 0.14
        static let wordPulseDuration = 0.20
        static let wordPulseDebounce = 0.12
        static let entranceDuration = 0.60
        static let idleBaseline = 0.10
    }

    private var levels: [Double] = []
    private var lastTime: TimeInterval?
    private var lastTrackSeed = ""
    private var trackStartedAt: TimeInterval = 0
    private var lastWordSignature = ""
    private var wordPulseStartedAt: TimeInterval?
    private var lastWordPulseAt: TimeInterval = 0
    private var stableSeed: UInt64 = 0

    func levels(
        time: TimeInterval,
        width: CGFloat,
        state: DarkPlaybackVisualState,
        trackSeed: String,
        lyricProgress: Double,
        wordSignature: String,
        positionMs: Int64
    ) -> [Double] {
        let count = barCount(for: width)
        ensureLevelCount(count)
        updateSeedIfNeeded(trackSeed: trackSeed, time: time)
        updateWordPulseIfNeeded(wordSignature: wordSignature, time: time)

        let delta = min(max(time - (lastTime ?? time), 1.0 / 60.0), 0.12)
        lastTime = time

        let rawTargets = rawTargets(
            count: count,
            time: time,
            state: state,
            lyricProgress: lyricProgress,
            positionMs: positionMs
        )
        let targets = smoothTargets(rawTargets)

        for index in levels.indices {
            let target = targets[index]
            let coefficient: Double
            if target > levels[index] {
                coefficient = Constants.attack
            } else if state == .paused {
                coefficient = Constants.pauseRelease
            } else if state == .stopped {
                coefficient = Constants.stoppedRelease
            } else {
                coefficient = Constants.release
            }
            let frameAdjusted = 1.0 - pow(1.0 - coefficient, delta * 60.0)
            levels[index] += (target - levels[index]) * frameAdjusted
        }

        return levels
    }

    private func rawTargets(
        count: Int,
        time: TimeInterval,
        state: DarkPlaybackVisualState,
        lyricProgress: Double,
        positionMs: Int64
    ) -> [Double] {
        let playbackPhase = Double(positionMs % 12_000) / 12_000.0
        let seedPhase = Double(stableSeed % 1_000) / 1_000.0 * Double.pi * 2.0
        let phase = (state == .paused || state == .stopped)
            ? playbackPhase + seedPhase
            : time + playbackPhase
        let global = globalEnergy(state: state, time: time)
        let motion = motionEnergy(state: state, time: time)
        let wordPulse = state == .playing ? currentWordPulse(time: time) : 0
        let entrance = trackEntrancePulse(time: time)

        return (0..<count).map { index in
            let x = Double(index) / Double(max(count - 1, 1))
            let band = bandProfile(x)
            let seedNoise = seededUnit(index: index + Int(stableSeed % 997))
            let localPhase = seedNoise * Double.pi * 2.0
            let group = floor(x * 9.0)
            let low = sin(phase * band.lowRate + localPhase * 0.62 + group * 0.38)
            let mid = sin(phase * band.midRate + localPhase * 1.17 + sin(phase * 0.62 + group) * 0.46)
            let high = sin(phase * band.highRate + localPhase * 2.3 + group * 1.15)
            let beat = pow((sin(phase * band.beatRate + localPhase * 0.7) + 1.0) / 2.0, band.beatShape)
            let lyricAccent = state == .playing
                ? sin((lyricProgress * Double.pi * 2.0) + localPhase * 0.25) * 0.025
                : 0
            let energy =
                Constants.idleBaseline
                + global * band.weight
                + motion * low * band.lowAmount
                + motion * mid * band.midAmount
                + motion * high * band.highAmount
                + motion * beat * band.beatAmount
                + wordPulse * band.wordAmount
                + entrance * band.entranceAmount
                + lyricAccent
            return min(1.0, max(0.04, energy))
        }
    }

    private func globalEnergy(state: DarkPlaybackVisualState, time: TimeInterval) -> Double {
        switch state {
        case .playing:
            return 0.34 + 0.05 * sin(time * 1.2)
        case .paused:
            return 0.055
        case .loading:
            return 0.10 + 0.04 * (sin(time * 2.0) + 1.0) / 2.0
        case .reconnecting:
            return 0.12 + 0.06 * (sin(time * 1.05) + 1.0) / 2.0
        case .stopped:
            return 0.02
        }
    }

    private func motionEnergy(state: DarkPlaybackVisualState, time: TimeInterval) -> Double {
        switch state {
        case .playing:
            return 1.0
        case .paused:
            return 0.08
        case .loading:
            return 0.12 + 0.04 * (sin(time * 1.6) + 1.0) / 2.0
        case .reconnecting:
            return 0.14 + 0.06 * (sin(time * 1.0) + 1.0) / 2.0
        case .stopped:
            return 0.0
        }
    }

    private func smoothTargets(_ raw: [Double]) -> [Double] {
        guard raw.count > 2 else { return raw }
        return raw.indices.map { index in
            let x = Double(index) / Double(max(raw.count - 1, 1))
            let nearBandBoundary = abs(x - 0.28) < 0.035 || abs(x - 0.76) < 0.035
            let neighborWeight = nearBandBoundary ? 0.06 : 0.13
            let selfWeight = 1.0 - neighborWeight * 2.0
            let left = raw[max(0, index - 1)]
            let right = raw[min(raw.count - 1, index + 1)]
            return raw[index] * selfWeight + left * neighborWeight + right * neighborWeight
        }
    }

    private func bandProfile(_ x: Double) -> RhythmBandProfile {
        if x < 0.28 {
            return RhythmBandProfile(
                weight: 0.90,
                lowRate: 1.45,
                lowSpan: 5.0,
                lowAmount: 0.18,
                midRate: 2.25,
                midSpan: 8.5,
                midAmount: 0.12,
                highRate: 4.0,
                highSpan: 16.0,
                highAmount: 0.035,
                beatRate: 1.95,
                beatShape: 2.4,
                beatAmount: 0.16,
                wordAmount: 0.03,
                entranceAmount: 0.08
            )
        } else if x < 0.76 {
            return RhythmBandProfile(
                weight: 0.72,
                lowRate: 1.10,
                lowSpan: 4.2,
                lowAmount: 0.11,
                midRate: 3.25,
                midSpan: 17.0,
                midAmount: 0.18,
                highRate: 7.8,
                highSpan: 30.0,
                highAmount: 0.07,
                beatRate: 2.55,
                beatShape: 2.9,
                beatAmount: 0.12,
                wordAmount: 0.14,
                entranceAmount: 0.06
            )
        } else {
            return RhythmBandProfile(
                weight: 0.46,
                lowRate: 0.85,
                lowSpan: 3.0,
                lowAmount: 0.055,
                midRate: 3.8,
                midSpan: 18.0,
                midAmount: 0.09,
                highRate: 10.8,
                highSpan: 42.0,
                highAmount: 0.13,
                beatRate: 3.4,
                beatShape: 3.2,
                beatAmount: 0.08,
                wordAmount: 0.05,
                entranceAmount: 0.03
            )
        }
    }

    private func currentWordPulse(time: TimeInterval) -> Double {
        guard let startedAt = wordPulseStartedAt else { return 0 }
        let age = time - startedAt
        guard age >= 0, age <= Constants.wordPulseDuration else { return 0 }
        let normalized = age / Constants.wordPulseDuration
        return pow(1.0 - normalized, 2.2)
    }

    private func trackEntrancePulse(time: TimeInterval) -> Double {
        let age = time - trackStartedAt
        guard age >= 0, age <= Constants.entranceDuration else { return 0 }
        return 0.22 * pow(1.0 - age / Constants.entranceDuration, 1.8)
    }

    private func updateSeedIfNeeded(trackSeed: String, time: TimeInterval) {
        guard trackSeed != lastTrackSeed else { return }
        lastTrackSeed = trackSeed
        stableSeed = stableHash(trackSeed)
        trackStartedAt = time
        lastWordSignature = ""
        wordPulseStartedAt = nil
    }

    private func updateWordPulseIfNeeded(wordSignature: String, time: TimeInterval) {
        guard !wordSignature.isEmpty else { return }
        if lastWordSignature.isEmpty {
            lastWordSignature = wordSignature
            return
        }
        guard wordSignature != lastWordSignature else { return }
        lastWordSignature = wordSignature
        guard time - lastWordPulseAt >= Constants.wordPulseDebounce else { return }
        lastWordPulseAt = time
        wordPulseStartedAt = time
    }

    private func ensureLevelCount(_ count: Int) {
        guard count > 0 else {
            levels = []
            return
        }
        guard levels.count != count else { return }
        guard !levels.isEmpty else {
            levels = Array(repeating: Constants.idleBaseline, count: count)
            return
        }
        let oldLevels = levels
        let oldCount = oldLevels.count
        levels = (0..<count).map { index in
            let progress = Double(index) / Double(max(count - 1, 1))
            let oldPosition = progress * Double(max(oldCount - 1, 1))
            let lower = Int(floor(oldPosition))
            let upper = min(oldCount - 1, lower + 1)
            let fraction = oldPosition - Double(lower)
            return oldLevels[lower] * (1.0 - fraction) + oldLevels[upper] * fraction
        }
    }

    private func barCount(for width: CGFloat) -> Int {
        let estimated = Int((width / 8.5).rounded())
        return min(Constants.maxBars, max(Constants.minBars, estimated))
    }

    private func seededUnit(index: Int) -> Double {
        let value = sin(Double(index * 127 + 31) * 12.9898 + Double(stableSeed % 10_000)) * 43758.5453
        return value - floor(value)
    }

    private func stableHash(_ text: String) -> UInt64 {
        var hash: UInt64 = 1469598103934665603
        for byte in text.utf8 {
            hash ^= UInt64(byte)
            hash &*= 1099511628211
        }
        return hash
    }
}

private struct RhythmBandProfile {
    let weight: Double
    let lowRate: Double
    let lowSpan: Double
    let lowAmount: Double
    let midRate: Double
    let midSpan: Double
    let midAmount: Double
    let highRate: Double
    let highSpan: Double
    let highAmount: Double
    let beatRate: Double
    let beatShape: Double
    let beatAmount: Double
    let wordAmount: Double
    let entranceAmount: Double
}

private struct DarkLyricSideDots: View {
    let color: Color

    var body: some View {
        VStack(spacing: 12) {
            Circle().fill(.white.opacity(0.14)).frame(width: 6, height: 6)
            Circle().fill(color).frame(width: 7, height: 7)
            Circle().fill(.white.opacity(0.14)).frame(width: 6, height: 6)
        }
    }
}

private struct PressScaleButtonStyle: ButtonStyle {
    var pressedScale: CGFloat = 0.96

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? pressedScale : 1.0)
            .animation(.spring(response: 0.18, dampingFraction: 0.72), value: configuration.isPressed)
    }
}

struct KaraokeLyricText: View {
    let text: String
    let progress: Double
    var words: [LyricWord] = []
    var positionMs: Int64? = nil
    let highlightColor: Color
    let normalColor: Color
    let font: Font
    var lineLimit: Int? = nil
    var alignment: TextAlignment = .leading

    private var highlightCount: Int {
        let characters = Array(text)
        let count = characters.count
        guard count > 0 else { return 0 }
        if let positionMs, !words.isEmpty {
            let wordCharacterCount = words.reduce(0) { partial, word in
                guard positionMs >= word.startMs else { return partial }
                return partial + Array(word.text).count
            }
            return min(max(wordCharacterCount, 0), count)
        }
        let boundedProgress = min(max(progress, 0), 1)
        let rawCount = Int((Double(count) * boundedProgress).rounded(.down))
        return min(max(rawCount, 0), count)
    }

    private var splitText: (String, String) {
        guard highlightCount > 0 else { return ("", text) }
        let characters = Array(text)
        guard highlightCount < characters.count else { return (text, "") }
        return (
            String(characters.prefix(highlightCount)),
            String(characters.dropFirst(highlightCount))
        )
    }

    var body: some View {
        let parts = splitText
        (Text(parts.0).foregroundColor(highlightColor) +
            Text(parts.1).foregroundColor(normalColor))
            .font(font)
            .multilineTextAlignment(alignment)
            .lineLimit(lineLimit)
            .animation(.linear(duration: 0.25), value: highlightCount)
    }
}

enum LyricTimelineHelper {
    static func currentIndex(lines: [LyricLine], positionMs: Int64) -> Int? {
        guard !lines.isEmpty else { return nil }
        if positionMs < lines[0].timeMs {
            return 0
        }

        var low = 0
        var high = lines.count - 1
        var result = 0
        while low <= high {
            let mid = (low + high) / 2
            if lines[mid].timeMs <= positionMs {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    static func lineProgress(
        lines: [LyricLine],
        index: Int,
        positionMs: Int64
    ) -> Double {
        guard lines.indices.contains(index) else { return 0 }
        let start = lines[index].timeMs
        let end: Int64
        if lines[index].durationMs > 0 {
            end = start + lines[index].durationMs
        } else if lines.indices.contains(index + 1) {
            end = max(lines[index + 1].timeMs, start + 1_000)
        } else {
            end = start + 4_000
        }
        let duration = max(end - start, 1_000)
        return Double(positionMs - start) / Double(duration)
    }
}

private struct DefaultAlbumArtView: View {
    var body: some View {
        ZStack {
            LinearGradient(
                colors: [
                    Color(red: 0.08, green: 0.24, blue: 0.44),
                    Color(red: 0.28, green: 0.36, blue: 0.62),
                    Color(red: 0.04, green: 0.05, blue: 0.10)
                ],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )

            Circle()
                .fill(.white.opacity(0.12))
                .frame(width: 150, height: 150)

            Image(systemName: "music.note")
                .font(.system(size: 76, weight: .semibold))
                .foregroundStyle(.white.opacity(0.86))
        }
    }
}

#Preview {
    ContentView()
}
