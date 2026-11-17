package io.github.jpsaud501.kern.setup

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.jpsaud501.kern.R

/**
 * Turns Kern on: enable it, select it, try it. Each step shows as done once the system reports
 * it, so the screen always reflects the real state when the user comes back from settings.
 */
class SetupActivity : ComponentActivity() {
    private var status by mutableStateOf(ImeStatus.Disabled)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KernTheme {
                SetupScreen(
                    status = status,
                    onEnable = { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                    onSelect = { getSystemService(InputMethodManager::class.java).showInputMethodPicker() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        status = ImeStatus.of(this)
    }

    /** The keyboard picker is a dialog over this activity, so focus returns when it closes. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) status = ImeStatus.of(this)
    }
}

@Composable
private fun KernTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun SetupScreen(status: ImeStatus, onEnable: () -> Unit, onSelect: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall)
            Text(
                stringResource(R.string.setup_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Step(
                number = 1,
                title = stringResource(R.string.setup_enable_title),
                body = stringResource(R.string.setup_enable_body),
                action = stringResource(R.string.setup_enable_action),
                state = if (status == ImeStatus.Disabled) StepState.Current else StepState.Done,
                onAction = onEnable,
            )
            Step(
                number = 2,
                title = stringResource(R.string.setup_select_title),
                body = stringResource(R.string.setup_select_body),
                action = stringResource(R.string.setup_select_action),
                state = when (status) {
                    ImeStatus.Disabled -> StepState.Upcoming
                    ImeStatus.NotSelected -> StepState.Current
                    ImeStatus.Ready -> StepState.Done
                },
                onAction = onSelect,
            )
            if (status == ImeStatus.Ready) TryIt()
        }
    }
}

private enum class StepState { Upcoming, Current, Done }

@Composable
private fun Step(
    number: Int,
    title: String,
    body: String,
    action: String,
    state: StepState,
    onAction: () -> Unit,
) {
    val containerColor = when (state) {
        StepState.Current -> MaterialTheme.colorScheme.primaryContainer
        StepState.Upcoming, StepState.Done -> MaterialTheme.colorScheme.surfaceContainer
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$number. $title", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (state == StepState.Done) {
                    Text(
                        stringResource(R.string.setup_step_done),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (state != StepState.Done) {
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
            if (state == StepState.Current) {
                Button(onClick = onAction) { Text(action) }
            }
        }
    }
}

@Composable
private fun TryIt() {
    var text by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.setup_try_title), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setup_try_hint)) },
        )
    }
}
