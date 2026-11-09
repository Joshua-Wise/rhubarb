package dev.jwise.rhubarb.ghostty

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * One Ghostty terminal fed by Rex's data stream instead of a local PTY.
 *
 * Rex runs the real terminal on the Mac; this is a mirror of it. So bytes Ghostty wants to
 * send back after server output (query replies such as device attributes) are dropped, because
 * the Mac's terminal already answered. Bytes produced by a local gesture (wheel events from
 * [scroll]) are input and are returned to the caller to send to the Mac.
 *
 * All native calls run on one thread; snapshots for drawing are published at most ~60 times a second.
 */
class GhosttyTerminal(private val scope: CoroutineScope) {
    private val bridge = GhosttyBridge()
    private val dispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "ghostty").apply { isDaemon = true } }.asCoroutineDispatcher()
    private var handle = 0L
    private var cols = 80
    private var rows = 24
    private var cellW = 8
    private var cellH = 16
    private var colors: Colors? = null
    private var snapshotJob: Job? = null
    @Volatile private var closed = false

    private val _snapshot = MutableStateFlow<TerminalSnapshot?>(null)
    val snapshot: StateFlow<TerminalSnapshot?> = _snapshot

    val available: Boolean get() = bridge.isLoaded()
    val status: String get() = bridge.nativeStatus()

    class Colors(val fg: IntArray, val bg: IntArray, val cursor: IntArray, val palette: ByteArray)

    private fun ensure() {
        if (handle != 0L || closed) return
        handle = bridge.nativeCreate(cols, rows, 32 * 1024 * 1024)
        if (handle == 0L) return
        bridge.nativeResize(handle, cols, rows, cellW, cellH)
        colors?.let { bridge.nativeSetDefaultColors(handle, it.fg, it.bg, it.cursor, it.palette) }
    }

    private fun run(block: () -> Unit) {
        if (!available || closed) return
        scope.launch(dispatcher) { ensure(); if (handle != 0L) block() }
    }

    /** Start over with an empty screen (a fresh stream follows). */
    fun reset() = run {
        bridge.nativeDestroy(handle); handle = 0L
        ensure(); publishSoon()
    }

    /**
     * Replace the terminal with one restored from Rex's Ghostty snapshot: the Mac's exact screen,
     * modes and scrollback. Returns false (keeping the current terminal) if it can't be decoded.
     */
    suspend fun restore(snapshot: ByteArray): Boolean {
        if (!available || closed) return false
        return withContext(dispatcher) {
            val h = runCatching { bridge.nativeRestoreSnapshot(snapshot) }.getOrDefault(0L)
            if (h == 0L || closed) { if (h != 0L) bridge.nativeDestroy(h); return@withContext false }
            if (handle != 0L) bridge.nativeDestroy(handle)
            handle = h
            colors?.let { bridge.nativeSetDefaultColors(handle, it.fg, it.bg, it.cursor, it.palette) }
            publishSoon()
            true
        }
    }

    /** Output from the Mac's terminal. */
    fun write(bytes: ByteArray) = run {
        bridge.nativeWriteRemote(handle, bytes)
        bridge.nativeDrainPtyWrites(handle) // replies to the program: the Mac already sent them
        publishSoon()
    }

    /** The Mac terminal's grid. [cellWpx]/[cellHpx] size mouse coordinates; use the drawn cell size. */
    fun resize(cols: Int, rows: Int, cellWpx: Int = cellW, cellHpx: Int = cellH) = run {
        this.cols = cols.coerceAtLeast(1); this.rows = rows.coerceAtLeast(1)
        cellW = cellWpx.coerceAtLeast(1); cellH = cellHpx.coerceAtLeast(1)
        bridge.nativeResize(handle, this.cols, this.rows, cellW, cellH)
        publishSoon()
    }

    fun setColors(c: Colors) {
        colors = c
        run { bridge.nativeSetDefaultColors(handle, c.fg, c.bg, c.cursor, c.palette); publishSoon() }
    }

    /**
     * Scroll by [delta] lines (negative = back in history) at pixel ([x], [y]). Full-screen and
     * mouse-reporting programs get wheel events, returned here to send to the Mac; otherwise the
     * local scrollback moves and this returns null.
     */
    suspend fun scroll(delta: Int, x: Float, y: Float): ByteArray? {
        if (!available || closed) return null
        return withContext(dispatcher) {
            ensure()
            if (handle == 0L) return@withContext null
            bridge.nativeDrainPtyWrites(handle)
            bridge.nativeScroll(handle, delta, x, y)
            val out = bridge.nativeDrainPtyWrites(handle)
            publishSoon()
            out.takeIf { it.isNotEmpty() }
        }
    }

    /** Matches for [query] on screen and in scrollback, oldest first (empty if none). */
    suspend fun search(query: String, max: Int = 2000): List<SearchMatch> {
        if (!available || closed || query.isEmpty()) return emptyList()
        return withContext(dispatcher) {
            if (handle == 0L) return@withContext emptyList()
            val a = bridge.nativeSearch(handle, query, max) ?: return@withContext emptyList()
            List(a.size / 4) { i -> SearchMatch(a[i * 4], a[i * 4 + 1], a[i * 4 + 2], a[i * 4 + 3]) }.asReversed()
        }
    }

    /** Scroll so [m] sits a little below the middle of the grid, visible even when the phone shows only its bottom part. */
    fun reveal(m: SearchMatch) = run { bridge.nativeScrollToRow(handle, m.startY, rows * 3 / 5); publishSoon() }

    /** Return to live output (e.g. after typing while scrolled back). */
    fun scrollToBottom() = run { bridge.nativeScrollToActive(handle); publishSoon() }

    private fun publishSoon() {
        if (snapshotJob?.isActive == true) return
        snapshotJob = scope.launch(dispatcher) {
            delay(16)
            if (handle == 0L || closed) return@launch
            val raw = bridge.nativeSnapshot(handle)
            val images = TerminalSnapshot.parseImages(bridge.nativeSnapshotImages(handle))
            _snapshot.value = TerminalSnapshot.fromByteBuffer(raw, images)
        }
    }

    fun close() {
        closed = true
        scope.launch(dispatcher) {
            if (handle != 0L) bridge.nativeDestroy(handle)
            handle = 0L
        }.invokeOnCompletion { dispatcher.close() }
    }

    companion object {
        private fun rgb(hex: String): IntArray {
            val v = hex.removePrefix("#").toInt(16)
            return intArrayOf((v shr 16) and 0xFF, (v shr 8) and 0xFF, v and 0xFF)
        }

        /** Default colors plus a full 256-color palette: the 16 given ANSI colors, then xterm's cube and grays. */
        fun colors(fg: String, bg: String, cursor: String, ansi16: List<String>): Colors {
            val pal = ByteArray(256 * 3)
            fun put(i: Int, r: Int, g: Int, b: Int) { pal[i * 3] = r.toByte(); pal[i * 3 + 1] = g.toByte(); pal[i * 3 + 2] = b.toByte() }
            ansi16.take(16).forEachIndexed { i, h -> val c = rgb(h); put(i, c[0], c[1], c[2]) }
            val steps = intArrayOf(0, 95, 135, 175, 215, 255)
            for (i in 16 until 232) { val n = i - 16; put(i, steps[n / 36], steps[(n / 6) % 6], steps[n % 6]) }
            for (i in 232 until 256) { val v = 8 + (i - 232) * 10; put(i, v, v, v) }
            return Colors(rgb(fg), rgb(bg), rgb(cursor), pal)
        }
    }
}
