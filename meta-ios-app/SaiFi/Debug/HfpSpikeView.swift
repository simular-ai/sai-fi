/* sai-fi — voice concierge. */

// Throwaway DEBUG screen that spikes HFP full-duplex + AEC on a real phone with real glasses.
//
// This is the Phase 3 risk-1 spike: `.playAndRecord` + `.voiceChat` + `.allowBluetoothHFP`, pick
// the HFP input, wait ~2 s, print `currentRoute` and the measured input-node format (HFP is 8 kHz
// mono on device), then record and play at the same time so duplex/AEC can be judged on hardware.
// Route loss falls back to the phone without stopping.
//
// There is no Bluetooth in Simulator, so HFP route selection cannot be verified there. The screen
// still runs on the Mac mic so the session + duplex path can be exercised.

#if DEBUG

import AVFoundation
import SaiFiCore
import SwiftUI

struct HfpSpikeView: View {
  @State private var model = HfpSpikeViewModel()

  var body: some View {
    NavigationStack {
      VStack(alignment: .leading, spacing: 12) {
        Text(
          "HFP duplex is unverified on hardware. Simulator has no Bluetooth — this screen is "
            + "meant to be run on a phone with glasses."
        )
        .font(.caption)
        .foregroundStyle(.secondary)

        HStack {
          statusChip("route", model.routeName)
          statusChip("glasses", model.onGlasses ? "yes" : "no")
        }
        statusChip("input format", model.inputFormat)

        Picker("Route", selection: $model.desiredRoute) {
          Text("Phone").tag(AudioIo.Route.phone)
          Text("Glasses (HFP)").tag(AudioIo.Route.glasses)
        }
        .pickerStyle(.segmented)
        .disabled(model.running)
        .onChange(of: model.desiredRoute) { _, route in
          model.selectRoute(route)
        }

        HStack {
          Button(model.running ? "Stop" : "Play") {
            Task { await model.toggle() }
          }
          .buttonStyle(.borderedProminent)

          Button("Capture cue") { model.playCue() }
            .disabled(!model.running)

          Toggle("Tone", isOn: $model.toneEnabled)
            .disabled(!model.running)
        }

        if let error = model.error {
          Text(error).font(.caption).foregroundStyle(.red)
        }

        ScrollView {
          Text(model.log.isEmpty ? "Press Play. After ~2 s the route and input format print here." : model.log)
            .font(.system(.caption, design: .monospaced))
            .frame(maxWidth: .infinity, alignment: .leading)
            .textSelection(.enabled)
        }
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 8))
      }
      .padding()
      .navigationTitle("HFP spike")
      .navigationBarTitleDisplayMode(.inline)
      .onDisappear { model.stop() }
    }
  }

  private func statusChip(_ label: String, _ value: String) -> some View {
    VStack(alignment: .leading, spacing: 2) {
      Text(label.uppercased()).font(.caption2).foregroundStyle(.secondary)
      Text(value).font(.subheadline.monospaced())
    }
    .padding(8)
    .background(Color(.secondarySystemBackground))
    .clipShape(RoundedRectangle(cornerRadius: 8))
  }
}

@Observable
@MainActor
final class HfpSpikeViewModel {
  var running = false
  var desiredRoute: AudioIo.Route = .glasses
  var routeName = "—"
  var onGlasses = false
  var inputFormat = "—"
  var log = ""
  var error: String?
  var toneEnabled = true

  private var io: AudioIo?
  private var toneTask: Task<Void, Never>?
  private var settleTask: Task<Void, Never>?
  private var frames = 0
  private var lastRms = 0.0

  func selectRoute(_ route: AudioIo.Route) {
    io?.selectRoute(route)
  }

  func toggle() async {
    if running { stop() } else { await start() }
  }

  func start() async {
    error = nil
    append("requesting microphone…")
    let granted = await requestMic()
    guard granted else {
      error = "microphone permission denied"
      append("start failed: microphone permission denied")
      return
    }

    let io = AudioIo { [weak self] name, onGlasses in
      Task { @MainActor in
        self?.routeName = name ?? "—"
        self?.onGlasses = onGlasses
        self?.append("comm device → \(name ?? "nil") (glasses=\(onGlasses))")
      }
    }
    io.selectRoute(desiredRoute)
    do {
      try io.start { [weak self] frame in
        guard let self else { return }
        Task { @MainActor in
          self.frames += 1
          self.lastRms = AudioIo.rms(frame, len: frame.count)
        }
      }
    } catch {
      self.error = error.localizedDescription
      append("start failed: \(error.localizedDescription)")
      return
    }
    self.io = io
    running = true
    append("session active — playAndRecord + voiceChat + HFP option")
    append("desired route: \(desiredRoute.rawValue)")
    io.playCaptureCue()
    append("played CaptureCue (\(CaptureCue.pcm.count) bytes, \(CaptureCue.durationMs) ms)")

    settleTask = Task { [weak self] in
      try? await Task.sleep(for: .seconds(2))
      guard let self, !Task.isCancelled else { return }
      self.dumpRoute()
    }
    startTone()
  }

  func stop() {
    settleTask?.cancel()
    settleTask = nil
    toneTask?.cancel()
    toneTask = nil
    io?.stop()
    io = nil
    running = false
    append("stopped")
  }

  func playCue() {
    io?.playCaptureCue()
    append("played CaptureCue")
  }

  private func dumpRoute() {
    let (name, glasses) = AudioIo.currentRoute()
    routeName = name ?? "—"
    onGlasses = glasses
    inputFormat = io?.measuredInputFormat ?? "—"
    let session = AVAudioSession.sharedInstance()
    append("—— after ~2 s settle ——")
    append("currentRoute inputs: \(session.currentRoute.inputs.map { "\($0.portName) (\($0.portType.rawValue))" }.joined(separator: ", "))")
    append("currentRoute outputs: \(session.currentRoute.outputs.map { "\($0.portName) (\($0.portType.rawValue))" }.joined(separator: ", "))")
    append("measured input node format: \(inputFormat)")
    append("HFP available: \(AudioIo.glassesHfpAvailable())")
    append("on glasses: \(glasses)")
    append("mic frames so far: \(frames), last RMS: \(String(format: "%.1f", lastRms))")
  }

  private func startTone() {
    toneTask?.cancel()
    toneTask = Task { [weak self] in
      while let self, !Task.isCancelled {
        try? await Task.sleep(for: .milliseconds(1500))
        guard !Task.isCancelled, self.running, self.toneEnabled else { continue }
        self.io?.play(Self.beepPcm())
      }
    }
  }

  private func requestMic() async -> Bool {
    await withCheckedContinuation { cont in
      AVAudioApplication.requestRecordPermission { granted in
        cont.resume(returning: granted)
      }
    }
  }

  private func append(_ line: String) {
    let stamp = Self.stamp()
    if log.isEmpty {
      log = "\(stamp) \(line)"
    } else {
      log += "\n\(stamp) \(line)"
    }
  }

  private static func stamp() -> String {
    let f = DateFormatter()
    f.dateFormat = "HH:mm:ss.SSS"
    return f.string(from: Date())
  }

  /// 440 Hz, 200 ms, 24 kHz PCM16 — a repeating beep so duplex/AEC can be judged against the live mic.
  private static func beepPcm() -> Data {
    let rate = 24_000
    let ms = 200
    let n = rate * ms / 1000
    var out = Data()
    out.reserveCapacity(n * 2)
    for i in 0..<n {
      let fade = min(1.0, min(Double(i), Double(n - i)) / (Double(rate) * 0.008))
      let v = sin(2.0 * Double.pi * 440.0 * Double(i) / Double(rate)) * 0.22 * fade
      let s = Int(v * Double(Int16.max))
      let clamped = max(-32768, min(32767, s))
      out.append(UInt8(clamped & 0xFF))
      out.append(UInt8((clamped >> 8) & 0xFF))
    }
    return out
  }
}

#endif
