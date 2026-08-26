/* sai-fi — theme: colours, type, and the SaiTheme wrapper. */

// One file for the whole theme. Wrap the root view in `SaiTheme` and nothing else. LIGHT BY DEFAULT,
// following the system: light matches the desktop Sai app. Both schemes are that app's own token
// sets, so dark mode is Sai's dark theme rather than an invention. Deliberately NOT iOS dynamic
// colour / wallpaper tint — that would let the phone's wallpaper repaint Sai's app, which is the
// opposite of the point.
//
// Ported from Android `ui/theme/Theme.kt`. Hex values are identical. Two rules:
//
//  1. `primary` is near-black / near-white, NOT the Sai green. The desktop app's CTAs are
//     high-contrast neutral and green means status.
//  2. Every role below is set. Anything omitted falls back to system defaults and only shows up
//     when something rare draws with it.

import SwiftUI

public struct SaiColors: Sendable, Equatable {
  public var background: Color
  public var foreground: Color
  public var surface: Color
  public var card: Color
  public var cardHover: Color
  public var primary: Color
  public var primaryForeground: Color
  public var secondary: Color
  public var mutedForeground: Color
  public var border: Color
  public var borderStrong: Color
  public var destructive: Color
  public var destructiveForeground: Color
  public var green: Color
  public var warning: Color
  public var brand: Color
}

extension SaiColors {
  /// `--success` / `--logo-bg` — the same green as the launcher icon. Mode-independent.
  public static let green = Color(hex: 0x16D342)
  /// `--warning`. Mode-independent.
  public static let amber = Color(hex: 0xFFAB3C)

  public static let light = SaiColors(
    background: Color(hex: 0xF9FAF5),  // --background
    foreground: Color(hex: 0x1A1A1E),  // --foreground
    surface: Color(hex: 0xF5F6F1),  // --surface
    card: Color(hex: 0xFFFFFF),  // --card
    cardHover: Color(hex: 0xE8E8E5),  // --card-hover
    primary: Color(hex: 0x0C0C0C),  // --primary
    primaryForeground: Color(hex: 0xFAFAFA),  // --primary-foreground
    secondary: Color(hex: 0xEFEFED),  // --secondary / --muted / --accent
    mutedForeground: Color(hex: 0x6B6B73),  // --muted-foreground
    border: Color(hex: 0xE2E2DF),  // --border
    borderStrong: Color(hex: 0xD0D0CC),  // --border-strong
    destructive: Color(hex: 0xDC2626),  // --destructive
    destructiveForeground: Color.white,
    // Green relit for the light background — #16D342 on #F9FAF5 is 1.92:1. Same hue pulled down
    // to land at 5.03:1. Dark mode keeps the logo green itself.
    green: Color(hex: 0x0D7D28),
    warning: amber,
    brand: Color(hex: 0x0022FF)  // --brand
  )

  public static let dark = SaiColors(
    background: Color(hex: 0x0C0C0C),
    foreground: Color(hex: 0xFAFAFA),
    surface: Color(hex: 0x111111),
    card: Color(hex: 0x1A1A1A),
    cardHover: Color(hex: 0x202020),
    primary: Color(hex: 0xFAFAFA),
    primaryForeground: Color(hex: 0x0C0C0C),
    secondary: Color(hex: 0x1A1A1A),
    mutedForeground: Color(hex: 0xA3A3A3),
    border: Color(hex: 0x1F1F1F),
    borderStrong: Color(hex: 0x2A2A2A),
    destructive: Color(hex: 0x82181A),  // oklch(0.396 0.141 25.723)
    destructiveForeground: Color(hex: 0xFB2C36),  // oklch(0.637 0.237 25.331)
    green: green,
    warning: amber,
    // #0022FF on #0C0C0C is 2.49:1. Same hue lifted to L=0.70 → 7.16:1.
    brand: Color(hex: 0x6B9AFF)
  )
}

private struct SaiColorsKey: EnvironmentKey {
  static let defaultValue = SaiColors.light
}

extension EnvironmentValues {
  public var saiColors: SaiColors {
    get { self[SaiColorsKey.self] }
    set { self[SaiColorsKey.self] = newValue }
  }
}

/// Light by default, following the system. Put this at the root; screens read `saiColors`.
public struct SaiTheme<Content: View>: View {
  @Environment(\.colorScheme) private var colorScheme
  private let content: Content

  public init(@ViewBuilder content: () -> Content) {
    self.content = content()
  }

  public var body: some View {
    content.environment(\.saiColors, colorScheme == .dark ? .dark : .light)
  }
}

extension Color {
  init(hex: UInt32, alpha: Double = 1) {
    self.init(
      .sRGB,
      red: Double((hex >> 16) & 0xFF) / 255,
      green: Double((hex >> 8) & 0xFF) / 255,
      blue: Double(hex & 0xFF) / 255,
      opacity: alpha)
  }
}
