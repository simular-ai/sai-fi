/* sai-fi — voice concierge (control surface). */

// AppModel — the (deliberately thin) control surface. It picks the machine, connects the glasses,
// sets the voice-UX options, and starts/stops the call; the call itself lives in CallCoordinator.
// Once a call is running, everything else is meant to happen by voice (and the glasses temple
// button) — the phone is just machine + on/off.
//
// Auth is in-app Google Sign-In (SaiAuth) → a Firebase ID token sent as the Bearer to the Sai API;
// there is no compiled-in credential.
//
// Ported from Android `VoiceConciergeActivity.kt`. Presenter / WindowCapture omitted.

import AVFoundation
import CoreLocation
import Foundation
import MWDATCore
import Observation
import SaiFiCore
import UIKit

#if DEBUG
import MWDATMockDevice
#endif

/// How long `glassesLinkedNow` waits for DAT to report a *connected* device before giving up.
private let linkProbeTimeoutNs: UInt64 = 1_500_000_000

@Observable
@MainActor
final class AppModel {

  let call = CallCoordinator()
  @ObservationIgnored private var dat: (any WearablesInterface)?

  // Machine picker (like `sai machine`): fetched from GET /v1/agents/machines.
  var machines: [Machine] = []
  var selectedMachine: Machine? = nil
  var machinesInfo: String = ""
  var machinesFetchOk: Bool = false

  /// Voice-UX settings, owned by the Settings tab and threaded into StartParams at call start.
  ///
  /// A `String` rather than an `Int` because it is what the text field holds, and a half-deleted
  /// field has to be allowed to not be a number.
  var askFirstThresholdSec: String = String(Prefs.defaultAskFirstSec)

  /// Developer mode: reveals the Logs tab and the in-call composer. Persisted, off by default.
  var devMode: Bool = false

  var glassesReg: RegistrationState? = nil
  /// DAT reports a device with LinkState.connected, or `nil` while we don't know yet.
  var glassesLinked: Bool? = nil
  var glassesCameraGranted: Bool = false
  var locationRationaleOpen: Bool = false

  var signedIn: Bool = false
  var userEmail: String? = nil
  #if DEBUG
  /// Home without Firebase. `refreshAuthState` must not wipe this.
  @ObservationIgnored private var authBypass = false
  #endif
  /// DEBUG: Home without Firebase. `refreshAuthState` must not wipe this.

  var authError: String? = nil
  var authErrorOpen: Bool = false
  var machinesError: String? = nil
  var machinesErrorOpen: Bool = false
  var glassesError: String? = nil
  var glassesErrorOpen: Bool = false

  @ObservationIgnored private var cameraPermRequested = false
  @ObservationIgnored private var startedRegistrationThisProcess = false
  @ObservationIgnored private var datReinitializedThisProcess = false
  @ObservationIgnored private var datTask: Task<Void, Never>?
  @ObservationIgnored private var routeObserver: NSObjectProtocol?
  @ObservationIgnored private var started = false
  @ObservationIgnored private let locationAsker = LocationAsker()
  @ObservationIgnored private let glassesLink = GlassesLink()

  /// Prefs + auth only. DAT observers wait until `Wearables.configure()` has succeeded —
  /// `Wearables.shared` traps otherwise, which is what crashed the XCTest host.
  init(datReady: Bool) {
    glassesCameraGranted = Prefs.glassesCameraGranted
    devMode = Prefs.devMode
    askFirstThresholdSec = String(Prefs.askFirstSec)
    locationAsker.onChange = { [weak self] granted in
      self?.call.appendIdleLog(
        granted
          ? "location: granted"
          : "location: denied — local questions will need asking")
    }
    refreshAuthState()
    refreshIdleRoute()
    startRouteObserver()
    if datReady { startDat() }
  }

  func startDat() {
    if started { return }
    started = true
    let wearables = Wearables.shared
    dat = wearables
    glassesReg = wearables.registrationState
    startDatObservers()
    Task { await refreshGlassesCameraStatus() }
  }

  deinit {
    datTask?.cancel()
    if let routeObserver {
      NotificationCenter.default.removeObserver(routeObserver)
    }
  }

  func onAskFirstSecChanged(_ typed: String) {
    askFirstThresholdSec = String(typed.filter(\.isNumber).prefix(4))
    if let value = Int(askFirstThresholdSec) {
      Prefs.setAskFirstSec(value)
    }
  }

  @discardableResult
  func commitAskFirstSec() -> Int {
    let settled = (Int(askFirstThresholdSec) ?? Prefs.askFirstSec)
      .clamped(to: AskFirst.minSec...AskFirst.maxSec)
    askFirstThresholdSec = String(settled)
    Prefs.setAskFirstSec(settled)
    return settled
  }

  func nudgeAskFirstSec(up: Bool) {
    let settled = steppedAskFirstSec(Int(askFirstThresholdSec) ?? Prefs.askFirstSec, up: up)
    askFirstThresholdSec = String(settled)
    Prefs.setAskFirstSec(settled)
  }

  func onDevModeChanged(_ value: Bool) {
    devMode = value
    Prefs.setDevMode(value)
  }

  func registerGlasses() {
    guard let wearables = dat else {
      showGlassesError("Wearables SDK is not configured — cannot register glasses")
      return
    }
    startedRegistrationThisProcess = true
    Task {
      do {
        try await wearables.startRegistration()
      } catch let error as RegistrationError {
        showGlassesError(error.description)
      } catch {
        showGlassesError(error.localizedDescription)
      }
    }
  }

  func requestGlassesCamera() {
    clearGlassesError()
    Task {
      let nowLinked = await glassesLinkedNow()
      if glassesLinked != true && !nowLinked {
        showGlassesError("Turn the glasses on and wait until they're linked, then grant camera")
        return
      }
      await requestCameraPermission()
    }
  }

  func signIn() {
    if !SaiAuth.isConfigured {
      showAuthError(
        "Firebase not configured — set firebase_app_id / firebase_api_key / "
          + "firebase_project_id / web_client_id in Secrets.xcconfig, then rebuild.")
      return
    }
    guard let vc = KeyWindow.topController else {
      showAuthError("Sign-in cancelled or failed: no window to present Google Sign-In")
      return
    }
    clearAuthError()
    Task {
      do {
        try await SaiAuth.signInWithGoogle(presenting: vc)
        refreshAuthState()
        if machines.isEmpty { loadMachines() }
      } catch {
        showAuthError("Sign-in cancelled or failed: \(error.localizedDescription)")
      }
    }
  }

  func signOut() {
    #if DEBUG
    authBypass = false
    #endif
    SaiAuth.signOut()
    refreshAuthState()
    machines = []
    selectedMachine = nil
    machinesInfo = ""
    machinesFetchOk = false
    clearMachinesError()
    clearAuthError()
  }

  func onLocationRationale(_ proceed: Bool) {
    locationRationaleOpen = false
    if !proceed { return }
    locationAsker.request()
  }

  func sceneBecameActive() {
    refreshAuthState()
    if !call.state.active { refreshIdleRoute() }
    guard started else { return }
    Task { await refreshGlassesCameraStatus() }
  }

  func loadMachines() {
    machinesInfo = "Loading…"
    clearMachinesError()
    machinesFetchOk = false
    Task {
      let token = await SaiAuth.idToken()
      if token == nil {
        machines = []
        selectedMachine = nil
        if SaiAuth.isSignedIn() {
          showMachinesError(
            "Couldn't refresh your sign-in token, so the machine list can't load.\n"
              + "You are still signed in — this is the token refresh failing, not the session. "
              + "Check the connection and hit Reload; if it keeps failing, sign out in Settings "
              + "and sign in again.",
            summary: "Sign-in token refresh failed")
        } else {
          refreshAuthState()
          machinesInfo = "Sign in to load machines"
        }
        return
      }
      if Secrets.saiApiUrl.isEmpty {
        machines = []
        selectedMachine = nil
        machinesFetchOk = false
        showMachinesError(
          "sai_api_url is empty. Set it in meta-ios-app/Secrets.xcconfig "
            + "(https://api.sai.simular.ai) and rebuild.",
          summary: "No sai_api_url")
        return
      }
      do {
        let list = try await ConciergeClient.listMachines(
          baseUrl: Secrets.saiApiUrl,
          bearerToken: token!,
          versionTag: Secrets.saiVersionTag)
        machines = list
        if selectedMachine == nil || !list.contains(where: { $0.machineId == selectedMachine?.machineId }) {
          let saved = Prefs.machineId
          selectedMachine = list.first { $0.machineId == saved } ?? list.first
        }
        machinesFetchOk = true
        machinesInfo = list.isEmpty ? "No machines found" : ""
      } catch {
        machines = []
        selectedMachine = nil
        machinesFetchOk = false
        let (summary, detail) = machinesLoadFailure(error)
        showMachinesError(detail, summary: "Load failed — \(summary)")
      }
    }
  }

  func onStartClicked() {
    clearGlassesError()
    if !SaiAuth.isSignedIn() && !signedIn {
      showAuthError("Sign in first")
      return
    }
    if !machinesFetchOk || selectedMachine == nil {
      let msg = !machinesFetchOk ? "Reload machines before starting" : "Select a machine"
      showMachinesError(msg, summary: msg)
      return
    }
    Task {
      let granted = await requestMic()
      if !granted {
        showGlassesError("Mic permission denied — can't start a call")
        return
      }
      await CallNotifications.requestAuthorizationIfNeeded()
      startCallNow()
    }
  }

  func handleOpenURL(_ url: URL) {
    if SaiAuth.handleURL(url) { return }
    guard
      let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
      components.queryItems?.contains(where: { $0.name == "metaWearablesAction" }) == true
    else { return }
    guard let wearables = dat else { return }
    Task {
      do {
        _ = try await wearables.handleUrl(url)
      } catch let error as RegistrationError {
        showGlassesError(error.description)
      } catch {
        showGlassesError(error.localizedDescription)
      }
    }
  }

  // MARK: - Private

  private func startCallNow() {
    guard let m = selectedMachine else { return }
    Prefs.setMachineId(m.machineId)
    let useGlasses = AudioIo.glassesHfpAvailable()
    refreshIdleRoute()
    clearGlassesError()
    Task {
      let token = await SaiAuth.idToken()
      if token == nil && SaiAuth.isSignedIn() {
        showAuthError(
          "Couldn't refresh your sign-in token, so the call can't start. Check the connection; "
            + "if it keeps failing, sign out in Settings and sign in again.")
        return
      }
      if token == nil {
        call.appendIdleLog(
          "no Firebase token — Gemini can still run; agent POSTs will fail until you sign in")
      }
      call.start(
        params: CallCoordinator.StartParams(
          baseUrl: Secrets.saiApiUrl,
          token: token ?? "",
          machineId: m.machineId,
          machineLabel: m.label,
          machines: machines,
          useGlasses: useGlasses,
          askFirstThresholdMs: Int64(commitAskFirstSec()) * 1000))
    }
  }

  private func startDatObservers() {
    datTask?.cancel()
    guard let wearables = dat else { return }
    datTask = Task { [weak self] in
      await withTaskGroup(of: Void.self) { group in
        group.addTask { @MainActor in
          for await state in wearables.registrationStateStream() {
            guard let self, !Task.isCancelled else { return }
            self.glassesReg = state
            await self.refreshGlassesCameraStatus()
            self.maybeAutoRequestGlassesCamera()
            self.maybeReinitializeAfterRegistration(state)
          }
        }
        group.addTask { [weak self] in
          await self?.observeGlassesLink(wearables)
        }
      }
    }
  }

  private func observeGlassesLink(_ wearables: WearablesInterface) async {
    let (stream, continuation) = AsyncStream<Bool?>.makeStream()
    let observe = Task { [glassesLink] in
      await glassesLink.observe(stream) { [weak self] linked in
        Task { @MainActor in
          self?.publishGlassesLink(linked)
          if linked == true { self?.maybeAutoRequestGlassesCamera() }
        }
      }
    }
    var tokens: [any AnyListenerToken] = []
    for await ids in wearables.devicesStream() {
      for token in tokens { await token.cancel() }
      tokens.removeAll()
      continuation.yield(Self.linkReading(ids, wearables: wearables))
      for id in ids {
        guard let device = wearables.deviceForIdentifier(id) else { continue }
        let token = device.addLinkStateListener { _ in
          Task { @MainActor in
            continuation.yield(Self.linkReading(wearables.devices, wearables: wearables))
          }
        }
        tokens.append(token)
      }
    }
    continuation.finish()
    for token in tokens { await token.cancel() }
    await observe.value
  }

  private static func linkReading(
    _ ids: [DeviceIdentifier],
    wearables: WearablesInterface
  ) -> Bool? {
    if ids.isEmpty { return nil }
    let devices = ids.compactMap { wearables.deviceForIdentifier($0) }
    if devices.isEmpty { return nil }
    if devices.contains(where: { $0.linkState == .connected }) { return true }
    if devices.contains(where: { $0.linkState == .connecting }) { return nil }
    return false
  }

  private func maybeReinitializeAfterRegistration(_ state: RegistrationState) {
    if state != .registered { return }
    if !startedRegistrationThisProcess || datReinitializedThisProcess { return }
    if call.state.active { return }
    #if DEBUG
    // Wearables.reset() after a MockDeviceKit fake registration drops the mock and the next
    // configure() fails (keychain / already-configured). Real hardware still needs the re-init.
    if MockDeviceKit.shared.isEnabled { return }
    #endif
    datReinitializedThisProcess = true
    reinitializeDatForFreshRegistration()
  }

  private func reinitializeDatForFreshRegistration() {
    Task {
      datTask?.cancel()
      ObjC_Wearables.reset()
      do {
        try Wearables.configure()
        self.dat = Wearables.shared
      } catch {
        call.appendIdleLog("glasses re-init failed: \(error.localizedDescription)")
      }
      cameraPermRequested = false
      publishGlassesLink(nil)
      startDatObservers()
    }
  }

  private func glassesLinkedNow() async -> Bool {
    guard let wearables = dat else { return false }
    let linked: Bool = await withTaskGroup(of: Bool.self) { group in
      group.addTask {
        try? await Task.sleep(nanoseconds: linkProbeTimeoutNs)
        return false
      }
      group.addTask { [wearables] in
        for await ids in wearables.devicesStream() {
          if ids.isEmpty { continue }
          let devices = ids.compactMap { wearables.deviceForIdentifier($0) }
          if devices.contains(where: { $0.linkState == .connected }) { return true }
        }
        return false
      }
      let first = await group.next() ?? false
      group.cancelAll()
      return first
    }
    if linked { publishGlassesLink(true) }
    return linked
  }

  private func publishGlassesLink(_ linked: Bool?) {
    let changed = glassesLinked != linked
    glassesLinked = linked
    if changed, !call.state.active { refreshIdleRoute() }
  }

  private func maybeAutoRequestGlassesCamera() {
    if cameraPermRequested { return }
    if glassesReg != .registered || glassesLinked != true { return }
    if Prefs.glassesCameraAutoPrompted { return }
    cameraPermRequested = true
    Task {
      guard let wearables = self.dat else { return }
      let status = try? await wearables.checkPermissionStatus(.camera)
      if status == .granted {
        markGlassesCameraGranted()
        return
      }
      if glassesLinked != true {
        cameraPermRequested = false
        return
      }
      Prefs.setGlassesCameraAutoPrompted(true)
      clearGlassesError()
      await requestCameraPermission()
    }
  }

  private func requestCameraPermission() async {
    guard let wearables = dat else { return }
    do {
      let status = try await wearables.requestPermission(.camera)
      call.appendIdleLog("glasses camera: \(status == .granted ? "Granted" : "Denied")")
      if status == .granted { markGlassesCameraGranted() }
    } catch {
      call.appendIdleLog("glasses camera: \(error.localizedDescription)")
      showGlassesError(error.localizedDescription)
    }
  }

  private func markGlassesCameraGranted() {
    glassesCameraGranted = true
    Prefs.setGlassesCameraGranted(true)
    Prefs.setGlassesCameraAutoPrompted(true)
  }

  private func refreshGlassesCameraStatus() async {
    guard let wearables = dat else { return }
    let status = try? await wearables.checkPermissionStatus(.camera)
    switch status {
    case .granted: markGlassesCameraGranted()
    case .denied: clearGlassesCameraGranted()
    default: break
    }
  }

  private func clearGlassesCameraGranted() {
    if !glassesCameraGranted && !Prefs.glassesCameraGranted { return }
    glassesCameraGranted = false
    Prefs.setGlassesCameraGranted(false)
  }

  func refreshAuthState() {
    #if DEBUG
    if ProcessInfo.processInfo.arguments.contains("--ui-testing") {
      signedIn = true
      userEmail = "ui-test@example.com"
      return
    }
    if authBypass {
      signedIn = true
      return
    }
    #endif
    signedIn = SaiAuth.isSignedIn()
    userEmail = SaiAuth.email()
    if signedIn { maybeAutoRequestLocation() }
  }

  /// DEBUG: reach Home without Firebase, so MockDeviceKit and a Mac-mic Gemini call can be exercised.
  #if DEBUG
  func continueWithoutAccount() {
    authBypass = true
    signedIn = true
    userEmail = nil
    if machines.isEmpty {
      let stub = Machine(
        machineId: "simulator",
        name: "Simulator (no agent)",
        status: "active",
        canWake: false)
      machines = [stub]
      selectedMachine = stub
      machinesFetchOk = true
      machinesInfo = "Voice only — sign in to load real machines"
    }
  }

  /// MockDeviceKit just changed registration / pairing. Re-read DAT so Home isn't stuck on
  /// "checking…" until the next scene activation.
  func refreshAfterMockSetup() {
    if dat == nil {
      do {
        try Wearables.configure()
      } catch {
        call.appendIdleLog("mock DAT configure: \(error.localizedDescription)")
      }
      startDat()
    }
    guard let wearables = dat else { return }
    glassesReg = wearables.registrationState
    Task { await refreshGlassesCameraStatus() }
    if wearables.registrationState == .registered {
      markGlassesCameraGranted()
    }
    sceneBecameActive()
  }
  #endif

  private func maybeAutoRequestLocation() {
    if Prefs.locationAutoPrompted { return }
    Prefs.setLocationAutoPrompted(true)
    if PhoneLocation.hasPermission() { return }
    locationRationaleOpen = true
  }

  private func startRouteObserver() {
    routeObserver = NotificationCenter.default.addObserver(
      forName: AVAudioSession.routeChangeNotification,
      object: nil,
      queue: .main
    ) { [weak self] _ in
      Task { @MainActor in
        guard let self, !self.call.state.active else { return }
        self.refreshIdleRoute()
      }
    }
  }

  private func refreshIdleRoute() {
    let status: String
    if AudioIo.glassesHfpAvailable() {
      status = "glasses"
    } else {
      status = "phone"
    }
    call.setIdleRouteStatus(status)
  }

  private func requestMic() async -> Bool {
    await withCheckedContinuation { cont in
      AVAudioApplication.requestRecordPermission { granted in
        cont.resume(returning: granted)
      }
    }
  }

  private func machinesLoadFailure(_ e: Error) -> (String, String) {
    let url = "\(Secrets.saiApiUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/")))/v1/agents/machines"
    if let http = e as? ConciergeHttpException {
      return ("HTTP \(http.status)", "HTTP \(http.status)\nGET \(url)\n\(http.message)")
    }
    let ns = e as NSError
    if ns.domain == NSURLErrorDomain {
      return (
        "No HTTP status (connection failed)",
        "No HTTP status (connection failed)\nGET \(url)\n\(type(of: e)): \(e.localizedDescription)")
    }
    return (
      "No HTTP status (\(type(of: e)))",
      "No HTTP status\nGET \(url)\n\(type(of: e)): \(e.localizedDescription)")
  }

  private func showAuthError(_ msg: String) {
    authError = msg
    authErrorOpen = true
  }

  private func clearAuthError() {
    authError = nil
    authErrorOpen = false
  }

  private func showMachinesError(_ msg: String, summary: String) {
    machinesError = msg
    machinesErrorOpen = true
    machinesInfo = summary
  }

  private func clearMachinesError() {
    machinesError = nil
    machinesErrorOpen = false
  }

  private func showGlassesError(_ msg: String) {
    glassesError = msg
    glassesErrorOpen = true
  }

  private func clearGlassesError() {
    glassesError = nil
    glassesErrorOpen = false
  }

}

/// Holds `CLLocationManager` so `AppModel` does not have to be an `NSObject`.
private final class LocationAsker: NSObject, CLLocationManagerDelegate {
  let manager = CLLocationManager()
  var onChange: ((Bool) -> Void)?

  override init() {
    super.init()
    manager.delegate = self
  }

  func request() { manager.requestWhenInUseAuthorization() }

  func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
    switch manager.authorizationStatus {
    case .authorizedAlways, .authorizedWhenInUse: onChange?(true)
    case .denied, .restricted: onChange?(false)
    default: break
    }
  }
}

private extension Comparable {
  func clamped(to range: ClosedRange<Self>) -> Self {
    min(max(self, range.lowerBound), range.upperBound)
  }
}
