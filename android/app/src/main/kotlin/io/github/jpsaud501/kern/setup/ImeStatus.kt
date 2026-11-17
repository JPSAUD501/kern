package io.github.jpsaud501.kern.setup

import android.content.Context
import android.view.inputmethod.InputMethodManager
import io.github.jpsaud501.kern.KernInputMethodService

/** Where the user is in turning Kern on. */
enum class ImeStatus {
    /** Kern is not enabled in the system's keyboard settings. */
    Disabled,

    /** Enabled, but another keyboard is the current one. */
    NotSelected,

    /** Kern is the current keyboard. */
    Ready,
    ;

    companion object {
        fun of(context: Context): ImeStatus {
            val manager = context.getSystemService(InputMethodManager::class.java)
            val serviceName = KernInputMethodService::class.java.name
            fun isKern(packageName: String, service: String) =
                packageName == context.packageName && service == serviceName

            val enabled = manager.enabledInputMethodList.any { isKern(it.packageName, it.serviceName) }
            if (!enabled) return Disabled
            val current = manager.currentInputMethodInfo
            return if (current != null && isKern(current.packageName, current.serviceName)) Ready else NotSelected
        }
    }
}
