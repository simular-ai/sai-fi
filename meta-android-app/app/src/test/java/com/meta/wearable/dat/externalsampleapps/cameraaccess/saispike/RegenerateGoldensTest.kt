/* sai-fi — voice concierge. */

// Write the golden fixtures. This is the only thing that writes them.
//
//   SAI_REGEN_GOLDENS=1 ./gradlew :app:testDebugUnitTest --rerun --tests "*RegenerateGoldensTest*"
//   git diff app/src/test/resources/parity/     # review the wording change, then commit it
//
// Run it after changing anything in `ConciergeProtocol.kt` or `ActivityLog.kt`. The asserting tests
// (`ConciergeProtocolGoldenTest`, `ActivityLogGoldenTest`) fail until you do — which is the whole
// design.
//
// WHY IT IS A SWITCHED-OFF TEST RATHER THAN A GRADLE TASK. It needs the unit-test runtime classpath
// and the same `org.json` the ports run against, which is what a test already has; a JavaExec task
// would have to reassemble both. The env gate is what keeps it honest: CI never sets
// SAI_REGEN_GOLDENS, exactly as it never sets SAI_CONVERSATION_EVAL or SAI_DEMO. That guarantee is
// load-bearing rather than cosmetic — in cloud-api this generator WAS an ordinary test that wrote
// its own expected output, so every CI run silently rewrote the fixtures and drift became
// undetectable. A golden that regenerates itself is not a golden.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

class RegenerateGoldensTest {

  @Test
  fun writeGoldens() {
    assumeTrue(
        "set SAI_REGEN_GOLDENS=1 to rewrite the golden fixtures",
        System.getenv("SAI_REGEN_GOLDENS") == "1")

    // Gradle runs unit tests with the module directory as the working directory, so this reaches the
    // committed sources rather than the copy staged onto the test classpath — writing to that one
    // would look like it worked and change nothing on disk.
    val dir = File("src/test/resources/parity")
    check(dir.isDirectory) { "expected the golden directory at ${dir.absolutePath}" }

    for ((file, build) in GOLDEN_FILES) {
      val fixtures = build()
      File(dir, file).writeText(renderFile(fixtures))
      println("wrote $file (${fixtures.size} fixtures)")
    }
    println("\nRefreshed ${dir.absolutePath}")
  }
}
