/* sai-fi — voice concierge. */

// Tiny DEBUG harness so `AudioIo` + `GeminiLiveClient` are constructible and a Simulator
// conversation can be tried when a Gemini key is present. Not CallCoordinator.

#if DEBUG

import AVFoundation
import SaiFiCore
import SwiftUI

struct LiveHarnessView: View {
  @State private var model = LiveHarnessViewModel()

  var body: some View {
    NavigationStack {
      VStack(alignment: .leading, spacing: 12) {
        Text(
          "Simulator uses the Mac mic. HFP is not available here. A missing Gemini key fails at "
            + "runtime with `start failed: no gemini_api_key` — never a build error."
        )
        .font(.caption)
        .foregroundStyle(.secondary)

        HStack {
          Button(model.running ? "Stop" : "Start") {
            Task { await model.toggle() }
          }
          .buttonStyle(.borderedProminent)
          Text(model.status).font(.caption).foregroundStyle(.secondary)
        }

        HStack {
          TextField("type a turn", text: $model.draft)
            .textFieldStyle(.roundedBorder)
          Button("Send") { model.sendDraft() }
            .disabled(!model.running || model.draft.isEmpty)
        }

        if !model.transcript.isEmpty {
          Text(model.transcript)
            .font(.body)
            .frame(maxWidth: .infinity, alignment: .leading)
        }

        ScrollView {
          Text(model.log.isEmpty ? "Start a session to talk through the Mac mic." : model.log)
            .font(.system(.caption, design: .monospaced))
            .frame(maxWidth: .infinity, alignment: .leading)
            .textSelection(.enabled)
        }
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 8))
      }
      .padding()
      .navigationTitle("Live harness")
      .navigationBarTitleDisplayMode(.inline)
      .onDisappear { model.stop() }
    }
  }
}

@Observable
@MainActor
final class LiveHarnessViewModel {
  var running = false
  var status = "idle"
  var draft = ""
  var transcript = ""
  var log = ""

  private var io: AudioIo?
  private var live: GeminiLiveClient?
  private var youTurn = ""
  private var saiTurn = ""

  func toggle() async {
    if running { stop() } else { await start() }
  }

  func start() async {
    let key = Secrets.geminiApiKey
    if key.isEmpty {
      append("start failed: no gemini_api_key")
      status = "no key"
      return
    }

    let granted = await requestMic()
    guard granted else {
      append("start failed: microphone permission denied")
      status = "no mic"
      return
    }

    let profile: VoiceProfile
    do {
      profile = try VoiceProfile.loadShipped()
    } catch {
      append("start failed: \(error)")
      status = "no profile"
      return
    }
    let boot = SessionBootstrap.from(profile: profile)

    let io = AudioIo { [weak self] name, onGlasses in
      Task { @MainActor in
        self?.append("route → \(name ?? "nil") (glasses=\(onGlasses))")
      }
    }
    io.selectRoute(.phone)

    let live = GeminiLiveClient(
      onAudio: { [weak io] pcm in io?.play(pcm) },
      onInterrupted: { [weak io] in io?.flushPlayback() },
      onTranscript: { [weak self] role, delta in
        Task { @MainActor in self?.onTranscript(role: role, delta: delta) }
      },
      onTurnComplete: { [weak self] in
        Task { @MainActor in self?.youTurn = ""; self?.saiTurn = "" }
      },
      onEffects: { [weak self] effects in
        Task { @MainActor in
          self?.append("effects: \(effects.map { $0.optString("kind") }.joined(separator: ", "))")
        }
      },
      onGetSaiStatus: { "debug harness — no concierge" },
      onRecallHistory: { respond in respond("no history in the debug harness") },
      onSwitchMachine: { _ in "debug harness cannot switch machines" },
      onEndCall: { [weak self] _ in
        Task { @MainActor in self?.stop() }
      },
      onCaptureImage: { respond in
        respond(false, "debug harness has no glasses camera")
      },
      onUsage: { _, _, _ in },
      onPhotoDestined: {},
      onLog: { [weak self] line in
        Task { @MainActor in self?.append(line) }
      },
      onReady: { [weak self] in
        Task { @MainActor in self?.status = "ready" }
      },
      onClosed: { [weak self] in
        Task { @MainActor in
          guard let self, self.running else { return }
          self.append("live closed")
          self.stop()
        }
      }
    )

    do {
      try io.start { [weak live] frame in live?.sendAudio(frame) }
    } catch {
      append("start failed: \(error.localizedDescription)")
      status = "audio failed"
      return
    }

    self.io = io
    self.live = live
    running = true
    status = "connecting"
    live.connect(boot: boot, apiKey: key)
  }

  func stop() {
    live?.close()
    live = nil
    io?.stop()
    io = nil
    running = false
    status = "idle"
  }

  func sendDraft() {
    let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !text.isEmpty else { return }
    live?.sendText(text)
    append("you (typed): \(text)")
    draft = ""
  }

  private func onTranscript(role: String, delta: String) {
    if role == "you" {
      youTurn += delta
    } else {
      saiTurn += delta
    }
    var lines: [String] = []
    if !youTurn.isEmpty { lines.append("you: \(youTurn)") }
    if !saiTurn.isEmpty { lines.append("sai: \(saiTurn)") }
    transcript = lines.joined(separator: "\n")
  }

  private func requestMic() async -> Bool {
    await withCheckedContinuation { cont in
      AVAudioApplication.requestRecordPermission { granted in
        cont.resume(returning: granted)
      }
    }
  }

  private func append(_ line: String) {
    if log.isEmpty { log = line } else { log += "\n\(line)" }
  }
}

#endif
