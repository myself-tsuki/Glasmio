package io.github.neoruaa.glasmio.glass.render

import io.github.neoruaa.glasmio.glass.render.GlassFrameRendererLifetime.SurfaceAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the capped-FPS menu crash and stale backdrop (0.2.2/0.2.3): the off-screen glass renderer was
 * destroyed on every size change, which made HWUI delete the glass layer's display lists while Compose still used
 * them. A menu resizes several times while it opens, so opening one with frameRateCap set broke.
 */
class GlassFrameRendererLifetimeTest {
    @Test fun firstGlassFrameCreatesTheRenderer() {
        assertEquals(SurfaceAction.Create, GlassFrameRendererLifetime.surfaceAction(false, 0, 0, 300, 200))
    }

    @Test fun sameSizeKeepsTheSurface() {
        assertEquals(SurfaceAction.Keep, GlassFrameRendererLifetime.surfaceAction(true, 300, 200, 300, 200))
    }

    @Test fun resizeWhileOpeningSwapsTheSurfaceAndNeverRecreatesTheRenderer() {
        // A menu opening: measured, retargeted, then settled. Every step after the first must keep the renderer.
        val sizes = listOf(300 to 120, 300 to 260, 320 to 268, 320 to 268)
        var current = 0 to 0
        var hasRenderer = false
        var created = 0
        for ((w, h) in sizes) {
            when (GlassFrameRendererLifetime.surfaceAction(hasRenderer, current.first, current.second, w, h)) {
                SurfaceAction.Create -> { created++; hasRenderer = true; current = w to h }
                SurfaceAction.Swap -> current = w to h
                SurfaceAction.Keep -> Unit
            }
        }
        assertEquals("one renderer for the whole opening", 1, created)
        assertEquals(320 to 268, current)
    }

    @Test fun lostSurfaceGetsANewSurfaceOnTheSameRenderer() {
        // After a lost surface the cache forgets the surface size but keeps the renderer.
        assertEquals(SurfaceAction.Swap, GlassFrameRendererLifetime.surfaceAction(true, 0, 0, 300, 200))
    }

    @Test fun deferredDestroyRunsOnceWhicheverTriggerFiresFirst() {
        var destroyed = 0
        val destroy = RunOnce { destroyed++ }
        assertFalse(destroy.hasRun)
        destroy.run() // window frame committed
        destroy.run() // fallback timer
        assertTrue(destroy.hasRun)
        assertEquals(1, destroyed)
    }

    @Test fun releaseWaitsForAWindowFrame() {
        assertTrue(GlassFrameRendererLifetime.ReleaseFallbackMillis > 0L)
    }
}
