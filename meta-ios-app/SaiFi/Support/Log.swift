/* sai-fi — voice concierge. */

// One logger. Subsystem is the bundle id so `log stream --predicate 'subsystem == "ai.simular.saiglasses"'`
// is the iOS counterpart of `adb logcat | grep SaiFi`.

import Foundation
import os

enum Log {
  static let logger = Logger(subsystem: "ai.simular.saiglasses", category: "SaiFi")

  static func debug(_ message: String) { logger.debug("\(message, privacy: .public)") }
  static func info(_ message: String) { logger.info("\(message, privacy: .public)") }
  static func error(_ message: String) { logger.error("\(message, privacy: .public)") }
}
