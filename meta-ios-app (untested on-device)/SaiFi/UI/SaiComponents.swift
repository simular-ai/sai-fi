/* sai-fi — the pieces every screen is built from. */

// Extracted from ConciergeScreen when that file stopped being one screen. Shared by four: the
// sign-in gate, Home, Settings and Logs.
//
// Ported from Android `ui/SaiComponents.kt`.

import MWDATCore
import SwiftUI

extension ShapeStyle where Self == Color {
  /// The border every outlined button uses — `outline`, not `outlineVariant`, which is invisible
  /// against the dark card.
  static var saiEdge: Color { Color.primary.opacity(0.25) }
}

/// A settings-group header: the label in the Sai accent, with a rule beneath it.
struct GroupHeader: View {
  @Environment(\.saiColors) private var colors
  let title: String

  var body: some View {
    VStack(alignment: .leading, spacing: 6) {
      Text(title.uppercased())
        .font(.caption.weight(.semibold))
        .tracking(0.8)
        .foregroundStyle(colors.green)
      Rectangle()
        .fill(colors.green.opacity(0.35))
        .frame(height: 1)
    }
    .frame(maxWidth: .infinity, alignment: .leading)
  }
}

/// Every screen's header: the title, the call-state chip, and the accent rule under both.
struct ScreenHeader: View {
  @Environment(\.saiColors) private var colors
  let title: String
  let state: CallCoordinator.State

  var body: some View {
    VStack(spacing: 0) {
      HStack(spacing: 8) {
        Text(title)
          .font(.title2.weight(.semibold))
          .frame(maxWidth: .infinity, alignment: .leading)
        CallStatusChip(
          active: state.active,
          reconnecting: state.reconnecting,
          paused: state.paused,
          muted: state.saiMuted)
      }
      .padding(.horizontal, 16)
      .padding(.vertical, 12)
      Rectangle()
        .fill(colors.green.opacity(0.35))
        .frame(height: 1)
    }
  }
}

/// One titled card, sized by its content. No inner scroll — the page scrolls once.
struct Section<Content: View>: View {
  @Environment(\.saiColors) private var colors
  let title: String
  @ViewBuilder var content: () -> Content

  var body: some View {
    VStack(alignment: .leading, spacing: 8) {
      Text(title).font(.headline)
      content()
    }
    .frame(maxWidth: .infinity, alignment: .leading)
    .padding(12)
    .background(colors.card)
    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    .overlay(
      RoundedRectangle(cornerRadius: 12, style: .continuous)
        .strokeBorder(colors.border, lineWidth: 1))
  }
}

/// One line of explanation, with the rest available on tap.
struct Hint: View {
  @Environment(\.saiColors) private var colors
  let summary: String
  var detail: String? = nil
  @State private var expanded = false

  var body: some View {
    VStack(alignment: .leading, spacing: 2) {
      Text(summary)
        .font(.footnote)
        .foregroundStyle(colors.mutedForeground)
      if let detail {
        if expanded {
          Text(detail)
            .font(.footnote)
            .foregroundStyle(colors.mutedForeground)
        }
        Button(expanded ? "Less" : "More") { expanded.toggle() }
          .font(.subheadline.weight(.medium))
          .foregroundStyle(colors.brand)
          .buttonStyle(.plain)
      }
    }
  }
}

/// Reopenable scrollable error dialog.
struct SectionErrorAffordance: View {
  @Environment(\.saiColors) private var colors
  let title: String
  let message: String?
  let open: Bool
  let onOpen: () -> Void
  let onDismiss: () -> Void

  var body: some View {
    if let message, !message.isEmpty {
      Button("View error", action: onOpen)
        .font(.subheadline.weight(.medium))
        .foregroundStyle(colors.brand)
        .buttonStyle(.plain)
        .alert(title, isPresented: Binding(get: { open }, set: { if !$0 { onDismiss() } })) {
          Button("Close", action: onDismiss)
        } message: {
          Text(message).font(.system(.footnote, design: .monospaced))
        }
    }
  }
}

/// The most recent glasses photo, with whether it has reached the agent yet.
struct CaptureThumbnail: View {
  @Environment(\.saiColors) private var colors
  let capture: CallCoordinator.Capture
  @State private var full = false

  var body: some View {
    let image = UIImage(data: capture.jpeg)
    HStack(alignment: .center, spacing: 10) {
      if let image {
        Image(uiImage: image)
          .resizable()
          .scaledToFill()
          .frame(width: 48, height: 48)
          .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
          .overlay(
            RoundedRectangle(cornerRadius: 8, style: .continuous)
              .strokeBorder(Color.saiEdge, lineWidth: 1))
      }
      VStack(alignment: .leading, spacing: 2) {
        Text("Latest capture")
        Text(sentLabel)
          .font(.footnote)
          .foregroundStyle(sentColor)
      }
      .frame(maxWidth: .infinity, alignment: .leading)
    }
    .contentShape(Rectangle())
    .onTapGesture { full = true }
    .sheet(isPresented: $full) {
      if let image {
        Image(uiImage: image)
          .resizable()
          .scaledToFit()
          .padding()
          .presentationDetents([.large])
      }
    }
  }

  private var sentLabel: String {
    switch capture.sent {
    case .sent: "Sent to the computer"
    case .sending: "Sending — a request is carrying it"
    case .held: "Held on the phone — say what to do with it"
    }
  }

  private var sentColor: Color {
    switch capture.sent {
    case .sent: colors.green
    case .sending: colors.brand
    case .held: colors.mutedForeground
    }
  }
}

/// Call state as a dot + short label, not a sentence.
struct CallStatusChip: View {
  @Environment(\.saiColors) private var colors
  let active: Bool
  let reconnecting: Bool
  let paused: Bool
  let muted: Bool

  var body: some View {
    let (dot, label): (Color, String) = {
      if !active { return (colors.mutedForeground, "idle") }
      if reconnecting { return (colors.destructive, "reconnecting") }
      if paused { return (colors.warning, "paused") }
      if muted { return (colors.brand, "muted") }
      return (colors.green, "live")
    }()
    HStack(spacing: 6) {
      Circle().fill(dot).frame(width: 8, height: 8)
      Text(label).font(.subheadline.weight(.medium))
    }
    .padding(.horizontal, 10)
    .padding(.vertical, 5)
    .background(colors.surface)
    .clipShape(Capsule())
    .overlay(Capsule().strokeBorder(dot, lineWidth: 1))
  }
}

extension RegistrationState {
  /// Plain English. `available` means *not registered, and able to be* — never print the enum name.
  var saiLabel: String {
    switch self {
    case .registered: "registered"
    case .registering: "registering…"
    case .available: "not registered"
    case .unavailable: "unavailable — check the Meta AI app"
    @unknown default: "checking…"
    }
  }
}
