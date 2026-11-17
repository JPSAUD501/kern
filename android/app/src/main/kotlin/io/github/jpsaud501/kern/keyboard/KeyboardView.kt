package io.github.jpsaud501.kern.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import androidx.core.view.ViewCompat
import io.github.jpsaud501.kern.R
import io.github.jpsaud501.kern.engine.ffi.Edit
import io.github.jpsaud501.kern.engine.ffi.FrameKey
import io.github.jpsaud501.kern.engine.ffi.KeyId
import io.github.jpsaud501.kern.engine.ffi.KeyKind
import io.github.jpsaud501.kern.engine.ffi.KeyboardEngine
import io.github.jpsaud501.kern.engine.ffi.KeyboardFrame
import io.github.jpsaud501.kern.engine.ffi.ShiftIndicator
import io.github.jpsaud501.kern.input.EnterAction

/**
 * Draws the keys and turns touches into key presses.
 *
 * The engine owns the layout and the keyboard state; this view asks it which key is under a
 * finger and what a press does, and hands the resulting edits to [onEdits]. Keys are drawn with
 * Canvas (D14) because the key area is redrawn on every touch and must stay within a frame.
 */
@SuppressLint("ViewConstructor") // Created only by the input method service, never inflated.
class KeyboardView(
    context: Context,
    private val engine: KeyboardEngine,
    private val onEdits: (List<Edit>) -> Unit,
) : View(context) {
    private val colors = KeyboardColors.from(context)
    private val metrics = KeyboardMetrics.from(context)
    private val accessibility = KeyAccessibilityHelper(this)

    private val icons = mapOf(
        Icon.Shift to drawable(R.drawable.ic_key_shift),
        Icon.ShiftOnce to drawable(R.drawable.ic_key_shift_once),
        Icon.ShiftLocked to drawable(R.drawable.ic_key_shift_locked),
        Icon.Backspace to drawable(R.drawable.ic_key_backspace),
        Icon.Enter to drawable(R.drawable.ic_key_enter),
        Icon.Go to drawable(R.drawable.ic_key_action_go),
        Icon.Search to drawable(R.drawable.ic_key_action_search),
        Icon.Send to drawable(R.drawable.ic_key_action_send),
        Icon.Next to drawable(R.drawable.ic_key_action_next),
        Icon.Done to drawable(R.drawable.ic_key_action_done),
    )

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val keyBounds = RectF()
    private val iconBounds = Rect()

    /** Fingers currently on the keyboard, by pointer id. */
    private val pointers = HashMap<Int, Pointer>()

    private var bottomInset = 0

    var frame: KeyboardFrame = engine.frame()
        private set

    var enterAction: EnterAction = EnterAction.Newline
        set(value) {
            if (field == value) return
            field = value
            invalidate()
            accessibility.invalidateRoot()
        }

    init {
        ViewCompat.setAccessibilityDelegate(this, accessibility)
        setOnApplyWindowInsetsListener { _, insets ->
            val inset = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            if (inset != bottomInset) {
                bottomInset = inset
                requestLayout()
            }
            insets
        }
    }

    /** Re-reads the frame from the engine after its state changed outside this view. */
    fun refresh() {
        frame = engine.frame()
        invalidate()
        accessibility.invalidateRoot()
    }

    /** Lifts every finger without typing, for when the field goes away mid-touch. */
    fun cancelTouches() {
        pointers.values.forEach { pointer -> pointer.repeat?.let(::removeCallbacks) }
        pointers.clear()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val keys = frame.rowCount.toFloat() * metrics.rowHeight
        val height = keys + 2 * metrics.verticalPadding + bottomInset
        setMeasuredDimension(width, height.toInt())
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        frame = engine.resize(keyAreaWidth(), keyAreaHeight())
        accessibility.invalidateRoot()
    }

    override fun onDetachedFromWindow() {
        cancelTouches()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(colors.background)
        for (key in frame.keys) {
            drawKey(canvas, key, pointers.values.any { it.key == key.id })
        }
    }

    private fun drawKey(canvas: Canvas, key: FrameKey, pressed: Boolean) {
        boundsOf(key, keyBounds)
        keyBounds.inset(metrics.keyGapX / 2, metrics.keyGapY / 2)

        val accent = key.kind == KeyKind.ENTER
        keyPaint.color = when {
            accent -> if (pressed) colors.accentKeyPressed else colors.accentKey
            key.isFunction() -> if (pressed) colors.functionKeyPressed else colors.functionKey
            else -> if (pressed) colors.keyPressed else colors.key
        }
        canvas.drawRoundRect(keyBounds, metrics.cornerRadius, metrics.cornerRadius, keyPaint)

        val foreground = if (accent) colors.accentLabel else colors.label
        val icon = iconFor(key)
        if (icon != null) {
            val drawable = icons.getValue(icon)
            val half = metrics.iconSize / 2
            iconBounds.set(
                (keyBounds.centerX() - half).toInt(),
                (keyBounds.centerY() - half).toInt(),
                (keyBounds.centerX() + half).toInt(),
                (keyBounds.centerY() + half).toInt(),
            )
            drawable.bounds = iconBounds
            drawable.setTint(foreground)
            drawable.draw(canvas)
        } else if (key.label.isNotEmpty()) {
            labelPaint.color = foreground
            labelPaint.textSize =
                if (key.kind == KeyKind.CHARACTER) metrics.letterTextSize else metrics.functionTextSize
            val baseline = keyBounds.centerY() - (labelPaint.ascent() + labelPaint.descent()) / 2
            canvas.drawText(key.label, keyBounds.centerX(), baseline, labelPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility") // Keys are virtual views; see KeyAccessibilityHelper.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                // Rollover: when a new finger lands, the keys under the other fingers are typed
                // now, so fast typists get their letters in the order they pressed them.
                releaseAll(event.eventTime)
                val key = keyAt(event.getX(index), event.getY(index)) ?: return true
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                val pointer = Pointer(key.id)
                pointers[event.getPointerId(index)] = pointer
                if (key.repeatable) startRepeating(pointer, event.eventTime)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    val pointer = pointers[event.getPointerId(index)] ?: continue
                    if (pointer.repeat != null) continue
                    val key = keyAt(event.getX(index), event.getY(index)) ?: continue
                    if (key.id != pointer.key) {
                        pointer.key = key.id
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pointer = pointers.remove(event.getPointerId(event.actionIndex))
                if (pointer != null) release(pointer, event.eventTime)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> cancelTouches()
        }
        return true
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    // Used by KeyAccessibilityHelper.

    internal fun keyIndexAt(x: Float, y: Float): Int? {
        val key = keyAt(x, y) ?: return null
        return frame.keys.indexOf(key).takeIf { it >= 0 }
    }

    internal fun boundsOf(key: FrameKey, out: RectF) {
        out.set(
            key.rect.left + metrics.horizontalPadding,
            key.rect.top + metrics.verticalPadding,
            key.rect.right + metrics.horizontalPadding,
            key.rect.bottom + metrics.verticalPadding,
        )
    }

    internal fun describe(key: FrameKey): String = when (key.kind) {
        KeyKind.CHARACTER, KeyKind.SWITCH_PAGE -> key.label
        KeyKind.SPACE -> context.getString(R.string.key_space)
        KeyKind.BACKSPACE -> context.getString(R.string.key_delete)
        KeyKind.SHIFT -> context.getString(
            when (frame.shift) {
                ShiftIndicator.OFF -> R.string.key_shift
                ShiftIndicator.ONCE -> R.string.key_shift_once
                ShiftIndicator.LOCKED -> R.string.key_caps_lock
            },
        )
        KeyKind.ENTER -> context.getString(
            when (enterAction) {
                EnterAction.Newline -> R.string.key_enter
                EnterAction.Go -> R.string.key_go
                EnterAction.Search -> R.string.key_search
                EnterAction.Send -> R.string.key_send
                EnterAction.Next -> R.string.key_next
                EnterAction.Previous -> R.string.key_previous
                EnterAction.Done -> R.string.key_done
            },
        )
    }

    internal fun pressForAccessibility(key: FrameKey) {
        press(key.id, SystemClock.uptimeMillis())
    }

    private fun keyAt(x: Float, y: Float): FrameKey? {
        val id = engine.keyAt(x - metrics.horizontalPadding, y - metrics.verticalPadding) ?: return null
        return frame.keys.firstOrNull { it.id == id }
    }

    private fun press(id: KeyId, time: Long) {
        val result = engine.press(id, time.toULong())
        if (result.edits.isNotEmpty()) onEdits(result.edits)
        if (result.frameChanged) refresh()
    }

    private fun release(pointer: Pointer, time: Long) {
        val repeat = pointer.repeat
        if (repeat != null) {
            removeCallbacks(repeat)
        } else {
            press(pointer.key, time)
        }
    }

    private fun releaseAll(time: Long) {
        val active = pointers.values.toList()
        pointers.clear()
        active.forEach { pointer -> release(pointer, time) }
    }

    /** Repeatable keys act on touch down, then repeat while held. */
    private fun startRepeating(pointer: Pointer, time: Long) {
        press(pointer.key, time)
        val repeat = object : Runnable {
            override fun run() {
                press(pointer.key, SystemClock.uptimeMillis())
                postDelayed(this, REPEAT_INTERVAL_MS)
            }
        }
        pointer.repeat = repeat
        postDelayed(repeat, REPEAT_DELAY_MS)
    }

    private fun keyAreaWidth() = (width - 2 * metrics.horizontalPadding).coerceAtLeast(0f)

    private fun keyAreaHeight() = (height - 2 * metrics.verticalPadding - bottomInset).coerceAtLeast(0f)

    private fun iconFor(key: FrameKey): Icon? = when (key.kind) {
        KeyKind.SHIFT -> when (frame.shift) {
            ShiftIndicator.OFF -> Icon.Shift
            ShiftIndicator.ONCE -> Icon.ShiftOnce
            ShiftIndicator.LOCKED -> Icon.ShiftLocked
        }
        KeyKind.BACKSPACE -> Icon.Backspace
        KeyKind.ENTER -> when (enterAction) {
            EnterAction.Newline, EnterAction.Previous -> Icon.Enter
            EnterAction.Go -> Icon.Go
            EnterAction.Search -> Icon.Search
            EnterAction.Send -> Icon.Send
            EnterAction.Next -> Icon.Next
            EnterAction.Done -> Icon.Done
        }
        KeyKind.CHARACTER, KeyKind.SPACE, KeyKind.SWITCH_PAGE -> null
    }

    private fun FrameKey.isFunction() = when (kind) {
        KeyKind.SHIFT, KeyKind.BACKSPACE, KeyKind.SWITCH_PAGE -> true
        KeyKind.CHARACTER, KeyKind.SPACE, KeyKind.ENTER -> false
    }

    private fun drawable(id: Int): Drawable =
        checkNotNull(context.getDrawable(id)) { "missing drawable $id" }.mutate()

    private class Pointer(var key: KeyId) {
        var repeat: Runnable? = null
    }

    private enum class Icon { Shift, ShiftOnce, ShiftLocked, Backspace, Enter, Go, Search, Send, Next, Done }

    private companion object {
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_INTERVAL_MS = 50L
    }
}
