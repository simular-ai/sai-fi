/* sai-fi — voice concierge. */

// The gate, with no test framework.
//
// `swift test` is the ordinary entry point and the one CI uses. This exists because a machine with
// only Command Line Tools installed has neither XCTest nor Testing, and a gate you cannot run is not
// a gate. Both call the same checks; this one just prints them and sets an exit code.

import Foundation
import SaiFiCore

let failures = await runAllChecks()
for failure in failures {
  FileHandle.standardError.write(Data("FAIL  \(failure.name)\n      \(failure.detail)\n".utf8))
}
let total = checkCount()
if failures.isEmpty {
  print("ok — \(total) checks passed")
} else {
  print("\(failures.count) of \(total) checks FAILED")
}
exit(failures.isEmpty ? 0 : 1)
