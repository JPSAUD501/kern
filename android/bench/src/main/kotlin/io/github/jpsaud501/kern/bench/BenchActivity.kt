package io.github.jpsaud501.kern.bench

import android.app.Activity
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView

class BenchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bench)

        val search = findViewById<EditText>(R.id.field_search)
        val result = findViewById<TextView>(R.id.search_result)
        search.setOnEditorActionListener { view, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            result.text = getString(R.string.search_result, view.text)
            true
        }
    }
}
