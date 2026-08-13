package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The bottom bar's visibility rule.
 *
 * Worth a test for one reason: [coerceTab] is what stops a user who turns developer mode off while
 * standing on the Logs tab from being left on a pane the bar no longer has an item for. That failure
 * is invisible until someone does it in that order, which is exactly the order a demo does it in.
 */
class SaiTabTest {

  @Test
  fun `logs is hidden unless developer mode is on`() {
    assertEquals(listOf(SaiTab.HOME, SaiTab.SETTINGS), tabsFor(devMode = false))
    assertEquals(listOf(SaiTab.HOME, SaiTab.SETTINGS, SaiTab.LOGS), tabsFor(devMode = true))
  }

  @Test
  fun `home is always first, so the bar never opens on a secondary destination`() {
    assertEquals(SaiTab.HOME, tabsFor(devMode = false).first())
    assertEquals(SaiTab.HOME, tabsFor(devMode = true).first())
  }

  @Test
  fun `turning developer mode off while on logs falls back to home`() {
    assertEquals(SaiTab.HOME, coerceTab(SaiTab.LOGS, devMode = false))
  }

  @Test
  fun `a visible tab is left alone`() {
    assertEquals(SaiTab.LOGS, coerceTab(SaiTab.LOGS, devMode = true))
    assertEquals(SaiTab.SETTINGS, coerceTab(SaiTab.SETTINGS, devMode = false))
    assertEquals(SaiTab.SETTINGS, coerceTab(SaiTab.SETTINGS, devMode = true))
    assertEquals(SaiTab.HOME, coerceTab(SaiTab.HOME, devMode = false))
  }

  @Test
  fun `every tab has a distinct label, so the bar has no two identical items`() {
    val labels = SaiTab.entries.map { it.label }
    assertEquals(labels.size, labels.toSet().size)
  }
}
