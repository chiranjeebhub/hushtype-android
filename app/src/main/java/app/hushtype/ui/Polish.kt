package app.hushtype.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.hushtype.HushtypeApp
import app.hushtype.data.CleanupEngine
import app.hushtype.polish.ClaudePolisher
import app.hushtype.polish.ModelDownload
import app.hushtype.polish.OnDeviceAvailability
import kotlinx.coroutines.launch

/** Chooses what polishes dictation: Gemini Nano, Claude, or nothing. One tile per engine. */
@Composable
fun PolishPicker(app: HushtypeApp) {
    val settings = app.settings
    var engine by remember { mutableStateOf(settings.engine) }
    var availability by remember { mutableStateOf<OnDeviceAvailability?>(null) }
    var download by remember { mutableStateOf<ModelDownload?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(download) {
        availability = app.onDevice.availability()
        // A fresh install starts on-device when the phone has it, unless Claude is set up already.
        if (!settings.engineChosen) {
            val preferred = when {
                app.secrets.hasAnthropicKey -> CleanupEngine.CLAUDE
                availability == OnDeviceAvailability.UNSUPPORTED -> CleanupEngine.OFF
                else -> CleanupEngine.ON_DEVICE
            }
            engine = preferred
        }
    }

    fun choose(next: CleanupEngine) {
        engine = next
        settings.engine = next
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        EngineTile(
            icon = Icons.Rounded.PhoneAndroid,
            title = "On this phone",
            subtitle = "Gemini Nano. Free, private, works offline.",
            selected = engine == CleanupEngine.ON_DEVICE,
            status = when (availability) {
                OnDeviceAvailability.AVAILABLE -> Hush.mint to "Ready"
                OnDeviceAvailability.DOWNLOADABLE -> Hush.amber to "Needs a download"
                OnDeviceAvailability.DOWNLOADING -> Hush.amber to "Downloading"
                OnDeviceAvailability.UNSUPPORTED -> HushTheme.colors.textTertiary to "Not on this phone"
                null -> null
            },
            onClick = { choose(CleanupEngine.ON_DEVICE) },
        ) {
            when {
                availability == OnDeviceAvailability.UNSUPPORTED -> Text(
                    "This phone doesn't have Gemini Nano (it needs a Pixel 9 or newer, or a recent Galaxy S). Use Claude instead.",
                    style = HushType.subhead, color = HushTheme.colors.textSecondary,
                )
                availability == OnDeviceAvailability.DOWNLOADABLE || availability == OnDeviceAvailability.DOWNLOADING || download is ModelDownload.Progress -> {
                    val progress = download as? ModelDownload.Progress
                    if (progress != null) {
                        val fraction = progress.fraction
                        if (fraction != null) {
                            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().clip(CircleShape), color = Hush.violet)
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().clip(CircleShape), color = Hush.violet)
                        }
                    } else {
                        SecondaryButton("Download Gemini Nano") {
                            scope.launch {
                                app.onDevice.download().collect { download = it }
                            }
                        }
                    }
                }
                download is ModelDownload.Failed -> Text((download as ModelDownload.Failed).message, style = HushType.subhead, color = Hush.red)
            }
        }
        EngineTile(
            icon = Icons.Rounded.AutoAwesome,
            title = "Claude",
            subtitle = "Claude Haiku with your own API key. Best with names and context.",
            selected = engine == CleanupEngine.CLAUDE,
            status = if (app.secrets.hasAnthropicKey) Hush.mint to "Key saved" else null,
            onClick = { choose(CleanupEngine.CLAUDE) },
        ) {
            ClaudeKeyField(app)
        }
        EngineTile(
            icon = Icons.Rounded.TextFields,
            title = "Off",
            subtitle = "Type exactly what you said. Snippets still work.",
            selected = engine == CleanupEngine.OFF,
            status = null,
            onClick = { choose(CleanupEngine.OFF) },
        )
    }
}

@Composable
private fun EngineTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    status: Pair<Color, String>?,
    onClick: () -> Unit,
    expanded: @Composable () -> Unit = {},
) {
    val c = HushTheme.colors
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface)
            // Selection is shown once: a thin violet border.
            .border(BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) Hush.violet else c.hairline), shape)
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, tint = if (selected) Color.White else Hush.violet, background = if (selected) Hush.violetDeep else c.accentSoft)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = HushType.headline, color = c.text)
                    if (status != null) {
                        Spacer(Modifier.width(10.dp))
                        StatusDot(status.first, status.second)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = HushType.subhead, color = c.textSecondary)
            }
        }
        AnimatedVisibility(selected) {
            Box(Modifier.padding(top = 14.dp)) { expanded() }
        }
    }
}

@Composable
fun ClaudeKeyField(app: HushtypeApp) {
    var key by remember { mutableStateOf(app.secrets.anthropicKey) }
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = key,
            onValueChange = { key = it; message = null },
            singleLine = true,
            label = { Text("Anthropic API key") },
            placeholder = { Text("sk-ant-…") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Hush.violet, cursorColor = Hush.violet),
            modifier = Modifier.fillMaxWidth(),
        )
        message?.let { (ok, text) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Bolt, null, tint = if (ok) Hush.mintDeep else Hush.red, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(text, style = HushType.subhead.copy(fontWeight = FontWeight.Medium), color = if (ok) Hush.mintDeep else Hush.red)
            }
        }
        PrimaryButton(if (checking) "Checking…" else "Save key", enabled = !checking && key.isNotBlank()) {
            checking = true
            scope.launch {
                val problem = ClaudePolisher.validate(key)
                checking = false
                if (problem == null) {
                    app.secrets.anthropicKey = key
                    app.settings.engine = CleanupEngine.CLAUDE
                    message = true to "Key works. Claude will polish your dictation."
                } else {
                    message = false to problem
                }
            }
        }
        Text(
            "Get a key at console.anthropic.com. It's stored encrypted on this phone and only sent to Anthropic.",
            style = HushType.subhead, color = HushTheme.colors.textTertiary,
        )
    }
}
