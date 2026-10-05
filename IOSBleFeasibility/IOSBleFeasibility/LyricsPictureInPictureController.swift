@preconcurrency import AVKit
@preconcurrency import AVFoundation
import Combine
import UIKit

@MainActor
final class LyricsPictureInPictureController: NSObject, ObservableObject {
    static let shared = LyricsPictureInPictureController()
    @Published private(set) var lifecycle = LyricsPiPStateMachine()
    @Published private(set) var reason = ""

    private weak var sourceView: LyricsPictureInPictureSourceUIView?
    private var displayLayer: AVSampleBufferDisplayLayer?
    private var snapshotProvider: (() -> LyricsPresentationSnapshot)?
    private var setPlaying: ((Bool) -> Bool)?
    private var controller: AVPictureInPictureController?
    private var possibleObservation: NSKeyValueObservation?
    private var callbackGeneration: UInt64 = 0
    private var startRequested = false
    private var startTimeout: Task<Void, Never>?
    private var stopTimeout: Task<Void, Never>?
    private var heartbeat: Timer?
    private var audioLease: LyricsPiPAudioLease?
    private var interruptionObserver: NSObjectProtocol?
    private var renderer: LyricsSampleBufferRenderer?
    private let renderQueue = DispatchQueue(label: "musicble.lyrics.pip.render", qos: .utility)
    private var frameQueue = LatestLyricsFrameQueue()
    private var waitingForBuffer = false
    private var waitingForDisplayRenderer = false
    private var flushingDisplayRenderer = false
    private var bufferRecoveryAttempted = false
    private var latestKey: LyricsPresentationSnapshot.Key?
    nonisolated private let playbackStatus = LyricsPiPPlaybackStatus()

    var state: LyricsPiPStateMachine.State { lifecycle.state }

    func setEnabled(_ enabled: Bool) {
        lifecycle.setEnabled(enabled)
        if !enabled { stop() }
    }

    func attach(
        _ view: LyricsPictureInPictureSourceUIView,
        snapshot: @escaping () -> LyricsPresentationSnapshot,
        setPlaying: @escaping (Bool) -> Bool
    ) {
        sourceView = view
        snapshotProvider = snapshot
        self.setPlaying = setPlaying
        prepareIfNeeded()
    }

    func sourceBecameVisible() {
        guard lifecycle.enabled else { return }
        prepareIfNeeded()
        tryStart()
    }

    func requestStart() {
        guard lifecycle.enabled, state != .active, state != .starting, state != .stopping else { return }
        guard UIApplication.shared.applicationState == .active else {
            fail(AppLocalization.string("请回到应用中显示悬浮歌词"), unavailable: true)
            return
        }
        guard AVPictureInPictureController.isPictureInPictureSupported() else {
            fail(AppLocalization.string("此设备不支持画中画"), unavailable: true)
            return
        }
        reason = ""
        startRequested = true
        prepareIfNeeded()
        if let snapshot = snapshotProvider?() { update(snapshot, force: true) }
        do {
            if audioLease == nil {
                let lease = LyricsPiPAudioLease()
                try lease.activate()
                audioLease = lease
                interruptionObserver = NotificationCenter.default.addObserver(
                    forName: AVAudioSession.interruptionNotification, object: nil, queue: .main
                ) { [weak self] notification in
                    let type = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
                    if type == AVAudioSession.InterruptionType.began.rawValue {
                        Task { @MainActor in self?.stop() }
                    }
                }
            }
        } catch {
            fail(AppLocalization.string("无法准备画中画音频会话") + ": " + error.localizedDescription)
            return
        }
        startTimeout?.cancel()
        startTimeout = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 8_000_000_000)
            guard !Task.isCancelled, let self, self.state != .active else { return }
            self.fail(AppLocalization.string("系统暂时无法显示画中画，请再次尝试"), unavailable: true)
        }
        tryStart()
    }

    private func prepareIfNeeded() {
        guard lifecycle.enabled, controller == nil, let sourceView,
              sourceView.window != nil,
              AVPictureInPictureController.isPictureInPictureSupported(),
              let generation = lifecycle.prepare() else { return }
        callbackGeneration = generation
        renderer = LyricsSampleBufferRenderer()
        displayLayer = sourceView.displayLayer
        let source = AVPictureInPictureController.ContentSource(
            sampleBufferDisplayLayer: sourceView.displayLayer, playbackDelegate: self
        )
        let pip = AVPictureInPictureController(contentSource: source)
        pip.delegate = self
        pip.requiresLinearPlayback = true
        pip.canStartPictureInPictureAutomaticallyFromInline = false
        controller = pip
        possibleObservation = pip.observe(\.isPictureInPicturePossible, options: [.initial, .new]) { [weak self] pip, _ in
            Task { @MainActor in
                guard let self, self.controller === pip, self.callbackGeneration == generation else { return }
                if pip.isPictureInPicturePossible { self.lifecycle.ready(generation: generation) }
                self.tryStart()
            }
        }
        if let snapshotProvider { update(snapshotProvider(), force: true) }
    }

    private func tryStart() {
        guard startRequested, UIApplication.shared.applicationState == .active,
              sourceView?.window != nil, let controller, controller.isPictureInPicturePossible else { return }
        lifecycle.ready(generation: callbackGeneration)
        guard lifecycle.start(generation: callbackGeneration) else { return }
        startRequested = false
        controller.startPictureInPicture()
    }

    func update(_ snapshot: LyricsPresentationSnapshot, force: Bool = false) {
        guard lifecycle.enabled, renderer != nil else { return }
        playbackStatus.update(snapshot, owner: controller.map(ObjectIdentifier.init))
        let key = snapshot.key
        if key != latestKey {
            if latestKey?.trackID != key.trackID || latestKey?.trackGeneration != key.trackGeneration {
                displayLayer?.sampleBufferRenderer.flush(removingDisplayedImage: true, completionHandler: nil)
            }
            latestKey = key
            controller?.invalidatePlaybackState()
        }
        if frameQueue.offer(snapshot, force: force) {
            waitingForBuffer = false
            bufferRecoveryAttempted = false
        }
        renderNext()
    }

    private func renderNext() {
        guard lifecycle.enabled, !waitingForBuffer, !flushingDisplayRenderer, frameQueue.hasPending,
              let renderer, let displayRenderer = displayLayer?.sampleBufferRenderer else { return }
        if displayRenderer.status == .failed || displayRenderer.requiresFlushToResumeDecoding {
            flushDisplayRenderer(displayRenderer)
            return
        }
        guard displayRenderer.isReadyForMoreMediaData else {
            waitForDisplayRenderer(displayRenderer)
            return
        }
        cancelDisplayRendererWait()
        guard let request = frameQueue.begin() else { return }
        let snapshot = request.snapshot
        renderQueue.async { [self] in
            let frame = autoreleasepool { LyricsRenderedFrame(result: Result { try renderer.render(snapshot) }) }
            DispatchQueue.main.async { [self] in
                guard self.lifecycle.enabled, self.renderer === renderer,
                      let displayRenderer = self.displayLayer?.sampleBufferRenderer else { return }
                let current = self.snapshotProvider?()
                let bufferPressure: Bool
                let needsRetry: Bool
                switch frame.result {
                case .success:
                    bufferPressure = false
                    needsRetry = !displayRenderer.isReadyForMoreMediaData || displayRenderer.status == .failed ||
                        displayRenderer.requiresFlushToResumeDecoding
                case .failure(let error):
                    bufferPressure = (error as? LyricsSampleBufferRenderer.RenderError) == .bufferPoolExhausted
                    needsRetry = bufferPressure
                }
                let accepted = self.frameQueue.finish(request.token, currentKey: current?.key,
                                                      retryIfCurrent: needsRetry)
                if bufferPressure {
                    self.waitingForBuffer = true
                    if !self.bufferRecoveryAttempted {
                        self.bufferRecoveryAttempted = true
                        self.flushDisplayRenderer(displayRenderer)
                    }
                }
                guard accepted else {
                    if let current { self.update(current) }
                    self.renderNext()
                    return
                }
                // Recheck the authority after expensive pixel generation, including
                // generation/line/timeline identity, not just the lyric string.
                switch frame.result {
                case .success(let sample):
                    if !needsRetry {
                        self.bufferRecoveryAttempted = false
                        displayRenderer.enqueue(sample)
                    }
                case .failure:
                    if !bufferPressure { self.fail(AppLocalization.string("无法绘制悬浮歌词")) }
                }
                self.renderNext()
            }
        }
    }

    private func flushDisplayRenderer(_ displayRenderer: AVSampleBufferVideoRenderer) {
        guard !flushingDisplayRenderer, let renderer else { return }
        flushingDisplayRenderer = true
        cancelDisplayRendererWait()
        let generation = callbackGeneration
        // Keep the visible image. Retry only after pending buffers are discarded;
        // a second pool exhaustion waits for the next line or active heartbeat.
        displayRenderer.flush(removingDisplayedImage: false) { [weak self, weak displayRenderer, weak renderer] in
            Task { @MainActor [weak self, weak displayRenderer, weak renderer] in
                guard let self, let displayRenderer, let renderer, self.lifecycle.enabled,
                      self.callbackGeneration == generation, self.renderer === renderer,
                      self.displayLayer?.sampleBufferRenderer === displayRenderer else { return }
                self.flushingDisplayRenderer = false
                self.waitingForBuffer = false
                if displayRenderer.status == .failed || displayRenderer.requiresFlushToResumeDecoding {
                    self.fail(AppLocalization.string("无法绘制悬浮歌词"))
                    return
                }
                if let snapshot = self.snapshotProvider?() { self.update(snapshot) }
                self.renderNext()
            }
        }
    }

    private func waitForDisplayRenderer(_ displayRenderer: AVSampleBufferVideoRenderer) {
        guard !waitingForDisplayRenderer else { return }
        waitingForDisplayRenderer = true
        let generation = callbackGeneration
        displayRenderer.requestMediaDataWhenReady(on: .main) { [weak self, weak displayRenderer] in
            MainActor.assumeIsolated {
                guard let self, let displayRenderer, self.callbackGeneration == generation,
                      self.displayLayer?.sampleBufferRenderer === displayRenderer,
                      self.waitingForDisplayRenderer else { return }
                // One wakeup per wait; never leave a repeating producer installed.
                self.cancelDisplayRendererWait()
                if let snapshot = self.snapshotProvider?() { self.update(snapshot) }
                self.renderNext()
            }
        }
    }

    private func cancelDisplayRendererWait() {
        guard waitingForDisplayRenderer else { return }
        displayLayer?.sampleBufferRenderer.stopRequestingMediaData()
        waitingForDisplayRenderer = false
    }

    func stop() {
        stop(requestSystemStop: true)
    }

    private func stop(requestSystemStop: Bool) {
        playbackStatus.blockControls(owner: controller.map(ObjectIdentifier.init))
        startRequested = false
        startTimeout?.cancel()
        startTimeout = nil
        heartbeat?.invalidate()
        heartbeat = nil
        cancelDisplayRendererWait()
        waitingForBuffer = false
        flushingDisplayRenderer = false
        bufferRecoveryAttempted = false
        frameQueue.invalidate()
        renderer = nil
        if let controller, controller.isPictureInPictureActive || state == .active || state == .starting || state == .stopping {
            if state != .stopping { lifecycle.stop() }
            if requestSystemStop { controller.stopPictureInPicture() }
            // Retain the delegate through a late start/stop callback.
            stopTimeout?.cancel()
            stopTimeout = Task { [weak self, weak controller] in
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                guard !Task.isCancelled, let self, self.controller === controller else { return }
                self.releaseResources(preserveDelegate: true)
                self.lifecycle.didStop()
            }
        } else {
            releaseResources()
            lifecycle.didStop()
        }
    }

    private func releaseResources(preserveDelegate: Bool = false) {
        stopTimeout?.cancel()
        stopTimeout = nil
        startTimeout?.cancel()
        startTimeout = nil
        heartbeat?.invalidate()
        heartbeat = nil
        cancelDisplayRendererWait()
        waitingForBuffer = false
        flushingDisplayRenderer = false
        bufferRecoveryAttempted = false
        possibleObservation = nil
        if !preserveDelegate { controller?.delegate = nil }
        controller = nil
        playbackStatus.clear()
        frameQueue.invalidate()
        renderer = nil
        latestKey = nil
        displayLayer?.sampleBufferRenderer.flush(removingDisplayedImage: true, completionHandler: nil)
        displayLayer = nil
        if let interruptionObserver { NotificationCenter.default.removeObserver(interruptionObserver) }
        interruptionObserver = nil
        audioLease?.release()
        audioLease = nil
    }

    private func fail(_ message: String, unavailable: Bool = false) {
        reason = message
        startRequested = false
        controller?.stopPictureInPicture()
        releaseResources(preserveDelegate: true)
        lifecycle.fail(unavailable: unavailable)
    }
}

extension LyricsPictureInPictureController: AVPictureInPictureControllerDelegate {
    nonisolated func pictureInPictureControllerDidStartPictureInPicture(_ pip: AVPictureInPictureController) {
        Task { @MainActor [weak self] in
            guard let self, self.controller === pip,
                  self.lifecycle.didStart(generation: self.callbackGeneration) else {
                pip.stopPictureInPicture()
                return
            }
            self.startTimeout?.cancel()
            self.startTimeout = nil
            self.playbackStatus.allowControls(owner: ObjectIdentifier(pip))
            self.heartbeat = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
                Task { @MainActor in
                    guard let self, self.state == .active, let snapshot = self.snapshotProvider?() else { return }
                    self.update(snapshot, force: true)
                }
            }
            if let snapshot = self.snapshotProvider?() { self.update(snapshot, force: true) }
        }
    }

    nonisolated func pictureInPictureControllerWillStopPictureInPicture(_ pip: AVPictureInPictureController) {
        // Block even already queued playback callbacks before the system closes
        // the window. Closing this display must never become a Sony pause.
        playbackStatus.blockControls(owner: ObjectIdentifier(pip))
        Task { @MainActor [weak self] in
            guard let self, self.controller === pip else { return }
            if self.state == .active || self.state == .starting { self.stop(requestSystemStop: false) }
        }
    }

    nonisolated func pictureInPictureControllerDidStopPictureInPicture(_ pip: AVPictureInPictureController) {
        Task { @MainActor [weak self] in
            guard let self, self.controller === pip else { return }
            self.startRequested = false
            self.releaseResources()
            self.lifecycle.didStop()
        }
    }

    nonisolated func pictureInPictureController(_ pip: AVPictureInPictureController,
                                               failedToStartPictureInPictureWithError error: Error) {
        Task { @MainActor [weak self] in
            guard let self, self.controller === pip else { return }
            self.fail(AppLocalization.string("系统暂时无法显示画中画，请再次尝试") + ": " + error.localizedDescription)
        }
    }
}

extension LyricsPictureInPictureController: AVPictureInPictureSampleBufferPlaybackDelegate {
    nonisolated func pictureInPictureController(_ pip: AVPictureInPictureController, setPlaying playing: Bool) {
        Task { @MainActor [weak self] in
            guard let self, self.controller === pip, self.state == .active, self.lifecycle.enabled,
                  self.playbackStatus.acceptsControls(owner: ObjectIdentifier(pip)) else { return }
            if self.setPlaying?(playing) != true {
                self.reason = AppLocalization.string("播放状态未同步，无法执行控制")
                pip.invalidatePlaybackState()
            }
        }
    }

    nonisolated func pictureInPictureControllerTimeRangeForPlayback(_ pictureInPictureController: AVPictureInPictureController) -> CMTimeRange {
        CMTimeRange(start: .zero, duration: .positiveInfinity)
    }

    nonisolated func pictureInPictureControllerIsPlaybackPaused(_ pip: AVPictureInPictureController) -> Bool {
        playbackStatus.isPaused(owner: ObjectIdentifier(pip))
    }

    nonisolated func pictureInPictureController(_ pip: AVPictureInPictureController,
                                               didTransitionToRenderSize newRenderSize: CMVideoDimensions) {
        Task { @MainActor [weak self] in
            guard let self, self.controller === pip, let snapshot = self.snapshotProvider?() else { return }
            self.update(snapshot, force: true)
        }
    }

    nonisolated func pictureInPictureController(_ pictureInPictureController: AVPictureInPictureController,
                                               skipByInterval skipInterval: CMTime, completion completionHandler: @escaping () -> Void) {
        // A live lyrics surface has no independent seekable media timeline.
        completionHandler()
    }
}

/// AVKit can query playback synchronously from its own queue. Keep that query
/// independent of MainActor, and expire it even when the app is suspended.
private final class LyricsPiPPlaybackStatus: @unchecked Sendable {
    private let lock = NSLock()
    private var owner: ObjectIdentifier?
    private var playing = false
    private var validUntil: TimeInterval = 0
    private var controlsAllowed = false
    private var controlsBlocked = false

    func update(_ snapshot: LyricsPresentationSnapshot, owner: ObjectIdentifier?) {
        lock.lock()
        defer { lock.unlock() }
        if self.owner != owner {
            controlsAllowed = false
            controlsBlocked = false
        }
        self.owner = owner
        playing = snapshot.isPlaying && snapshot.hasAuthoritativePlayback
        validUntil = snapshot.validUntilUptime
    }

    func clear() {
        lock.lock()
        defer { lock.unlock() }
        owner = nil
        playing = false
        validUntil = 0
        controlsAllowed = false
        controlsBlocked = false
    }

    func allowControls(owner: ObjectIdentifier) {
        lock.lock()
        defer { lock.unlock() }
        if self.owner == owner && !controlsBlocked { controlsAllowed = true }
    }

    func blockControls(owner: ObjectIdentifier?) {
        lock.lock()
        defer { lock.unlock() }
        if self.owner == owner {
            controlsAllowed = false
            // A queued didStart must not reopen controls after willStop.
            controlsBlocked = true
        }
    }

    func acceptsControls(owner: ObjectIdentifier) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return self.owner == owner && controlsAllowed
    }

    func isPaused(owner: ObjectIdentifier) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return self.owner != owner || !playing || ProcessInfo.processInfo.systemUptime >= validUntil
    }
}

/// This app has no other audio-session owner. Claim only on an explicit PiP
/// start, mix with local audio, and restore only while our configuration is intact.
@MainActor
private final class LyricsPiPAudioLease {
    private var previous: (AVAudioSession.Category, AVAudioSession.Mode, AVAudioSession.CategoryOptions)?

    func activate() throws {
        let session = AVAudioSession.sharedInstance()
        previous = (session.category, session.mode, session.categoryOptions)
        do {
            try session.setCategory(.playback, mode: .moviePlayback, options: [.mixWithOthers])
            try session.setActive(true)
        } catch {
            release()
            throw error
        }
    }

    func release() {
        guard let previous else { return }
        self.previous = nil
        let session = AVAudioSession.sharedInstance()
        guard session.category == .playback, session.mode == .moviePlayback,
              session.categoryOptions == [.mixWithOthers] else { return }
        do {
            try session.setActive(false, options: [.notifyOthersOnDeactivation])
            try session.setCategory(previous.0, mode: previous.1, options: previous.2)
        } catch {
            AppLogStore.shared.append("[Lyrics-PiP] audio session release failed: \(error.localizedDescription)")
        }
    }
}
