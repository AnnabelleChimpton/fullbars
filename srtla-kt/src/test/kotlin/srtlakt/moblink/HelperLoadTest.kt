// SPDX-License-Identifier: Apache-2.0
package srtlakt.moblink

import srtlakt.moblink.Moblink.Thermal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HelperLoadTest {
    @Test
    fun `a hot helper is demoted at once and eased back only after two clear reports`() {
        val l = HelperLoad()
        assertFalse(l.update(Thermal.WHITE, 80))
        assertTrue(l.update(Thermal.RED, 80))
        assertTrue(l.demoted)
        assertEquals(0.5, l.capScale)
        assertFalse(l.update(Thermal.WHITE, 80), "one clear report isn't enough")
        assertTrue(l.demoted)
        assertTrue(l.update(Thermal.WHITE, 80))
        assertFalse(l.demoted)
        assertEquals(1.0, l.capScale)
    }

    @Test
    fun `a warm helper carries a smaller share without being demoted`() {
        val l = HelperLoad()
        assertTrue(l.update(Thermal.YELLOW, 80))
        assertFalse(l.demoted)
        assertEquals(0.5, l.capScale)
        l.update(Thermal.WHITE, 80)
        l.update(Thermal.YELLOW, 80) // flapping keeps it reduced
        l.update(Thermal.WHITE, 80)
        assertEquals(0.5, l.capScale)
        l.update(Thermal.WHITE, 80)
        assertEquals(1.0, l.capScale)
    }

    @Test
    fun `a low battery demotes until it has recovered past the threshold`() {
        val l = HelperLoad()
        l.update(null, 14)
        assertTrue(l.demoted)
        l.update(null, 16); l.update(null, 17); l.update(null, 18)
        assertTrue(l.demoted, "charging back past 15 % isn't enough")
        l.update(null, 20); l.update(null, 21)
        assertFalse(l.demoted)
        assertEquals(1.0, l.capScale)
    }
}
