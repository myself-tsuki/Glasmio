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

## Simple menu animation

`simpleMenus` (on for `Efficient` and `Minimal`, off for `Full` and `Balanced`) makes `GlasmioMenu` and the cascading/pop-up menus open with the plain scale + fade spring instead of the liquid morph from the anchor. It skips the source capture, the CPU/FP16 field, the contour shader and the entrance blur on the popup host. Force it with `GlasmioPerformanceConfig(simpleMenus = true/false)`. It is read when a menu opens; an already open menu keeps its path.

## What each knob changes

- **Backdrop downscale (`samplingScale`)** multiplies each surface's own effect-texture scale (floor 0.25×). Surfaces that already sample at 0.25–0.5× (buttons, tab bars, toolbars) are unaffected at the floor; full-resolution surfaces (navigation bar, pills, contact/toolbar/union shapes, menus, sheets, sidebars, backdrop blur) get cheaper. Shape fields (contact, toolbar, union) are evaluated in full-size pixels and mapped into the reduced effect space (`NativeGlassFilterField.atSamplingScale`), and the optical center is scaled with them, so downscaled glass keeps its size and position. Text, shape clips, highlights and shadows stay at device resolution. Where the control field plane needs unit scale, the renderer uses its analytic path, as it already did for any scale other than 1.
- **Frame-rate cap (`frameRateCap`)** applies to the glass only; the window keeps drawing at the panel rate.
  - **Glass rendering:** each backdrop source (the page content glass samples) is snapshotted off screen at most `frameRateCap` times a second, at the backdrop resolution (`BackdropSnapshot`: one `HardwareRenderer` per source into a hardware-buffer `ImageReader`). Capped glass samples the latest snapshot instead of the live content, so its blur, lens and material chain reruns only when a new snapshot arrives, while the window, scrolling, text and the rest of the app stay at the panel rate. Only content render nodes enter the snapshot renderer: glass layers, render effects and runtime shaders always stay in the window's renderer. One snapshot serves every glass surface over the same source, including menus and sheets in their own popup window. All snapshots share one clock (`GlassFrameClock`): while a window with a backdrop source draws an app frame, they re-render at the cap; 250 ms after the last app frame the clock stops, so an idle screen does no glass work. Until the first snapshot exists (first frame, mid-resize), glass samples the live content. Wide-gamut (color-managed) and HDR windows, software drawing, uniform fills and a cap at or above the panel rate sample live.
  - **Glass animation clocks:** Glasmio's own animation clocks (navigation scaffold, adaptive navigation/sidebar, menu morph, loupe follow/spring, reaction bar) skip frames too. They integrate real elapsed time, so motion speed is unchanged.
  - Prefer divisors of the panel rate (30/40/60 on 120 Hz, 30 on 60 Hz).
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

## Usage guide

### 1. Do nothing (Auto)

Auto works without any code. The first glass surface that draws classifies the device and applies the matching tier. Call `GlasmioPerformance.configure(applicationContext)` in `Application.onCreate` only if you want detection to finish before the first frame.

### 2. Read what is in force

```kotlin
val tier = GlasmioPerformance.tier           // configured tier, else detected, else Full
val cap = GlasmioPerformance.frameRateCap    // Hz, 0 = uncapped
```

Both are Compose snapshot reads, so a composable that reads them updates when `config` changes.

### 3. Set a fixed configuration

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        GlasmioPerformance.configure(
            this,
            GlasmioPerformanceConfig(tier = GlasmioPerformanceTier.Efficient, frameRateCap = 30),
        )
    }
}
```

Any property left `null` follows the tier. `tier = null` means Auto.

### 4. Let the user choose (settings screen)

```kotlin
@Composable
fun GraphicsQualitySetting() {
    val current = GlasmioPerformance.config
    val options = listOf<GlasmioPerformanceTier?>(null) + GlasmioPerformanceTier.entries
    Column {
        options.forEach { option ->
            Row(Modifier.selectable(current.tier == option, onClick = {
                GlasmioPerformance.config = current.copy(tier = option)
            })) {
                RadioButton(selected = current.tier == option, onClick = null)
                Text(option?.name ?: "Auto (${GlasmioPerformance.tier.name})")
            }
        }
        Text("Glass frame rate: ${current.frameRateCap ?: GlasmioPerformance.frameRateCap} Hz")
        Slider(
            value = (current.frameRateCap ?: GlasmioPerformance.frameRateCap).toFloat(),
            onValueChange = { GlasmioPerformance.config = current.copy(frameRateCap = it.toInt()) },
            valueRange = 0f..120f, steps = 3,
        )
    }
}
```

Persist the choice yourself (DataStore or SharedPreferences) and restore it with `GlasmioPerformance.config = ...` at startup.

### 5. Recipes

| Goal | Configuration |
| --- | --- |
| Original look on every device | `tier = Full` |
| Keep lens refraction but lower GPU load | `tier = Balanced`, or `Full` with `samplingScale = 0.6f` |
| Smooth on Snapdragon 865 / Exynos 1200 | `tier = Efficient` (this is what Auto picks) |
| Efficient look but keep the lens on small controls | `tier = Efficient, refraction = true` |
| Battery saver | `tier = Efficient, frameRateCap = 30` |
| Lowest cost, no page capture | `tier = Minimal` or `backdrop = false` |
| Render glass at 30 Hz on a 120 Hz screen | `frameRateCap = 30` (display refresh is unaffected) |

### 6. Behavior notes

- Changing `config` at runtime re-renders glass surfaces immediately, except the liquid-menu field step, which applies at the next geometry change.
- `frameRateCap` caps glass rendering and Glasmio's glass clocks only. Compose scrolling, `animate*AsState` and your own animations keep the panel rate; glass content behind them updates at the cap, so it can trail a fast scroll by up to one glass frame. Use divisors of the panel rate (30/40/60 on 120 Hz; 30 on 60 Hz) to avoid uneven cadence.
- `refraction = false` is the same appearance as `GlassAppearance(liquidGlassEnabled = false)`; `backdrop = false` is the same as `reduceTransparency = true`. The host's own `GlassAppearance` is still respected: a tier can only turn effects off, never on.
- The tier is applied inside `GlasmioTheme`. If you provide `LocalGlassAppearance` yourself outside `GlasmioTheme`, pass it through `GlasmioPerformance.adjust(appearance)`.
- `samplingScale` has a floor of 0.25; most small controls already sample at 0.25–0.5, so it mainly affects menus, sheets, sidebars and full-resolution backdrops.

### 7. Troubleshooting

| Symptom | Try |
| --- | --- |
| Still slow with many glass surfaces | `backdrop = false` on that tier, or fewer simultaneous glass surfaces; each one references the page layer |
| Glass looks flat | Auto may have chosen `Minimal` (low RAM); set `tier = Efficient` or `Balanced` |
| Wrong tier detected for your device | Set `tier` explicitly; Auto reads `Build.SOC_MODEL`, RAM and `isLowRamDevice` |
| Glass lags behind finger during drags at 30 Hz | Raise `frameRateCap` to 60 or 0 |

### API reference

| Symbol | Purpose |
| --- | --- |
| `GlasmioPerformanceTier` | `Full`, `Balanced`, `Efficient`, `Minimal` presets |
| `GlasmioPerformanceConfig(tier, frameRateCap, samplingScale, refraction, backdrop)` | Host overrides; `null` follows the tier |
| `GlasmioPerformance.config` | Observable current config (get/set) |
| `GlasmioPerformance.configure(context, config)` | Detect the device, install a config |
| `GlasmioPerformance.tier` / `frameRateCap` | Values in force |
| `GlasmioPerformance.sampling(base)` / `adjust(appearance)` / `fieldStep(base)` | Hooks used by the renderer |
| `GlasmioFrameGate` | Per-loop frame limiter for your own `withFrameNanos` clocks: `if (gate.accept(now)) update()` |
