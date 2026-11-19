package io.github.neoruaa.glasmio.glass.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassFrameCadenceTest {
    private val frame120 = 8_333_333L

    @Test fun firstRenderStartsImmediately() {
        val cadence = GlassFrameCadence()
        assertTrue(cadence.onWindowDraw(presentOnly = false))
        assertEquals(0L, cadence.waitNanos(0L, 30))
    }

    @Test fun rendersAt30HzWhileTheWindowDrawsAt120() {
        val cadence = GlassFrameCadence()
        var renders = 0
        for (i in 0 until 120) {
            val now = i * frame120
            cadence.onWindowDraw(presentOnly = false)
            if (cadence.dirty && cadence.waitNanos(now, 30) == 0L) {
                cadence.onRendered(now)
                renders++
            }
        }
        assertEquals(30, renders)
    }

    @Test fun presentFramesSettleOnceThenStop() {
        val cadence = GlassFrameCadence()
        cadence.onWindowDraw(presentOnly = false)
        cadence.onRendered(0L)
        // The frame that shows the new image: one settle render, in case a change landed in that frame.
        assertTrue(cadence.onWindowDraw(presentOnly = true))
        cadence.onRendered(frame120 * 4)
        // The settle render's own present must not start another render: an idle screen costs nothing.
        assertFalse(cadence.onWindowDraw(presentOnly = true))
        assertFalse(cadence.dirty)
    }

    @Test fun uncappedNeverWaits() {
        val cadence = GlassFrameCadence()
        cadence.onRendered(0L)
        assertEquals(0L, cadence.waitNanos(1L, 0))
    }
}
