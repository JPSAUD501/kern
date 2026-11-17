package io.github.jpsaud501.kern.bench

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.InputType
import android.util.AttributeSet
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

/**
 * An editor that declares `TYPE_NULL` and only understands key events, like a terminal.
 *
 * Its input connection is a non-editable [BaseInputConnection], which turns committed text into
 * key events, as Termux does. It records what it receives in [received]: printable characters as
 * themselves, enter as `⏎` and delete as `⌫`.
 */
class RawInputView(context: Context, attributes: AttributeSet?) : View(context, attributes) {
    private val builder = StringBuilder()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 18f, resources.displayMetrics)
        color = context.getColor(android.R.color.system_neutral1_500)
    }

    val received: String
        get() = builder.toString()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
        return BaseInputConnection(this, false)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            requestFocus()
            context.getSystemService(InputMethodManager::class.java).showSoftInput(this, 0)
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> builder.append('⏎')
            KeyEvent.KEYCODE_DEL -> builder.append('⌫')
            else -> {
                val char = event.unicodeChar
                if (char == 0) return super.onKeyDown(keyCode, event)
                builder.appendCodePoint(char)
            }
        }
        invalidate()
        return true
    }

    /** Text that has no key on the virtual key map (such as "ç") still arrives this way. */
    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        @Suppress("DEPRECATION") // ACTION_MULTIPLE is deprecated, but BaseInputConnection still sends it.
        val characters = event.characters ?: return super.onKeyMultiple(keyCode, repeatCount, event)
        builder.append(characters)
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawText(received, paddingLeft.toFloat(), height / 2f, paint)
    }
}
