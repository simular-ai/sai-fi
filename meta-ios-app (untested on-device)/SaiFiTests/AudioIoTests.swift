/* sai-fi — voice concierge. */

@testable import SaiFi
import XCTest

final class AudioIoTests: XCTestCase {

  func testFrameIs100msAt16kHzMonoPcm16() {
    XCTAssertEqual(AudioIo.frameBytes, 3200)
    XCTAssertEqual(AudioIo.inRate * 2 / 10, AudioIo.frameBytes)
    XCTAssertEqual(AudioIo.outRate, 24_000)
  }

  func testRmsOfSilenceIsZero() {
    let silence = Data(count: AudioIo.frameBytes)
    XCTAssertEqual(AudioIo.rms(silence, len: silence.count), 0)
    XCTAssertFalse(AudioIo.carriesSpeech(silence))
  }

  func testRmsMatchesKotlinLittleEndianPcm16() {
    // Two samples: 300 and -400. RMS = sqrt((300² + 400²) / 2) = 353.55…
    var frame = Data()
    func append(_ sample: Int16) {
      let bits = UInt16(bitPattern: sample)
      frame.append(UInt8(bits & 0xFF))
      frame.append(UInt8(bits >> 8))
    }
    append(300)
    append(-400)
    let expected = sqrt((300.0 * 300.0 + 400.0 * 400.0) / 2.0)
    XCTAssertEqual(AudioIo.rms(frame, len: frame.count), expected, accuracy: 0.001)
  }

  func testCarriesSpeechThresholdIs500() {
    XCTAssertFalse(AudioIo.carriesSpeech(pcm(amplitude: 499)))
    XCTAssertTrue(AudioIo.carriesSpeech(pcm(amplitude: 500)))
  }

  func testNoiseGateSubstitutesSilenceNeverDropsFrames() {
    var hangover: Int64 = 0
    let quiet = pcm(amplitude: 0)
    let out = AudioIo.gated(quiet, hangoverUntil: &hangover, nowMs: 0)
    XCTAssertEqual(out.count, quiet.count)
    XCTAssertEqual(out, Data(count: quiet.count))
  }

  func testNoiseGateHangoverKeepsLoudThenSoft() {
    var hangover: Int64 = 0
    let loud = pcm(amplitude: 2000)
    let soft = pcm(amplitude: 0)
    let first = AudioIo.gated(loud, hangoverUntil: &hangover, nowMs: 10_000)
    XCTAssertEqual(first, loud)
    XCTAssertEqual(hangover, 10_000 + AudioIo.noiseGateHangoverMs)

    let during = AudioIo.gated(soft, hangoverUntil: &hangover, nowMs: 10_000 + 1_499)
    XCTAssertEqual(during, soft)

    let after = AudioIo.gated(soft, hangoverUntil: &hangover, nowMs: 10_000 + 1_500)
    XCTAssertEqual(after, Data(count: soft.count))
  }

  private func pcm(amplitude: Int16) -> Data {
    var frame = Data(count: AudioIo.frameBytes)
    let bits = UInt16(bitPattern: amplitude)
    for i in stride(from: 0, to: AudioIo.frameBytes, by: 2) {
      frame[i] = UInt8(bits & 0xFF)
      frame[i + 1] = UInt8(bits >> 8)
    }
    return frame
  }
}
