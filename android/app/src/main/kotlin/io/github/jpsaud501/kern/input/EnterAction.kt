package io.github.jpsaud501.kern.input

import android.view.inputmethod.EditorInfo

/** What the enter key does in the current field. */
enum class EnterAction(val editorAction: Int?) {
    Newline(null),
    Go(EditorInfo.IME_ACTION_GO),
    Search(EditorInfo.IME_ACTION_SEARCH),
    Send(EditorInfo.IME_ACTION_SEND),
    Next(EditorInfo.IME_ACTION_NEXT),
    Previous(EditorInfo.IME_ACTION_PREVIOUS),
    Done(EditorInfo.IME_ACTION_DONE),
    ;

    companion object {
        /**
         * The field's action, unless it asks for enter to stay a newline
         * (`IME_FLAG_NO_ENTER_ACTION`, set by multi-line fields) or has no action.
         */
        fun of(editor: EditorInfo): EnterAction {
            if (editor.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return Newline
            val action = editor.imeOptions and EditorInfo.IME_MASK_ACTION
            return entries.firstOrNull { it.editorAction == action } ?: Newline
        }
    }
}
