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
import android.os.HandlerThread
import android.os.Looper
import android.view.Choreographer
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import io.github.neoruaa.glasmio.core.GlasmioPerformance
import java.util.WeakHashMap
import kotlin.math.ceil

/**
 * When the process-wide glass clock ticks. Pure, so the cadence can be unit tested.
 *
 * Content changes under the glass are not observable from Compose (a scroll moves child render nodes without
 * re-recording the backdrop source), so the clock does not try to detect them per source. While a window that hosts
 * a backdrop source draws, every active snapshot re-renders on a fixed cadence of capHz; after the last activity it
 * keeps that cadence for [tailNanos] and then stops, so an idle screen does no glass work. Frames drawn only to show
 * new snapshots are not activity, otherwise an idle screen would keep the clock running forever.
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
 * One clock for every backdrop snapshot in the process.
 *
 * All snapshots render in the same tick, so each window gets one present frame per glass frame and the frames in
 * between are clearly app frames. Activity comes from the windows that host backdrop sources: a snapshot shows the
 * source's content, so only a draw of the source's window can change it. Glass in a popup (menus, sheets) samples
 * the snapshot of the content window behind it and refreshes while that window scrolls.
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
    private val snapshots = LinkedHashSet<BackdropSnapshot>()
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

    fun add(snapshot: BackdropSnapshot) {
        snapshots += snapshot
    }

    fun remove(snapshot: BackdropSnapshot) {
        snapshots -= snapshot
    }

    /** The next draw of [root] only shows new snapshots. */
    fun presented(root: View) {
        windows[root]?.presentPending = true
    }

    /** Something changed (an app frame, a new or resized source): keep the glass clock running. */
    fun activity() {
        schedule.onActivity(System.nanoTime())
        post()
    }

    private fun post() {
        if (posted || snapshots.isEmpty()) return
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
            for (snapshot in snapshots.toList()) snapshot.render()
        }
        post()
    }
}

/**
 * Lifetime rules for a snapshot's off-screen renderer. Pure, so they can be unit tested.
 *
 * While glass shows a snapshot, the off-screen renderer's root display list is the only parent of the backdrop
 * source layer's render node (the window draws the source's content directly, not that layer). HWUI counts parents
 * per display list: destroying the renderer drops that parent, and HWUI deletes the source layer's display list.
 * So a renderer lives as long as its snapshot, a resize only swaps its output surface, and destruction waits until
 * the window has committed a frame in which the glass draws the live layer again (or the source is gone).
 */
internal object GlassFrameRendererLifetime {
    enum class SurfaceAction { Keep, Create, Swap }

    fun surfaceAction(hasRenderer: Boolean, surfaceWidth: Int, surfaceHeight: Int, width: Int, height: Int): SurfaceAction =
        when {
            !hasRenderer -> SurfaceAction.Create
            surfaceWidth != width || surfaceHeight != height -> SurfaceAction.Swap
            else -> SurfaceAction.Keep
        }

    /** How long a released renderer waits for the window to commit a frame before it is destroyed anyway. */
    const val ReleaseFallbackMillis = 1_000L
}

/** Runs [action] once, however many of its triggers (frame commit, fallback timer) fire. */
internal class RunOnce(private val action: () -> Unit) : Runnable {
    private var done = false
    val hasRun: Boolean get() = done

    override fun run() {
        if (done) return
        done = true
        action()
    }
}

/**
 * Which glass draw is capped. [DrawBackdropNode] sets [view] around its own backdrop recording (main thread,
 * synchronous), so backdrops reached from that recording ([LayerBackdrop], cross-window backdrops) draw their
 * source's snapshot instead of the live source layer.
 */
internal object CappedGlassDraw {
    @JvmField var view: View? = null

    inline fun <T> with(view: View?, block: () -> T): T {
        val previous = this.view
        this.view = view
        try {
            return block()
        } finally {
            this.view = previous
        }
    }
}

/**
 * Renders one backdrop source's content (the plain page content glass samples, never a glass filter chain) off
 * screen at most [GlasmioPerformance.frameRateCap] times per second on the shared [GlassFrameClock], at the backdrop
 * resolution setting. Capped glass draws the latest image instead of the live source layer, so its blur, lens and
 * material chain only runs again when a new image arrives, while the window keeps the panel rate.
 *
 * Only content render nodes enter this renderer. The glass layers, their render effects, runtime shaders and atlas
 * layers stay in the window's renderer, which is the path HWUI is built for. One snapshot serves every glass surface
 * that samples the same source (navigation bar, pills, menus over it), so a screen has one or two of these renderers.
 *
 * Two images stay open (the one shown and the one before it), so a buffer is reused only after the glass has drawn a
 * newer one. Finished images leave their queue on [GlassFrameImageThread], never on the main thread, so the
 * RenderThread can always get a free buffer. See [GlassFrameRendererLifetime] for the renderer's lifetime.
 */
internal class BackdropSnapshot(
    private val layer: GraphicsLayer,
    /** The source window: tracked for activity by the source node, marked present-only after a new image. */
    private val sourceRoot: () -> View?,
    /** Whether the source node is still attached; a detached source is never rendered. */
    private val sourceAttached: () -> Boolean,
    /** Tells every consumer to record its glass again with the new image. */
    private val onPresent: () -> Unit,
    private val onReleased: (BackdropSnapshot) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val node = RenderNode("GlasmioBackdropSnapshot")
    private var renderer: HardwareRenderer? = null
    // Read on the image thread, so the reader that is current and the released flag are volatile.
    @Volatile private var reader: ImageReader? = null
    private var surfaceSize = IntSize.Zero
    private var readerLayerSize = IntSize.Zero
    private var layerSize = IntSize.Zero
    private var sizeChangedNanos = 0L
    private var lastConsumedNanos = System.nanoTime()
    private var shown: Image? = null
    private var shownBitmap: ImageBitmap? = null
    private var shownLayerSize = IntSize.Zero
    private var retired: Image? = null
    private val staleReaders = ArrayList<ImageReader>(2)
    @Volatile private var released = false

    // Hand-off from the image thread to the main thread, guarded by [lock]. [incoming] was never shown, so the image
    // thread may close it when a newer image arrives.
    private val lock = Any()
    private var incoming: Image? = null
    private var incomingBitmap: ImageBitmap? = null
    private var incomingLayerSize = IntSize.Zero
    private var promotePosted = false
    private val promote = Runnable { promoteIncoming() }

    init {
        GlassFrameClock.add(this)
    }

    /** Glass sampled this snapshot within the last [ActiveNanos]; otherwise it is not rendered. */
    val isActive: Boolean get() = !released && System.nanoTime() - lastConsumedNanos < ActiveNanos

    /**
     * Draws the latest image of the source at the source layer's size and returns true, or returns false when there
     * is none for the current size yet (first frame, mid-resize) so the caller draws the live layer.
     */
    fun draw(drawScope: DrawScope): Boolean {
        lastConsumedNanos = System.nanoTime()
        val size = layer.size
        if (size != layerSize) {
            layerSize = size
            sizeChangedNanos = lastConsumedNanos
        }
        val bitmap = shownBitmap
        if (bitmap != null && shownLayerSize == size) {
            drawScope.drawImage(bitmap, srcOffset = IntOffset.Zero, srcSize = IntSize(bitmap.width, bitmap.height),
                dstOffset = layer.topLeft, dstSize = size, filterQuality = FilterQuality.Low)
            return true
        }
        // Nothing to show yet: keep the clock running so the snapshot takes over.
        GlassFrameClock.activity()
        return false
    }

    /** Glass drew the live layer (cap off, wide-gamut or HDR window). Stops once no glass has used the snapshot. */
    fun liveDrawn() {
        if (System.nanoTime() - lastConsumedNanos > IdleReleaseNanos) release()
    }

    /** Called by [GlassFrameClock] on a glass frame. */
    fun render() {
        if (released) return
        if (!sourceAttached()) return
        if (!isActive) {
            // No glass has sampled this source for a while: free the renderer; glass that comes back starts a new one.
            if (System.nanoTime() - lastConsumedNanos > IdleReleaseNanos) release()
            return
        }
        val size = layer.size
        if (size.width <= 0 || size.height <= 0) return
        // Mid-resize every image would arrive with a stale size and be thrown away; glass draws the live layer instead.
        val cap = GlasmioPerformance.frameRateCap
        if (cap > 0 && System.nanoTime() - sizeChangedNanos < 1_000_000_000L / cap) {
            GlassFrameClock.activity()
            return
        }
        val scale = GlasmioPerformance.sampling(1f).coerceIn(MinimumScale, 1f)
        val target = IntSize(ceil(size.width * scale).toInt().coerceAtLeast(1),
            ceil(size.height * scale).toInt().coerceAtLeast(1))
        // Readers replaced on an earlier glass frame: the renderer has moved to the new surface by now.
        closeStaleReaders()
        val renderer = ensureSurface(target, size)
        node.setPosition(0, 0, target.width, target.height)
        // Re-recording keeps the source layer parented: HWUI adds the new display list's children before it drops
        // the old one's, so the layer's node never reaches zero parents between glass frames.
        val canvas = node.beginRecording(target.width, target.height)
        try {
            canvas.scale(target.width / size.width.toFloat(), target.height / size.height.toFloat())
            canvas.translate(-layer.topLeft.x.toFloat(), -layer.topLeft.y.toFloat())
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(canvas), size.toSize()) { drawLayer(layer) }
        } finally {
            node.endRecording()
        }
        try {
            renderer.createRenderRequest().setWaitForPresent(false).syncAndDraw()
        } catch (_: RuntimeException) {
            // The output surface was lost between frames. Keep the renderer (destroying it would delete the source
            // layer's display list); the next glass frame gives it a new surface and only then retires this reader,
            // so a reader is never closed while the renderer still draws into it.
            surfaceSize = IntSize.Zero
        }
    }

    private fun ensureSurface(target: IntSize, size: IntSize): HardwareRenderer {
        val current = renderer
        val action = GlassFrameRendererLifetime.surfaceAction(current != null, surfaceSize.width, surfaceSize.height,
            target.width, target.height)
        if (action == GlassFrameRendererLifetime.SurfaceAction.Keep && current != null && readerLayerSize == size) {
            return current
        }
        val reader = ImageReader.newInstance(target.width, target.height, PixelFormat.RGBA_8888, MaxImages,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val imageLayerSize = size
        // Images leave the queue on their own thread, never the main thread: the RenderThread waits for a free buffer
        // while it draws, and the main thread waits for the RenderThread, so a buffer the main thread had to free
        // could never be freed (input-dispatch ANR).
        reader.setOnImageAvailableListener({ onImage(it, imageLayerSize) }, GlassFrameImageThread.handler)
        // A resize swaps only the output surface: the renderer and its root display list stay alive.
        retireReader()
        val renderer = current ?: HardwareRenderer().apply {
            setContentRoot(node)
            isOpaque = false
        }
        renderer.setSurface(reader.surface)
        this.reader = reader
        this.renderer = renderer
        surfaceSize = target
        readerLayerSize = size
        return renderer
    }

    /** Stops showing images of the current reader and closes it on the next glass frame. */
    private fun retireReader() {
        shownBitmap = null
        shownLayerSize = IntSize.Zero
        retired?.close(); retired = null
        shown?.close(); shown = null
        synchronized(lock) {
            reader?.let { staleReaders += it }
            reader = null
            incoming?.close(); incoming = null; incomingBitmap = null
        }
        surfaceSize = IntSize.Zero
        readerLayerSize = IntSize.Zero
    }

    private fun closeStaleReaders() {
        if (staleReaders.isEmpty()) return
        staleReaders.forEach { it.close() }
        staleReaders.clear()
    }

    /** Image thread. Always takes the image off the queue, so the RenderThread never waits on the main thread. */
    private fun onImage(reader: ImageReader, size: IntSize) {
        val image = try { reader.acquireLatestImage() } catch (_: IllegalStateException) { null } ?: return
        if (released || reader !== this.reader) { image.close(); return }
        val buffer = image.hardwareBuffer
        val bitmap = try {
            buffer?.let { Bitmap.wrapHardwareBuffer(it, ColorSpace.get(ColorSpace.Named.SRGB)) }
        } finally {
            buffer?.close()
        }
        if (bitmap == null) { image.close(); return }
        val post: Boolean
        synchronized(lock) {
            if (released || reader !== this.reader) { image.close(); return }
            incoming?.close()
            incoming = image
            incomingBitmap = bitmap.asImageBitmap()
            incomingLayerSize = size
            post = !promotePosted
            promotePosted = true
        }
        if (post) handler.post(promote)
    }

    /** Main thread: shows the newest finished image and keeps the one before it open until the glass draws again. */
    private fun promoteIncoming() {
        val image: Image
        val bitmap: ImageBitmap
        val size: IntSize
        synchronized(lock) {
            promotePosted = false
            image = incoming ?: return
            bitmap = incomingBitmap ?: run { image.close(); incoming = null; return }
            size = incomingLayerSize
            incoming = null
            incomingBitmap = null
        }
        if (released) { image.close(); return }
        retired?.close()
        retired = shown
        shown = image
        shownBitmap = bitmap
        shownLayerSize = size
        sourceRoot()?.let(GlassFrameClock::presented)
        onPresent()
    }

    /**
     * Stops the snapshot. Glass draws the live layer from its next frame on (or the source is gone), so the renderer
     * is destroyed only after the source window has committed a frame, when glass in the window's own tree holds the
     * source layer again. Destroying it now could drop the layer's last parent and delete its display list.
     */
    fun release() {
        if (released) return
        released = true
        handler.removeCallbacks(promote)
        GlassFrameClock.remove(this)
        retireReader()
        onReleased(this)
        val renderer = renderer ?: run { closeStaleReaders(); return }
        this.renderer = null
        val destroy = RunOnce {
            renderer.destroy()
            node.discardDisplayList()
            closeStaleReaders()
        }
        val observer = sourceRoot()?.viewTreeObserver
        if (observer?.isAlive == true) observer.registerFrameCommitCallback(destroy)
        handler.postDelayed(destroy, GlassFrameRendererLifetime.ReleaseFallbackMillis)
    }

    companion object {
        // The image shown, the one before it and one in flight.
        private const val MaxImages = 3
        private const val MinimumScale = 0.25f
        private const val ActiveNanos = 1_000_000_000L
        private const val IdleReleaseNanos = 5_000_000_000L

        /**
         * Whether glass in [view] should sample snapshots: a positive cap below the panel's refresh rate, hardware
         * drawing, and plain sRGB output (wide-gamut and HDR windows keep live sampling).
         */
        fun applies(view: View, hardware: Boolean, colorManaged: Boolean, headroom: Float): Boolean {
            val cap = GlasmioPerformance.frameRateCap
            if (cap <= 0 || !hardware || colorManaged || headroom != 1f) return false
            val refresh = view.display?.refreshRate ?: return false
            return cap < refresh - 1f
        }
    }
}

/** One process-wide thread that takes finished snapshot images off their queues, independent of the main thread. */
private object GlassFrameImageThread {
    val handler: Handler by lazy {
        val thread = HandlerThread("GlasmioGlassFrames")
        thread.start()
        Handler(thread.looper)
    }
}
