package io.github.neoruaa.glasmio.glass.render

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import io.github.neoruaa.glasmio.core.GlasmioPerformance
import java.util.WeakHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Decides when a capped glass surface renders again. Pure, so the cadence can be unit tested.
 *
 * Damage is not observable from Compose: content scrolling under the glass moves child render nodes without
 * re-recording the backdrop source. The window drawing is the damage signal instead. A frame the window drew for
 * anything else marks the glass dirty; a frame drawn only to present a new glass image does not, except once after
 * each burst ([settled]), so a change that landed in the same frame as a present is still picked up.
 */
internal class GlassFrameCadence {
    var dirty = false
        private set
    private var settled = true
    private var lastRenderNanos = Long.MIN_VALUE

    /** Records a window draw. Returns true when a render should be scheduled. */
    fun onWindowDraw(presentOnly: Boolean): Boolean {
        if (presentOnly) {
            if (settled) return false
            settled = true
        } else settled = false
        dirty = true
        return true
    }

    /** Nanoseconds to wait before the next render may start; 0 when one may start now. */
    fun waitNanos(nowNanos: Long, capHz: Int): Long {
        if (capHz <= 0 || lastRenderNanos == Long.MIN_VALUE) return 0L
        // The same 3 ms jitter tolerance as GlasmioFrameGate, so 30 Hz on 60 Hz stays every other vsync.
        val interval = 1_000_000_000L / capHz - 3_000_000L
        return (lastRenderNanos + interval - nowNanos).coerceAtLeast(0L)
    }

    fun onRendered(nowNanos: Long) {
        dirty = false
        lastRenderNanos = nowNanos
    }

    fun reset() {
        dirty = false
        settled = true
        lastRenderNanos = Long.MIN_VALUE
    }
}

/**
 * One draw listener per window. Glass images presented in a frame mark that frame as present-only, so caches in
 * the same window do not keep re-rendering each other.
 */
private class GlassFrameWindow(val root: View) : ViewTreeObserver.OnDrawListener {
    val caches = LinkedHashSet<GlassFrameCache>()
    var presentPending = false
    private var listening = false

    fun add(cache: GlassFrameCache) {
        caches += cache
        if (!listening) {
            root.viewTreeObserver.addOnDrawListener(this)
            listening = true
        }
    }

    fun remove(cache: GlassFrameCache) {
        caches -= cache
        if (caches.isEmpty() && listening) {
            listening = false
            // A listener must not be removed while the observer dispatches it.
            root.post { if (caches.isEmpty()) root.viewTreeObserver.takeIf { it.isAlive }?.removeOnDrawListener(this) }
            Windows.remove(root)
        }
    }

    override fun onDraw() {
        val presentOnly = presentPending
        presentPending = false
        for (cache in caches.toList()) cache.onWindowDraw(presentOnly)
    }

    companion object {
        val Windows = WeakHashMap<View, GlassFrameWindow>()
        fun of(view: View): GlassFrameWindow {
            val root = view.rootView
            return Windows.getOrPut(root) { GlassFrameWindow(root) }
        }
    }
}

/**
 * Renders one glass layer (its whole filter chain: capture, mips, blur, lens, material) off screen at most
 * [GlasmioPerformance.frameRateCap] times per second, while the window keeps drawing at the panel rate. Between
 * renders the window draws the last image, which is one texture draw instead of the full chain.
 *
 * The off-screen renderer shares the process RenderThread and GPU context with the window, so it sees the same
 * live render nodes; it only runs them less often. Two images stay open (the one shown and the one before it), so a
 * buffer is reused only after the window has drawn a newer one.
 */
internal class GlassFrameCache(
    private val view: View,
    private val scope: CoroutineScope,
    private val requestPresent: () -> Unit,
) {
    private val window = GlassFrameWindow.of(view).also { it.add(this) }
    private val cadence = GlassFrameCadence()
    private val handler = Handler(Looper.getMainLooper())
    private val root = RenderNode("GlasmioGlassFrame")
    private var renderer: HardwareRenderer? = null
    private var reader: ImageReader? = null
    private var surfaceSize = IntSize.Zero
    private var renderedSize = IntSize.Zero
    private var source: GraphicsLayer? = null
    private var sourceSize = IntSize.Zero
    private var sizeChangedNanos = 0L
    private var shown: Image? = null
    private var shownBitmap: ImageBitmap? = null
    private var shownSize = IntSize.Zero
    private var retired: Image? = null
    private var job: Job? = null
    private var released = false

    /** Draws the cached image of [layer] when it matches the layer's current size, else the live layer. */
    fun draw(drawScope: DrawScope, layer: GraphicsLayer) {
        source = layer
        if (layer.size != sourceSize) {
            sourceSize = layer.size
            sizeChangedNanos = System.nanoTime()
        }
        val bitmap = shownBitmap
        if (bitmap != null && shownSize == layer.size) drawScope.drawImage(bitmap)
        else {
            drawScope.drawLayer(layer)
            // Nothing shown yet (first frame, or a resize): queue a render so the cache takes over.
            if (cadence.onWindowDraw(presentOnly = false)) schedule()
        }
    }

    fun onWindowDraw(presentOnly: Boolean) {
        if (cadence.onWindowDraw(presentOnly)) schedule()
    }

    private fun schedule() {
        if (released || job?.isActive == true) return
        job = scope.launch {
            while (!released && cadence.dirty) {
                val cap = GlasmioPerformance.frameRateCap
                val wait = cadence.waitNanos(System.nanoTime(), cap)
                if (wait > 0L) delay((wait + 999_999L) / 1_000_000L)
                if (released || !cadence.dirty) break
                // Mid-resize, every image would arrive with a stale size and be thrown away; let the size settle.
                val stable = System.nanoTime() - sizeChangedNanos
                val minimum = if (cap > 0) 1_000_000_000L / cap else 0L
                if (stable < minimum) { delay((minimum - stable + 999_999L) / 1_000_000L); continue }
                render()
                cadence.onRendered(System.nanoTime())
            }
        }
    }

    private fun render() {
        val layer = source ?: return
        val size = layer.size
        if (size.width <= 0 || size.height <= 0) return
        val renderer = ensureSurface(size) ?: return
        root.setPosition(0, 0, size.width, size.height)
        val canvas = root.beginRecording(size.width, size.height)
        try {
            CanvasDrawScope().draw(Density(view.resources.displayMetrics.density), LayoutDirection.Ltr,
                Canvas(canvas), size.toSize()) { drawLayer(layer) }
        } finally {
            root.endRecording()
        }
        renderedSize = size
        try {
            renderer.createRenderRequest().setWaitForPresent(false).syncAndDraw()
        } catch (_: RuntimeException) {
            // A surface lost between frames; the next window draw re-creates it.
            releaseSurface()
        }
    }

    private fun ensureSurface(size: IntSize): HardwareRenderer? {
        val current = renderer
        if (current != null && surfaceSize == size) return current
        releaseSurface()
        val reader = ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, MaxImages,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val imageSize = size
        reader.setOnImageAvailableListener({ onImage(it, imageSize) }, handler)
        val created = HardwareRenderer().apply {
            setSurface(reader.surface)
            setContentRoot(root)
            isOpaque = false
        }
        this.reader = reader
        this.renderer = created
        surfaceSize = size
        return created
    }

    private fun onImage(reader: ImageReader, size: IntSize) {
        if (released || reader !== this.reader) return
        val image = try { reader.acquireLatestImage() } catch (_: IllegalStateException) { null } ?: return
        val buffer = image.hardwareBuffer
        val bitmap = buffer?.let { Bitmap.wrapHardwareBuffer(it, ColorSpace.get(ColorSpace.Named.SRGB)) }
        buffer?.close()
        if (bitmap == null) { image.close(); return }
        retired?.close()
        retired = shown
        shown = image
        shownBitmap = bitmap.asImageBitmap()
        shownSize = size
        window.presentPending = true
        requestPresent()
    }

    private fun releaseSurface() {
        shownBitmap = null
        shownSize = IntSize.Zero
        retired?.close(); retired = null
        shown?.close(); shown = null
        renderer?.destroy(); renderer = null
        reader?.close(); reader = null
        surfaceSize = IntSize.Zero
        root.discardDisplayList()
    }

    fun release() {
        if (released) return
        released = true
        job?.cancel(); job = null
        window.remove(this)
        releaseSurface()
        source = null
        cadence.reset()
    }

    companion object {
        private const val MaxImages = 4

        /**
         * Whether a surface in [view] should render through the cache: a positive cap below the panel's refresh
         * rate, hardware drawing, and plain sRGB output (wide-gamut and HDR windows keep live rendering).
         */
        fun applies(view: View, hardware: Boolean, colorManaged: Boolean, headroom: Float): Boolean {
            val cap = GlasmioPerformance.frameRateCap
            if (cap <= 0 || !hardware || colorManaged || headroom != 1f) return false
            val refresh = view.display?.refreshRate ?: return false
            return cap < refresh - 1f
        }
    }
}
