package com.wiselabs.rhubarb.ui

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

/**
 * An invisible view that owns the soft keyboard and turns everything typed into terminal
 * bytes, the way a terminal emulator does (no text field in between).
 *
 * The keyboard is asked for a "visible password" field with no suggestions, which stops Gboard
 * from autocorrecting or holding text in a composing region. Some keyboards compose anyway, so
 * composing text is diffed and sent as it changes.
 */
class TermInputView(context: Context, private val send: (String) -> Unit) : View(context) {
    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun showKeyboard() {
        requestFocus()
        val imm = context.getSystemService(InputMethodManager::class.java)
        post { imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT) }
    }

    fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            private var composing = ""

            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                val t = text.toString()
                // The committed text replaces whatever was composing.
                if (composing.isNotEmpty() && t.startsWith(composing)) emit(t.substring(composing.length))
                else { erase(composing.length); emit(t) }
                composing = ""
                return true
            }

            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                val t = text.toString()
                val common = composing.commonPrefixWith(t).length
                erase(composing.length - common)
                emit(t.substring(common))
                composing = t
                return true
            }

            override fun finishComposingText(): Boolean { composing = ""; return true }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                erase(beforeLength); return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) handleKey(event)
                return true
            }

            private fun erase(n: Int) { repeat(n.coerceAtLeast(0)) { send("\u007f") } }
            private fun emit(s: String) { if (s.isNotEmpty()) send(s.replace("\n", "\r")) }
        }
    }

    // Hardware keyboards (and some soft-keyboard keys) arrive as key events.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = handleKey(event) || super.onKeyDown(keyCode, event)

    private fun handleKey(e: KeyEvent): Boolean {
        val esc = "\u001b"
        val seq = when (e.keyCode) {
            KeyEvent.KEYCODE_DEL -> "\u007f"
            KeyEvent.KEYCODE_FORWARD_DEL -> "$esc[3~"
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "\r"
            KeyEvent.KEYCODE_TAB -> if (e.isShiftPressed) "$esc[Z" else "\t"
            KeyEvent.KEYCODE_ESCAPE -> esc
            KeyEvent.KEYCODE_DPAD_UP -> "$esc[A"
            KeyEvent.KEYCODE_DPAD_DOWN -> "$esc[B"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "$esc[C"
            KeyEvent.KEYCODE_DPAD_LEFT -> "$esc[D"
            KeyEvent.KEYCODE_MOVE_HOME -> "$esc[H"
            KeyEvent.KEYCODE_MOVE_END -> "$esc[F"
            KeyEvent.KEYCODE_PAGE_UP -> "$esc[5~"
            KeyEvent.KEYCODE_PAGE_DOWN -> "$esc[6~"
            else -> {
                val c = e.getUnicodeChar(e.metaState and KeyEvent.META_CTRL_MASK.inv())
                if (c == 0) return false
                if (e.isCtrlPressed && c.toChar().isLetter()) ((c.toChar().uppercaseChar().code - 64).toChar()).toString()
                else String(Character.toChars(c))
            }
        }
        send(if (e.isAltPressed && seq.length == 1) esc + seq else seq)
        return true
    }
}
