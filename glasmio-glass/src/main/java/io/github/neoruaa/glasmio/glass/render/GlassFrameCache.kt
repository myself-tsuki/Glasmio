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
import android.view.Choreographer
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

/**
 * When the process-wide glass clock ticks. Pure, so the cadence can be unit tested.
 *
 * Content changes under the glass are not observable from Compose (a scroll moves child render nodes without
 * re-recording the backdrop source), so the clock does not try to detect them per surface. While anything in the
 * app draws, every capped glass surface re-renders on a fixed cadence of capHz; after the last activity it keeps
 * that cadence for [tailNanos] and then stops, so an idle screen does no glass work. Frames drawn only to show new
 * glass images are not activity, otherwise an idle screen would keep the clock running forever.
 */
internal class GlassFrameSchedule {
    private var lastActivity = Never
    private var lastTick = Never

    fun onActivity(nowNanos: Long) {
        lastActivity = nowNanos
    }

    /** The time of the next tick, or [Never] when the clock should stop. */
    fun nextTickAt(capHz: Int): Long {
        if (capHz <= 0 || lastActivity == Never) return Never
        if (lastTick != Never && lastTick >= lastActivity + tailNanos(capHz)) return Never
        return if (lastTick == Never) lastActivity else lastTick + intervalNanos(capHz)
    }

    fun onTick(nowNanos: Long) {
        lastTick = nowNanos
    }

    companion object {
        const val Never = Long.MIN_VALUE

        /** The same 3 ms jitter tolerance as GlasmioFrameGate, so 30 Hz on 60 Hz stays every other vsync. */
        fun intervalNanos(capHz: Int): Long = (1_000_000_000L / capHz - 3_000_000L).coerceAtLeast(0L)

        /** At least 250 ms and three glass frames: a change that lands in a present-only frame is still caught. */
        fun tailNanos(capHz: Int): Long = maxOf(250_000_000L, 3L * 1_000_000_000L / capHz)
    }
}

/**
 * One clock for every capped glass surface in the process.
 *
 * All surfaces render in the same tick, so each window gets one present frame per glass frame and the frames in
 * between are clearly app frames. Activity is collected from every window that hosts capped glass or a backdrop
 * source, so glass in a popup (menus, sheets) keeps refreshing while the content window behind it scrolls.
 */
internal object GlassFrameClock : Choreographer.FrameCallback {
    private class Window : ViewTreeObserver.OnDrawListener {
        var users = 0
        var presentPending = false
        var observer: ViewTreeObserver? = null

        override fun onDraw() {
            if (presentPending) presentPending = false
            else activity()
        }
    }

    private val windows = WeakHashMap<View, Window>()
    private val caches = LinkedHashSet<GlassFrameCache>()
    private val schedule = GlassFrameSchedule()
    private var posted = false

    /** Counts [view]'s window as an activity source until [untrack]; returns the root to untrack. Main thread. */
    fun track(view: View): View {
        val root = view.rootView
        val window = windows.getOrPut(root) { Window() }
        window.users++
        if (window.observer?.isAlive != true) {
            val observer = root.viewTreeObserver
            observer.addOnDrawListener(window)
            window.observer = observer
        }
        return root
    }

    fun untrack(root: View) {
        val window = windows[root] ?: return
        if (--window.users > 0) return
        windows.remove(root)
        val observer = window.observer ?: return
        window.observer = null
        // A listener must not be removed while the observer dispatches it.
        root.post { if (observer.isAlive) observer.removeOnDrawListener(window) }
    }

    fun add(cache: GlassFrameCache) {
        caches += cache
    }

    fun remove(cache: GlassFrameCache) {
        caches -= cache
    }

    /** The next draw of [root] only shows new glass images. */
    fun presented(root: View) {
        windows[root]?.presentPending = true
    }

    /** Something changed (an app frame, a new or resized glass surface): keep the glass clock running. */
    fun activity() {
        schedule.onActivity(System.nanoTime())
        post()
    }

    private fun post() {
        if (posted || caches.isEmpty()) return
        val at = schedule.nextTickAt(GlasmioPerformance.frameRateCap)
        if (at == GlassFrameSchedule.Never) return
        posted = true
        val delayMillis = ((at - System.nanoTime()).coerceAtLeast(0L) + 999_999L) / 1_000_000L
        Choreographer.getInstance().postFrameCallbackDelayed(this, delayMillis)
    }

    override fun doFrame(frameTimeNanos: Long) {
        posted = false
        val now = System.nanoTime()
        val at = schedule.nextTickAt(GlasmioPerformance.frameRateCap)
        if (at == GlassFrameSchedule.Never) return
        if (at <= now + 1_000_000L) {
            schedule.onTick(now)
            for (cache in caches.toList()) cache.render()
        }
        post()
    }
}

/**
 * Renders one glass layer (its whole filter chain: capture, mips, blur, lens, material) off screen at most
 * [GlasmioPerformance.frameRateCap] times per second on the shared [GlassFrameClock], while the window keeps drawing
 * at the panel rate. Between renders the window draws the last image, which is one texture draw instead of the
 * full chain.
 *
 * The off-screen renderer shares the process RenderThread and GPU context with the window, so it sees the same
 * live render nodes; it only runs them less often. Two images stay open (the one shown and the one before it), so a
 * buffer is reused only after the window has drawn a newer one.
 */
internal class GlassFrameCache(
    private val view: View,
    private val requestPresent: () -> Unit,
) {
    private val root = GlassFrameClock.track(view)
    private val handler = Handler(Looper.getMainLooper())
    private val node = RenderNode("GlasmioGlassFrame")
    private var renderer: HardwareRenderer? = null
    private var reader: ImageReader? = null
    private var surfaceSize = IntSize.Zero
    private var source: GraphicsLayer? = null
    private var sourceSize = IntSize.Zero
    private var sizeChangedNanos = 0L
    private var shown: Image? = null
    private var shownBitmap: ImageBitmap? = null
    private var shownSize = IntSize.Zero
    private var retired: Image? = null
    private var released = false

    init {
        GlassFrameClock.add(this)
    }

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
            // Nothing shown yet (first frame, or a resize): keep the clock running so the cache takes over.
            GlassFrameClock.activity()
        }
    }

    /** Called by [GlassFrameClock] on a glass frame. */
    fun render() {
        if (released) return
        val layer = source ?: return
        val size = layer.size
        if (size.width <= 0 || size.height <= 0) return
        // Mid-resize every image would arrive with a stale size and be thrown away; the live layer is drawn instead.
        val cap = GlasmioPerformance.frameRateCap
        if (cap > 0 && System.nanoTime() - sizeChangedNanos < 1_000_000_000L / cap) {
            GlassFrameClock.activity()
            return
        }
        val renderer = ensureSurface(size)
        node.setPosition(0, 0, size.width, size.height)
        val canvas = node.beginRecording(size.width, size.height)
        try {
            CanvasDrawScope().draw(Density(view.resources.displayMetrics.density), LayoutDirection.Ltr,
                Canvas(canvas), size.toSize()) { drawLayer(layer) }
        } finally {
            node.endRecording()
        }
        try {
            renderer.createRenderRequest().setWaitForPresent(false).syncAndDraw()
        } catch (_: RuntimeException) {
            // A surface lost between frames; the next glass frame re-creates it.
            releaseSurface()
        }
    }

    private fun ensureSurface(size: IntSize): HardwareRenderer {
        val current = renderer
        if (current != null && surfaceSize == size) return current
        releaseSurface()
        val reader = ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, MaxImages,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val imageSize = size
        reader.setOnImageAvailableListener({ onImage(it, imageSize) }, handler)
        val created = HardwareRenderer().apply {
            setSurface(reader.surface)
            setContentRoot(node)
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
        GlassFrameClock.presented(root)
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
        node.discardDisplayList()
    }

    fun release() {
        if (released) return
        released = true
        GlassFrameClock.remove(this)
        GlassFrameClock.untrack(root)
        releaseSurface()
        source = null
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
