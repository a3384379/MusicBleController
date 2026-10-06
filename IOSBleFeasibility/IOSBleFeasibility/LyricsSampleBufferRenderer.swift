import AVFoundation
import UIKit

/// Confined to the controller's serial utility queue. Playback position is
/// deliberately absent from the frame clock, so backward seeks remain valid.
final class LyricsSampleBufferRenderer: @unchecked Sendable {
    static let width = 640
    static let height = 360
    private var pool: CVPixelBufferPool?
    private var lastPresentationTime = CMTime.invalid

    enum RenderError: Error { case bufferPoolExhausted, pixelBuffer, context, format, sampleBuffer }

    func render(_ snapshot: LyricsPresentationSnapshot) throws -> CMSampleBuffer {
        if pool == nil {
            let attributes: [String: Any] = [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
                kCVPixelBufferWidthKey as String: Self.width,
                kCVPixelBufferHeightKey as String: Self.height,
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
            data: CVPixelBufferGetBaseAddress(buffer), width: Self.width, height: Self.height,
            bitsPerComponent: 8, bytesPerRow: CVPixelBufferGetBytesPerRow(buffer),
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGBitmapInfo.byteOrder32Little.rawValue | CGImageAlphaInfo.premultipliedFirst.rawValue
        ) else { throw RenderError.context }
        context.setFillColor(UIColor(red: 0.055, green: 0.065, blue: 0.085, alpha: 1).cgColor)
        context.fill(CGRect(x: 0, y: 0, width: Self.width, height: Self.height))
        context.translateBy(x: 0, y: CGFloat(Self.height))
        context.scaleBy(x: 1, y: -1)
        UIGraphicsPushContext(context)
        defer { UIGraphicsPopContext() }

        let paragraph = NSMutableParagraphStyle()
        paragraph.alignment = .center
        paragraph.lineBreakMode = .byTruncatingTail
        let title = snapshot.title == "-" ? AppLocalization.string("等待同步") : snapshot.title
        (String(title.prefix(100)) as NSString).draw(
            in: CGRect(x: 36, y: 36, width: 568, height: 36),
            withAttributes: [.font: UIFont.systemFont(ofSize: 22, weight: .medium),
                             .foregroundColor: UIColor.lightGray, .paragraphStyle: paragraph]
        )
        let line = String(snapshot.displayText.prefix(512))
        let attributes: [NSAttributedString.Key: Any] = [
            .font: UIFont.systemFont(ofSize: 34, weight: .semibold),
            .foregroundColor: snapshot.status == .ready ? UIColor.white : UIColor.lightGray,
            .paragraphStyle: paragraph
        ]
        let textHeight = min(204, ceil((line as NSString).boundingRect(
            with: CGSize(width: 568, height: 204), options: [.usesLineFragmentOrigin, .usesFontLeading],
            attributes: attributes, context: nil
        ).height))
        (line as NSString).draw(
            in: CGRect(x: 36, y: 100 + (204 - textHeight) / 2, width: 568, height: textHeight),
            withAttributes: attributes
        )

        if snapshot.playbackControlState != .idle {
            let feedbackParagraph = NSMutableParagraphStyle()
            feedbackParagraph.alignment = .center
            (snapshot.playbackControlState.message as NSString).draw(
                in: CGRect(x: 36, y: 312, width: 568, height: 42),
                withAttributes: [.font: UIFont.systemFont(ofSize: 16),
                                 .foregroundColor: UIColor.lightGray, .paragraphStyle: feedbackParagraph]
            )
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

/// Ownership transfers once from the render queue to MainActor. Neither queue
/// mutates a completed sample buffer after this immutable envelope is created.
struct LyricsRenderedFrame: @unchecked Sendable {
    let result: Result<CMSampleBuffer, Error>
}
