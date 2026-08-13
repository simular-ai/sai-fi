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
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.ui.theme.SaiTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long [VoiceConciergeActivity.glassesLinkedNow] waits for DAT to report a *connected* device
 * before giving up and refusing the grant.
 *
 * A feedback budget, not a "wait for the user to put the glasses on" budget: the press already
 * happened, and something has to appear on screen promptly either way.
 */
private const val LINK_PROBE_TIMEOUT_MS = 1_500L

// The control surface AND its state. `ConciergeScreen` renders this directly — there was an interface
// (`ConciergeUi`) between them whose only job was to narrow what the composables could touch, but with
// one implementer and one caller it was ceremony. The `val` vs `var` split below is the contract it
// used to state: `var` is what the UI writes back, everything else is the Activity's to own. The
// Compose reads still work because these are `mutableStateOf` snapshot fields read through getters.
class VoiceConciergeActivity : ComponentActivity() {
  // Machine picker (like `sai machine`): fetched from GET /v1/agents/machines, selected in a dropdown.
  val machines = mutableStateListOf<Machine>()
  var selectedMachine by mutableStateOf<Machine?>(null)
  var machinesInfo by mutableStateOf("") // short non-error status ("Loading…", "No machines found")
  var machinesFetchOk by mutableStateOf(false) // last load succeeded (dropdown enabled)

  /**
   * Voice-UX settings, owned by the Settings tab and threaded into StartParams at call start.
   *
   * A `String` rather than an `Int` because it is what the text field holds, and a half-deleted field
   * has to be allowed to not be a number. [Prefs.askFirstSec] stores the parsed value;
   * [onAskFirstSecChanged] is the write path that keeps the two in step.
   */
  var askFirstThresholdSec by mutableStateOf(Prefs.DEFAULT_ASK_FIRST_SEC.toString())
    private set

  /**
   * Developer mode: reveals the Logs tab and the in-call composer. Persisted, off by default.
   *
   * Seeded in [onCreate] and written through [onDevModeChanged]. Not a `BuildConfig.DEBUG` read — see
   * [Prefs.devMode] for why the build type turned out to be the wrong question.
   */
  var devMode by mutableStateOf(false)
    private set

  /**
   * Settings' write path for [askFirstThresholdSec]: keeps the field and [Prefs] in step.
   *
   * Named `on…Changed` rather than `set…` because a `setDevMode`/`setAskFirstThresholdSec` would
   * collide on the JVM with the property's own generated setter.
   */
  fun onAskFirstSecChanged(typed: String) {
    askFirstThresholdSec = typed.filter(Char::isDigit).take(4)
    // Only persist a value that parses. Blanking the field to retype it must not write a 0 that
    // becomes "ask immediately" if the app dies before the user finishes.
    askFirstThresholdSec.toIntOrNull()?.let { Prefs.setAskFirstSec(this, it) }
  }

  /** Settings' write path for [devMode]. */
  fun onDevModeChanged(value: Boolean) {
    devMode = value
    Prefs.setDevMode(this, value)
  }

  // DAT glasses registration (one-time) — enables the temple button to start/stop the call.
  var glassesReg by mutableStateOf<RegistrationState?>(null)
  /**
   * DAT reports a device with LinkState.CONNECTED (powered on / in range), or `null` while we don't
   * know yet.
   *
   * Three states, not two, and the difference matters: a `false` standing in for "hasn't answered
   * yet" is what once disabled the camera-grant button on exactly the run that needed it. Gate
   * destructive/blocking UI on `== false` — an affirmative "no device" — and leave `null` permissive;
   * say nothing about the glasses at all while `null`. The DAT flows behind this cannot produce the
   * unknown state on their own — they are StateFlows seeded with "nothing connected" — so [GlassesLink]
   * is what turns that seed into an honest `null`.
   */
  var glassesLinked by mutableStateOf<Boolean?>(null)
  // Glasses-camera DAT permission (device-level, via Meta AI). Shown as an action only when missing.
  var glassesCameraGranted by mutableStateOf(false)
  // Our own "why we want location" dialog, shown once at sign-in, immediately before the system sheet.
  var locationRationaleOpen by mutableStateOf(false)
  // Request the glasses camera permission automatically once per process, right after registration.
  private var cameraPermRequested = false

  // DAT device discovery is wired up exactly once, inside Wearables.initialize() (SaiFiApp), against
  // whatever ACDC link exists at that instant. A *first* registration happens after that, and the SDK
  // does not restart device monitoring when it completes — so Wearables.devices stays empty for the
  // rest of the process and the glasses we just registered never appear. That is what left the camera
  // grant greyed (glassesLinked settled to false) with no auto-prompt until a manual force-quit, on
  // exactly the fresh-install run that needs it. When *we* started registration in this process,
  // rebuild DAT once it lands (reinitializeDatForFreshRegistration) so monitoring re-runs against the
  // now-registered link — the in-process equivalent of the restart that used to be required.
  private var startedRegistrationThisProcess = false
  private var datReinitializedThisProcess = false
  // The DAT-bound collectors (registration state + live link). Held so the re-init above can drop the
  // subscriptions to the outgoing Wearables instance and re-establish them against the new one.
  private var datObservers: Job? = null

  // Auth state (Google Sign-In → Firebase). Drives the sign-in UI + gates loading machines / starting.
  var signedIn by mutableStateOf(false)
  var userEmail by mutableStateOf<String?>(null)

  // Section errors: full text in a reopenable scrollable dialog (not truncated inline red).
  var authError by mutableStateOf<String?>(null)
  var authErrorOpen by mutableStateOf(false)
  var machinesError by mutableStateOf<String?>(null)
  var machinesErrorOpen by mutableStateOf(false)
  var glassesError by mutableStateOf<String?>(null)
  var glassesErrorOpen by mutableStateOf(false)

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
        if (granted) beginRegistration()
        else showGlassesError("Bluetooth needed to register glasses")
      }

  // DAT glasses camera permission (device-level, via the Meta AI app) — needed for the captureImage
  // voice tool. Auto-requested at most once (see maybeAutoRequestGlassesCamera); after that use the button.
  private val datCameraPermission =
      registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        val status = result.getOrNull()
        CallController.appendLog("glasses camera: ${status ?: "denied"}")
        // The flow's own result is authoritative — latch a grant and persist it. Deliberately do NOT
        // re-query checkPermissionStatus here: it lags the grant by seconds and would answer stale
        // `Denied`, undoing the grant we just watched succeed. A `Denied` result leaves the flag as it
        // was (a real grant is never revoked by a failed attempt).
        if (status == PermissionStatus.Granted) markGlassesCameraGranted()
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Seed the grant from what we last confirmed, so a grant already obtained stays shown while DAT's
    // laggy checkPermissionStatus catches up (see Prefs.glassesCameraGranted). refresh only upgrades.
    glassesCameraGranted = Prefs.glassesCameraGranted(this)
    devMode = Prefs.devMode(this)
    askFirstThresholdSec = Prefs.askFirstSec(this).toString()
    // Before setContent, so the first composition already knows whether to draw the sign-in gate or
    // the shell. Firebase restores its persisted session during FirebaseApp.initializeApp (SaiFiApp),
    // and SaiAuth.isSignedIn() is a synchronous currentUser read, so a returning user never sees the
    // gate flash past.
    refreshAuthState()
    refreshRouteStatus()
    refreshGlassesCameraStatus()
    enableEdgeToEdge()
    // SaiTheme carries the desktop app's palette + type ramp, light by default and following the
    // system. This used to be a bare `darkColorScheme()` — the stock Material baseline, i.e. Google's
    // purple, with nothing of Sai's brand in it.
    setContent { SaiTheme { ConciergeScreen(this) } }
    startDatObservers()
    // A call ends leaving the *service's* mid-call route string in place — "on glasses: SCO", or the
    // alarming "glasses lost — …reconnect glasses" — and nothing recomputed it: the DAT link hasn't
    // changed, and onResume never fires because this screen was already resumed. Recompute on the edge
    // back to idle, which is exactly when this Activity takes the line back.
    lifecycleScope.launch {
      CallController.state.map { it.active }.distinctUntilChanged().collect { active ->
        if (!active) refreshRouteStatus()
      }
    }
  }

  /**
   * (Re)subscribe the DAT-bound collectors: registration state, and the live glasses link.
   *
   * Kept in one relaunchable place because [reinitializeDatForFreshRegistration] tears the SDK down and
   * back up after a first registration, which invalidates the flows these collect — they belong to the
   * outgoing Wearables instance and simply go silent. Cancelling this job and calling it again rebinds
   * both to the new instance. (The call-state collector in [onCreate] is deliberately not here: it is
   * independent of DAT.)
   *
   * Registration drives the one-time camera auto-request the first time we see REGISTERED *and* a
   * device is linked — otherwise Meta AI opens a permission sheet that can't complete with the glasses
   * offline. The live-link half flattens DAT into `null | Boolean` and hands it to [GlassesLink.observe]:
   * both `Wearables.devices` and the per-device metadata are StateFlows, so the first reading is a
   * seeded empty set (see GlassesLink's docs). The observe call is the whole defence — do not inline a
   * collector that maps that first empty set to `false`.
   */
  private fun startDatObservers() {
    datObservers?.cancel()
    datObservers =
        lifecycleScope.launch {
          launch {
            Wearables.registrationState.collect { state ->
              glassesReg = state
              refreshGlassesCameraStatus()
              maybeAutoRequestGlassesCamera()
              maybeReinitializeAfterRegistration(state)
            }
          }
          launch {
            @OptIn(ExperimentalCoroutinesApi::class)
            val readable =
                Wearables.devices.transformLatest { ids ->
                  val flows = ids.mapNotNull { Wearables.devicesMetadata[it] }
                  if (flows.isEmpty()) {
                    emit(null)
                  } else {
                    emitAll(
                        combine(flows) { devices ->
                          when {
                            devices.any { it.linkState == LinkState.CONNECTED } -> true
                            // A device is present but still negotiating the link (CONNECTING) — that
                            // is "checking", not an affirmative "no glasses". Report it as unknown
                            // (`null`) so the grant button stays live and the label doesn't flip to
                            // "disconnected" while the radio links, which is most of the window right
                            // after a first registration. All-DISCONNECTED is a real no.
                            devices.any { it.linkState == LinkState.CONNECTING } -> null
                            else -> false
                          }
                        })
                  }
                }
            GlassesLink().observe(readable) { linked ->
              publishGlassesLink(linked)
              if (linked == true) maybeAutoRequestGlassesCamera()
            }
          }
        }
  }

  /**
   * Rebuild DAT once, after a registration *we* started in this process reaches REGISTERED.
   *
   * Gated on [startedRegistrationThisProcess] rather than the REGISTERED state alone: a launch that is
   * already registered also emits REGISTERED, and re-initializing on every cold start would be wasteful
   * and pointless (that path already discovers the device). Never mid-call — [Wearables.reset] drops
   * the SDK singleton — though right after registration there is no call to disturb.
   */
  private fun maybeReinitializeAfterRegistration(state: RegistrationState) {
    if (state != RegistrationState.REGISTERED) return
    if (!startedRegistrationThisProcess || datReinitializedThisProcess) return
    if (CallController.state.value.active) return
    datReinitializedThisProcess = true
    reinitializeDatForFreshRegistration()
  }

  /**
   * Tear the SDK down and back up so device monitoring re-runs against the now-registered ACDC link,
   * then re-arm the link detection and resubscribe. This is what makes the just-registered glasses
   * appear without a force-quit; see the field docs on [startedRegistrationThisProcess].
   */
  private fun reinitializeDatForFreshRegistration() {
    lifecycleScope.launch {
      // Drop the collectors bound to the outgoing instance before it goes away.
      datObservers?.cancelAndJoin()
      runCatching {
            Wearables.reset()
            Wearables.initialize(this@VoiceConciergeActivity)
          }
          .onFailure { CallController.appendLog("glasses re-init failed: ${it.message}") }
      // New instance, new flows: re-arm the one-shot auto-request and drop the link back to "checking",
      // then resubscribe so the now-visible device can drive glassesLinked -> true (and the grant).
      cameraPermRequested = false
      publishGlassesLink(null)
      startDatObservers()
    }
  }

  /**
   * Keeps the idle audio-route line in step with the hardware.
   *
   * The line is derived from the phone's list of communication devices, and the glasses' SCO device
   * appears *after* DAT reports the link — often a second or two after. So refreshing on the DAT edge
   * alone latched "phone" for the whole pre-call window: no further link change was coming, and
   * onResume never fires when the screen was already open. The header then read "phone" for a call that
   * went on to start on glasses, since the route is decided again at Start.
   *
   * Registered only while resumed — mid-call the service owns this string, and [refreshIdleRoute]
   * refuses to touch it.
   */
  private val audioDevices =
      object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refreshIdleRoute()

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) =
            refreshIdleRoute()
      }

  /** Recompute the route line, unless a call owns it (the service reports the real device then). */
  private fun refreshIdleRoute() {
    if (!CallController.state.value.active) refreshRouteStatus()
  }

  override fun onResume() {
    super.onResume()
    // The sign-in state is now a gate, not a card, so a stale `true` is no longer a cosmetic problem:
    // it strands the user on Home while every request 401s. There is no AuthStateListener anywhere, so
    // a refresh token revoked out of band (password reset, account disabled, sign-out elsewhere) is
    // invisible until something asks. This is the cheap half of the answer — isSignedIn() is a
    // synchronous currentUser read, no I/O — and it catches the common case, where whatever revoked
    // the session happened while this app was backgrounded.
    refreshAuthState()
    refreshIdleRoute()
    getSystemService(AudioManager::class.java)
        ?.registerAudioDeviceCallback(audioDevices, Handler(Looper.getMainLooper()))
    refreshGlassesCameraStatus()
    startWindowMirror()
  }

  override fun onPause() {
    super.onPause()
    runCatching {
      getSystemService(AudioManager::class.java)?.unregisterAudioDeviceCallback(audioDevices)
    }
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
  fun registerGlasses() {
    if (!hasBt()) {
      datBtPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
      return
    }
    beginRegistration()
  }

  /**
   * Start DAT registration, recording that we did so this process — the one-time re-init that lets the
   * just-registered glasses appear without a force-quit ([maybeReinitializeAfterRegistration]) is gated
   * on this, so it never fires on a launch that was already registered.
   */
  private fun beginRegistration() {
    startedRegistrationThisProcess = true
    Wearables.startRegistration(this)
  }

  /**
   * DAT camera permission via Meta AI. Requires a linked (powered-on) device — otherwise Meta AI still
   * opens and the user gets stuck in a flow that can't succeed.
   *
   * The link is read **live** here rather than trusted from [glassesLinked]. That field is fed by the
   * devices collector in [onCreate], which on a first install has usually not heard from DAT by the time
   * the user finishes registering — so a stale `false` was refusing the grant on exactly the run that
   * needs it, and the button that would have retried was disabled by the same flag. Checking the SDK
   * directly settles it; the guard itself is worth keeping, so a genuinely powered-off pair still gets
   * an explanation instead of a Meta AI flow that cannot complete.
   */
  fun requestGlassesCamera() {
    clearGlassesError()
    lifecycleScope.launch {
      if (glassesLinked != true && !glassesLinkedNow()) {
        showGlassesError("Turn the glasses on and wait until they're linked, then grant camera")
        return@launch
      }
      datCameraPermission.launch(Permission.CAMERA)
    }
  }

  /**
   * One bounded **wait** for "is a device connected right now", independent of the collected flag.
   *
   * It has to wait rather than sample, and the difference is the whole value of this function. Every
   * DAT flow involved is a `StateFlow` with a value already in it — an empty device set, a device whose
   * link state is still CONNECTING — so a plain `first()` returns that seed instantly, the timeout is
   * never spent, and the "live re-probe" this exists to be answered "not linked" before DAT had a
   * chance to say otherwise. The user got "turn the glasses on" about glasses already on their face.
   *
   * Bounded because the wait may never be satisfied: nothing is attached, and no emission is coming.
   */
  private suspend fun glassesLinkedNow(): Boolean {
    val linked =
        withTimeoutOrNull(LINK_PROBE_TIMEOUT_MS) {
          val ids = Wearables.devices.first { it.isNotEmpty() }
          val flows = ids.mapNotNull { Wearables.devicesMetadata[it] }
          if (flows.isEmpty()) return@withTimeoutOrNull false
          combine(flows) { devices -> devices.any { it.linkState == LinkState.CONNECTED } }
              .first { connected -> connected }
        } ?: false
    // Keep the rest of the screen honest: if the probe found a link the collector had not reported
    // yet, "Link: disconnected" is now a lie.
    if (linked) publishGlassesLink(true)
    return linked
  }

  /**
   * Record the live DAT link — `null` for "DAT hasn't said" — and keep the audio-route line in step.
   *
   * The route line is derived from whether a glasses SCO device is present, which changes around the
   * moments the link does, so this is one of its triggers. Not a sufficient one: SCO lags the DAT link,
   * which is why [audioDevices] watches the hardware directly.
   */
  private fun publishGlassesLink(linked: Boolean?) {
    val changed = glassesLinked != linked
    glassesLinked = linked
    // During a call the service owns this line (it reports the real selected device, incl. mid-call
    // SCO loss); recomputing from the activity would stomp a more accurate string with a guess.
    if (changed) refreshIdleRoute()
  }

  private fun maybeAutoRequestGlassesCamera() {
    if (cameraPermRequested) return
    if (glassesReg != RegistrationState.REGISTERED || glassesLinked != true) return
    // Don't bounce into Meta AI on every cold start — once was enough; button remains for retries.
    if (Prefs.glassesCameraAutoPrompted(this)) return
    cameraPermRequested = true
    lifecycleScope.launch {
      val status = Wearables.checkPermissionStatus(Permission.CAMERA).getOrNull()
      if (status == PermissionStatus.Granted) {
        markGlassesCameraGranted()
        return@launch
      }
      if (glassesLinked != true) {
        cameraPermRequested = false // allow retry when link comes back
        return@launch
      }
      Prefs.setGlassesCameraAutoPrompted(this@VoiceConciergeActivity, true)
      clearGlassesError()
      datCameraPermission.launch(Permission.CAMERA)
    }
  }

  /**
   * Latch the glasses-camera grant on and persist it. Called from the authoritative grant result and
   * from a `checkPermissionStatus` that comes back `Granted`. There is no matching "un-grant": DAT's
   * status read is too laggy to trust for a downgrade (it reports stale `Denied` for seconds after a
   * real grant), so the flag only ever moves to `true` in a session — a genuine revocation is picked up
   * on the next fresh install. Also arms the auto-prompt "already done" flag.
   */
  private fun markGlassesCameraGranted() {
    glassesCameraGranted = true
    Prefs.setGlassesCameraGranted(this, true)
    Prefs.setGlassesCameraAutoPrompted(this, true)
  }

  /** Upgrade-only: promote to granted if DAT confirms it, but never downgrade on a stale/laggy read. */
  private fun refreshGlassesCameraStatus() {
    lifecycleScope.launch {
      val status = Wearables.checkPermissionStatus(Permission.CAMERA).getOrNull()
      if (status == PermissionStatus.Granted) markGlassesCameraGranted()
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
  fun onLocationRationale(proceed: Boolean) {
    locationRationaleOpen = false
    if (!proceed) return
    locationPermission.launch(
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
    )
  }

  /** Show the Credential Manager Google sign-in sheet (needs the Firebase config in local.properties). */
  fun signIn() {
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

  fun signOut() {
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
  fun loadMachines() {
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
  fun onStartClicked() {
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
        // NOT "sign in first", which is what this used to say: `currentUser` is non-null or we would
        // not be past the gate, so the session exists and the *refresh* is what failed — offline, or
        // a revoked refresh token. Telling someone who is demonstrably signed in to sign in sends
        // them looking for a button that isn't there.
        showAuthError(
            "Couldn't refresh your sign-in token, so the call can't start. Check the connection; " +
                "if it keeps failing, sign out in Settings and sign in again.")
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
