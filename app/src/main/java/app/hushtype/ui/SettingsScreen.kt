package app.hushtype.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.speech.ModelDownloadListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.BubbleChart
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.OfflineBolt
import androidx.compose.material.icons.rounded.PhotoSizeSelectSmall
import androidx.compose.material.icons.rounded.TextSnippet
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.hushtype.BuildConfig
import app.hushtype.HushtypeApp
import app.hushtype.data.BubbleMode
import java.util.Locale

private val languages = listOf(
    "" to "Phone language", "en-US" to "English (US)", "en-GB" to "English (UK)", "en-IN" to "English (India)",
    "en-AU" to "English (Australia)", "hi-IN" to "Hindi", "bn-IN" to "Bengali", "or-IN" to "Odia", "ta-IN" to "Tamil",
    "te-IN" to "Telugu", "mr-IN" to "Marathi", "es-ES" to "Spanish (Spain)", "es-US" to "Spanish (US)",
    "fr-FR" to "French", "de-DE" to "German", "it-IT" to "Italian", "pt-BR" to "Portuguese (Brazil)",
    "nl-NL" to "Dutch", "ja-JP" to "Japanese", "ko-KR" to "Korean", "zh-CN" to "Chinese (Simplified)",
)

@Composable
fun SettingsScreen(app: HushtypeApp, setup: SetupState) {
    val c = HushTheme.colors
    val s = app.settings
    // Recompose on any change to the stored settings.
    @Suppress("UNUSED_VARIABLE") val version by s.changes.collectAsState()
    val context = LocalContext.current
    var pickLanguage by remember { mutableStateOf(false) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
        Text("Settings", style = HushType.largeTitle, color = c.text, modifier = Modifier.padding(top = 36.dp, bottom = 4.dp))

        if (!setup.ready) {
            SectionLabel("Setup")
            HushCard(padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                SettingRow("Microphone", if (setup.microphone) "Allowed" else "Needed to hear you", Icons.Rounded.Mic, onClick = { SetupState.openAppSettings(context) }) {
                    StatusDot(if (setup.microphone) Hush.mint else Hush.amber, if (setup.microphone) "On" else "Off")
                }
                Divider()
                SettingRow("Bubble", "Accessibility → Hushtype bubble", Icons.Rounded.Accessibility, onClick = { SetupState.openAccessibilitySettings(context) }) {
                    StatusDot(if (setup.accessibility) Hush.mint else Hush.amber, if (setup.accessibility) "On" else "Off")
                }
            }
        }

        SectionLabel("Polish")
        PolishPicker(app)

        SectionLabel("Bubble")
        HushCard(padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
            SwitchRow(
                "Always show the bubble", "Off: it shows only when you're typing", Icons.Rounded.Visibility,
                s.bubbleMode == BubbleMode.ALWAYS,
            ) { s.bubbleMode = if (it) BubbleMode.ALWAYS else BubbleMode.WHEN_TYPING }
            Divider()
            var size by remember { mutableFloatStateOf(s.bubbleSize) }
            SettingRow("Size", "Takes effect the next time the bubble turns on", Icons.Rounded.PhotoSizeSelectSmall)
            Slider(
                value = size, onValueChange = { size = it }, onValueChangeFinished = { s.bubbleSize = size },
                valueRange = 0.8f..1.3f, steps = 4,
                colors = SliderDefaults.colors(thumbColor = Hush.violet, activeTrackColor = Hush.violet),
                modifier = Modifier.padding(start = 48.dp, bottom = 6.dp),
            )
            Divider()
            SwitchRow("Haptics", "A tap when the bubble starts listening", Icons.Rounded.Vibration, s.haptics) { s.haptics = it }
            if (System.currentTimeMillis() < s.bubbleSnoozedUntil) {
                Divider()
                SettingRow("Bubble is hidden for now", "You dropped it on ✕", Icons.Rounded.BubbleChart, onClick = { s.bubbleSnoozedUntil = 0 }) {
                    Text("Show", color = Hush.violet, style = HushType.body.copy(fontWeight = FontWeight.SemiBold))
                }
            }
        }

        SectionLabel("Dictation")
        HushCard(padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
            SettingRow("Language", languages.firstOrNull { it.first == s.language }?.second ?: s.language, Icons.Rounded.Language, onClick = { pickLanguage = true })
            Divider()
            SwitchRow("Recognize speech on this phone", "Faster and private. Turn off if your language isn't downloaded.", Icons.Rounded.OfflineBolt, s.preferOffline) { s.preferOffline = it }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Divider()
                SettingRow("Download speech for offline use", "For the language above", Icons.Rounded.CloudDownload, onClick = { downloadSpeech(context, s.language) })
            }
            Divider()
            SwitchRow("Use the text around the cursor", "Helps continue your sentence and spell names already there", Icons.Rounded.TextSnippet, s.useNearbyText) { s.useNearbyText = it }
            Divider()
            SwitchRow("Learn from my corrections", "Names you respell after dictating join your dictionary", Icons.Rounded.AutoFixHigh, s.learnFromCorrections) { s.learnFromCorrections = it }
        }

        SectionLabel("Privacy")
        HushCard(padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
            SwitchRow("Keep history", "Dictations stay on this phone only", Icons.Rounded.History, s.keepHistory) { s.keepHistory = it }
            Divider()
            SettingRow("Clear history", null, null, onClick = {
                app.history.clear()
                Toast.makeText(context, "History cleared", Toast.LENGTH_SHORT).show()
            }) {}
        }

        SectionLabel("About")
        HushCard(padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
            SettingRow("Hushtype for Android", "Version ${BuildConfig.VERSION_NAME}", null)
            Divider()
            SettingRow("Source code", "github.com/chiranjeebhub/hushtype-android", Icons.Rounded.Code, onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/chiranjeebhub/hushtype-android")))
            })
        }
    }

    if (pickLanguage) {
        AlertDialog(
            onDismissRequest = { pickLanguage = false },
            containerColor = c.surface,
            shape = RoundedCornerShape(26.dp),
            title = { Text("Language", style = HushType.title) },
            text = {
                LazyColumn(Modifier.height(380.dp)) {
                    items(languages) { (tag, name) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { s.language = tag; pickLanguage = false }.padding(vertical = 12.dp, horizontal = 4.dp),
                        ) {
                            Text(name, style = HushType.body, color = if (tag == s.language) Hush.violet else c.text, modifier = Modifier.weight(1f))
                            if (tag == s.language) Text("✓", color = Hush.violet)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickLanguage = false }) { Text("Done", color = Hush.violet) } },
        )
    }
}

private fun downloadSpeech(context: android.content.Context, language: String) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
        Toast.makeText(context, "This phone doesn't offer on-device speech recognition.", Toast.LENGTH_LONG).show()
        return
    }
    val tag = language.ifBlank { Locale.getDefault().toLanguageTag() }
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
    val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
    val main = android.os.Handler(android.os.Looper.getMainLooper())
    fun say(text: String) = main.post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    if (Build.VERSION.SDK_INT >= 34) {
        recognizer.triggerModelDownload(intent, context.mainExecutor, object : ModelDownloadListener {
            override fun onProgress(completedPercent: Int) {}
            override fun onSuccess() { say("Offline speech is ready for $tag."); main.post { recognizer.destroy() } }
            override fun onScheduled() { say("The download is scheduled. Android will finish it on Wi-Fi."); main.post { recognizer.destroy() } }
            override fun onError(error: Int) { say("Android couldn't download $tag (error $error)."); main.post { recognizer.destroy() } }
        })
    } else {
        recognizer.triggerModelDownload(intent)
        say("Android is downloading offline speech for $tag.")
    }
}
