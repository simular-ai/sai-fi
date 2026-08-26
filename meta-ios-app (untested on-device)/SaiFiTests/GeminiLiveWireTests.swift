/* sai-fi — voice concierge. */

@testable import SaiFi
import XCTest

final class GeminiLiveWireTests: XCTestCase {

  func testEndpointIsBidiGenerateContentWithKeyNotConstrainedAccessToken() {
    let url = GeminiLiveWire.endpoint(apiKey: "test-key-1")
    XCTAssertNotNil(url)
    let s = url!.absoluteString
    XCTAssertTrue(s.contains("BidiGenerateContent?key=test-key-1"))
    XCTAssertFalse(s.contains("Constrained"))
    XCTAssertFalse(s.contains("access_token"))
  }

  func testSetupKeepsEmptyInputAudioTranscriptionAndVAD() throws {
    let boot = SessionBootstrap(
      model: "gemini-test",
      systemPrompt: "be sai",
      toolsJson: #"[{"name":"captureImage","description":"take a photo","parameters":{"type":"object"}}]"#,
      toolCount: 1,
      voice: "Aoede"
    )
    let root = GeminiLiveWire.setupObject(boot)
    let setup = try XCTUnwrap(root["setup"] as? [String: Any])

    let input = try XCTUnwrap(setup["inputAudioTranscription"] as? [String: Any])
    XCTAssertTrue(input.isEmpty, "inputAudioTranscription must stay empty — languageCodes is Vertex-only")
    XCTAssertNil(input["languageCodes"])

    let vad = try XCTUnwrap(
      (setup["realtimeInputConfig"] as? [String: Any])?["automaticActivityDetection"] as? [String: Any])
    XCTAssertEqual(vad["startOfSpeechSensitivity"] as? String, "START_SENSITIVITY_HIGH")
    XCTAssertEqual(vad["endOfSpeechSensitivity"] as? String, "END_SENSITIVITY_LOW")
    XCTAssertEqual(vad["prefixPaddingMs"] as? Int, 400)
    XCTAssertEqual(vad["silenceDurationMs"] as? Int, 1200)

    let tools = try XCTUnwrap(setup["tools"] as? [[String: Any]])
    let decls = try XCTUnwrap(tools.first?["functionDeclarations"] as? [[String: Any]])
    let capture = try XCTUnwrap(decls.first)
    XCTAssertNotNil(capture["parametersJsonSchema"])
    XCTAssertNil(capture["parameters"])
    XCTAssertEqual(setup["model"] as? String, "models/gemini-test")
  }

  func testNoopCallObserverIsConstructible() {
    let observer: CallObserver = NoopCallObserver.shared
    observer.onMic(pcm: Data())
    observer.onInterrupted()
    observer.onCallEnded(machineLabel: "x")
  }
}
