package io.github.neoruaa.glasmio.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Rendering budget presets. [Full] reproduces the original, uncapped rendering. Every other tier trades
 * backdrop resolution and update cadence for GPU time; geometry, motion timing and layout are unchanged.
 *
 * @property samplingMultiplier scale applied on top of each surface's own backdrop sampling scale (1 = unchanged).
 * @property fieldStepMultiplier coarser grid step for the CPU/FP16 liquid-menu and contact fields (1 = unchanged).
 * @property frameRateCap rate, in Hz, at which Glasmio's own animation clocks update glass; 0 means every display frame.
 * @property refraction whether lens refraction/dispersion shaders run (off = frosted blur glass, no lens passes).
 * @property backdrop whether glass samples the backdrop at all (off = flat translucent material, no page capture).
 * @property luminanceProbeIntervalMs minimum time between GPU-to-CPU backdrop luminance read-backs.
 */
enum class GlasmioPerformanceTier(
    val samplingMultiplier: Float,
    val fieldStepMultiplier: Float,
    val frameRateCap: Int,
    val luminanceProbeIntervalMs: Long,
    val refraction: Boolean,
    val backdrop: Boolean,
    /** Menus open with a plain scale+fade instead of the liquid morph from the anchor (no source capture, field or contour shader). */
    val simpleMenus: Boolean = false,
) {
    /** Original quality: flagship SoCs. */
    Full(1f, 1f, 0, 33L, refraction = true, backdrop = true),
    /** Snapdragon 8 Gen 1/2 class and 8 GB flagships: full optics, lighter sampling. */
    Balanced(.75f, 1f, 60, 50L, refraction = true, backdrop = true),
    /** Snapdragon 865/888, Exynos 1200/1280, Dimensity 700–900, Snapdragon 7-series: blurred glass without lens passes. */
    Efficient(.5f, 1.5f, 60, 100L, refraction = false, backdrop = true, simpleMenus = true),
    /** Low-RAM or very old devices: flat translucent material, no backdrop capture. */
    Minimal(.35f, 2f, 30, 250L, refraction = false, backdrop = false, simpleMenus = true),
}

/**
 * Host-facing overrides. Every property left null follows the tier; a null [tier] means "detect this device".
 *
 * @property frameRateCap glass update rate in Hz (0 = every display frame). The display itself keeps its refresh rate.
 * @property samplingScale explicit backdrop downscale multiplier in 0.25..1, replacing the tier's value.
 */
data class GlasmioPerformanceConfig(
    val tier: GlasmioPerformanceTier? = null,
    val frameRateCap: Int? = null,
    val samplingScale: Float? = null,
    /** Force lens refraction on/off; null follows the tier. */
    val refraction: Boolean? = null,
    /** Force backdrop sampling on/off; null follows the tier. */
    val backdrop: Boolean? = null,
    /** Force the simple scale+fade menu animation on/off; null follows the tier. */
    val simpleMenus: Boolean? = null,
)

/**
 * Process-wide performance settings. Reads from draw or composition are snapshot-observed, so changing
 * [config] re-renders every glass surface. Call [configure] once at startup, or change [config] at any time
 * (for example from a settings screen).
 */
object GlasmioPerformance {
    private var state by mutableStateOf(GlasmioPerformanceConfig())
    @Volatile private var detected: GlasmioPerformanceTier? = null

    var config: GlasmioPerformanceConfig
        get() = state
        set(value) { state = value.copy(
            frameRateCap = value.frameRateCap?.coerceIn(0, 240),
            samplingScale = value.samplingScale?.takeIf { it.isFinite() }?.coerceIn(MinimumSamplingScale, 1f),
        ) }

    /** Detects the device (once) and installs [config]. Safe to call from `Application.onCreate`. */
    fun configure(context: Context, config: GlasmioPerformanceConfig = GlasmioPerformanceConfig()) {
        ensureDetected(context)
        this.config = config
    }

    /** The tier in force: the configured one, else the detected one, else [GlasmioPerformanceTier.Full]. */
    val tier: GlasmioPerformanceTier get() = state.tier ?: detected ?: GlasmioPerformanceTier.Full

    /** Effective glass update rate in Hz; 0 means uncapped. */
    val frameRateCap: Int get() = state.frameRateCap ?: tier.frameRateCap

    /**
     * Applies the tier or explicit downscale to a surface's own [base] backdrop scale, full-size (1) surfaces
     * included. Contact, toolbar and union shape fields are mapped into the reduced effect space by the renderer,
     * so the navigation bar and pills downscale like every other surface.
     */
    fun sampling(base: Float): Float {
        val multiplier = state.samplingScale ?: tier.samplingMultiplier
        return if (multiplier >= 1f) base else (base * multiplier).coerceAtLeast(MinimumSamplingScale)
    }

    /**
     * The appearance Glasmio components actually render with: [GlasmioTheme] passes the host's appearance
     * through here, so one switch covers every control, bar, menu, sheet and sidebar.
     */
    fun adjust(appearance: GlassAppearance): GlassAppearance {
        val refraction = state.refraction ?: tier.refraction
        val backdrop = state.backdrop ?: tier.backdrop
        if (refraction && backdrop) return appearance
        return appearance.copy(
            liquidGlassEnabled = appearance.liquidGlassEnabled && refraction,
            reduceTransparency = appearance.reduceTransparency || !backdrop,
        )
    }

    /** Whether menus use the plain scale+fade opening instead of the liquid morph. Snapshot-observed. */
    val simpleMenus: Boolean get() = state.simpleMenus ?: tier.simpleMenus

    fun fieldStep(base: Float): Float = base * tier.fieldStepMultiplier

    val luminanceProbeIntervalNanos: Long get() = tier.luminanceProbeIntervalMs * 1_000_000L

    /** Idempotent and cheap after the first call. */
    fun ensureDetected(context: Context) {
        if (detected == null) detected = detect(context.applicationContext ?: context)
    }

    internal fun detect(context: Context): GlasmioPerformanceTier {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo().also { manager?.getMemoryInfo(it) }
        val memoryGb = info.totalMem / (1024.0 * 1024.0 * 1024.0)
        return classify(
            soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL} ${Build.HARDWARE}" else Build.HARDWARE,
            memoryGb = memoryGb,
            lowRam = manager?.isLowRamDevice == true,
        )
    }

    /** Pure so it can be unit tested. */
    internal fun classify(soc: String, memoryGb: Double, lowRam: Boolean): GlasmioPerformanceTier {
        val name = soc.lowercase()
        if (lowRam || memoryGb in 0.1..3.5) return GlasmioPerformanceTier.Minimal
        val byMemory = when {
            memoryGb >= 11.5 -> GlasmioPerformanceTier.Full
            memoryGb >= 7.5 -> GlasmioPerformanceTier.Balanced
            else -> GlasmioPerformanceTier.Efficient
        }
        val bySoc = when {
            FlagshipSoc.any { it.containsMatchIn(name) } -> GlasmioPerformanceTier.Full
            PreviousFlagshipSoc.any { it.containsMatchIn(name) } -> GlasmioPerformanceTier.Balanced
            MidRangeSoc.any { it.containsMatchIn(name) } -> GlasmioPerformanceTier.Efficient
            else -> null
        }
        // The weaker verdict wins: a flagship SoC with 4 GB of RAM still thrashes on large offscreen layers.
        return if (bySoc == null) byMemory else if (bySoc.ordinal > byMemory.ordinal) bySoc else byMemory
    }

    private const val MinimumSamplingScale = .25f
    // Matched against "<manufacturer> <SOC_MODEL> <HARDWARE>"; Exynos parts report Samsung codenames (Exynos 1200 = S5E8825).
    private val FlagshipSoc = listOf(
        Regex("sm8[5-9]\\d\\d"), Regex("tensor g[3-9]"), Regex("exynos ?2[4-9]\\d\\d|s5e99[4-9]\\d"),
        Regex("dimensity ?9[3-9]\\d\\d|mt69[3-9]\\d"),
    )
    private val PreviousFlagshipSoc = listOf(
        Regex("sm84\\d\\d"), Regex("sm7[4-9]\\d\\d"), Regex("tensor g[12]\\b|google tensor"),
        Regex("exynos ?2[1-3]\\d\\d|s5e98\\d\\d|s5e992\\d"), Regex("dimensity ?(8\\d|9[0-2])\\d\\d|mt689\\d|mt69[0-2]\\d"),
    )
    private val MidRangeSoc = listOf(
        Regex("sm8[23]\\d\\d|sm[4-7]\\d\\d\\d|sdm\\d+|msm\\d+"), Regex("exynos ?(8|9|1)\\d{3}\\b|s5e\\d+"),
        Regex("dimensity ?[67]\\d\\d\\d?|mt67\\d\\d|mt68[0-8]\\d"), Regex("helio|unisoc|spreadtrum|ums\\d"),
    )
}

/**
 * Admits at most [GlasmioPerformance.frameRateCap] updates per second from a per-frame callback. Use one gate
 * per animation loop and call [accept] from inside `withFrameNanos`; skipped frames cost nothing because the
 * caller does not touch state, so nothing is recomposed or redrawn.
 */
class GlasmioFrameGate {
    private var last = Long.MIN_VALUE

    fun accept(frameNanos: Long): Boolean {
        val cap = GlasmioPerformance.frameRateCap
        if (cap <= 0 || last == Long.MIN_VALUE || frameNanos < last) { last = frameNanos; return true }
        // A 3 ms tolerance keeps a 60 Hz cap on 60 Hz panels from skipping every other frame on jitter.
        if (frameNanos - last >= 1_000_000_000L / cap - JitterNanos) { last = frameNanos; return true }
        return false
    }

    fun reset() { last = Long.MIN_VALUE }

    private companion object { const val JitterNanos = 3_000_000L }
}
