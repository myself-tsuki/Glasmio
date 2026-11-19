# Changelog

## Unreleased

- Rebuild the glass frame-rate cap so capped glass cannot crash the app. 0.2.2-0.2.4 rendered each glass surface's whole filter chain (render effects, runtime shaders, atlas layers) in a second off-screen renderer and lent the glass layer to it. That crashed on the RenderThread on some devices, at app start, when a menu opened, and as an ANR. The cap now snapshots only the page content behind the glass (one off-screen renderer per backdrop source, at the backdrop resolution) at most `frameRateCap` times a second. The glass itself always stays in the window's renderer and reruns its chain only when a new snapshot arrives, so the GPU saving remains while the window keeps the panel rate.

## 0.2.4

- Fix an "Input dispatching timed out" ANR with `frameRateCap` set, typically when a menu opens. The capped-glass renderer draws into a 4-buffer queue, and buffers were only taken off that queue on the main thread. The RenderThread waited for a free buffer while drawing glass, the main thread waited for the RenderThread, and neither could continue. Finished glass images are now taken off the queue on their own thread (`GlasmioGlassFrames`). The main thread only swaps in the newest image, so the RenderThread never waits on it.
- Fix the app crashing when a menu opens with `frameRateCap` set (0.2.2/0.2.3), and the backdrop going stale or blank with the cap on. While the window shows the cached glass image, the off-screen renderer is the only parent of the glass layer's render node. The cache destroyed that renderer on every size change (a menu resizes several times while it opens) and right after handing the layer back to the window. That dropped the node's last parent, and Android's renderer then deleted the glass layer's display lists while Compose still used them. Each cache now keeps one renderer for its whole life, a resize only swaps its output surface, and the renderer is destroyed only after the window has committed a frame that draws the glass layer itself.

## 0.2.3

- Fix the backdrop freezing on an old frame with `frameRateCap` (0.2.2). The glass cache guessed when to re-render from window draws and treated frames that showed a new glass image as "nothing changed". With several glass surfaces, almost every frame was such a frame, so glass stopped updating. Glass in a popup also never saw the content window scroll. Capped glass now renders on one shared clock at the cap whenever any glass or backdrop-source window draws, then stops 250 ms after the last app frame.

## 0.2.2

- `frameRateCap` now caps glass rendering itself: each glass surface renders its capture and filter chain off screen at the cap and the window composites the last image at the panel rate (`GlassFrameCache`). The rest of the app is not capped.
- Downscale full-size glass surfaces (navigation bar, pills, contact/toolbar/union shapes) properly: their shape fields and optical center are mapped into the reduced effect space, which fixes the oversized/cropped glass at the root. `GlasmioPerformance.sampling()` no longer exempts base-1 surfaces.
- `simpleMenus` (on for Efficient and Minimal): menus open with a plain scale and fade instead of the liquid morph and entrance blur.

## 0.2.1

- Fix navigation bar and pill surfaces rendering oversized/cropped on non-Full performance tiers: the tier downscale no longer applies to surfaces that sample at full size (contact, toolbar and union shapes).
- Merge upstream: glass popovers and tooltips, floating-window caption inset, pull-to-refresh indicator (`GlasmioPullToRefreshBox`), and Android 12 support with guarded glass rendering fallbacks.

## 0.2.0

- Add `GlasmioPerformance`: device-tier detection (Auto), `Full`/`Balanced`/`Efficient`/`Minimal` tiers, a glass frame-rate cap and a backdrop downscale, all runtime-switchable. See `docs/performance-tiers.md`.
- Gate the scaffold, adaptive navigation, menu morph, loupe and reaction-bar clocks by the cap; skipped frames no longer recompose or update shaders.
- Space luminance read-backs by tier, coarsen the liquid-menu field grid on lower tiers, memoize the native-atlas shader key, and stop the context-menu exit from spinning the frame clock.
- `Full` keeps the previous rendering path.

## 0.1.3

- Reuse compiled glass programs across navigation, menu, and header transitions.
- Reduce detached backdrop work and stabilize adaptive toolbar, sidebar, and floating-search transitions.

## 0.1.2

- Add adaptive top tabs, native sidebar transitions, and fullscreen overlays above adaptive navigation.
- Improve sidebar materials, content sampling, and navigation glass rendering performance.

## 0.1.1

- Reduce active navigation tab rendering work by sharing scene and lens programs.
- Reuse tab programs and build atlas shader sources lazily.

## 0.1.0

Initial Android Compose framework extraction from MeiloX.

- Introduce Core, Glass, UI, Scaffold, Preference, and Icons modules with one-way dependencies.
- Add light/dark themes, host-configurable colors and typography, and continuous rounded shapes across the component system.
- Preserve backdrop sampling, liquid refraction, press highlights, shadows, navigation contact effects, and menu morph rendering.
- Provide buttons, switches, sliders, segmented controls, text/search fields, steppers, cards, lists, toolbars, navigation bars, menus, alerts, sheets, a color picker, and reorderable groups.
- Add collapsing large-title pages, lazy content, overscroll, pull-refresh rebound, insets, and generic anchor-based sheet state.
- Collapse large titles on a scroll breakpoint (56 dp enter, 24 dp exit) instead of mapping the scroll offset, and play the collapse with the navigation spring. `rememberGlasmioGridCollapseProgress` keeps its name but returns the animated breakpoint value; `rememberGlasmioHeaderScrollEdge` exposes the still-continuous top-bar material progress.
- Separate preference presentation from persistence and symbol rendering from application resources.
- Provide one root-level SF symbol font provider, a replaceable icon resolver, and a local debug font option for the sample.
- Add an independent interactive sample and documentation for component use, backdrop hosting, and migration.
- Unify standalone glass under the navigation renderer with Regular Small, Regular Medium, Regular Large, and Clear scene presets from Apple's Materials page.
- Preserve the original calibrated navigation defaults and complete outline, side-shadow, inner-shadow and shadow-lift layers; scene overrides remain opt-in.
- Add Home, Materials, Settings, and About tabs, reusable floating search, collapsing titles, progressive edge blur, root-tab fades, and Miuix detail-page transitions.
- Use solid catalog backgrounds and the original light/dark iOS 27 wallpaper in glass demonstrations.
- Match the reference text metrics, tracking, control label weights, and continuous corners.
- Add eleven semantic text styles, 34 emphasis/italic variants, and all twelve Dynamic Type categories.
- Match tall swipe actions, structural separators, reorder handles, Alert field spacing, and Stepper press clipping.
- Return plain Pop-up menus to their live text source, preserve corner tangents on tall menus, and retain four-sided shadows through opening and dismissal.
- Retain source-material shadows and interpolate backdrop frost and tint when glass menus return to their toolbar or button.
- Add compact and expanded text editing menus, real 16 dp selection handles, and a Clear cursor magnifier adapted from AndroidLiquidGlass, with safe-area placement and spring following.
- Match the caret loupe to UIKit's glass implementation: a 115 × 85 dp capsule at 1.25× lifted 75 dp above the finger, a 0.3 low-pass into a damping 0.7 / 0.2 s spring, a two-pass opening, a 0.23 s dismissal that collapses the sampled content towards 0.125×, acceleration-driven body deformation with UIKit's tanh soft clamp and live-bounds leading-edge drift, and a dormant line-break dismissal hook. `GlasmioMagnifier` exposes the timings as `GlasmioLoupeMotion`, `rememberGlasmioLoupeFollow` reuses the follower for other lenses, and `docs/ios-loupe-motion.zh-CN.md` records the recovered parameters. The follower keeps UIKit's model and presentation centres apart, and the deformation differentiates absolute velocity the way `prefersDirectionlessAcceleration` does, so a drag in either direction flattens the body and only the drift mirrors.
- Configure signed Maven artifacts, sources, API documentation, and automated Central publication.
- Apache-2.0 license with third-party notices included in every published module.

MeiloX remains responsible for music playback, product navigation, application settings, and player-specific rendering.

