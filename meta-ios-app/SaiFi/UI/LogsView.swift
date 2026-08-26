/* sai-fi — Logs: the transcript and the log stream, interleaved. */

// Reachable only while developer mode is on. Ported from Android `ui/LogsScreen.kt`.

import SwiftUI

struct LogsView: View {
  @Environment(\.saiColors) private var colors
  @Bindable var app: AppModel
  @State private var typed = ""
  private var s: CallCoordinator.State { app.call.state }

  var body: some View {
    let entries = s.entries
    let copyText = entries.map { entry -> String in
      switch entry.kind {
      case .you: "you: \(entry.text)"
      case .sai: "sai: \(entry.text)"
      case .log: entry.text
      }
    }.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)

    VStack(spacing: 0) {
      ScreenHeader(title: "Logs", state: s)
      VStack(alignment: .leading, spacing: 8) {
        if s.active {
          HStack(spacing: 8) {
            TextField("Type a message", text: $typed)
              .textFieldStyle(.roundedBorder)
            Button("Send") {
              app.call.sendText(typed)
              typed = ""
            }
            .buttonStyle(.saiFilled)
            .disabled(typed.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            .frame(width: 72, height: 40)
          }
        }
        if entries.isEmpty {
          Text("Transcript and logs appear here during a call.")
            .font(.footnote)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        } else {
          ScrollViewReader { proxy in
            ScrollView {
              LazyVStack(alignment: .leading, spacing: 2) {
                ForEach(entries, id: \.id) { entry in
                  switch entry.kind {
                  case .you:
                    Text("you: \(entry.text)")
                  case .sai:
                    Text("sai: \(entry.text)")
                  case .log:
                    Text(entry.text)
                      .font(.system(.footnote, design: .monospaced))
                      .foregroundStyle(colors.mutedForeground)
                  }
                }
              }
              .frame(maxWidth: .infinity, alignment: .leading)
            }
            .onChange(of: entries.count) { _, _ in
              if let last = entries.last { proxy.scrollTo(last.id, anchor: .bottom) }
            }
            .onChange(of: entries.last?.text) { _, _ in
              if let last = entries.last { proxy.scrollTo(last.id, anchor: .bottom) }
            }
          }
        }
        HStack {
          Spacer()
          Button("Copy logs") {
            UIPasteboard.general.string = copyText
          }
          .buttonStyle(.saiOutlined)
          .disabled(copyText.isEmpty)
          .padding(.vertical, 8)
          .padding(.horizontal, 12)
        }
      }
      .padding(.horizontal, 16)
      .padding(.vertical, 12)
    }
    .background(colors.background)
  }
}
