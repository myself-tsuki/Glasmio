package io.github.neoruaa.glasmio.glass.render

import io.github.neoruaa.glasmio.glass.render.GlassFrameSchedule.Companion.Never
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GlassFrameCadenceTest {
    private val frame120 = 8_333_333L

    /** Drives the schedule like GlassFrameClock: one check per 120 Hz vsync. Returns the tick count. */
    private fun run(schedule: GlassFrameSchedule, frames: IntRange, cap: Int, active: (Int) -> Boolean): Int {
        var ticks = 0
        for (i in frames) {
            val now = i * frame120
            if (active(i)) schedule.onActivity(now)
            val at = schedule.nextTickAt(cap)
            if (at != Never && at <= now + 1_000_000L) {
                schedule.onTick(now)
                ticks++
            }
        }
        return ticks
    }

    @Test fun idleClockNeverTicks() {
        assertEquals(Never, GlassFrameSchedule().nextTickAt(30))
    }

    @Test fun firstActivityTicksImmediately() {
        val schedule = GlassFrameSchedule()
        schedule.onActivity(5L)
        assertEquals(5L, schedule.nextTickAt(30))
    }

    @Test fun rendersAt30HzWhileTheAppDrawsAt120() {
        assertEquals(30, run(GlassFrameSchedule(), 0 until 120, 30) { true })
    }

    @Test fun keepsRenderingWhenOnlySomeFramesCountAsActivity() {
        // The old per-surface damage guess starved here: most app frames were also glass-present frames.
        assertEquals(30, run(GlassFrameSchedule(), 0 until 120, 30) { it % 4 == 1 })
    }

    @Test fun tailCatchesTheFinalStateThenStops() {
        val schedule = GlassFrameSchedule()
        run(schedule, 0 until 60, 30) { true }
        // About 250 ms of 30 Hz glass frames after the last change, then nothing.
        assertEquals(9, run(schedule, 60 until 240, 30) { false })
        assertEquals(Never, schedule.nextTickAt(30))
    }

    @Test fun newActivityRestartsAStoppedClock() {
        val schedule = GlassFrameSchedule()
        run(schedule, 0 until 240, 30) { it == 0 }
        assertEquals(Never, schedule.nextTickAt(30))
        schedule.onActivity(300 * frame120)
        assertNotEquals(Never, schedule.nextTickAt(30))
    }

    @Test fun uncappedNeverTicks() {
        val schedule = GlassFrameSchedule()
        schedule.onActivity(0L)
        assertEquals(Never, schedule.nextTickAt(0))
    }
}
