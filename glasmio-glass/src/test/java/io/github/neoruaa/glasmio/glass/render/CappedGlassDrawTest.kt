package io.github.neoruaa.glasmio.glass.render

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Only glass being recorded under [CappedGlassDraw.with] may sample snapshots; nothing leaks past its scope. */
class CappedGlassDrawTest {
    @Test fun scopeIsClearedAfterTheDraw() {
        CappedGlassDraw.with(null) { assertNull(CappedGlassDraw.view) }
        assertNull(CappedGlassDraw.view)
    }

    @Test fun nestedLiveDrawInsideCappedDrawRestoresTheOuterScope() {
        var innerWasLive = false
        CappedGlassDraw.with(null) {
            CappedGlassDraw.with(null) { innerWasLive = CappedGlassDraw.view == null }
            assertNull(CappedGlassDraw.view)
        }
        assertTrue(innerWasLive)
    }

    @Test fun scopeIsClearedWhenTheDrawThrows() {
        try {
            CappedGlassDraw.with(null) { error("draw failed") }
            fail("expected the draw to throw")
        } catch (_: IllegalStateException) {
        }
        assertNull(CappedGlassDraw.view)
    }
}
