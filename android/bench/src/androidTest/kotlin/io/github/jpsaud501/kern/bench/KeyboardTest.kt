package io.github.jpsaud501.kern.bench

import android.os.SystemClock
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Types into the bench app's fields with the installed Kern keyboard, through the keys'
 * accessibility nodes, and checks what each field received.
 *
 * Kern's debug build must be installed on the device before this runs.
 */
@RunWith(AndroidJUnit4::class)
class KeyboardTest {
    @get:Rule
    val activity = ActivityScenarioRule(BenchActivity::class.java)

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun capitalizesTheFirstLetterOfASentence() {
        focus(R.id.field_sentence)
        type("H", "i")
        assertFieldText(R.id.field_sentence, "Hi")
    }

    @Test
    fun deletesAWholeEmojiWithOneBackspace() {
        setText(R.id.field_sentence, "ok👍🏽")
        focus(R.id.field_sentence)
        type(DELETE)
        assertFieldText(R.id.field_sentence, "ok")
    }

    @Test
    fun deletesTheSelection() {
        setText(R.id.field_sentence, "hello world")
        focus(R.id.field_sentence)
        activity.scenario.onActivity { it.findViewById<EditText>(R.id.field_sentence).setSelection(0, 6) }
        type(DELETE)
        assertFieldText(R.id.field_sentence, "world")
    }

    @Test
    fun enterRunsTheSearchAction() {
        focus(R.id.field_search)
        type("a", SEARCH)
        assertEventually("search:a") { it.findViewById<TextView>(R.id.search_result).text.toString() }
    }

    @Test
    fun enterAddsANewLineInMultiLineFields() {
        focus(R.id.field_multiline)
        type("A", ENTER, "B", "c")
        assertFieldText(R.id.field_multiline, "A\nBc")
    }

    @Test
    fun digitsReachNumberFields() {
        focus(R.id.field_number)
        type(SYMBOLS, "4", "2")
        assertFieldText(R.id.field_number, "42")
    }

    @Test
    fun rawFieldsReceiveKeyEvents() {
        focus(R.id.field_raw)
        type("l", "s", ENTER, DELETE)
        assertEventually("ls⏎⌫") { it.findViewById<RawInputView>(R.id.field_raw).received }
    }

    private fun focus(fieldId: Int) {
        val name = InstrumentationRegistry.getInstrumentation().targetContext.resources.getResourceEntryName(fieldId)
        val field = device.wait(Until.findObject(By.res(BENCH_PACKAGE, name)), TIMEOUT_MS)
        assertNotNull("field $name not found", field)
        field.click()
        assertNotNull("Kern did not open", device.wait(Until.findObject(key(DELETE)), OPEN_TIMEOUT_MS))
    }

    private fun type(vararg keys: String) {
        for (description in keys) {
            val key = device.wait(Until.findObject(key(description)), TIMEOUT_MS)
            assertNotNull("no key described as \"$description\"", key)
            key.click()
            device.waitForIdle()
        }
    }

    private fun key(description: String) = By.pkg(KERN_PACKAGE).desc(description)

    private fun setText(fieldId: Int, text: String) {
        activity.scenario.onActivity { bench ->
            val field = bench.findViewById<EditText>(fieldId)
            field.setText(text)
            field.setSelection(text.length)
        }
    }

    private fun assertFieldText(fieldId: Int, expected: String) =
        assertEventually(expected) { it.findViewById<EditText>(fieldId).text.toString() }

    /** Edits cross two processes, so the field catches up a moment after the tap. */
    private fun assertEventually(expected: String, read: (BenchActivity) -> String) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        var actual: String
        do {
            var value = ""
            activity.scenario.onActivity { value = read(it) }
            actual = value
            if (actual == expected) return
            SystemClock.sleep(POLL_MS)
        } while (SystemClock.uptimeMillis() < deadline)
        assertEquals(expected, actual)
    }

    companion object {
        private const val BENCH_PACKAGE = "io.github.jpsaud501.kern.bench"
        private const val KERN_PACKAGE = "io.github.jpsaud501.kern"
        private const val KERN_IME = "$KERN_PACKAGE/.KernInputMethodService"
        private const val TIMEOUT_MS = 5_000L

        /**
         * The first field to gain focus starts Kern's process and loads the engine. On a CI emulator
         * without a GPU that alone can take several seconds. These tests check behavior; latency is
         * measured with traces (D16).
         */
        private const val OPEN_TIMEOUT_MS = 20_000L
        private const val POLL_MS = 50L

        // Key descriptions come from Kern's English strings; the test device runs in English.
        private const val DELETE = "Delete"
        private const val ENTER = "Enter"
        private const val SEARCH = "Search"
        private const val SYMBOLS = "?123"

        @JvmStatic
        @BeforeClass
        fun selectKern() {
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            val installed = device.executeShellCommand("pm path $KERN_PACKAGE")
            check(installed.isNotBlank()) { "Kern ($KERN_PACKAGE) must be installed before running the bench tests" }
            // Emulators report the host's keyboard as a hardware keyboard, and Android hides every
            // on-screen keyboard while one is attached unless this setting is on. The tests need
            // the device to behave like a phone with no hardware keyboard.
            device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
            device.executeShellCommand("ime enable $KERN_IME")
            device.executeShellCommand("ime set $KERN_IME")
        }
    }
}
