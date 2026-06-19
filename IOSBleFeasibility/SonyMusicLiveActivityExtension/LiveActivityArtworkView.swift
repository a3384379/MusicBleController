import SwiftUI
import UIKit

struct LiveActivityArtworkView: View {
    let artworkKey: String?
    let artworkRevision: Int
    let size: CGFloat

    var body: some View {
        ZStack {
            if let image = loadImage() {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                placeholder
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: size * 0.22, style: .continuous))
    }

    @ViewBuilder
    private var placeholder: some View {
        ZStack {
            RoundedRectangle(cornerRadius: size * 0.22, style: .continuous)
                .fill(
                    LinearGradient(
                        colors: [
                            Color(red: 0.10, green: 0.24, blue: 0.42),
                            Color(red: 0.20, green: 0.44, blue: 0.32)
                        ],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )
            Image(systemName: "music.note")
                .font(.system(size: size * 0.42, weight: .semibold))
                .foregroundStyle(.white)
        }
    }

    private func loadImage() -> UIImage? {
        guard let artworkKey,
              !artworkKey.isEmpty,
              artworkRevision > 0,
              let containerURL = FileManager.default.containerURL(
                forSecurityApplicationGroupIdentifier:
                    LiveActivitySharedConstants.appGroupIdentifier
              ) else {
            return nil
        }

        let fileURL = containerURL
            .appendingPathComponent(
                LiveActivitySharedConstants.artworkDirectoryName,
                isDirectory: true
            )
            .appendingPathComponent(
                LiveActivitySharedConstants.artworkFileName(
                    key: artworkKey,
                    revision: artworkRevision
                )
            )

        guard let data = try? Data(contentsOf: fileURL),
              !data.isEmpty else {
            return nil
        }
        return UIImage(data: data)
    }
}
