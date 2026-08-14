// Headers every cloud-api request carries, in one place.
//
// This exists because the alternative failed. The auth header and the staging version tag were set
// inline at each call site, and `VoiceChannelClient`'s two — the turn stream and the operations POST
// — were written without the version tag. The picker (`ConciergeClient`) sent it and the turn did
// not, so `GET /v1/agents/machines` was served by the PR's staging revision while
// `POST /v1/agents/message` fell through to the shared staging default. A PR could therefore look
// tested end to end while none of its server changes had ever run a turn, and nothing anywhere
// reported a problem: both requests succeed, they are just answered by different builds.
//
// A forgotten header cannot be caught by a test that only exercises one call site, so the fix is to
// leave no call site with the choice.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import java.net.HttpURLConnection

/**
 * Authorize the request and pin it to a cloud-api build.
 *
 * `x-sai-version` routes to a specific PR's staging revision through the shared staging Gateway;
 * blank means the shared staging default, which is why it is only sent when set — an empty header
 * would be matched by no HTTPRoute rather than falling through to the default.
 */
fun HttpURLConnection.applyCloudApiHeaders(bearerToken: String) {
    setRequestProperty("Authorization", "Bearer $bearerToken")
    if (BuildConfig.SAI_VERSION_TAG.isNotBlank()) {
        setRequestProperty("x-sai-version", BuildConfig.SAI_VERSION_TAG)
    }
}
