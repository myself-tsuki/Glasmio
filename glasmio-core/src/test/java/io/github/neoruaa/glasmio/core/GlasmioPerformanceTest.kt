package io.github.neoruaa.glasmio.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlasmioPerformanceTest {
    @After fun reset() { GlasmioPerformance.config = GlasmioPerformanceConfig() }

    private fun tier(soc: String, gb: Double, low: Boolean = false) = GlasmioPerformance.classify(soc, gb, low)

    @Test fun snapdragon865IsEfficient() {
        assertEquals(GlasmioPerformanceTier.Efficient, tier("Qualcomm SM8250 kona", 8.0))
        assertEquals(GlasmioPerformanceTier.Efficient, tier("Qualcomm SM8250 kona", 12.0))
        assertEquals(GlasmioPerformanceTier.Balanced, tier("Qualcomm SM8450 waipio", 12.0))
    }

    @Test fun exynos1200IsEfficient() {
        assertEquals(GlasmioPerformanceTier.Efficient, tier("Samsung S5E8825 s5e8825", 8.0))
        assertEquals(GlasmioPerformanceTier.Efficient, tier("Samsung Exynos 1200", 6.0))
    }

    @Test fun flagshipNeedsMemoryToStayFull() {
        assertEquals(GlasmioPerformanceTier.Full, tier("Qualcomm SM8650 pineapple", 12.0))
        assertEquals(GlasmioPerformanceTier.Balanced, tier("Qualcomm SM8650 pineapple", 8.0))
    }

    @Test fun lowRamIsMinimal() {
        assertEquals(GlasmioPerformanceTier.Minimal, tier("Qualcomm SM8650", 16.0, low = true))
        assertEquals(GlasmioPerformanceTier.Minimal, tier("unknown", 2.0))
    }

    @Test fun tiersDropOpticsInsteadOfComponents() {
        val base = GlassAppearance()
        GlasmioPerformance.config = GlasmioPerformanceConfig(GlasmioPerformanceTier.Full)
        assertEquals(base, GlasmioPerformance.adjust(base))
        GlasmioPerformance.config = GlasmioPerformanceConfig(GlasmioPerformanceTier.Efficient)
        GlasmioPerformance.adjust(base).let { assertFalse(it.usesRefraction); assertTrue(it.usesBackdrop) }
        GlasmioPerformance.config = GlasmioPerformanceConfig(GlasmioPerformanceTier.Minimal)
        assertFalse(GlasmioPerformance.adjust(base).usesBackdrop)
        GlasmioPerformance.config = GlasmioPerformanceConfig(GlasmioPerformanceTier.Efficient, refraction = true)
        assertTrue(GlasmioPerformance.adjust(base).usesRefraction)
    }

    @Test fun fullTierLeavesSamplingUntouched() {
        GlasmioPerformance.config = GlasmioPerformanceConfig(tier = GlasmioPerformanceTier.Full)
        assertEquals(.5f, GlasmioPerformance.sampling(.5f), 0f)
        assertEquals(1f, GlasmioPerformance.fieldStep(1f), 0f)
        assertEquals(0, GlasmioPerformance.frameRateCap)
    }

    @Test fun explicitOverridesWinAndAreClamped() {
        GlasmioPerformance.config = GlasmioPerformanceConfig(GlasmioPerformanceTier.Full, frameRateCap = 30, samplingScale = .1f)
        assertEquals(30, GlasmioPerformance.frameRateCap)
        assertEquals(.25f, GlasmioPerformance.sampling(.5f), 0f)
        assertEquals(1f, GlasmioPerformance.sampling(1f), 0f)
        GlasmioPerformance.config = GlasmioPerformanceConfig(GlasmioPerformanceTier.Efficient, samplingScale = .6f)
        assertEquals(.3f, GlasmioPerformance.sampling(.5f), 1e-6f)
        assertEquals(1f, GlasmioPerformance.sampling(1f), 0f)
    }

    @Test fun gateHalvesA60HzClockAt30() {
        GlasmioPerformance.config = GlasmioPerformanceConfig(GlasmioPerformanceTier.Full, frameRateCap = 30)
        val gate = GlasmioFrameGate()
        val period = 16_666_667L
        val accepted = (0 until 60).count { gate.accept(it * period) }
        assertEquals(30, accepted)
    }

    @Test fun gateCaps120HzTo60AndUncappedPassesEverything() {
        GlasmioPerformance.config = GlasmioPerformanceConfig(GlasmioPerformanceTier.Full, frameRateCap = 60)
        val gate = GlasmioFrameGate()
        assertEquals(60, (0 until 120).count { gate.accept(it * 8_333_333L) })
        GlasmioPerformance.config = GlasmioPerformanceConfig(GlasmioPerformanceTier.Full, frameRateCap = 0)
        val open = GlasmioFrameGate()
        assertTrue((0 until 10).all { open.accept(it * 8_333_333L) })
        assertFalse(GlasmioFrameGate().also { GlasmioPerformance.config = GlasmioPerformanceConfig(frameRateCap = 30) }.run { accept(0); accept(1_000_000) })
    }
}
