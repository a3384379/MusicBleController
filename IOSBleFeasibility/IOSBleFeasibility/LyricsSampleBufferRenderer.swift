import AVFoundation
import UIKit

/// Confined to the controller's serial utility queue. Playback position is
/// deliberately absent from the frame clock, so backward seeks remain valid.
final class LyricsSampleBufferRenderer: @unchecked Sendable {
    static let width = 640
    private var pool: CVPixelBufferPool?
    private var poolHeight = 0
    private var lastPresentationTime = CMTime.invalid

    enum RenderError: Error { case bufferPoolExhausted, pixelBuffer, context, format, sampleBuffer }

    func render(_ snapshot: LyricsPresentationSnapshot,
                appearance: FloatingLyricsAppearance = FloatingLyricsAppearance()) throws -> CMSampleBuffer {
        let height = appearance.pixelHeight(hasControlFeedback: snapshot.playbackControlState != .idle)
        if poolHeight != height {
            pool = nil
            poolHeight = height
        }
        if pool == nil {
            let attributes: [String: Any] = [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
                kCVPixelBufferWidthKey as String: Self.width,
                kCVPixelBufferHeightKey as String: height,
                kCVPixelBufferCGImageCompatibilityKey as String: true,
                kCVPixelBufferCGBitmapContextCompatibilityKey as String: true,
                kCVPixelBufferIOSurfacePropertiesKey as String: [:]
            ]
            guard CVPixelBufferPoolCreate(nil, nil, attributes as CFDictionary, &pool) == kCVReturnSuccess else {
                throw RenderError.pixelBuffer
            }
        }
        guard let pool else { throw RenderError.pixelBuffer }
        var output: CVPixelBuffer?
        let allocation = [kCVPixelBufferPoolAllocationThresholdKey as String: 3] as CFDictionary
        let allocationResult = CVPixelBufferPoolCreatePixelBufferWithAuxAttributes(nil, pool, allocation, &output)
        if allocationResult == kCVReturnWouldExceedAllocationThreshold { throw RenderError.bufferPoolExhausted }
        guard allocationResult == kCVReturnSuccess, let buffer = output else { throw RenderError.pixelBuffer }
        CVPixelBufferLockBaseAddress(buffer, [])
        defer { CVPixelBufferUnlockBaseAddress(buffer, []) }
        guard let context = CGContext(
            data: CVPixelBufferGetBaseAddress(buffer), width: Self.width, height: height,
            bitsPerComponent: 8, bytesPerRow: CVPixelBufferGetBytesPerRow(buffer),
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGBitmapInfo.byteOrder32Little.rawValue | CGImageAlphaInfo.premultipliedFirst.rawValue
        ) else { throw RenderError.context }
        let palette = appearance.theme.palette
        let colors = [palette.start.uiColor.cgColor, palette.end.uiColor.cgColor] as CFArray
        guard let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors,
                                        locations: [0, 1]) else { throw RenderError.context }
        context.drawLinearGradient(gradient, start: .zero,
                                   end: CGPoint(x: Self.width, y: 0), options: [])
        context.translateBy(x: 0, y: CGFloat(height))
        context.scaleBy(x: 1, y: -1)
        UIGraphicsPushContext(context)
        defer { UIGraphicsPopContext() }

        let paragraph = NSMutableParagraphStyle()
        paragraph.alignment = .left
        paragraph.lineBreakMode = .byTruncatingTail
        // Keep each line in one fixed row. Long lyrics never change the canvas
        // or wrap into the neighbouring row. AVKit still owns the window size.
        func drawLine(_ text: String, y: CGFloat, size: CGFloat, color: UIColor,
                      weight: UIFont.Weight = .regular) {
            let singleLine = String(text.prefix(512)).components(separatedBy: .newlines).joined(separator: " ")
            let font = UIFont.systemFont(ofSize: size, weight: weight)
            (singleLine as NSString).draw(
                in: CGRect(x: 18, y: y, width: CGFloat(Self.width - 36), height: ceil(font.lineHeight) + 2),
                withAttributes: [.font: font,
                                 .foregroundColor: color, .paragraphStyle: paragraph]
            )
        }
        if appearance.showsTitle {
            drawLine(snapshot.title == "-" ? AppLocalization.string("等待同步") : snapshot.title,
                     y: 4, size: 18, color: palette.next.uiColor)
        }
        let primaryY: CGFloat = appearance.showsTitle ? 30 : 6
        drawLine(snapshot.displayText, y: primaryY, size: 38,
                 color: palette.current.uiColor,
                 weight: .semibold)
        if appearance.lineMode == .double {
            drawLine(snapshot.nextText, y: primaryY + 50, size: 32,
                     color: palette.next.uiColor)
        }

        if snapshot.playbackControlState != .idle {
            drawLine(snapshot.playbackControlState.message, y: CGFloat(height - 26), size: 18,
                     color: palette.current.uiColor)
        }

        var format: CMVideoFormatDescription?
        guard CMVideoFormatDescriptionCreateForImageBuffer(
            allocator: kCFAllocatorDefault, imageBuffer: buffer, formatDescriptionOut: &format
        ) == noErr, let format else { throw RenderError.format }
        let hostTime = CMClockGetTime(CMClockGetHostTimeClock())
        let presentationTime = lastPresentationTime.isValid && CMTimeCompare(hostTime, lastPresentationTime) <= 0
            ? CMTimeAdd(lastPresentationTime, CMTime(value: 1, timescale: 60_000)) : hostTime
        lastPresentationTime = presentationTime
        var timing = CMSampleTimingInfo(duration: .invalid, presentationTimeStamp: presentationTime,
                                        decodeTimeStamp: .invalid)
        var sample: CMSampleBuffer?
        guard CMSampleBufferCreateReadyWithImageBuffer(
            allocator: kCFAllocatorDefault, imageBuffer: buffer, formatDescription: format,
            sampleTiming: &timing, sampleBufferOut: &sample
        ) == noErr, let sample else { throw RenderError.sampleBuffer }
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: true)
            as? [NSMutableDictionary] {
            attachments.first?[kCMSampleAttachmentKey_DisplayImmediately] = true
        }
        return sample
    }
}

extension FloatingLyricsTheme.RGB {
    var uiColor: UIColor {
        UIColor(red: red, green: green, blue: blue, alpha: 1)
    }
}

/// Ownership transfers once from the render queue to MainActor. Neither queue
/// mutates a completed sample buffer after this immutable envelope is created.
struct LyricsRenderedFrame: @unchecked Sendable {
    let result: Result<CMSampleBuffer, Error>
}
