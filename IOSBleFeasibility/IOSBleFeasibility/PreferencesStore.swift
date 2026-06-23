import Foundation
import SwiftUI

enum ArtworkDisplaySizeOption: Int, CaseIterable, Identifiable {
    case small = 200
    case medium = 220
    case large = 260

    var id: Int { rawValue }

    var title: String {
        switch self {
        case .small: return "小"
        case .medium: return "中"
        case .large: return "大"
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
    static let lyricOffsetMsKey = "lyricOffsetMs"
    static let artworkEnhancementEnabledKey = "artworkEnhancementEnabled"
    static let artworkEnhancementTargetPixelSizeKey = "artworkEnhancementTargetPixelSize"
    static let artworkEnhancementSharpnessKey = "artworkEnhancementSharpness"

    @Published var appExperienceMode: AppExperienceMode {
        didSet { persistAppExperienceMode(oldValue: oldValue) }
    }

    @Published var autoReconnectEnabled: Bool {
        didSet { persistBool(autoReconnectEnabled, oldValue: oldValue, key: Self.autoReconnectEnabledKey) }
    }

    @Published var lyricOffsetMs: Int {
        didSet { persistInt(lyricOffsetMs, oldValue: oldValue, key: Self.lyricOffsetMsKey) }
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

    private let defaults: UserDefaults

    private init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        appExperienceMode = Self.loadAppExperienceMode(defaults: defaults)
        autoReconnectEnabled = Self.loadBool(
            defaults: defaults,
            key: Self.autoReconnectEnabledKey,
            defaultValue: true
        )
        lyricOffsetMs = Self.loadInt(
            defaults: defaults,
            key: Self.lyricOffsetMsKey,
            defaultValue: 600
        )
        lyricDisplayMode = Self.loadLyricDisplayMode(defaults: defaults)
        artworkEnhancementEnabled = Self.loadBool(
            defaults: defaults,
            key: Self.artworkEnhancementEnabledKey,
            defaultValue: true
        )
        artworkDisplaySize = Self.loadArtworkDisplaySize(defaults: defaults)
        logLoaded()
    }

    func load() {
        appExperienceMode = Self.loadAppExperienceMode(defaults: defaults)
        autoReconnectEnabled = Self.loadBool(
            defaults: defaults,
            key: Self.autoReconnectEnabledKey,
            defaultValue: true
        )
        lyricOffsetMs = Self.loadInt(
            defaults: defaults,
            key: Self.lyricOffsetMsKey,
            defaultValue: 600
        )
        lyricDisplayMode = Self.loadLyricDisplayMode(defaults: defaults)
        artworkEnhancementEnabled = Self.loadBool(
            defaults: defaults,
            key: Self.artworkEnhancementEnabledKey,
            defaultValue: true
        )
        artworkDisplaySize = Self.loadArtworkDisplaySize(defaults: defaults)
        logLoaded()
    }

    func resetToDefaults() {
        appExperienceMode = .defaultMode
        autoReconnectEnabled = true
        lyricOffsetMs = 600
        lyricDisplayMode = .originalTranslation
        artworkEnhancementEnabled = true
        artworkDisplaySize = .defaultOption
    }

    private static func loadAppExperienceMode(defaults: UserDefaults) -> AppExperienceMode {
        let raw = defaults.string(forKey: AppExperienceMode.userDefaultsKey)
        return raw.flatMap(AppExperienceMode.init(rawValue:)) ?? .defaultMode
    }

    private static func loadLyricDisplayMode(defaults: UserDefaults) -> LyricDisplayMode {
        let raw = defaults.string(forKey: LyricDisplayMode.userDefaultsKey)
        return raw.flatMap(LyricDisplayMode.init(rawValue:)) ?? .originalTranslation
    }

    private static func loadArtworkDisplaySize(defaults: UserDefaults) -> ArtworkDisplaySizeOption {
        let value = defaults.integer(forKey: ArtworkDisplaySizeOption.userDefaultsKey)
        return ArtworkDisplaySizeOption(rawValue: value) ?? .defaultOption
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
            "[Preferences] loaded mode=\(appExperienceMode.rawValue) " +
                "autoReconnect=\(autoReconnectEnabled) lyricOffsetMs=\(lyricOffsetMs) " +
                "lyricDisplayMode=\(lyricDisplayMode.rawValue) " +
                "artworkEnhancement=\(artworkEnhancementEnabled) " +
                "artworkDisplaySize=\(artworkDisplaySize.rawValue)"
        )
    }

    private func logChanged(key: String, value: String) {
        AppLogStore.shared.append("[Preferences] changed key=\(key) value=\(value)")
    }
}
