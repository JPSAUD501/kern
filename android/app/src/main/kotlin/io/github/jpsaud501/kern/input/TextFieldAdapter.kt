package io.github.jpsaud501.kern.input

import android.icu.text.BreakIterator
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import io.github.jpsaud501.kern.engine.ffi.Edit

/**
 * The only code that talks to the app's text field (D14).
 *
 * The engine decides *what* to change; this decides *how*, according to the kind of field and
 * the known behavior of apps. Every behavior here that departs from a plain `commitText` exists
 * because a class of apps needs it, and the bench app has a field that reproduces it.
 */
class TextFieldAdapter(private val service: InputMethodService) {
    private var editor: EditorInfo? = null
    private var selectionStart = 0
    private var selectionEnd = 0

    val enterAction: EnterAction
        get() = editor?.let(EnterAction::of) ?: EnterAction.Newline

    /** Terminals and similar editors declare no text type and expect key events. */
    private val isRawField: Boolean
        get() = editor?.let { it.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL } ?: false

    fun start(editor: EditorInfo) {
        this.editor = editor
        selectionStart = editor.initialSelStart
        selectionEnd = editor.initialSelEnd
    }

    fun finish() {
        editor = null
    }

    fun onSelectionChanged(start: Int, end: Int) {
        selectionStart = start
        selectionEnd = end
    }

    /** Whether the field asks for a capital letter at the cursor. */
    fun wantsCapital(): Boolean {
        val editor = editor ?: return false
        if (isRawField) return false
        val connection = service.currentInputConnection ?: return false
        return connection.getCursorCapsMode(editor.inputType) != 0
    }

    fun apply(edits: List<Edit>) {
        val connection = service.currentInputConnection ?: return
        val batch = edits.size > 1
        if (batch) connection.beginBatchEdit()
        try {
            edits.forEach { edit -> apply(connection, edit) }
        } finally {
            if (batch) connection.endBatchEdit()
        }
    }

    private fun apply(connection: InputConnection, edit: Edit) {
        when (edit) {
            is Edit.Commit -> commit(connection, edit.text)
            Edit.DeleteBackward -> deleteBackward(connection)
            Edit.Enter -> enter(connection)
        }
    }

    private fun commit(connection: InputConnection, text: String) {
        val digit = text.singleOrNull()?.digitToIntOrNull()
        if (digit != null) {
            // Chrome one-time-code fields and other web forms only react to digit key events
            // (HeliBoard #585); LatinIME sends digits this way everywhere.
            service.sendDownUpKeyEvents(KeyEvent.KEYCODE_0 + digit)
        } else {
            connection.commitText(text, 1)
        }
    }

    private fun deleteBackward(connection: InputConnection) {
        if (isRawField) {
            service.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            return
        }
        if (selectionStart != selectionEnd) {
            connection.commitText("", 1)
            return
        }
        val before = connection.getTextBeforeCursor(GRAPHEME_LOOKBEHIND, 0)
        if (before.isNullOrEmpty()) {
            // Nothing to delete that the field reports. Apps that track backspace themselves
            // (chat apps clearing a reply, React Native's onKeyPress) still expect the key.
            service.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            return
        }
        connection.deleteSurroundingText(lastGraphemeLength(before), 0)
    }

    private fun enter(connection: InputConnection) {
        if (isRawField) {
            service.sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            return
        }
        when (val action = enterAction.editorAction) {
            null -> connection.commitText("\n", 1)
            else -> connection.performEditorAction(action)
        }
    }

    private companion object {
        /** Long enough for the longest emoji sequences (families, flags with tags). */
        const val GRAPHEME_LOOKBEHIND = 64

        /** UTF-16 length of the last user-perceived character, so backspace never splits one. */
        fun lastGraphemeLength(text: CharSequence): Int {
            val iterator = BreakIterator.getCharacterInstance()
            iterator.setText(text)
            val start = iterator.preceding(text.length)
            return if (start == BreakIterator.DONE) text.length else text.length - start
        }
    }
}
