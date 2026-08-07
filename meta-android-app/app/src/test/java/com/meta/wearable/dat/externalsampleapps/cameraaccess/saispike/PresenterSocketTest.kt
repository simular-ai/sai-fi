package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * resolveUrl decides whether the presenter feed is on at all, so its branching is worth pinning: an
 * explicit URL always wins, a LAN/dev cloud-api host derives one (the demo laptop runs both, so
 * pointing the app at it should be enough), and anything else stays OFF — deriving against staging
 * would retry forever against a server that was never there.
 */
class PresenterSocketTest {
  @Test
  fun explicitUrlWins() {
    assertEquals(
        "ws://10.0.0.5:9000",
        PresenterSocket.resolveUrl("ws://10.0.0.5:9000", "https://staging.cloud-api.simular.cloud"),
    )
  }

  @Test
  fun explicitUrlLosesTrailingSlash() {
    assertEquals(
        "ws://10.0.0.5:9000",
        PresenterSocket.resolveUrl("ws://10.0.0.5:9000/", "http://192.168.1.2:8080"),
    )
  }

  @Test
  fun derivesFromLanConciergeHost() {
    assertEquals("ws://192.168.1.50:8899", PresenterSocket.resolveUrl("", "http://192.168.1.50:8080"))
    assertEquals("ws://10.110.23.80:8899", PresenterSocket.resolveUrl("", "http://10.110.23.80:8080"))
    assertEquals("ws://localhost:8899", PresenterSocket.resolveUrl("", "http://localhost:8080"))
    // The emulator's host alias — a dev setup like any other.
    assertEquals("ws://10.0.2.2:8899", PresenterSocket.resolveUrl("", "http://10.0.2.2:8080"))
  }

  @Test
  fun offForStagingOrProduction() {
    assertEquals("", PresenterSocket.resolveUrl("", "https://staging.cloud-api.simular.cloud"))
    assertEquals("", PresenterSocket.resolveUrl("", "https://cloud-api.simular.cloud"))
  }

  @Test
  fun offWhenConciergeUrlIsUnusable() {
    assertEquals("", PresenterSocket.resolveUrl("", ""))
    assertEquals("", PresenterSocket.resolveUrl("", "not a url"))
  }
}
