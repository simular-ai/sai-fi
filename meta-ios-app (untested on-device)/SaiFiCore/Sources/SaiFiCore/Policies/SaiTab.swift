/* sai-fi — the bottom-bar destinations, and the rule for which of them exist. */

// Two destinations always, three when developer mode is on. The visibility rule lives here as two
// pure functions rather than inline in the shell because it is the one piece of genuinely new logic
// in the navigation, and the failure it prevents is silent: turn developer mode off while standing
// on Logs and the selected tab no longer has a bar item, which leaves a pane on screen with no way
// back.
//
// Icons stay in the app target (SwiftUI). The labels and the coerce rule are the contract.
//
// Ported from Android `ui/SaiTab.kt`.

public enum SaiTab: String, Sendable, CaseIterable {
  /// Status, the machine picker, the glasses, and Start/Stop. Was called "Controls".
  case home = "Home"
  /// Account, the ask-first threshold, developer mode, and what build this is.
  case settings = "Settings"
  /// The interleaved transcript + log stream. Only exists while developer mode is on.
  case logs = "Logs"

  public var label: String { rawValue }
}

/// The destinations the bottom bar shows.
///
/// Logs is hidden rather than disabled. A greyed third item in a two-item bar reads as something
/// broken; two items reads as finished.
public func tabsFor(devMode: Bool) -> [SaiTab] {
  devMode ? [.home, .settings, .logs] : [.home, .settings]
}

/// The tab to actually show, given what the user last selected and whether developer mode is still
/// on.
///
/// Falls back to Home rather than to the nearest visible tab: the only way to reach a hidden tab is
/// to have turned developer mode off from Settings, and after that the useful place to be is the
/// screen you actually operate, not the one you just came from.
public func coerceTab(_ selected: SaiTab, devMode: Bool) -> SaiTab {
  tabsFor(devMode: devMode).contains(selected) ? selected : .home
}
