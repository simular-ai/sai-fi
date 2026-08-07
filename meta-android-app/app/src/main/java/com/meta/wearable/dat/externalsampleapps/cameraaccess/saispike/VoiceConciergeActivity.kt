/*
 * sai-fi — voice concierge (control surface).
 */

// VoiceConciergeActivity — the (deliberately thin) control surface + the app's launcher. It picks the
// machine, connects the glasses, sets the voice-UX options, and starts/stops the call; the call itself
// lives in CallService (a mic foreground service) so it survives screen-off/pocket. Audio route is
// automatic (glasses SCO when available, otherwise phone). The Activity only renders
// CallController.state and issues commands. Once a call is running, everything else is meant to happen
// by voice (and the glasses temple button) — the phone is just machine + on/off.
//
// Auth is in-app Google Sign-In (SaiAuth) → a Firebase ID token sent as the Bearer to cloud-api; there
// is no compiled-in credential. Point CONCIERGE_URL at a PR-staging cloud-api to test a PR.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.LinkState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.ui.ConciergeScreen
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.ui.ConciergeUi
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.ui.theme.SaiTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** How long [VoiceConciergeActivity.glassesLinkedNow] waits for the DAT flows before giving up. */
private const val LINK_PROBE_TIMEOUT_MS = 1_500L

class VoiceConciergeActivity : ComponentActivity(), ConciergeUi {
  // Machine picker (like `sai machine`): fetched from GET /v1/agents/machines, selected in a dropdown.
  override val machines = mutableStateListOf<Machine>()
  override var selectedMachine by mutableStateOf<Machine?>(null)
  override var machinesInfo by mutableStateOf("") // short non-error status ("Loading…", "No machines found")
  override var machinesFetchOk by mutableStateOf(false) // last load succeeded (dropdown enabled)

  // Voice-UX settings (in-memory; threaded into StartParams at call start — no persistence yet).
  override var askFirstThresholdSec by mutableStateOf("15")

  // DAT glasses registration (one-time) — enables the temple button to start/stop the call.
  override var glassesReg by mutableStateOf<RegistrationState?>(null)
  // True when DAT reports at least one device with LinkState.CONNECTED (powered on / in range).
  override var glassesLinked by mutableStateOf(false)
  // Glasses-camera DAT permission (device-level, via Meta AI). Shown as an action only when missing.
  override var glassesCameraGranted by mutableStateOf(false)
  // Our own "why we want location" dialog, shown once at sign-in, immediately before the system sheet.
  override var locationRationaleOpen by mutableStateOf(false)
  // Request the glasses camera permission automatically once per process, right after registration.
  private var cameraPermRequested = false

  // Auth state (Google Sign-In → Firebase). Drives the sign-in UI + gates loading machines / starting.
  override var signedIn by mutableStateOf(false)
  override var userEmail by mutableStateOf<String?>(null)

  // Section errors: full text in a reopenable scrollable dialog (not truncated inline red).
  override var authError by mutableStateOf<String?>(null)
  override var authErrorOpen by mutableStateOf(false)
  override var machinesError by mutableStateOf<String?>(null)
  override var machinesErrorOpen by mutableStateOf(false)
  override var glassesError by mutableStateOf<String?>(null)
  override var glassesErrorOpen by mutableStateOf(false)

  private fun showAuthError(msg: String) {
    authError = msg
    authErrorOpen = true
  }

  private fun clearAuthError() {
    authError = null
    authErrorOpen = false
  }

  private fun showMachinesError(msg: String, summary: String) {
    machinesError = msg
    machinesErrorOpen = true
    machinesInfo = summary
  }

  private fun clearMachinesError() {
    machinesError = null
    machinesErrorOpen = false
  }

  /** Human-readable machines-load failure: always states HTTP status or that none was received. */
  private fun machinesLoadFailure(e: Throwable): Pair<String, String> {
    val url = "${BuildConfig.CONCIERGE_URL.trimEnd('/')}/v1/agents/machines"
    return when (e) {
      is ConciergeHttpException ->
          "HTTP ${e.status}" to
              "HTTP ${e.status}\nGET $url\n${e.message ?: "(no body)"}"
      is java.net.ConnectException,
      is java.net.UnknownHostException,
      is java.net.NoRouteToHostException,
      is java.net.SocketTimeoutException ->
          "No HTTP status (connection failed)" to
              "No HTTP status (connection failed)\nGET $url\n${e.javaClass.simpleName}: ${e.message}"
      else ->
          "No HTTP status (${e.javaClass.simpleName})" to
              "No HTTP status\nGET $url\n${e.javaClass.simpleName}: ${e.message}"
    }
  }

  private fun showGlassesError(msg: String) {
    glassesError = msg
    glassesErrorOpen = true
  }

  private fun clearGlassesError() {
    glassesError = null
    glassesErrorOpen = false
  }

  private val micPermission =
      registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onStartClicked()
        else showGlassesError("Mic permission denied — can't start a call")
      }

  private val notifPermission =
      registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        // Proceed either way — the call runs; only the notification is affected. Still need BT
        // for SCO detect before start.
        when {
          !hasBt() && !btDenied -> btPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
          else -> startServiceNow()
        }
      }

  // BLUETOOTH_CONNECT is required (API 31+) to enumerate/route the glasses' SCO device.
  // Requested on Start when missing so we can auto-pick glasses vs phone.
  private var btDenied = false
  private val btPermission =
      registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
          btDenied = true
          CallController.update { it.copy(routeStatus = "Bluetooth denied — staying on phone") }
        }
        refreshRouteStatus()
        // Re-enter the start gate (skips BT once granted or denied) — avoid referencing
        // notifPermission here (circular ActivityResultLauncher vals confuse type inference).
        onStartClicked()
      }

  // Phone location, for "what's the weather" / "what's near me" — the agent runs on a datacenter VM
  // and has no idea where the user is. Asked for ONCE, at sign-in (maybeAutoRequestLocation), never
  // per call and never mid-conversation: an Android permission sheet appearing in the middle of a
  // hands-free call is a sheet nobody can see to answer. Both granularities are requested together
  // because the sheet lets the user pick approximate, and PhoneLocation honours whichever it gets.
  private val locationPermission =
      registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val granted = grants.values.any { it }
        CallController.appendLog(
            if (granted) "location: granted" else "location: denied — local questions will need asking",
        )
      }

  // Presenter screen mirror (DEBUG only): the app's OWN window, via PixelCopy — no MediaProjection,
  // so no consent dialog, no cast indicator, and nothing outside this app's window is captured. Runs
  // only while the Activity is resumed, which is the whole lifetime there is anything to mirror.
  private var windowCapture: WindowCapture? = null

  /** Off unless this is a DEBUG build with a presenter URL — otherwise there's nowhere to send frames. */
  private fun presenterEnabled() =
      BuildConfig.DEBUG &&
          PresenterSocket.resolveUrl(BuildConfig.PRESENTER_URL, BuildConfig.CONCIERGE_URL).isNotBlank()

  // Separate BT gate for DAT registration (its grant kicks off registration, not routing).
  private val datBtPermission =
      registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) Wearables.startRegistration(this)
        else showGlassesError("Bluetooth needed to register glasses")
      }

  // DAT glasses camera permission (device-level, via the Meta AI app) — needed for the captureImage
  // voice tool. Auto-requested at most once (see maybeAutoRequestGlassesCamera); after that use the button.
  private val datCameraPermission =
      registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        val status = result.getOrNull()
        glassesCameraGranted = status == PermissionStatus.Granted
        CallController.appendLog("glasses camera: ${status ?: "denied"}")
        refreshGlassesCameraStatus()
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    refreshAuthState()
    refreshRouteStatus()
    refreshGlassesCameraStatus()
    enableEdgeToEdge()
    // SaiTheme carries the desktop app's palette + type ramp, light by default and following the
    // system. This used to be a bare `darkColorScheme()` — the stock Material baseline, i.e. Google's
    // purple, with nothing of Sai's brand in it.
    setContent { SaiTheme { ConciergeScreen(this) } }
    // React to DAT registration changes (SDK initialized in SaiFiApp). Auto-request the glasses
    // camera permission the first time we see REGISTERED *and* a device is linked — otherwise Meta AI
    // opens a permission sheet that can't complete with the glasses offline.
    lifecycleScope.launch {
      Wearables.registrationState.collect { state ->
        glassesReg = state
        refreshGlassesCameraStatus()
        maybeAutoRequestGlassesCamera()
      }
    }
    // Live DAT link (powered on / in range) — separate from registration, which persists offline.
    lifecycleScope.launch {
      Wearables.devices.collectLatest { ids ->
        if (ids.isEmpty()) {
          glassesLinked = false
          return@collectLatest
        }
        val flows = ids.mapNotNull { Wearables.devicesMetadata[it] }
        if (flows.isEmpty()) {
          glassesLinked = false
          return@collectLatest
        }
        combine(flows) { devices -> devices.any { it.linkState == LinkState.CONNECTED } }.collect {
          glassesLinked = it
          maybeAutoRequestGlassesCamera()
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    if (!CallController.state.value.active) refreshRouteStatus()
    refreshGlassesCameraStatus()
    startWindowMirror()
  }

  override fun onPause() {
    super.onPause()
    // The window has no surface to copy once we're not resumed, and there is nothing worth showing
    // the room anyway — the capture is of this app's UI.
    windowCapture?.stop()
    windowCapture = null
  }

  private fun startWindowMirror() {
    if (!presenterEnabled() || windowCapture != null) return
    windowCapture =
        WindowCapture(
                window = window,
                // CallService owns the socket and opens this sink while a call is up; frames are
                // dropped when it's null or the socket is down.
                onFrame = { CallController.screenSink?.invoke(it) },
                onLog = { CallController.appendLog(it) },
            )
            .also { it.start() }
  }

  /** One-time DAT registration with Meta AI (not a live Bluetooth link). */
  override fun registerGlasses() {
    if (!hasBt()) {
      datBtPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
      return
    }
    Wearables.startRegistration(this)
  }

  /**
   * DAT camera permission via Meta AI. Requires a linked (powered-on) device — otherwise Meta AI still
   * opens and the user gets stuck in a flow that can't succeed.
   *
   * The link is read **live** here rather than trusted from [glassesLinked]. That field is fed by the
   * devices collector in [onCreate], which on a first install has usually not emitted by the time the
   * user finishes registering — so a stale `false` was refusing the grant on exactly the run that
   * needs it, and the button that would have retried was disabled by the same flag. Checking the SDK
   * directly settles it; the guard itself is worth keeping, so a genuinely powered-off pair still gets
   * an explanation instead of a Meta AI flow that cannot complete.
   */
  override fun requestGlassesCamera() {
    clearGlassesError()
    lifecycleScope.launch {
      if (!glassesLinked && !glassesLinkedNow()) {
        showGlassesError("Turn the glasses on and wait until they're linked, then grant camera")
        return@launch
      }
      datCameraPermission.launch(Permission.CAMERA)
    }
  }

  /**
   * One bounded read of "is a device connected right now", independent of the collected flag.
   *
   * Bounded because these flows need not emit at all when nothing is attached — an unguarded
   * `first()` would hang the grant instead of refusing it.
   */
  private suspend fun glassesLinkedNow(): Boolean {
    val linked =
        withTimeoutOrNull(LINK_PROBE_TIMEOUT_MS) {
          val ids = Wearables.devices.first()
          if (ids.isEmpty()) return@withTimeoutOrNull false
          val flows = ids.mapNotNull { Wearables.devicesMetadata[it] }
          if (flows.isEmpty()) return@withTimeoutOrNull false
          combine(flows) { devices -> devices.any { it.linkState == LinkState.CONNECTED } }.first()
        } ?: false
    // Keep the rest of the screen honest: if the probe found a link the collector had not reported
    // yet, "Link: disconnected" is now a lie.
    if (linked) glassesLinked = true
    return linked
  }

  private fun maybeAutoRequestGlassesCamera() {
    if (cameraPermRequested) return
    if (glassesReg != RegistrationState.REGISTERED || !glassesLinked) return
    // Don't bounce into Meta AI on every cold start — once was enough; button remains for retries.
    if (Prefs.glassesCameraAutoPrompted(this)) return
    cameraPermRequested = true
    lifecycleScope.launch {
      val granted =
          Wearables.checkPermissionStatus(Permission.CAMERA).getOrNull() == PermissionStatus.Granted
      glassesCameraGranted = granted
      if (granted) {
        Prefs.setGlassesCameraAutoPrompted(this@VoiceConciergeActivity, true)
        return@launch
      }
      if (!glassesLinked) {
        cameraPermRequested = false // allow retry when link comes back
        return@launch
      }
      Prefs.setGlassesCameraAutoPrompted(this@VoiceConciergeActivity, true)
      clearGlassesError()
      datCameraPermission.launch(Permission.CAMERA)
    }
  }

  private fun refreshGlassesCameraStatus() {
    lifecycleScope.launch {
      glassesCameraGranted =
          Wearables.checkPermissionStatus(Permission.CAMERA).getOrNull() == PermissionStatus.Granted
      // If DAT already says granted, never auto-prompt again.
      if (glassesCameraGranted) {
        Prefs.setGlassesCameraAutoPrompted(this@VoiceConciergeActivity, true)
      }
    }
  }

  private fun refreshAuthState() {
    signedIn = SaiAuth.isSignedIn()
    userEmail = SaiAuth.email()
    if (signedIn) maybeAutoRequestLocation()
  }

  /**
   * Show the location sheet once, at sign-in, and never again.
   *
   * Sign-in is the right beat for it: the user is already at the phone answering prompts, and it is
   * the one moment in this app that is deliberately not hands-free. The alternatives are worse — the
   * Start gate runs on every call, and asking at first need puts a system dialog in front of someone
   * whose eyes and hands are busy, which is the whole situation the glasses exist for.
   *
   * The prompt is spent whatever the answer, so a decline is respected rather than re-litigated on
   * the next launch. Already-granted short-circuits so a reinstall-then-grant doesn't re-ask.
   *
   * What is shown first is our own rationale, not the system sheet. Android does not let an app put a
   * word of its own into the platform permission dialog — that text is fixed — so the only place the
   * reason can be given is a screen of ours immediately before it. Without one, the first thing a new
   * user sees after signing in is a bare "Allow Sai-Fi to access this device's location?" with no
   * stated purpose, which is the version most people decline.
   */
  private fun maybeAutoRequestLocation() {
    if (Prefs.locationAutoPrompted(this)) return
    Prefs.setLocationAutoPrompted(this, true)
    if (PhoneLocation.hasPermission(this)) return
    locationRationaleOpen = true
  }

  /**
   * Answer to the rationale above. [proceed] hands off to the system sheet; declining stops here.
   *
   * Either way the one-shot is already spent (set when the rationale opened), matching the previous
   * behaviour of the bare system prompt — this adds an explanation, not a second ask.
   */
  override fun onLocationRationale(proceed: Boolean) {
    locationRationaleOpen = false
    if (!proceed) return
    locationPermission.launch(
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
    )
  }

  /** Show the Credential Manager Google sign-in sheet (needs the Firebase config in local.properties). */
  override fun signIn() {
    if (!SaiAuth.isConfigured) {
      showAuthError(
          "Firebase not configured — set firebase_app_id / firebase_api_key / " +
              "firebase_project_id / web_client_id in local.properties, then rebuild.")
      return
    }
    clearAuthError()
    lifecycleScope.launch {
      try {
        SaiAuth.signInWithGoogle(this@VoiceConciergeActivity)
        refreshAuthState()
        if (machines.isEmpty()) loadMachines()
      } catch (e: Exception) {
        showAuthError("Sign-in cancelled or failed: ${e.message}")
      }
    }
  }

  override fun signOut() {
    lifecycleScope.launch {
      SaiAuth.signOut(this@VoiceConciergeActivity)
      refreshAuthState()
      machines.clear()
      selectedMachine = null
      machinesInfo = ""
      machinesFetchOk = false
      clearMachinesError()
      clearAuthError()
    }
  }

  private fun hasMic() =
      ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
          PackageManager.PERMISSION_GRANTED

  private fun hasBt() =
      ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
          PackageManager.PERMISSION_GRANTED

  private fun needsNotifPermission() =
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
          ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
              PackageManager.PERMISSION_GRANTED

  /** Load the user's machines for the picker (like `sai machine`). */
  override fun loadMachines() {
    machinesInfo = "Loading…"
    clearMachinesError()
    machinesFetchOk = false
    lifecycleScope.launch {
      val token = SaiAuth.idToken()
      if (token == null) {
        machines.clear()
        selectedMachine = null
        machinesInfo = "Sign in to load machines"
        return@launch
      }
      try {
        val list = ConciergeClient.listMachines(BuildConfig.CONCIERGE_URL, token)
        machines.clear()
        machines.addAll(list)
        if (selectedMachine == null || machines.none { it.machineId == selectedMachine?.machineId }) {
          // Default to the machine the user last selected (persisted); else the first one.
          val saved = Prefs.machineId(this@VoiceConciergeActivity)
          selectedMachine = list.firstOrNull { it.machineId == saved } ?: list.firstOrNull()
        }
        machinesFetchOk = true
        machinesInfo =
            when {
              list.isEmpty() -> "No machines found"
              else -> "" // count lives in the dropdown label
            }
      } catch (e: Exception) {
        machines.clear()
        selectedMachine = null
        machinesFetchOk = false
        val (summary, detail) = machinesLoadFailure(e)
        showMachinesError(detail, "Load failed — $summary")
      }
    }
  }

  /** Gate Start on sign-in + mic + notification + BT (for SCO detect), then hand off to the service. */
  override fun onStartClicked() {
    clearGlassesError()
    if (!SaiAuth.isSignedIn()) {
      showAuthError("Sign in first")
      return
    }
    if (!machinesFetchOk || selectedMachine == null) {
      val msg = if (!machinesFetchOk) "Reload machines before starting" else "Select a machine"
      showMachinesError(msg, msg)
      return
    }
    when {
      !hasMic() -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
      needsNotifPermission() -> notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
      !hasBt() && !btDenied -> btPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
      else -> startServiceNow()
    }
  }

  private fun startServiceNow() {
    val m = selectedMachine ?: return
    Prefs.setMachineId(this, m.machineId) // the started machine becomes the default next launch
    val useGlasses = preferredGlassesRoute()
    refreshRouteStatus()
    clearGlassesError()
    lifecycleScope.launch {
      // Fresh ID token per call start; the service re-mints its own on a Live-session reconnect.
      val token = SaiAuth.idToken()
      if (token == null) {
        showAuthError("Sign in first")
        return@launch
      }
      CallController.start(
          this@VoiceConciergeActivity,
          CallController.StartParams(
              baseUrl = BuildConfig.CONCIERGE_URL,
              token = token,
              machineId = m.machineId,
              machineLabel = m.label,
              machines = machines.toList(),
              useGlasses = useGlasses,
              askFirstThresholdMs =
                  (askFirstThresholdSec.toLongOrNull() ?: 15L).coerceAtLeast(0L) * 1000L,
          ))
    }
  }

  /** Glasses SCO when BT is granted and a headset is present; otherwise phone. */
  private fun preferredGlassesRoute(): Boolean = hasBt() && AudioIo.glassesScoAvailable(this)

  private fun refreshRouteStatus() {
    if (hasBt()) btDenied = false // user may have granted BT in Settings after denying once
    CallController.update {
      it.copy(
          routeStatus =
              when {
                btDenied -> "phone (Bluetooth denied)"
                !hasBt() -> "phone (Bluetooth needed for glasses)"
                preferredGlassesRoute() -> "glasses"
                else -> "phone"
              })
    }
  }

}
