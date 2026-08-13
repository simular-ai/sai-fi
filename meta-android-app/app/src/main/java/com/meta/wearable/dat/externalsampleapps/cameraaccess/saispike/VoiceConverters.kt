/* sai-fi — voice concierge. */

// The three conversions between the FSM's typed world and the JSON one around it.
//
// ActivityLog, ConciergeProtocol and AgentEventRouter all read raw JSONObject, and they stay that
// way ON PURPOSE: they are pinned against the server by the parity fixtures, so changing their input
// shape to suit the FSM would break the one check that keeps the two renderings honest. Converting
// at the boundary is the cheaper side of that trade.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.AgentEvent
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.TaskAttachment
import org.json.JSONArray
import org.json.JSONObject

/** A typed agent event back to the wire shape the log and the nudge router expect. */
fun agentEventToJson(e: AgentEvent): JSONObject =
    when (e) {
      is AgentEvent.Text -> JSONObject().put("type", "text").put("text", e.text)
      is AgentEvent.Progress ->
          JSONObject().put("type", "progress").put("text", e.text).apply {
            e.tool?.let { put("tool", it) }
            if (e.failed) put("failed", true)
          }
      is AgentEvent.Status -> JSONObject().put("type", "status").put("status", e.status.wire)
      is AgentEvent.Complete ->
          JSONObject().put("type", "complete").apply { e.summary?.let { put("summary", it) } }
      is AgentEvent.Error -> JSONObject().put("type", "error").put("text", e.text)
      is AgentEvent.Notice -> JSONObject().put("type", "notice").put("text", e.text)
      is AgentEvent.ApprovalRequest ->
          JSONObject()
              .put("type", "approval-request")
              .put("id", e.id)
              .put("title", e.title)
              .put("description", e.description)
              .put("approvalType", e.approvalType)
              .put("isLinkOnly", e.isLinkOnly)
              .put("allowAlways", e.allowAlways)
              .apply {
                e.options?.let { opts ->
                  put(
                      "options",
                      JSONArray().apply {
                        opts.forEach { put(JSONObject().put("value", it.value).put("label", it.label)) }
                      })
                }
                e.multiple?.let { put("multiple", it) }
                e.allowOther?.let { put("allowOther", it) }
              }
      is AgentEvent.ApprovalResolved ->
          JSONObject().put("type", "approval-resolved").put("id", e.id).put("status", e.status)
      is AgentEvent.SessionState ->
          JSONObject().put("type", "session-state").apply {
            e.running?.let { put("running", it) }
            e.blockedOn?.let { put("blockedOn", it) }
            put("queued", JSONArray().apply { e.queued.forEach { put(it) } })
          }
    }

/**
 * A phone fix as the FSM's location type.
 *
 * Carries the Place's OWN `capturedAt` rather than stamping now: a queued task freezes its location
 * line at enqueue and may run much later, so the instant has to be when the fix was actually taken,
 * not when it happened to be forwarded.
 */
fun Place.toTaskLocation(): TaskLocation =
    TaskLocation(
        lat = lat,
        lon = lon,
        accuracyM = accuracyM?.toDouble(),
        approximate = approximate,
        label = label,
        capturedAt = capturedAt,
    )

/** An uploaded attachment as the FSM's task attachment. */
fun JSONObject.toTaskAttachment(): TaskAttachment =
    TaskAttachment(
        path = optString("path"),
        name = optString("name"),
        mime = optString("mime"),
        size = optLong("size"),
        downloadUrl = optString("downloadUrl", "").takeIf { it.isNotEmpty() },
        fileId = optString("fileId", "").takeIf { it.isNotEmpty() },
        width = optInt("width", 0).takeIf { it > 0 },
        height = optInt("height", 0).takeIf { it > 0 },
    )
