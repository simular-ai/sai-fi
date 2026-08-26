/* sai-fi — voice concierge (audio capture/playback + glasses routing). */

// AudioIo — capture (16 kHz PCM16) + playback (24 kHz PCM16) for the Gemini Live loop, with a
// selectable route (phone vs glasses).
//
// Both routes ride `.playAndRecord` + `.voiceChat` for the whole call, so the platform AEC cancels
// our own playback out of the mic. Without this, speaker output leaks into the mic and the model
// constantly self-barges-in.
//
// PHONE route: capture + playback ride the built-in earpiece/speaker + mic.
//
// GLASSES route: one persistent HFP session for the whole call — the glasses mic streams to the
// model while the model's TTS plays back over the same HFP link. Mono, lower-fidelity (HFP is 8 kHz
// narrowband on device, not A2DP hi-fi), but full-duplex: the mic stays live during playback, so
// the automatic-VAD voice barge-in works on the glasses route exactly like it does on the phone
// route. We deliberately do NOT switch to A2DP for hi-fi playback — that would drop the mic
// mid-utterance and make barge-in impossible on the glasses.
//
// The input node's format on an HFP route is 8 kHz mono — conversion up to 16 kHz is mandatory.
//
// Ported from Android `AudioIo.kt`.

import AVFoundation
import Foundation
import os
import SaiFiCore

public final class AudioIo: @unchecked Sendable {

  public enum Route: String, Sendable {
    case phone
    case glasses
  }

  /// Notified whenever the active mic/speaker route changes — including an unsolicited change like
  /// the glasses powering off mid-call. Delivered on the main thread. `onGlasses` is true when the
  /// active input is the BT HFP route.
  public typealias RouteChanged = @Sendable (String?, Bool) -> Void

  private let onRouteChanged: RouteChanged?
  private let log = Logger(subsystem: "ai.simular.saiglasses", category: "Audio")

  private let engine = AVAudioEngine()
  private let player = AVAudioPlayerNode()
  private let playFormat = AudioIo.makePlayFormat()
  private let captureFormat = AudioIo.makeCaptureFormat()

  private let playLock = NSLock()
  private var playQueue: [Data] = []
  private let playAvailable = DispatchSemaphore(value: 0)
  private var playerTask: Thread?
  private let playerLock = NSLock()

  private let gateLock = NSLock()
  private var hangoverUntil: Int64 = 0
  private var leftover = Data()
  private var converter: AVAudioConverter?
  private var converterInputFormat: AVAudioFormat?

  private var capturing = false
  private var desiredRoute: Route = .phone
  private var onPcm: ((Data) -> Void)?
  private var observers: [NSObjectProtocol] = []

  /// Last measured input-node format, for the HFP spike (HFP is 8 kHz mono on device).
  public private(set) var measuredInputFormat: String = "—"

  public init(onRouteChanged: RouteChanged? = nil) {
    self.onRouteChanged = onRouteChanged
  }

  // ── Queries ────────────────────────────────────────────────────────────────

  /// The glasses on the HFP (voice) route, if connected as a Bluetooth audio input.
  public func glassesInput() -> AVAudioSessionPortDescription? {
    AVAudioSession.sharedInstance().availableInputs?.first { $0.portType == .bluetoothHFP }
  }

  /// True if the glasses (or any BT HFP headset) are reachable as an input right now.
  public func glassesAvailable() -> Bool { glassesInput() != nil }

  /// Is there model audio still waiting to be written to the player?
  public var playbackPending: Bool {
    playLock.lock()
    defer { playLock.unlock() }
    return !playQueue.isEmpty
  }

  // ── Route ──────────────────────────────────────────────────────────────────

  /// Choose the route to use. Safe to call before `start` (stored and applied when capture opens)
  /// or during a call (applied immediately).
  public func selectRoute(_ route: Route) {
    desiredRoute = route
    if capturing { applyRoute() }
  }

  /// Apply `desiredRoute` to the session. The route that actually took effect reaches the UI
  /// through `onRouteChanged`.
  private func applyRoute() {
    let session = AVAudioSession.sharedInstance()
    switch desiredRoute {
    case .glasses:
      if let dev = glassesInput() {
        do {
          try session.setPreferredInput(dev)
          log.debug("setCommunicationDevice(glasses)=true")
        } catch {
          log.warning("setPreferredInput(glasses) failed: \(error.localizedDescription, privacy: .public)")
        }
      } else {
        log.warning("GLASSES requested but no SCO device — staying on phone")
        applyPhoneRoute(session)
      }
    case .phone:
      applyPhoneRoute(session)
    }
  }

  private func applyPhoneRoute(_ session: AVAudioSession) {
    if let builtin = session.availableInputs?.first(where: { $0.portType == .builtInMic }) {
      try? session.setPreferredInput(builtin)
    }
  }

  private func notifyCurrentRoute() {
    let (name, onGlasses) = Self.currentRoute()
    if let onRouteChanged {
      DispatchQueue.main.async { onRouteChanged(name, onGlasses) }
    }
  }

  /// Active input name and whether it is the HFP (glasses) route.
  public static func currentRoute() -> (name: String?, onGlasses: Bool) {
    let route = AVAudioSession.sharedInstance().currentRoute
    let input = route.inputs.first
    let onGlasses = route.inputs.contains { $0.portType == .bluetoothHFP }
    return (input?.portName, onGlasses)
  }

  /// True when a BT HFP headset (the glasses) is available as an input.
  public static func glassesHfpAvailable() -> Bool {
    AVAudioSession.sharedInstance().availableInputs?.contains { $0.portType == .bluetoothHFP } ?? false
  }

  /// Prompt for microphone access. Simulator can report `.denied` until the system dialog has
  /// actually run, so this always requests unless already granted rather than trusting a stale denial.
  public static func requestRecordPermission() async -> Bool {
    if AVAudioApplication.shared.recordPermission == .granted { return true }
    return await withCheckedContinuation { cont in
      AVAudioApplication.requestRecordPermission { granted in
        cont.resume(returning: granted)
      }
    }
  }

  // ── Session ────────────────────────────────────────────────────────────────

  /// `.playAndRecord` + `.voiceChat` + HFP option. `.voiceChat` is what engages hardware AEC.
  public static func configureSession() throws {
    let session = AVAudioSession.sharedInstance()
    var options: AVAudioSession.CategoryOptions = []
    // Plan: `.allowBluetoothHFP` on iOS 26+, else `.allowBluetooth`. On the iOS 27 SDK those are
    // the same option (`allowBluetooth` is the deprecated name), so the HFP name is used on every
    // supported OS (deployment is iOS 17).
    options.insert(.allowBluetoothHFP)
    try session.setCategory(.playAndRecord, mode: .voiceChat, options: options)
    try session.setActive(true, options: .notifyOthersOnDeactivation)
  }

  // ── Lifecycle ──────────────────────────────────────────────────────────────

  /// Open playback + start streaming mic frames (16 kHz PCM16 mono) to `onPcm` until `stop`.
  public func start(onPcm: @escaping (Data) -> Void) throws {
    self.onPcm = onPcm
    try Self.configureSession()
    applyRoute()

    if player.engine == nil {
      engine.attach(player)
      engine.connect(player, to: engine.mainMixerNode, format: playFormat)
    }

    do {
      try engine.inputNode.setVoiceProcessingEnabled(true)
    } catch {
      log.warning("voice processing: \(error.localizedDescription, privacy: .public)")
    }

    installTap()
    engine.prepare()
    try engine.start()
    player.play()

    capturing = true
    playLock.lock()
    playQueue.removeAll()
    playLock.unlock()

    let thread = Thread { [weak self] in
      self?.playbackLoop()
    }
    thread.name = "live-playback"
    thread.start()
    playerTask = thread

    listenForRouteChanges()
    notifyCurrentRoute()
    snapshotInputFormat()
  }

  public func stop() {
    capturing = false
    playAvailable.signal()

    observers.forEach { NotificationCenter.default.removeObserver($0) }
    observers.removeAll()

    engine.inputNode.removeTap(onBus: 0)
    playLock.lock()
    playQueue.removeAll()
    playLock.unlock()

    player.stop()
    player.reset()
    let playbackFinished = joinPlayback(timeoutMs: 500)
    engine.stop()

    leftover.removeAll()
    converter = nil
    converterInputFormat = nil
    hangoverUntil = 0
    onPcm = nil

    if !playbackFinished {
      log.warning("playback thread still in write() at stop — not releasing the track under it")
    }
    try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    log.debug("audio stopped")
  }

  private func joinPlayback(timeoutMs: Int) -> Bool {
    guard let thread = playerTask else { return true }
    let deadline = Date().addingTimeInterval(Double(timeoutMs) / 1000.0)
    while thread.isExecuting && Date() < deadline {
      Thread.sleep(forTimeInterval: 0.02)
    }
    playerTask = nil
    return !thread.isExecuting
  }

  // ── Playback ───────────────────────────────────────────────────────────────

  /// Queue a chunk of 24 kHz PCM16 from the model.
  ///
  /// Never writes to the player directly. A dedicated playback thread drains this queue so the
  /// Live reader is never stalled and a flush takes effect at once.
  public func play(_ pcm: Data) {
    guard capturing, !pcm.isEmpty else { return }
    playLock.lock()
    playQueue.append(pcm)
    playLock.unlock()
    playAvailable.signal()
  }

  /// A short two-note cue, played the instant a glasses capture starts. Plays `CaptureCue.pcm`
  /// from SaiFiCore — does not reimplement the sine math.
  public func playCaptureCue() {
    guard capturing else { return }
    play(CaptureCue.pcm)
  }

  /// Barge-in: drop everything queued for playback so the model goes quiet immediately.
  public func flushPlayback() {
    playLock.lock()
    playQueue.removeAll()
    playLock.unlock()
    playerLock.lock()
    if player.isPlaying {
      player.stop()
      player.reset()
      if capturing { player.play() }
    }
    playerLock.unlock()
  }

  private func playbackLoop() {
    while capturing {
      _ = playAvailable.wait(timeout: .now() + 0.05)
      guard capturing else { break }
      let chunk: Data?
      playLock.lock()
      if playQueue.isEmpty {
        chunk = nil
      } else {
        chunk = playQueue.removeFirst()
      }
      playLock.unlock()
      guard let chunk, let buffer = Self.pcm16Buffer(chunk, format: playFormat) else { continue }
      playerLock.lock()
      if capturing { player.scheduleBuffer(buffer) }
      playerLock.unlock()
    }
  }

  // ── Capture ────────────────────────────────────────────────────────────────

  private func installTap() {
    let input = engine.inputNode
    input.removeTap(onBus: 0)
    let format = input.outputFormat(forBus: 0)
    snapshotInputFormat()
    input.installTap(onBus: 0, bufferSize: 4096, format: format) { [weak self] buffer, _ in
      self?.ingest(buffer)
    }
  }

  private func snapshotInputFormat() {
    let format = engine.inputNode.outputFormat(forBus: 0)
    measuredInputFormat =
      "\(Int(format.sampleRate)) Hz, \(format.channelCount) ch, \(Self.describe(format))"
  }

  private func ingest(_ buffer: AVAudioPCMBuffer) {
    guard capturing, let onPcm else { return }
    guard let converted = convert(buffer) else { return }
    leftover.append(converted)
    while leftover.count >= Self.frameBytes {
      let frame = Data(leftover.prefix(Self.frameBytes))
      leftover.removeFirst(Self.frameBytes)
      gateLock.lock()
      let gated = Self.gated(frame, hangoverUntil: &hangoverUntil, nowMs: Self.nowMs())
      gateLock.unlock()
      onPcm(gated)
    }
  }

  private func convert(_ buffer: AVAudioPCMBuffer) -> Data? {
    let inFormat = buffer.format
    if converter == nil || converterInputFormat?.sampleRate != inFormat.sampleRate
      || converterInputFormat?.channelCount != inFormat.channelCount
    {
      converter = AVAudioConverter(from: inFormat, to: captureFormat)
      converterInputFormat = inFormat
      leftover.removeAll()
      snapshotInputFormat()
    }
    guard let converter else { return nil }

    let ratio = captureFormat.sampleRate / inFormat.sampleRate
    let outFrames = AVAudioFrameCount((Double(buffer.frameLength) * ratio).rounded(.up) + 32)
    guard let out = AVAudioPCMBuffer(pcmFormat: captureFormat, frameCapacity: outFrames) else {
      return nil
    }
    var error: NSError?
    var consumed = false
    let status = converter.convert(to: out, error: &error) { _, outStatus in
      if consumed {
        outStatus.pointee = .noDataNow
        return nil
      }
      consumed = true
      outStatus.pointee = .haveData
      return buffer
    }
    if status == .error {
      return nil
    }
    return Self.int16Data(out)
  }

  // ── Route follow ───────────────────────────────────────────────────────────

  private func listenForRouteChanges() {
    let center = NotificationCenter.default
    let session = AVAudioSession.sharedInstance()
    observers.append(
      center.addObserver(
        forName: AVAudioSession.routeChangeNotification,
        object: session,
        queue: .main
      ) { [weak self] note in
        self?.handleRouteChange(note)
      })
    observers.append(
      center.addObserver(
        forName: .AVAudioEngineConfigurationChange,
        object: engine,
        queue: .main
      ) { [weak self] _ in
        self?.handleEngineConfigChange()
      })
  }

  private func handleRouteChange(_ note: Notification) {
    guard capturing else { return }
    let reasonValue = (note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt)
      .flatMap(AVAudioSession.RouteChangeReason.init(rawValue:))
    let (_, onGlasses) = Self.currentRoute()

    if reasonValue == .oldDeviceUnavailable {
      log.debug("SCO device removed")
      if desiredRoute == .glasses && !onGlasses {
        applyPhoneRoute(AVAudioSession.sharedInstance())
      }
    } else if glassesAvailable() && !onGlasses && desiredRoute == .glasses {
      log.debug("SCO device added")
      applyRoute()
    }

    notifyCurrentRoute()
    let (name, glasses) = Self.currentRoute()
    log.debug("comm device → \(name ?? "nil", privacy: .public) (glasses=\(glasses))")
  }

  private func handleEngineConfigChange() {
    guard capturing else { return }
    installTap()
    if !engine.isRunning {
      try? engine.start()
    }
    if !player.isPlaying {
      player.play()
    }
    snapshotInputFormat()
    notifyCurrentRoute()
  }

  // ── Noise gate (byte-for-byte with Kotlin) ─────────────────────────────────

  /// RMS amplitude (0..32767) of a little-endian PCM16 `frame` over `len` bytes — the gate's metric.
  public static func rms(_ frame: Data, len: Int) -> Double {
    var sumSq = 0.0
    var count = 0
    var i = 0
    let bytes = frame
    let n = min(len, bytes.count)
    while i + 1 < n {
      let lo = Int(bytes[i])
      let hi = Int(Int8(bitPattern: bytes[i + 1]))
      let sample = (hi << 8) | lo
      sumSq += Double(sample * sample)
      count += 1
      i += 2
    }
    return count == 0 ? 0.0 : sqrt(sumSq / Double(count))
  }

  /// True if `frame` carries speech, by the same RMS test the noise gate uses.
  public static func carriesSpeech(_ frame: Data) -> Bool {
    rms(frame, len: frame.count) >= noiseGateRms
  }

  /// Apply the gate: sub-threshold frames are REPLACED WITH DIGITAL SILENCE, never dropped.
  public static func gated(_ frame: Data, hangoverUntil: inout Int64, nowMs: Int64) -> Data {
    if rms(frame, len: frame.count) >= noiseGateRms {
      hangoverUntil = nowMs + noiseGateHangoverMs
    }
    // Same cadence either way — one frame in, one frame out.
    return nowMs < hangoverUntil ? frame : Data(count: frame.count)
  }

  public static let inRate = 16_000
  public static let outRate = 24_000
  public static let frameBytes = 3200  // 100 ms @ 16 kHz mono 16-bit
  public static let noiseGateRms = 500.0
  public static let noiseGateHangoverMs: Int64 = 1500

  // ── PCM helpers ────────────────────────────────────────────────────────────

  private static func makeCaptureFormat() -> AVAudioFormat {
    AVAudioFormat(
      commonFormat: .pcmFormatInt16,
      sampleRate: Double(inRate),
      channels: 1,
      interleaved: true
    )!
  }

  private static func makePlayFormat() -> AVAudioFormat {
    AVAudioFormat(
      commonFormat: .pcmFormatInt16,
      sampleRate: Double(outRate),
      channels: 1,
      interleaved: true
    )!
  }

  private static func pcm16Buffer(_ data: Data, format: AVAudioFormat) -> AVAudioPCMBuffer? {
    let frames = AVAudioFrameCount(data.count / 2)
    guard frames > 0,
      let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames)
    else { return nil }
    buffer.frameLength = frames
    data.withUnsafeBytes { raw in
      if let src = raw.baseAddress, let dst = buffer.int16ChannelData?[0] {
        memcpy(dst, src, data.count)
      }
    }
    return buffer
  }

  private static func int16Data(_ buffer: AVAudioPCMBuffer) -> Data {
    let frames = Int(buffer.frameLength)
    let bytes = frames * 2
    guard bytes > 0, let src = buffer.int16ChannelData?[0] else { return Data() }
    return Data(bytes: src, count: bytes)
  }

  private static func describe(_ format: AVAudioFormat) -> String {
    switch format.commonFormat {
    case .pcmFormatInt16: return "pcm16"
    case .pcmFormatInt32: return "pcm32"
    case .pcmFormatFloat32: return "f32"
    case .pcmFormatFloat64: return "f64"
    default: return "other"
    }
  }

  private static func nowMs() -> Int64 {
    Int64((Date().timeIntervalSince1970 * 1000.0).rounded())
  }
}
