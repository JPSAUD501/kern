package io.github.jpsaud501.kern

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import io.github.jpsaud501.kern.engine.ffi.Edit
import io.github.jpsaud501.kern.engine.ffi.KeyboardEngine
import io.github.jpsaud501.kern.input.TextFieldAdapter
import io.github.jpsaud501.kern.keyboard.KeyboardView

class KernInputMethodService : InputMethodService() {
    private lateinit var engine: KeyboardEngine
    private val field = TextFieldAdapter(this)
    private var keyboard: KeyboardView? = null

    override fun onCreate() {
        super.onCreate()
        engine = KeyboardEngine.withBuiltinLayout(DEFAULT_LAYOUT)
    }

    override fun onDestroy() {
        keyboard = null
        engine.close()
        super.onDestroy()
    }

    /** Called again after configuration changes (rotation, dark mode, wallpaper colors). */
    override fun onCreateInputView(): View =
        KeyboardView(this, engine, ::onEdits).also { view ->
            keyboard = view
            view.enterAction = field.enterAction
        }

    /** The extracted full-screen editor is a relic of small landscape screens; Kern never uses it. */
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(editor: EditorInfo, restarting: Boolean) {
        super.onStartInputView(editor, restarting)
        field.start(editor)
        if (!restarting) engine.reset()
        keyboard?.let { view ->
            view.enterAction = field.enterAction
            view.refresh()
        }
        updateAutoCapitalization()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        keyboard?.cancelTouches()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        field.finish()
        super.onFinishInput()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        field.onSelectionChanged(newSelStart, newSelEnd)
        updateAutoCapitalization()
    }

    private fun onEdits(edits: List<Edit>) {
        field.apply(edits)
        updateAutoCapitalization()
    }

    private fun updateAutoCapitalization() {
        if (engine.setAutoCapitalize(field.wantsCapital())) keyboard?.refresh()
    }

    private companion object {
        const val DEFAULT_LAYOUT = "qwerty"
    }
}
