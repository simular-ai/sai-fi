// Headers every Sai API request carries, in one place.
//
// This exists because the alternative failed. The auth header and the optional version tag were set
// inline at each call site, and `VoiceChannelClient`'s two — the turn stream and the operations POST
// — were written without the version tag. The picker (`ConciergeClient`) sent it and the turn did
// not, so `GET /v1/agents/machines` could be served by one server revision while
// `POST /v1/agents/message` fell through to another. A build could therefore look tested end to end
// while none of its server changes had ever run a turn, and nothing anywhere reported a problem:
// both requests succeed, they are just answered by different builds.
//
// A forgotten header cannot be caught by a test that only exercises one call site, so the fix is to
// leave no call site with the choice.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import java.net.HttpURLConnection

/**
 * Authorize the request and, when set, pin it to one server revision.
 *
 * `x-sai-version` is only sent when `SAI_VERSION_TAG` is set — an empty header is matched by no
 * route rather than falling through to the host's default.
 */
fun HttpURLConnection.applyCloudApiHeaders(bearerToken: String) {
    setRequestProperty("Authorization", "Bearer $bearerToken")
    if (BuildConfig.SAI_VERSION_TAG.isNotBlank()) {
        setRequestProperty("x-sai-version", BuildConfig.SAI_VERSION_TAG)
    }
}
