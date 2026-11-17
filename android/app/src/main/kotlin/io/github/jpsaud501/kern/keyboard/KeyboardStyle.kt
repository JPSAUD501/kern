package io.github.jpsaud501.kern.keyboard

import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue
import androidx.annotation.ColorRes

/** Colors of the keyboard, from the system's Material You palette. */
data class KeyboardColors(
    val background: Int,
    val key: Int,
    val keyPressed: Int,
    val functionKey: Int,
    val functionKeyPressed: Int,
    val accentKey: Int,
    val accentKeyPressed: Int,
    val label: Int,
    val accentLabel: Int,
) {
    companion object {
        fun from(context: Context): KeyboardColors {
            fun color(@ColorRes id: Int) = context.getColor(id)
            val night = context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            return if (night) {
                KeyboardColors(
                    background = color(android.R.color.system_neutral1_900),
                    key = color(android.R.color.system_neutral1_800),
                    keyPressed = color(android.R.color.system_neutral1_700),
                    functionKey = color(android.R.color.system_neutral2_700),
                    functionKeyPressed = color(android.R.color.system_neutral2_600),
                    accentKey = color(android.R.color.system_accent1_200),
                    accentKeyPressed = color(android.R.color.system_accent1_300),
                    label = color(android.R.color.system_neutral1_50),
                    accentLabel = color(android.R.color.system_accent1_800),
                )
            } else {
                KeyboardColors(
                    background = color(android.R.color.system_neutral2_100),
                    key = color(android.R.color.system_neutral1_10),
                    keyPressed = color(android.R.color.system_neutral2_200),
                    functionKey = color(android.R.color.system_neutral2_200),
                    functionKeyPressed = color(android.R.color.system_neutral2_300),
                    accentKey = color(android.R.color.system_accent1_600),
                    accentKeyPressed = color(android.R.color.system_accent1_700),
                    label = color(android.R.color.system_neutral1_900),
                    accentLabel = color(android.R.color.system_accent1_0),
                )
            }
        }
    }
}

/** Sizes of the keyboard in pixels. */
data class KeyboardMetrics(
    val rowHeight: Float,
    val horizontalPadding: Float,
    val verticalPadding: Float,
    val keyGapX: Float,
    val keyGapY: Float,
    val cornerRadius: Float,
    val letterTextSize: Float,
    val functionTextSize: Float,
    val iconSize: Float,
) {
    companion object {
        fun from(context: Context): KeyboardMetrics {
            val metrics = context.resources.displayMetrics
            fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, metrics)
            fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, metrics)
            val landscape =
                context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            return KeyboardMetrics(
                rowHeight = dp(if (landscape) 44f else 56f),
                horizontalPadding = dp(4f),
                verticalPadding = dp(6f),
                keyGapX = dp(5f),
                keyGapY = dp(8f),
                cornerRadius = dp(10f),
                letterTextSize = sp(22f),
                functionTextSize = sp(15f),
                iconSize = dp(24f),
            )
        }
    }
}
