/*
 * sai-fi — voice concierge.
 */

// The call's notification: channel, the ongoing card, and the dismissible "why it ended" one.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * What the notification says, given the call's state.
 *
 * Split from the Notification objects on purpose: this part is a pure function of three booleans and a
 * string, so it is readable — and assertable — without an Android framework. The bug it guards against
 * is a wording bug, and wording bugs are the only kind this surface has ever had. "Sai is listening"
 * reads as "your mic is hot", which is the opposite of what a MUTED user wants to see, so the title has
 * to state which of the two states it is in.
 *
 * Pause dominates mute in every line: while paused there are no mic frames, so no keepalives, so the
 * server's idle guard treats a long pause exactly like a walked-away call and ends it. Saying so is
 * better than surprising someone on resume.
 */
object CallNotificationText {
  fun title(muted: Boolean, paused: Boolean): String =
      when {
        paused -> "Sai is paused"
        muted -> "Sai is muted (still listening)"
        else -> "Sai is listening"
      }

  fun body(muted: Boolean, paused: Boolean, machineLabel: String): String =
      when {
        paused -> "Paused — Sai can't hear you (a long pause ends the call)"
        muted -> "Muted — still listening, won't speak"
        else -> "Listening — $machineLabel"
      }

  /** Label of the secondary action. While paused, "Mute" would do nothing worth offering. */
  fun secondaryAction(muted: Boolean, paused: Boolean): String =
      when {
        paused -> "Resume"
        muted -> "Unmute"
        else -> "Mute"
      }
}

/**
 * Builds the notifications. Everything Android-shaped lives here; the wording lives in
 * [CallNotificationText].
 */
class CallNotifications(private val context: Context) {

  fun ensureChannel() {
    val mgr = context.getSystemService(NotificationManager::class.java)
    if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
      mgr.createNotificationChannel(
          NotificationChannel(CHANNEL_ID, "Sai voice call", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Keeps the Sai voice call running while the screen is off."
          },
      )
    }
  }

  /** The ongoing call card, with a Stop action and a mute-or-resume action. */
  fun ongoing(text: String, muted: Boolean, paused: Boolean): Notification {
    val secondary = if (paused) CallService.ACTION_TOGGLE_PAUSE else CallService.ACTION_TOGGLE_MUTE
    return NotificationCompat.Builder(context, CHANNEL_ID)
        .setContentTitle(CallNotificationText.title(muted, paused))
        .setContentText(text)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setOngoing(true)
        .setContentIntent(openActivity())
        .addAction(0, CallNotificationText.secondaryAction(muted, paused), service(2, secondary))
        .addAction(0, "Stop", service(1, CallService.ACTION_STOP))
        .build()
  }

  /**
   * Why the call ended (out of credits / voice off / access denied).
   *
   * A separate id from the ongoing card so it survives the service stopping — for a screen-free user
   * with no Live audio left to speak the reason, this is the only durable surface there is.
   */
  fun endedReason(reason: String): Notification =
      NotificationCompat.Builder(context, CHANNEL_ID)
          .setContentTitle("Sai voice call ended")
          .setContentText(reason)
          .setSmallIcon(android.R.drawable.ic_dialog_alert)
          .setAutoCancel(true)
          .setContentIntent(openActivity())
          .build()

  fun show(id: Int, notification: Notification) {
    context.getSystemService(NotificationManager::class.java).notify(id, notification)
  }

  private fun openActivity(): PendingIntent =
      PendingIntent.getActivity(
          context,
          2,
          Intent(context, VoiceConciergeActivity::class.java)
              .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
          PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )

  private fun service(requestCode: Int, action: String): PendingIntent =
      PendingIntent.getService(
          context,
          requestCode,
          Intent(context, CallService::class.java).setAction(action),
          PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )

  companion object {
    const val CHANNEL_ID = "sai_voice_call"
  }
}
