package com.bizzarosn.heightmark

import com.bizzarosn.heightmark.TestLocations.idleWakeFix
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleWakePolicyTest {

    @Test
    fun `fix within drift and accuracy does not wake`() {
        val anchor = idleWakeFix(driftMeters = 10f)
        val fix = idleWakeFix(horizontalAccuracy = 5f)

        assertFalse(IdleWakePolicy.shouldWake(anchor, fix))
    }

    @Test
    fun `coarse fix with large apparent drift but bad accuracy does not wake`() {
        // A cell-tower fix: drift alone would clear the margin (500 > 30 + 300 = 330),
        // so only the accuracy gate (accuracy > 50) can be rejecting this fix.
        val anchor = idleWakeFix(driftMeters = 500f)
        val fix = idleWakeFix(horizontalAccuracy = 300f)

        assertFalse(IdleWakePolicy.shouldWake(anchor, fix))
    }

    @Test
    fun `same apparent drift with accuracy just under the gate wakes`() {
        // Same drift as above, but accuracy (49) clears the 50 m gate this time,
        // pinning the accuracy boundary from the other side.
        val anchor = idleWakeFix(driftMeters = 500f)
        val fix = idleWakeFix(horizontalAccuracy = 49f)

        assertTrue(IdleWakePolicy.shouldWake(anchor, fix))
    }

    @Test
    fun `fix with no horizontal accuracy does not wake on apparent drift`() {
        val anchor = idleWakeFix(driftMeters = 100f)
        val fix = idleWakeFix(horizontalAccuracy = null)

        assertFalse(IdleWakePolicy.shouldWake(anchor, fix))
    }

    @Test
    fun `accurate fix beyond drift plus accuracy wakes`() {
        val anchor = idleWakeFix(driftMeters = 45f)
        val fix = idleWakeFix(horizontalAccuracy = 5f) // 30 + 5 = 35 < 45

        assertTrue(IdleWakePolicy.shouldWake(anchor, fix))
    }

    @Test
    fun `accurate fix within drift plus accuracy margin does not wake`() {
        val anchor = idleWakeFix(driftMeters = 32f)
        val fix = idleWakeFix(horizontalAccuracy = 5f) // 30 + 5 = 35, not exceeded

        assertFalse(IdleWakePolicy.shouldWake(anchor, fix))
    }

    @Test
    fun `vertical movement with good vertical accuracy wakes`() {
        val anchor = idleWakeFix(driftMeters = 0f, altitude = 100.0)
        val fix = idleWakeFix(altitude = 115.0, verticalAccuracy = 2f) // delta 15 > 10

        assertTrue(IdleWakePolicy.shouldWake(anchor, fix))
    }

    @Test
    fun `vertical movement without vertical accuracy does not wake`() {
        val anchor = idleWakeFix(driftMeters = 0f, altitude = 100.0)
        val fix = idleWakeFix(altitude = 115.0, verticalAccuracy = null)

        assertFalse(IdleWakePolicy.shouldWake(anchor, fix))
    }

    @Test
    fun `vertical movement with poor vertical accuracy does not wake`() {
        val anchor = idleWakeFix(driftMeters = 0f, altitude = 100.0)
        val fix = idleWakeFix(altitude = 115.0, verticalAccuracy = 8f) // worse than 5 m threshold

        assertFalse(IdleWakePolicy.shouldWake(anchor, fix))
    }

    @Test
    fun `missing altitude on anchor skips the vertical test`() {
        val anchor = idleWakeFix(driftMeters = 0f, hasAltitude = false)
        val fix = idleWakeFix(altitude = 200.0, verticalAccuracy = 1f)

        assertFalse(IdleWakePolicy.shouldWake(anchor, fix))
    }

    @Test
    fun `missing altitude on fix skips the vertical test`() {
        val anchor = idleWakeFix(driftMeters = 0f, altitude = 100.0)
        val fix = idleWakeFix(hasAltitude = false, altitude = 200.0, verticalAccuracy = 1f)

        assertFalse(IdleWakePolicy.shouldWake(anchor, fix))
    }

    @Test
    fun `stationary fix with no accuracies at all does not wake`() {
        val anchor = idleWakeFix(driftMeters = 0f)
        val fix = idleWakeFix(horizontalAccuracy = null, verticalAccuracy = null)

        assertFalse(IdleWakePolicy.shouldWake(anchor, fix))
    }
}
