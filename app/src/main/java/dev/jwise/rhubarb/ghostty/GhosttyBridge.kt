// Adapted from chuchu (https://github.com/jossephus/chuchu, MIT; see native/LICENSE-chuchu).
package dev.jwise.rhubarb.ghostty

import java.nio.ByteBuffer

class GhosttyBridge {
    companion object {
        private val loadError: Throwable? = runCatching {
            System.loadLibrary("rexterm")
        }.exceptionOrNull()
    }

    fun nativeStatus(): String {
        return if (loadError == null) {
            "loaded"
        } else {
            val message = loadError.message?.takeIf { it.isNotBlank() } ?: "unknown"
            "not loaded (${loadError::class.simpleName}: $message)"
        }
    }

    fun isLoaded(): Boolean = loadError == null

    external fun nativeVersion(): String
    external fun nativeCreate(cols: Int, rows: Int, maxScrollback: Int): Long
    external fun nativeDestroy(handle: Long)
    /** A terminal restored from a Ghostty binary snapshot, or 0 if it can't be decoded. */
    external fun nativeRestoreSnapshot(data: ByteArray): Long
    external fun nativeWriteRemote(handle: Long, data: ByteArray)
    external fun nativeResize(handle: Long, cols: Int, rows: Int, cellW: Int, cellH: Int)
    external fun nativeScroll(handle: Long, delta: Int, x: Float, y: Float)
    external fun nativeScrollToActive(handle: Long)
    external fun nativeSnapshot(handle: Long): ByteBuffer
    external fun nativePollTitle(handle: Long): String?
    external fun nativePollPwd(handle: Long): String?
    external fun nativePollClipboard(handle: Long): ByteArray?
    external fun nativeDrainBellCount(handle: Long): Int
    external fun nativeSetColorScheme(handle: Long, scheme: Int)
    external fun nativeSetDefaultColors(
        handle: Long,
        fgRgb: IntArray?,
        bgRgb: IntArray?,
        cursorRgb: IntArray?,
        paletteRgb: ByteArray?,
    )
    external fun nativeEncodeKey(handle: Long, key: Int, cp: Int, mods: Int, action: Int, utf8: String?): ByteArray?
    external fun nativeEncodePaste(handle: Long, data: String): ByteArray?
    external fun nativeSetMouseEncodingSize(
        handle: Long,
        screenWidth: Int,
        screenHeight: Int,
        cellWidth: Int,
        cellHeight: Int,
        paddingTop: Int,
        paddingBottom: Int,
        paddingLeft: Int,
        paddingRight: Int,
    )
    external fun nativeEncodeMouse(
        handle: Long,
        action: Int,
        button: Int,
        mods: Int,
        x: Float,
        y: Float,
        anyButtonPressed: Boolean,
        trackLastCell: Boolean,
    ): ByteArray?
    external fun nativeEncodeFocus(handle: Long, focused: Boolean): ByteArray?
    external fun nativeDrainPtyWrites(handle: Long): ByteArray
    external fun nativeSnapshotImages(handle: Long): ByteBuffer
    external fun nativeIsImageLoading(handle: Long): Boolean
    external fun nativeFormatSelectionRange(handle: Long, startCell: Int, endCell: Int): String?
    external fun nativeFormatSelectionScreenRange(handle: Long, startScreenCell: Int, endScreenCell: Int): String?
    external fun nativeSelectWordAt(handle: Long, cellX: Int, cellY: Int): String?
    external fun nativeSelectLineAt(handle: Long, cellX: Int, cellY: Int): String?
    external fun nativeSelectAll(handle: Long): String?
    /** Matches for [needle], newest first: [startY, startX, endY, endX] per match, screen coordinates. */
    external fun nativeSearch(handle: Long, needle: String, max: Int): IntArray?
    /** Scroll the viewport so screen row [screenY] sits [rowsAbove] rows from the top. */
    external fun nativeScrollToRow(handle: Long, screenY: Int, rowsAbove: Int)
}
