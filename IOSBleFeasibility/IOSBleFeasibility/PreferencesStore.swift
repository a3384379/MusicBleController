import Foundation
import SwiftUI

enum PlaybackPerformanceMode: String, CaseIterable, Identifiable {
    case automatic
    case smooth
    case powerSaving

    var id: String { rawValue }

    var title: String {
        switch self {
        case .automatic: return AppLocalization.string("自动")
        case .smooth: return AppLocalization.string("流畅")
        case .powerSaving: return AppLocalization.string("省电")
        }
    }

    var detail: String {
        switch self {
        case .automatic:
            return AppLocalization.string("根据低电量模式和播放状态自动平衡实时性与耗电。")
        case .smooth:
            return AppLocalization.string("优先频谱、封面和灵动岛的响应速度。")
        case .powerSaving:
            return AppLocalization.string("降低动画和后台刷新频率，高清封面仍会延后加载。")
        }
    }

    var hqDelayMultiplier: Double {
        switch self {
        case .automatic:
            return ProcessInfo.processInfo.isLowPowerModeEnabled ? 1.8 : 1.0
        case .smooth:
            return 0.75
        case .powerSaving:
            return 2.5
        }
    }

    static let userDefaultsKey = "playbackPerformanceMode"
    static let defaultMode: PlaybackPerformanceMode = .automatic
}

enum ArtworkDisplaySizeOption: Int, CaseIterable, Identifiable {
    case small = 184
    case medium = 224
    case large = 272

    var id: Int { rawValue }

    var title: String {
        switch self {
        case .small: return AppLocalization.string("小")
        case .medium: return AppLocalization.string("中")
        case .large: return AppLocalization.string("大")
        }
    }

    var pointSize: CGFloat {
        CGFloat(rawValue)
    }

    static let userDefaultsKey = "artworkDisplaySize"
    static let defaultOption: ArtworkDisplaySizeOption = .large
}

final class PreferencesStore: ObservableObject {
    static let shared = PreferencesStore()

    static let autoReconnectEnabledKey = "autoReconnectEnabled"
    static let automaticLyricSyncEnabledKey = "automaticLyricSyncEnabled"
    static let lyricOffsetMsKey = "lyricOffsetMs"
    static let automaticLyricSyncMigrationV1Key = "automaticLyricSyncMigrationV1"
    static let artworkEnhancementEnabledKey = "artworkEnhancementEnabled"
    static let artworkEnhancementTargetPixelSizeKey = "artworkEnhancementTargetPixelSize"
    static let artworkEnhancementSharpnessKey = "artworkEnhancementSharpness"
    static let forceProtocolV2Key = "forceProtocolV2"

    @Published var appLanguage: AppLanguage {
        didSet { persistAppLanguage(oldValue: oldValue) }
    }

    @Published var appExperienceMode: AppExperienceMode {
        didSet { persistAppExperienceMode(oldValue: oldValue) }
    }

    @Published var autoReconnectEnabled: Bool {
        didSet { persistBool(autoReconnectEnabled, oldValue: oldValue, key: Self.autoReconnectEnabledKey) }
    }

    @Published var lyricOffsetMs: Int {
        didSet { persistInt(lyricOffsetMs, oldValue: oldValue, key: Self.lyricOffsetMsKey) }
    }

    @Published var automaticLyricSyncEnabled: Bool {
        didSet {
            persistBool(
                automaticLyricSyncEnabled,
                oldValue: oldValue,
                key: Self.automaticLyricSyncEnabledKey
            )
        }
    }

    @Published var lyricDisplayMode: LyricDisplayMode {
        didSet { persistLyricDisplayMode(oldValue: oldValue) }
    }

    @Published var artworkEnhancementEnabled: Bool {
        didSet { persistBool(artworkEnhancementEnabled, oldValue: oldValue, key: Self.artworkEnhancementEnabledKey) }
    }

    @Published var artworkDisplaySize: ArtworkDisplaySizeOption {
        didSet { persistArtworkDisplaySize(oldValue: oldValue) }
    }

    @Published var dynamicIslandStyle: DynamicIslandStyle {
        didSet { persistDynamicIslandStyle(oldValue: oldValue) }
    }

    @Published var playbackPerformanceMode: PlaybackPerformanceMode {
        didSet { persistPlaybackPerformanceMode(oldValue: oldValue) }
    }

    @Published var forceProtocolV2: Bool {
        didSet { persistBool(forceProtocolV2, oldValue: oldValue, key: Self.forceProtocolV2Key) }
    }

    private let defaults: UserDefaults

    private init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        Self.migrateAutomaticLyricSyncIfNeeded(defaults: defaults)
        appLanguage = Self.loadAppLanguage(defaults: defaults)
        appExperienceMode = Self.loadAppExperienceMode(defaults: defaults)
        autoReconnectEnabled = Self.loadBool(
            defaults: defaults,
            key: Self.autoReconnectEnabledKey,
            defaultValue: true
        )
        lyricOffsetMs = Self.loadInt(
            defaults: defaults,
            key: Self.lyricOffsetMsKey,
            defaultValue: 0
        )
        automaticLyricSyncEnabled = Self.loadBool(
            defaults: defaults,
            key: Self.automaticLyricSyncEnabledKey,
            defaultValue: true
        )
        lyricDisplayMode = Self.loadLyricDisplayMode(defaults: defaults)
        artworkEnhancementEnabled = Self.loadBool(
            defaults: defaults,
            key: Self.artworkEnhancementEnabledKey,
            defaultValue: true
        )
        artworkDisplaySize = Self.loadArtworkDisplaySize(defaults: defaults)
        dynamicIslandStyle = Self.loadDynamicIslandStyle(defaults: defaults)
        playbackPerformanceMode = Self.loadPlaybackPerformanceMode(defaults: defaults)
        forceProtocolV2 = Self.loadBool(
            defaults: defaults,
            key: Self.forceProtocolV2Key,
            defaultValue: false
        )
        logLoaded()
    }

    func load() {
        appLanguage = Self.loadAppLanguage(defaults: defaults)
        appExperienceMode = Self.loadAppExperienceMode(defaults: defaults)
        autoReconnectEnabled = Self.loadBool(
            defaults: defaults,
            key: Self.autoReconnectEnabledKey,
            defaultValue: true
        )
        lyricOffsetMs = Self.loadInt(
            defaults: defaults,
            key: Self.lyricOffsetMsKey,
            defaultValue: 0
        )
        automaticLyricSyncEnabled = Self.loadBool(
            defaults: defaults,
            key: Self.automaticLyricSyncEnabledKey,
            defaultValue: true
        )
        lyricDisplayMode = Self.loadLyricDisplayMode(defaults: defaults)
        artworkEnhancementEnabled = Self.loadBool(
            defaults: defaults,
            key: Self.artworkEnhancementEnabledKey,
            defaultValue: true
        )
        artworkDisplaySize = Self.loadArtworkDisplaySize(defaults: defaults)
        dynamicIslandStyle = Self.loadDynamicIslandStyle(defaults: defaults)
        playbackPerformanceMode = Self.loadPlaybackPerformanceMode(defaults: defaults)
        forceProtocolV2 = Self.loadBool(
            defaults: defaults,
            key: Self.forceProtocolV2Key,
            defaultValue: false
        )
        logLoaded()
    }

    func resetToDefaults() {
        appLanguage = .defaultLanguage
        appExperienceMode = .defaultMode
        autoReconnectEnabled = true
        automaticLyricSyncEnabled = true
        lyricOffsetMs = 0
        lyricDisplayMode = .originalTranslation
        artworkEnhancementEnabled = true
        artworkDisplaySize = .defaultOption
        dynamicIslandStyle = .defaultStyle
        playbackPerformanceMode = .defaultMode
        forceProtocolV2 = false
    }

    private static func loadAppExperienceMode(defaults: UserDefaults) -> AppExperienceMode {
        let raw = defaults.string(forKey: AppExperienceMode.userDefaultsKey)
        return raw.flatMap(AppExperienceMode.init(rawValue:)) ?? .defaultMode
    }

    private static func loadAppLanguage(defaults: UserDefaults) -> AppLanguage {
        let raw = defaults.string(forKey: AppLanguage.userDefaultsKey)
        return raw.flatMap(AppLanguage.init(rawValue:)) ?? .defaultLanguage
    }

    static func migratedLegacyLyricOffset(
        storedOffset: Int?,
        migrationCompleted: Bool
    ) -> Int? {
        guard !migrationCompleted else { return storedOffset }
        if storedOffset == nil || storedOffset == 600 {
            return 0
        }
        return storedOffset
    }

    private static func migrateAutomaticLyricSyncIfNeeded(defaults: UserDefaults) {
        let completed = defaults.bool(forKey: automaticLyricSyncMigrationV1Key)
        guard !completed else { return }
        let storedOffset = (defaults.object(forKey: lyricOffsetMsKey) as? NSNumber)?.intValue
        let migrated = migratedLegacyLyricOffset(
            storedOffset: storedOffset,
            migrationCompleted: completed
        ) ?? 0
        defaults.set(migrated, forKey: lyricOffsetMsKey)
        defaults.set(true, forKey: automaticLyricSyncEnabledKey)
        defaults.set(true, forKey: automaticLyricSyncMigrationV1Key)
        AppLogStore.shared.append(
            "[Preferences] migrated automatic lyric sync offsetMs=\(migrated)"
        )
    }

    private static func loadLyricDisplayMode(defaults: UserDefaults) -> LyricDisplayMode {
        let raw = defaults.string(forKey: LyricDisplayMode.userDefaultsKey)
        return raw.flatMap(LyricDisplayMode.init(rawValue:)) ?? .originalTranslation
    }

    private static func loadArtworkDisplaySize(defaults: UserDefaults) -> ArtworkDisplaySizeOption {
        let value = defaults.integer(forKey: ArtworkDisplaySizeOption.userDefaultsKey)
        let option = migratedArtworkDisplaySize(storedValue: value)
        if value > 0, value != option.rawValue {
            defaults.set(option.rawValue, forKey: ArtworkDisplaySizeOption.userDefaultsKey)
        }
        return option
    }

    static func migratedArtworkDisplaySize(storedValue: Int) -> ArtworkDisplaySizeOption {
        if let current = ArtworkDisplaySizeOption(rawValue: storedValue) {
            return current
        }
        switch storedValue {
        case 200: return .small
        case 220: return .medium
        case 260: return .large
        default: return .defaultOption
        }
    }

    private static func loadDynamicIslandStyle(defaults: UserDefaults) -> DynamicIslandStyle {
        let raw = defaults.string(forKey: DynamicIslandStyle.userDefaultsKey)
        return raw.flatMap(DynamicIslandStyle.init(rawValue:)) ?? .defaultStyle
    }

    private static func loadPlaybackPerformanceMode(defaults: UserDefaults) -> PlaybackPerformanceMode {
        let raw = defaults.string(forKey: PlaybackPerformanceMode.userDefaultsKey)
        return raw.flatMap(PlaybackPerformanceMode.init(rawValue:)) ?? .defaultMode
    }

    private static func loadBool(defaults: UserDefaults, key: String, defaultValue: Bool) -> Bool {
        defaults.object(forKey: key) as? Bool ?? defaultValue
    }

    private static func loadInt(defaults: UserDefaults, key: String, defaultValue: Int) -> Int {
        defaults.object(forKey: key) as? Int ?? defaultValue
    }

    private func persistAppExperienceMode(oldValue: AppExperienceMode) {
        guard appExperienceMode != oldValue else { return }
        defaults.set(appExperienceMode.rawValue, forKey: AppExperienceMode.userDefaultsKey)
        logChanged(key: AppExperienceMode.userDefaultsKey, value: appExperienceMode.rawValue)
    }

    private func persistAppLanguage(oldValue: AppLanguage) {
        guard appLanguage != oldValue else { return }
        defaults.set(appLanguage.rawValue, forKey: AppLanguage.userDefaultsKey)
        UserDefaults(
            suiteName: LiveActivitySharedConstants.appGroupIdentifier
        )?.set(appLanguage.rawValue, forKey: AppLanguage.userDefaultsKey)
        logChanged(key: AppLanguage.userDefaultsKey, value: appLanguage.rawValue)
    }

    private func persistLyricDisplayMode(oldValue: LyricDisplayMode) {
        guard lyricDisplayMode != oldValue else { return }
        defaults.set(lyricDisplayMode.rawValue, forKey: LyricDisplayMode.userDefaultsKey)
        logChanged(key: LyricDisplayMode.userDefaultsKey, value: lyricDisplayMode.rawValue)
    }

    private func persistArtworkDisplaySize(oldValue: ArtworkDisplaySizeOption) {
        guard artworkDisplaySize != oldValue else { return }
        defaults.set(artworkDisplaySize.rawValue, forKey: ArtworkDisplaySizeOption.userDefaultsKey)
        logChanged(key: ArtworkDisplaySizeOption.userDefaultsKey, value: "\(artworkDisplaySize.rawValue)")
    }

    private func persistDynamicIslandStyle(oldValue: DynamicIslandStyle) {
        guard dynamicIslandStyle != oldValue else { return }
        defaults.set(dynamicIslandStyle.rawValue, forKey: DynamicIslandStyle.userDefaultsKey)
        logChanged(key: DynamicIslandStyle.userDefaultsKey, value: dynamicIslandStyle.rawValue)
    }

    private func persistPlaybackPerformanceMode(oldValue: PlaybackPerformanceMode) {
        guard playbackPerformanceMode != oldValue else { return }
        defaults.set(
            playbackPerformanceMode.rawValue,
            forKey: PlaybackPerformanceMode.userDefaultsKey
        )
        logChanged(
            key: PlaybackPerformanceMode.userDefaultsKey,
            value: playbackPerformanceMode.rawValue
        )
    }

    private func persistBool(_ value: Bool, oldValue: Bool, key: String) {
        guard value != oldValue else { return }
        defaults.set(value, forKey: key)
        logChanged(key: key, value: "\(value)")
    }

    private func persistInt(_ value: Int, oldValue: Int, key: String) {
        guard value != oldValue else { return }
        defaults.set(value, forKey: key)
        logChanged(key: key, value: "\(value)")
    }

    private func logLoaded() {
        AppLogStore.shared.append(
            "[Preferences] loaded language=\(appLanguage.rawValue) " +
                "mode=\(appExperienceMode.rawValue) " +
                "autoReconnect=\(autoReconnectEnabled) " +
                "automaticLyricSync=\(automaticLyricSyncEnabled) " +
                "lyricOffsetMs=\(lyricOffsetMs) " +
                "lyricDisplayMode=\(lyricDisplayMode.rawValue) " +
                "artworkEnhancement=\(artworkEnhancementEnabled) " +
                "artworkDisplaySize=\(artworkDisplaySize.rawValue) " +
                "dynamicIslandStyle=\(dynamicIslandStyle.rawValue) " +
                "performanceMode=\(playbackPerformanceMode.rawValue) " +
                "forceProtocolV2=\(forceProtocolV2)"
        )
    }

    private func logChanged(key: String, value: String) {
        AppLogStore.shared.append("[Preferences] changed key=\(key) value=\(value)")
    }
}
