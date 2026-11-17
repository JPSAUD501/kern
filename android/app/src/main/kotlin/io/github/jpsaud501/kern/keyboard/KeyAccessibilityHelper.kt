package io.github.jpsaud501.kern.keyboard

import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.widget.Button
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper

/**
 * Exposes each key as a virtual view, so screen readers can explore and press keys and UI tests
 * can find them by their description.
 */
internal class KeyAccessibilityHelper(private val view: KeyboardView) : ExploreByTouchHelper(view) {
    private val bounds = RectF()
    private val roundedBounds = Rect()

    override fun getVirtualViewAt(x: Float, y: Float): Int = view.keyIndexAt(x, y) ?: INVALID_ID

    override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
        virtualViewIds.addAll(view.frame.keys.indices)
    }

    override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
        val key = view.frame.keys.getOrNull(virtualViewId)
        if (key == null) {
            // The frame changed under the accessibility service; report an empty node.
            node.contentDescription = ""
            @Suppress("DEPRECATION") // ExploreByTouchHelper requires bounds in parent.
            node.setBoundsInParent(Rect())
            return
        }
        view.boundsOf(key, bounds)
        bounds.roundOut(roundedBounds)
        node.contentDescription = view.describe(key)
        node.className = Button::class.java.name
        @Suppress("DEPRECATION") // ExploreByTouchHelper requires bounds in parent.
        node.setBoundsInParent(roundedBounds)
        node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
    }

    override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
        if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
        val key = view.frame.keys.getOrNull(virtualViewId) ?: return false
        view.pressForAccessibility(key)
        return true
    }
}
