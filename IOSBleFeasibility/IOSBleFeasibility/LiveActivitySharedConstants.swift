import Foundation

enum LiveActivitySharedConstants {
    static let appGroupIdentifier = "group.com.sqz.IOSBleFeasibility"
    static let artworkDirectoryName = "LiveActivityArtwork"

    static func artworkFileName(key: String, revision: Int) -> String {
        "\(sanitizedArtworkKey(key))_\(max(revision, 0)).jpg"
    }

    static func sanitizedArtworkKey(_ key: String) -> String {
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_"))
        let scalars = key.unicodeScalars.map { scalar -> Character in
            allowed.contains(scalar) ? Character(scalar) : "_"
        }
        let value = String(scalars).trimmingCharacters(in: CharacterSet(charactersIn: "_"))
        return value.isEmpty ? "artwork" : String(value.prefix(80))
    }
}
