# Performance tiers, frame-rate cap and backdrop downscale

Glasmio 0.2.0 adds one process-wide switchboard, `GlasmioPerformance` (in `glasmio-core`). The default is **Auto**: the device is classified once from its SoC, RAM and low-RAM flag. `Full` is the original, unmodified rendering path, so flagships look and behave exactly as before.

```kotlin
// Application.onCreate (optional: Auto works without it, this just detects early)
GlasmioPerformance.configure(this)

// Settings screen: pick a tier, cap the glass update rate, or set an explicit downscale.
GlasmioPerformance.config = GlasmioPerformanceConfig(
    tier = GlasmioPerformanceTier.Efficient, // null = Auto
    frameRateCap = 30,                        // Hz; null = tier default, 0 = every display frame
    samplingScale = 0.5f,                     // 0.25..1; null = tier default
    refraction = false,                       // lens passes on/off; null = tier default
    backdrop = true,                          // backdrop sampling on/off; null = tier default
)
```

`config` is Compose snapshot state: changing it re-renders every glass surface with no restart.

## Tiers

| Tier | Backdrop scale | Field step | Glass clock cap | Lens refraction | Backdrop capture | Auto picks it for |
| --- | ---: | ---: | ---: | :---: | :---: | --- |
| `Full` | 1.0 | 1.0 | uncapped | on | on | 8 Gen 2+/Tensor G3+/Exynos 2400+/Dimensity 9300+ with ≥ 12 GB |
| `Balanced` | 0.75 | 1.0 | 60 Hz | on | on | Snapdragon 8 Gen 1, Tensor G1/G2, Exynos 2100/2200, any flagship with 8 GB |
| `Efficient` | 0.5 | 1.5 | 60 Hz | **off** | on | Snapdragon 865/888, Exynos 1200/1280, SD 4/6/7 series, Dimensity 700–900, ≤ 6 GB |
| `Minimal` | 0.35 | 2.0 | 30 Hz | off | **off** | Low-RAM devices, ≤ 3.5 GB |

`Efficient` keeps the frosted blur, tint, highlights and shadows but removes every lens/refraction/dispersion pass (control lenses, tab indicator lens, menu refraction, contact refraction, outer ray). `Minimal` also stops sampling the backdrop, which removes the per-surface page capture that dominates HWUI cost (see "Header glass page references" in the interaction doc); components render as the same flat translucent material the `reduceTransparency` accessibility setting already produces. Both are applied once, in `GlasmioTheme`, through `GlasmioPerformance.adjust(appearance)`, so every component follows. Use `refraction = true/false` or `backdrop = true/false` in `GlasmioPerformanceConfig` to override a tier.

The weaker of the SoC and RAM verdicts wins. Detection reads `Build.SOC_MANUFACTURER`/`SOC_MODEL`/`HARDWARE` (SDK 31+), total RAM and `isLowRamDevice`. Unknown SoCs fall back to RAM alone. Set `tier` explicitly to override.

## What each knob changes

- **Backdrop downscale (`samplingScale`)** multiplies each surface's own effect-texture scale (floor 0.25×). Surfaces that already sample at 0.25–0.5× (buttons, tab bars, toolbars) are unaffected at the floor; full-resolution surfaces (menus, sheets, sidebars, backdrop blur) get cheaper. Text, shape clips, highlights and shadows stay at device resolution. Where the control field plane needs unit scale, the renderer uses its analytic path, as it already did for any scale other than 1.
- **Frame-rate cap (`frameRateCap`)** gates Glasmio's own animation clocks (navigation scaffold, adaptive navigation/sidebar, menu morph, loupe follow/spring, reaction bar). Skipped frames do no work: no state write, no recomposition, no shader uniform update. The clocks integrate real elapsed time, so motion speed is unchanged and the display keeps its high refresh rate; only the glass update cadence drops. On a 120 Hz panel a 60 Hz cap updates every 2nd frame, 30 Hz every 4th. Compose's own animations and scrolling are not capped. Prefer divisors of the panel rate (30/40/60 on 120 Hz, 30 on 60 Hz).
- **Field grid step** coarsens the CPU/FP16 liquid-menu field (4× fewer texels at 2×), which shrinks the per-update Bitmap allocation, native build and GPU upload. It takes effect at the next geometry update.
- **Luminance probe interval** spaces the GPU→CPU backdrop read-back that adaptive label colors use.

## Other fixes in 0.2.0

- Context-menu exit retention waited on the frame clock in an empty loop for the whole tail; it now sleeps with `delay`.
- The native-atlas material runtime-shader cache key string is memoized per immutable source instead of rebuilt on every effect update.
- Every `withFrameNanos` driven animation clock listed above shares the cap.

## Not changed (and why)

The design invariants in [interaction-performance.md](interaction-performance.md) still hold: kernels, field updates on geometry change and the live sheet layout are untouched; `Full` is bit-for-bit the previous path. The FP16 field bitmap is still allocated per generation: the existing comment documents that HWUI/RenderThread ownership can't be bounded by a frame-counted ring, so pooling needs a fence and was left out rather than risk tearing. The `revision++` recomposition in the scaffold still exists (now gated) because its geometry is read in composition and measurement; moving it to the draw phase is a larger refactor that needs the device traces described in the validation doc. The C++ field builder was reviewed and left alone: one JNI call per field, SIMD, reused scratch, critical-array output.

## Verifying on a device

Follow [interaction-performance.md → Device measurement](interaction-performance.md#device-measurement): Perfetto FrameTimeline on a physical device, cleared shader caches, p50/p95/p99 and deadline misses per tier. Suggested matrix: `Full` vs `Balanced` on an SD865, `Balanced` vs `Efficient` on an Exynos 1200, each with cap 0/60/30 on the Controls scroll, a menu open/close and a sheet drag. These numbers have not been collected yet.
