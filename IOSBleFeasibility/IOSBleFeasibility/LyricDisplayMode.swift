import Foundation

enum LyricDisplayMode: String, CaseIterable, Identifiable {
    case original
    case originalTranslation
    case originalRomanization
    case originalTranslationRomanization

    var id: String { rawValue }

    var title: String {
        switch self {
        case .original:
            return "原文"
        case .originalTranslation:
            return "翻译"
        case .originalRomanization:
            return "罗马音"
        case .originalTranslationRomanization:
            return "全部"
        }
    }

    var menuTitle: String {
        switch self {
        case .original:
            return "原文"
        case .originalTranslation:
            return "原文 + 翻译"
        case .originalRomanization:
            return "原文 + 罗马音"
        case .originalTranslationRomanization:
            return "原文 + 翻译 + 罗马音"
        }
    }

    var showsTranslation: Bool {
        self == .originalTranslation || self == .originalTranslationRomanization
    }

    var showsRomanization: Bool {
        self == .originalRomanization || self == .originalTranslationRomanization
    }

    static let userDefaultsKey = "lyricsDisplayMode"
}

extension String {
    var nonEmptyString: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}

func sanitizedSecondaryText(_ value: String?) -> String? {
    guard let value else { return nil }
    let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !trimmed.isEmpty else { return nil }
    if trimmed.allSatisfy({ $0 == "/" }) {
        return nil
    }
    let placeholderValues: Set<String> = [
        "--",
        "---",
        "null",
        "nil",
        "none",
        "暂无",
        "暂无翻译",
        "暂无罗马音"
    ]
    if placeholderValues.contains(trimmed.lowercased()) {
        return nil
    }
    return trimmed
}
