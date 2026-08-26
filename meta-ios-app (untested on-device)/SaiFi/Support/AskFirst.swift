/* sai-fi — the ask-first stepper arithmetic. */

// Bounds and step for the ask-first threshold.
//
// 0 is a real choice, not a floor to be avoided — it means "check with me about everything" — so the
// minimum is 0 rather than one step. The maximum exists because the field takes four digits and 9999
// seconds is nearly three hours of an open microphone before Sai says anything, which nobody selects
// on purpose. An hour is already far past useful and is a round number to state.
//
// Ported from Android `VoiceConciergeActivity.kt` (top-level constants + `steppedAskFirstSec`).

enum AskFirst {
  static let minSec = 0
  static let maxSec = 3_600
  static let stepSec = 5
}

/// One stepper notch from `current`, snapped to the `AskFirst.stepSec` grid and clamped.
///
/// Snapping rather than plain addition, because the field also accepts typed values: from a typed 17,
/// "+" means 20, not 22 — the notch goes to the next round number, so repeated taps converge on the
/// grid instead of carrying an arbitrary offset forever. Going down from a value already on the grid
/// moves a full step (15 → 10); going down from off-grid lands on the grid (17 → 15).
func steppedAskFirstSec(_ current: Int, up: Bool) -> Int {
  let step = AskFirst.stepSec
  let notch: Int
  if up {
    notch = (current / step + 1) * step
  } else if current % step != 0 {
    notch = (current / step) * step
  } else {
    notch = current - step
  }
  return min(max(notch, AskFirst.minSec), AskFirst.maxSec)
}
